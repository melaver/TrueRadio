package com.trueradio.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.trueradio.app.DaySegment
import com.trueradio.app.R
import com.trueradio.app.RadioApplication
import com.trueradio.app.SecureSettings
import com.trueradio.app.TrackInfo
import com.trueradio.app.ai.GeminiClient
import com.trueradio.app.audio.AudioPlaybackManager
import com.trueradio.app.news.NewsRepository
import com.trueradio.app.spotify.HourlyMixEngine
import com.trueradio.app.spotify.SpotifyManager
import com.trueradio.app.spotify.SpotifyWebApiClient
import com.trueradio.app.spotify.SpotifyWebAuthManager
import com.trueradio.app.tts.TtsManager
import com.trueradio.app.tts.TtsResult
import com.trueradio.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/**
 * Long-running foreground service that:
 *  1. Owns the SpotifyManager connection and subscribes to PlayerState updates.
 *  2. Watches the clock for the top-of-the-hour news window (00:00-00:03 past the hour).
 *  3. Watches track position for the ~15-second-remaining trivia trigger.
 *  4. Watches the clock for the top-of-the-hour genre switch and (if Spotify Web API is
 *     connected) rebuilds and plays a personalized, genre-targeted mix via [HourlyMixEngine].
 *  5. Calls Gemini for a script, ElevenLabs (with local TTS fallback) for audio, and plays
 *     it back through AudioPlaybackManager with ducking.
 *  6. Publishes a persistent notification with status + Play/Pause controls.
 */
class RadioForegroundService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.trueradio.app.action.START"
        const val ACTION_STOP = "com.trueradio.app.action.STOP"
        const val ACTION_PLAY_PAUSE = "com.trueradio.app.action.PLAY_PAUSE"
        const val EXTRA_SPOTIFY_CLIENT_ID = "extra_spotify_client_id"
        private const val NOTIFICATION_ID = 42
        private const val TRIVIA_TRIGGER_WINDOW_MS = 15_000L
        private const val NEWS_WINDOW_MINUTE_CUTOFF = 3 // top-of-hour window: minute 0..3
        private const val GENRE_WINDOW_MINUTE_CUTOFF = 3 // same top-of-hour window as news
    }

    private lateinit var settings: SecureSettings
    private var spotifyManager: SpotifyManager? = null
    private var geminiClient: GeminiClient? = null
    private var ttsManager: TtsManager? = null
    private lateinit var newsRepository: NewsRepository
    private lateinit var audioPlaybackManager: AudioPlaybackManager
    private var spotifyWebAuthManager: SpotifyWebAuthManager? = null
    private var hourlyMixEngine: HourlyMixEngine? = null

    private var lastTrackUri: String? = null
    private var hasFiredTriviaForCurrentTrack = false
    private var lastNewsFlashHour = -1
    private var lastGenreSwitchHour = -1

    // Guards the DJ's "one voice at a time" invariant. This used to be a plain `isSpeaking`
    // boolean set true only deep inside speakLine() - but news and genre-switch both fire in the
    // *same* 0-3-minutes-past-the-hour window, and both are checked back-to-back synchronously
    // inside a single onPlayerState() call. Since lifecycleScope uses Dispatchers.Main.immediate,
    // each trigger's coroutine runs synchronously up to its first suspension point (the network
    // call) before the next trigger check even runs - meaning BOTH would see isSpeaking still
    // false and both proceed, only setting the flag true later once each independently reached
    // speakLine(). For anyone with both news and personalized genre mixing enabled (the common
    // case), this meant the news flash and the genre-change line would talk over each other
    // basically every hour. tryLock() here is synchronous and atomic, so whichever trigger calls
    // it first genuinely wins; the other bails out immediately and retries on the next
    // player-state tick, by which point the winner has likely finished and released the lock.
    private val speakingMutex = Mutex()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> get() = _status

    override fun onCreate() {
        super.onCreate()
        settings = SecureSettings(applicationContext)
        newsRepository = NewsRepository()
        audioPlaybackManager = AudioPlaybackManager(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification("Starting up...", isPaused = false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> {
                togglePlayback()
                return START_STICKY
            }
            else -> {
                val clientId = intent?.getStringExtra(EXTRA_SPOTIFY_CLIENT_ID)
                lifecycleScope.launch { initializeAndConnect(clientId) }
            }
        }
        return START_STICKY
    }

    private suspend fun initializeAndConnect(clientIdOverride: String?) {
        val clientId = clientIdOverride?.takeIf { it.isNotBlank() } ?: settings.snapshotSpotifyClientId()
        val geminiKey = settings.snapshotGeminiKey()
        val elevenLabsKey = settings.snapshotElevenLabsKey()
        val elevenLabsVoiceId = settings.snapshotElevenLabsVoiceId()

        if (clientId.isBlank()) {
            updateStatus("Missing Spotify client ID - open the app and enter your keys.")
            return
        }

        geminiClient = GeminiClient(geminiKey)
        ttsManager = TtsManager(applicationContext, elevenLabsKey, elevenLabsVoiceId)

        val webAuth = SpotifyWebAuthManager(applicationContext, settings, clientId)
        spotifyWebAuthManager = webAuth
        hourlyMixEngine = HourlyMixEngine(SpotifyWebApiClient(webAuth), settings)

        val manager = SpotifyManager(applicationContext, clientId)
        spotifyManager = manager
        manager.connect { success, error ->
            if (success) {
                updateStatus("Connected to Spotify")
                observePlayback(manager)
                lifecycleScope.launch { startInitialPlayback(manager) }
            } else {
                updateStatus("Spotify connection failed: ${error?.message}")
            }
        }
    }

    /** Plays the current hour's personalized genre mix if Web API is connected, else a static fallback. */
    private suspend fun startInitialPlayback(manager: SpotifyManager) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (spotifyWebAuthManager?.isConnected() == true) {
            lastGenreSwitchHour = hour
            speakingMutex.withLock { runGenreSwitch(hour, announce = false) }
        } else {
            manager.playForSegment(DaySegment.forHour(hour))
        }
    }

    private fun observePlayback(manager: SpotifyManager) {
        manager.observePlayerState()
            .onEach { track -> onPlayerState(track) }
            .catch { e -> updateStatus("Playback stream error: ${e.message}") }
            .launchIn(lifecycleScope)
    }

    private fun onPlayerState(track: TrackInfo) {
        if (track.uri != lastTrackUri) {
            lastTrackUri = track.uri
            hasFiredTriviaForCurrentTrack = false
        }

        updateNotification("${track.artist} - ${track.title}", track.isPaused)

        if (speakingMutex.isLocked || track.isPaused) return

        maybeTriggerHourlyNews()
        maybeTriggerGenreSwitch()
        maybeTriggerTrackTrivia(track)
    }

    /** Top-of-hour news flash: fires once per hour, inside the first few minutes of that hour. */
    private fun maybeTriggerHourlyNews() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        if (minute > NEWS_WINDOW_MINUTE_CUTOFF) return
        if (hour == lastNewsFlashHour) return
        if (!speakingMutex.tryLock()) return // something else is already speaking; retry on the next player-state tick

        lastNewsFlashHour = hour
        lifecycleScope.launch {
            try {
                runNewsFlash()
            } finally {
                speakingMutex.unlock()
            }
        }
    }

    /** Top-of-hour genre switch: fires once per hour if Spotify Web API is connected. */
    private fun maybeTriggerGenreSwitch() {
        if (spotifyWebAuthManager == null) return
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        if (minute > GENRE_WINDOW_MINUTE_CUTOFF) return
        if (hour == lastGenreSwitchHour) return
        if (!speakingMutex.tryLock()) return // something else is already speaking; retry on the next player-state tick

        lastGenreSwitchHour = hour
        lifecycleScope.launch {
            try {
                if (spotifyWebAuthManager?.isConnected() == true) {
                    runGenreSwitch(hour, announce = true)
                }
            } finally {
                speakingMutex.unlock()
            }
        }
    }

    /** Between-track trivia: fires once per track when ~15s remain before it ends. */
    private fun maybeTriggerTrackTrivia(track: TrackInfo) {
        if (hasFiredTriviaForCurrentTrack) return
        if (track.durationMs <= 0) return
        val remaining = track.durationMs - track.positionMs
        if (remaining !in 0..TRIVIA_TRIGGER_WINDOW_MS) return
        if (!speakingMutex.tryLock()) return // something else is already speaking; if the ~15s window
        // closes before it frees up, this track's trivia is simply skipped (hasFiredTriviaForCurrentTrack
        // stays false, but the next track resets it anyway) - a rare missed line beats overlapping speech.

        hasFiredTriviaForCurrentTrack = true
        lifecycleScope.launch {
            try {
                runTrackTrivia(track)
            } finally {
                speakingMutex.unlock()
            }
        }
    }

    private suspend fun runNewsFlash() {
        val gemini = geminiClient ?: return
        updateStatus("Fetching news...")
        val preferences = settings.snapshotNewsPreferences()
        val headlinesResult = newsRepository.fetchTopHeadlines(preferences = preferences)
        val headlines = headlinesResult.getOrElse {
            updateStatus("News fetch failed: ${it.message}")
            return
        }
        val scriptResult = gemini.generateHourlyNews(headlines, likedTopics = preferences.likedTopics)
        val script = scriptResult.getOrElse {
            updateStatus("News script generation failed: ${it.message}")
            return
        }
        speakLine(script, label = "News flash")
    }

    /**
     * Rebuilds the hourly playlist for the new hour's genre and switches Spotify to it.
     * [announce] controls whether the DJ speaks a short genre-change line first - skipped on the
     * very first playback after connecting, since there's no need to narrate the opening pick.
     */
    private suspend fun runGenreSwitch(hour: Int, announce: Boolean) {
        val engine = hourlyMixEngine ?: return
        val manager = spotifyManager ?: return
        val rotation = settings.snapshotGenreRotation()
        val daySeed = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val genre = rotation.genreForHour(hour, daySeed) ?: return

        updateStatus("Building $genre mix for this hour...")
        val playlistUriResult = engine.buildAndPublishMixForGenre(genre)
        val playlistUri = playlistUriResult.getOrElse {
            updateStatus("Genre mix build failed: ${it.message}")
            return
        }

        if (announce) {
            val gemini = geminiClient
            val line = gemini?.generateGenreChangeLine(genre)?.getOrNull()
            if (line != null) {
                speakLine(line, label = "Genre change")
            }
        }

        manager.playUri(playlistUri)
        updateStatus("On air - $genre")
    }

    private suspend fun runTrackTrivia(track: TrackInfo) {
        val gemini = geminiClient ?: return
        updateStatus("Writing trivia for ${track.title}...")
        // In a fuller implementation, look ahead at Spotify's queue/context to know the next
        // track title; here we pass null and let the DJ speak generically about "the next song".
        val scriptResult = gemini.generateTrackTransition(track.artist, track.title, nextTitle = null)
        val script = scriptResult.getOrElse {
            updateStatus("Trivia generation failed: ${it.message}")
            return
        }
        speakLine(script, label = "Trivia")
    }

    private suspend fun speakLine(script: String, label: String) {
        val tts = ttsManager ?: return
        val manager = spotifyManager
        updateStatus("$label: speaking...")
        try {
            when (val result = tts.synthesize(script)) {
                is TtsResult.AudioFile -> {
                    audioPlaybackManager.playDuckedLine(result.file)
                    result.file.delete()
                }
                TtsResult.SpokenLocally -> {
                    // Local TextToSpeech already played synchronously inside TtsManager;
                    // Spotify was not explicitly ducked in that fallback path since the
                    // platform TTS engine requests its own transient focus internally.
                }
            }
        } finally {
            updateStatus("On air")
        }
        // Resume Spotify playback in case it was paused by the OS during the ducking window.
        manager?.play()
    }

    private fun togglePlayback() {
        val manager = spotifyManager ?: return
        // NOTE: SpotifyManager does not currently expose the last-known isPaused flag directly;
        // wire this to the latest TrackInfo (e.g. cache it in onPlayerState) for a precise toggle.
        manager.play()
    }

    private fun updateStatus(message: String) {
        _status.value = message
    }

    private fun updateNotification(trackLabel: String, isPaused: Boolean) {
        val notification = buildNotification(trackLabel, isPaused)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String, isPaused: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, RadioForegroundService::class.java).setAction(ACTION_PLAY_PAUSE)
        val playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RadioForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RadioApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Play" else "Pause",
                playPausePendingIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        spotifyManager?.disconnect()
        ttsManager?.release()
        audioPlaybackManager.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null // clients interact via StateFlow / broadcast, not binding, in this scaffold
    }
}
