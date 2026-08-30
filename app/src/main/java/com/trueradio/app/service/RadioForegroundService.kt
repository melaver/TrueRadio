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
import com.trueradio.app.TrackVerdict
import android.util.Log
import com.trueradio.app.DjLanguage
import com.trueradio.app.ai.GeminiClient
import com.trueradio.app.audio.AudioPlaybackManager
import com.trueradio.app.news.NewsRepository
import com.trueradio.app.spotify.HourlyMixEngine
import com.trueradio.app.spotify.SpotifyManager
import com.trueradio.app.spotify.SpotifyWebApiClient
import com.trueradio.app.spotify.SpotifyWebAuthManager
import com.trueradio.app.tts.LocalTtsFallback
import com.trueradio.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 *  5. Calls Gemini for a script, Gemini native TTS (with on-device fallback) for audio, and plays
 *     it back through AudioPlaybackManager with ducking.
 *  6. Publishes a persistent notification with status + Play/Pause controls.
 */
class RadioForegroundService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.trueradio.app.action.START"
        const val ACTION_STOP = "com.trueradio.app.action.STOP"
        const val ACTION_PLAY_PAUSE = "com.trueradio.app.action.PLAY_PAUSE"
        const val ACTION_LIKE = "com.trueradio.app.action.LIKE"
        const val ACTION_DISLIKE = "com.trueradio.app.action.DISLIKE"
        /** Debug-only: run the DJ flow immediately regardless of track position. */
        const val ACTION_FORCE_DJ = "com.trueradio.app.action.FORCE_DJ"
        /** Rebuild this hour's mix on demand with fresh material and start playing it. */
        const val ACTION_REMIX = "com.trueradio.app.action.REMIX"
        const val EXTRA_SPOTIFY_CLIENT_ID = "extra_spotify_client_id"
        private const val NOTIFICATION_ID = 42
        private const val TRIVIA_TRIGGER_WINDOW_MS = 15_000L
        /**
         * News now fires after this much accumulated *playback* time rather than at the top of
         * the hour. Playback time (not wall-clock) means pausing for an hour doesn't leave a
         * news flash queued to fire the instant you resume.
         */
        private const val NEWS_INTERVAL_PLAYBACK_MS = 20 * 60 * 1000L // 20 minutes of music
        /**
         * 2s, not 10s: the trivia window is only ~15s wide, so a coarse tick could step straight
         * over it (…20s left, tick, 8s left) and miss the boundary entirely.
         */
        private const val PLAYBACK_TICK_MS = 2_000L
        private const val DJ_TAG = "DJ_FLOW"
        private const val GENRE_WINDOW_MINUTE_CUTOFF = 3 // same top-of-hour window as news
        private const val TAG = "RadioDJService"
        /**
         * Must exceed GeminiClient's worst case: 3 attempts x 45s read timeout, plus ~3s of
         * backoff between them. A tighter bound here would cancel the retry chain mid-backoff,
         * silently defeating the retries added for Gemini's transient 503s.
         */
        private const val SPEECH_TIMEOUT_MS = 150_000L
        private const val PLAYBACK_TIMEOUT_MS = 90_000L
    }

    private lateinit var settings: SecureSettings
    private var spotifyManager: SpotifyManager? = null
    private var geminiClient: GeminiClient? = null
    private var localTts: LocalTtsFallback? = null
    private var djLanguage: DjLanguage = DjLanguage.HEBREW
    private lateinit var newsRepository: NewsRepository
    private lateinit var audioPlaybackManager: AudioPlaybackManager
    private var spotifyWebAuthManager: SpotifyWebAuthManager? = null
    private var hourlyMixEngine: HourlyMixEngine? = null

    private var lastTrackUri: String? = null
    // Full current track, needed so like/dislike can record artist as well as URI.
    private var currentTrack: TrackInfo? = null

    /**
     * Wall-clock time the last PlayerState arrived, used to PROJECT the current playback position
     * between events. App Remote only emits on state *changes*, so during steady playback of a
     * 4-minute track there may be no events at all inside the 15s trivia window - reading
     * positionMs straight off the last event would have it frozen minutes in the past. Projection
     * gives a continuously-accurate position without polling the SDK.
     */
    private var lastStateTimestampMs: Long = 0L

    /**
     * URI of the track whose DJ segment has already been handled. Guards against double-triggers:
     * a boolean flag alone breaks when a track repeats (same URI, needs re-arming) or when
     * several ticks land inside the same window before the mutex is taken.
     */
    private var lastProcessedTrackId: String? = null

    /** Incremented per manual remix so each one reaches different material - see HourlyMixEngine. */
    private var remixCount = 0
    /** Guards against a double-tap queuing two overlapping rebuilds of the same playlist. */
    private var isRemixing = false
    // Accumulated milliseconds of actual playback since the last news flash.
    private var playbackMsSinceNews = 0L
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

    // The notification is the ONLY thing an end user can actually see once the DJ is running -
    // there's no bound-service/UI wiring to `status` above, so without this, every one of the
    // many updateStatus(...) calls throughout this file (connection failures, news-fetch
    // failures, genre-mix build failures, etc.) would update an internal StateFlow that nothing
    // ever reads, leaving failures completely invisible ("nothing happens" with no way to tell
    // why). Both pieces of state below feed the same notification: title = current track,
    // text = latest status message - so failures actually surface somewhere the user can see.
    private var lastKnownTrackLabel: String = "Starting up..."
    private var lastKnownIsPaused: Boolean = false

    override fun onCreate() {
        super.onCreate()
        settings = SecureSettings(applicationContext)
        newsRepository = NewsRepository()
        audioPlaybackManager = AudioPlaybackManager(applicationContext)
        RadioServiceState.setRunning(true)
        startForeground(NOTIFICATION_ID, buildNotification(lastKnownTrackLabel, _status.value, isPaused = false))
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
            ACTION_REMIX -> {
                remixCurrentMix()
                return START_STICKY
            }
            ACTION_FORCE_DJ -> {
                forceDjTransition()
                return START_STICKY
            }
            ACTION_LIKE -> {
                recordFeedback(liked = true)
                return START_STICKY
            }
            ACTION_DISLIKE -> {
                recordFeedback(liked = false)
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
        djLanguage = settings.snapshotDjLanguage()

        if (clientId.isBlank()) {
            updateStatus("Missing Spotify client ID - open the app and enter your keys.")
            return
        }

        if (geminiKey.isBlank()) {
            Log.e(TAG, "Gemini API key is blank - DJ speech will be unavailable")
            updateStatus("Missing Gemini API key - the DJ can't speak. Add it in Settings.")
        }
        geminiClient = GeminiClient(geminiKey, djLanguage)
        localTts = LocalTtsFallback(applicationContext)
        Log.d(TAG, "Initialized DJ (language=$djLanguage, geminiKeyPresent=${geminiKey.isNotBlank()})")

        val webAuth = SpotifyWebAuthManager(applicationContext, settings, clientId)
        spotifyWebAuthManager = webAuth
        hourlyMixEngine = HourlyMixEngine(SpotifyWebApiClient(webAuth), settings, geminiClient)

        val manager = SpotifyManager(applicationContext, clientId)
        spotifyManager = manager
        manager.connect { success, error ->
            if (success) {
                updateStatus("Connected to Spotify")
                observePlayback(manager)
                startPlaybackTicker()
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
        val previous = currentTrack
        currentTrack = track
        lastStateTimestampMs = System.currentTimeMillis()

        // Re-arm on a genuine new track OR on a restart of the same one. Keying only on URI would
        // permanently suppress trivia for a track played twice in a row (repeat-one, or a replay),
        // since its URI never changes.
        val isNewTrack = track.uri != lastTrackUri
        val restartedSameTrack = !isNewTrack && previous != null &&
            track.positionMs + 5_000 < previous.positionMs // position jumped backwards = restart/seek
        if (isNewTrack || restartedSameTrack) {
            Log.d(DJ_TAG, "Track changed: ${track.artist} - ${track.title} (${track.durationMs}ms), re-arming DJ")
            lastTrackUri = track.uri
            lastProcessedTrackId = null
        }

        updateNotification("${track.artist} - ${track.title}", track.isPaused)

        if (speakingMutex.isLocked || track.isPaused) return
        maybeTriggerGenreSwitch()
    }

    /**
     * Playback position projected forward from the last PlayerState event. Returns null when
     * paused or when no state has arrived yet.
     */
    private fun projectedPosition(): Pair<TrackInfo, Long>? {
        val track = currentTrack ?: return null
        if (track.isPaused || lastStateTimestampMs == 0L) return null
        val elapsed = System.currentTimeMillis() - lastStateTimestampMs
        val projected = track.positionMs + elapsed
        // Guard against a stale event projecting past the end of the track.
        if (track.durationMs > 0 && projected > track.durationMs + 30_000) return null
        return track to projected
    }

    /**
     * Drives time-based triggers from a steady ticker rather than from PlayerState callbacks.
     *
     * App Remote emits PlayerState on *changes* (play/pause/seek/track change), not on a clock,
     * so accumulating elapsed time from those events alone would undercount badly during a long
     * track - a 4-minute song might produce almost no events. The ticker gives a reliable time
     * base independent of how chatty the SDK happens to be.
     */
    private fun startPlaybackTicker() {
        lifecycleScope.launch {
            while (isActive) {
                delay(PLAYBACK_TICK_MS)
                if (lastKnownIsPaused) continue // only count time the music is actually playing

                playbackMsSinceNews += PLAYBACK_TICK_MS
                if (playbackMsSinceNews >= NEWS_INTERVAL_PLAYBACK_MS) {
                    maybeTriggerNewsFlash()
                    continue // don't also evaluate trivia on the same tick
                }
                maybeTriggerTrackTrivia()
            }
        }
    }

    /** News flash: fires after NEWS_INTERVAL_PLAYBACK_MS of accumulated playback. */
    private fun maybeTriggerNewsFlash() {
        if (!speakingMutex.tryLock()) return // something else is speaking; retry on the next tick

        // Reset before the coroutine runs so a slow fetch can't let a second flash queue up.
        playbackMsSinceNews = 0L
        Log.d(TAG, "News flash triggered after 20 min of playback")
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
        Log.d(TAG, "Genre switch triggered for hour $hour")
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

    /**
     * Between-track trivia: fires exactly once per track when ~15s remain.
     *
     * Driven by the ticker using a projected position rather than by PlayerState callbacks -
     * see [projectedPosition] for why event-driven detection missed the window entirely.
     */
    private fun maybeTriggerTrackTrivia() {
        val (track, position) = projectedPosition() ?: return
        if (track.durationMs <= 0) return
        if (track.uri == lastProcessedTrackId) return // already handled this track

        val remaining = track.durationMs - position
        if (remaining !in 0..TRIVIA_TRIGGER_WINDOW_MS) return

        // Claim the track id BEFORE taking the mutex. Ticks are 2s apart and the window is ~15s,
        // so several ticks fall inside it; without claiming first, a slow mutex handoff could let
        // two of them both pass the check and fire duplicate Gemini calls for one boundary.
        lastProcessedTrackId = track.uri

        if (!speakingMutex.tryLock()) {
            Log.d(DJ_TAG, "Step 1: boundary hit for '${track.title}' but DJ busy - skipping this one")
            return
        }

        Log.d(DJ_TAG, "Step 1: DETECTED boundary - '${track.artist} - ${track.title}', ${remaining}ms remaining")
        lifecycleScope.launch {
            try {
                runTrackTrivia(track)
            } finally {
                speakingMutex.unlock()
            }
        }
    }

    /**
     * Rebuilds the current hour's playlist with fresh material and starts it.
     *
     * Requires the Spotify Web API connection: without it there's no playlist to rebuild, only
     * the static fallback playlists, so remix is a no-op rather than a silent misleading success.
     */
    private fun remixCurrentMix() {
        val engine = hourlyMixEngine
        if (engine == null || spotifyWebAuthManager == null) {
            Log.w(TAG, "Remix unavailable - Spotify Web API not connected")
            updateStatus("Connect your Spotify account to remix")
            return
        }
        if (isRemixing) {
            Log.d(TAG, "Remix already in progress; ignoring")
            return
        }
        isRemixing = true
        remixCount++

        lifecycleScope.launch {
            try {
                if (spotifyWebAuthManager?.isConnected() != true) {
                    updateStatus("Connect your Spotify account to remix")
                    return@launch
                }
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val segment = DaySegment.forHour(hour)
                // Reuse whatever genre is actually on air; fall back to the daypart's first
                // configured genre if the service hasn't built a mix yet this session.
                val genre = RadioServiceState.currentGenre.value
                    ?: settings.snapshotSegmentGenres().genresFor(segment).firstOrNull()
                if (genre == null) {
                    updateStatus("No genre configured for this time of day")
                    return@launch
                }

                updateStatus("Remixing $genre...")
                Log.d(TAG, "Remix #$remixCount for genre '$genre'")
                val uri = engine.buildAndPublishMixForGenre(genre, variation = remixCount)
                    .getOrElse {
                        Log.e(TAG, "Remix failed", it)
                        updateStatus("Remix failed: ${it.message}")
                        return@launch
                    }
                RadioServiceState.setCurrentGenre(genre)
                spotifyManager?.playUri(uri)
                updateStatus("Fresh $genre mix on air")
            } catch (e: Exception) {
                Log.e(TAG, "Remix threw", e)
                updateStatus("Remix failed")
            } finally {
                isRemixing = false
            }
        }
    }

    /**
     * Debug entry point for ACTION_FORCE_DJ: runs the full DJ flow against whatever is playing,
     * ignoring track position, so the pipeline can be verified without waiting for a song to end.
     */
    private fun forceDjTransition() {
        val track = currentTrack
        if (track == null) {
            Log.e(DJ_TAG, "FORCE: no current track - is Spotify playing?")
            updateStatus("Force DJ: nothing is playing")
            return
        }
        if (!speakingMutex.tryLock()) {
            Log.w(DJ_TAG, "FORCE: DJ already speaking, ignoring")
            return
        }
        Log.d(DJ_TAG, "FORCE: manually triggering DJ for '${track.artist} - ${track.title}'")
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

        // Prefer the genres the user picked for THIS daypart (Settings > "Music genres by time of
        // day"). Those were previously stored and editable but never actually consulted here, so
        // changing them had no audible effect - the global rotation list won regardless. The
        // global list is now only the fallback for a daypart with nothing selected.
        val segment = DaySegment.forHour(hour)
        val segmentGenres = settings.snapshotSegmentGenres().genresFor(segment)
        val genre = if (segmentGenres.isNotEmpty()) {
            // Same rotation semantics as GenreRotation: sequential walks the list by hour,
            // otherwise pick a per-hour-stable but day-varying entry.
            if (rotation.sequential) {
                segmentGenres[hour % segmentGenres.size]
            } else {
                segmentGenres[kotlin.random.Random(hour * 31 + daySeed).nextInt(segmentGenres.size)]
            }
        } else {
            rotation.genreForHour(hour, daySeed)
        } ?: return
        Log.d(TAG, "Hour $hour -> segment $segment -> genre '$genre'")
        RadioServiceState.setDaySegment(segment)
        RadioServiceState.setCurrentGenre(genre)

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
        Log.d(DJ_TAG, "Step 2: generating script for '${track.artist} - ${track.title}'")
        updateStatus("Writing trivia for ${track.title}...")
        // In a fuller implementation, look ahead at Spotify's queue/context to know the next
        // track title; here we pass null and let the DJ speak generically about "the next song".
        val scriptResult = gemini.generateTrackTransition(track.artist, track.title, nextTitle = null)
        val script = scriptResult.getOrElse {
            Log.e(DJ_TAG, "Step 2: SCRIPT GENERATION FAILED - ${it.message}; segment aborted, music unaffected")
            updateStatus("Trivia generation failed: ${it.message}")
            return
        }
        speakLine(script, label = "Trivia")
    }

    /**
     * Full DJ speech path: Gemini TTS -> ducked WAV playback, with on-device TTS as fallback.
     *
     * Every stage is logged and time-boxed. The hard timeout matters more than it looks: if a
     * synthesis or playback call hangs, audio focus stays held and Spotify stays ducked
     * indefinitely - the music would sit quiet forever with no error. Bailing out and letting
     * music continue is always better than a stuck DJ.
     */
    private suspend fun speakLine(script: String, label: String) {
        val gemini = geminiClient
        val startedAt = System.currentTimeMillis()
        Log.d(DJ_TAG, "Step 3: [$label] API REQUEST START - script: \"${script.take(60)}...\"")
        updateStatus("$label: speaking...")

        try {
            val wav = withTimeoutOrNull(SPEECH_TIMEOUT_MS) {
                gemini?.synthesizeSpeech(script)?.getOrNull()
            }

            if (wav != null) {
                Log.d(DJ_TAG, "Step 4: [$label] API SUCCESS - ${wav.size} bytes in ${System.currentTimeMillis() - startedAt}ms")
                val played = withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
                    audioPlaybackManager.playDuckedAudio(wav)
                    true
                }
                if (played == null) {
                    Log.e(DJ_TAG, "Step 6: [$label] PLAYBACK TIMED OUT - focus released by cancellation")
                } else {
                    Log.d(DJ_TAG, "Step 6: [$label] PLAYBACK COMPLETE")
                }
            } else {
                Log.w(DJ_TAG, "Step 4: [$label] API FAILED after ${System.currentTimeMillis() - startedAt}ms - falling back to on-device TTS")
                val spoke = localTts?.speak(script, djLanguage) ?: false
                Log.d(DJ_TAG, "Step 6: [$label] fallback TTS ${if (spoke) "COMPLETE" else "FAILED - segment skipped"}")
            }
        } catch (e: Exception) {
            // Never let a DJ failure kill the service or leave music ducked.
            Log.e(DJ_TAG, "[$label] speech path threw - aborting segment", e)
        } finally {
            updateStatus("On air")
            Log.d(DJ_TAG, "Step 7: [$label] SEGMENT END - restoring Spotify")
            spotifyManager?.play() // resume in case the OS paused rather than ducked
        }
    }

    /**
     * Records a like/dislike for whatever is playing. A dislike also skips the track: pressing
     * "dislike" and then continuing to hear the song out would be a strange experience, and the
     * skip is the immediate feedback that the button did something.
     */
    private fun recordFeedback(liked: Boolean) {
        val track = currentTrack
        if (track == null || track.uri.isBlank()) {
            Log.w(TAG, "Feedback ignored - no current track")
            return
        }
        val verdict = TrackVerdict(track.uri, track.artist)
        lifecycleScope.launch {
            try {
                val current = settings.snapshotTrackFeedback()
                val updated = if (liked) current.withLike(verdict) else current.withDislike(verdict)
                settings.saveTrackFeedback(updated)
                Log.d(TAG, "Recorded ${if (liked) "LIKE" else "DISLIKE"} for ${track.artist} - ${track.title}")
                updateStatus(if (liked) "Liked ${track.title}" else "Disliked ${track.title} - skipping")
                if (!liked) spotifyManager?.skipNext()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record feedback", e)
            }
        }
    }

    private fun togglePlayback() {
        val manager = spotifyManager ?: return
        // NOTE: SpotifyManager does not currently expose the last-known isPaused flag directly;
        // wire this to the latest TrackInfo (e.g. cache it in onPlayerState) for a precise toggle.
        manager.play()
    }

    private fun updateStatus(message: String) {
        _status.value = message
        RadioServiceState.setStatus(message)
        refreshNotification()
    }

    private fun updateNotification(trackLabel: String, isPaused: Boolean) {
        RadioServiceState.setNowPlaying(trackLabel)
        lastKnownTrackLabel = trackLabel
        lastKnownIsPaused = isPaused
        refreshNotification()
    }

    private fun refreshNotification() {
        val notification = buildNotification(lastKnownTrackLabel, _status.value, isPaused = lastKnownIsPaused)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(trackLabel: String, statusText: String, isPaused: Boolean): Notification {
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

        val likeIntent = Intent(this, RadioForegroundService::class.java).setAction(ACTION_LIKE)
        val likePendingIntent = PendingIntent.getService(
            this, 3, likeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dislikeIntent = Intent(this, RadioForegroundService::class.java).setAction(ACTION_DISLIKE)
        val dislikePendingIntent = PendingIntent.getService(
            this, 4, dislikeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RadioForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RadioApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(trackLabel)
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$trackLabel\n$statusText"))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Play" else "Pause",
                playPausePendingIntent
            )
            .addAction(android.R.drawable.btn_star_big_on, "Like", likePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dislike", dislikePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        spotifyManager?.disconnect()
        RadioServiceState.setRunning(false)
        localTts?.release()
        audioPlaybackManager.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null // clients interact via StateFlow / broadcast, not binding, in this scaffold
    }
}
