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
import com.trueradio.app.VoiceMode
import com.trueradio.app.ai.GeminiClient
import com.trueradio.app.audio.AudioPlaybackManager
import com.trueradio.app.news.NewsRepository
import com.trueradio.app.spotify.HourlyMixEngine
import com.trueradio.app.spotify.SpotifyManager
import com.trueradio.app.spotify.SpotifyWebApiClient
import com.trueradio.app.spotify.SpotifyWebAuthManager
import com.trueradio.app.tts.CloudTtsClient
import com.trueradio.app.tts.LocalTtsFallback
import com.trueradio.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Job
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
        /** Debug/manual: read the news flash immediately, resetting the 20-minute timer. */
        const val ACTION_FORCE_NEWS = "com.trueradio.app.action.FORCE_NEWS"
        /** Sets or clears the sleep timer; minutes in EXTRA_SLEEP_MINUTES (0 = cancel). */
        const val ACTION_SET_SLEEP_TIMER = "com.trueradio.app.action.SET_SLEEP_TIMER"
        const val EXTRA_SLEEP_MINUTES = "extra_sleep_minutes"
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
        /** How many upcoming tracks' scripts to generate per batched request. */
        private const val SCRIPT_BATCH_SIZE = 10
        /** Refill the batch when fewer than this many cached scripts remain. */
        private const val SCRIPT_REFILL_THRESHOLD = 3
        /** Play a real trivia script every Nth segment; evergreen filler otherwise. */
        private const val REAL_TRIVIA_EVERY = 3
        /** Minimum gap between automatic playlist rebuilds; user-initiated remixes ignore this. */
        private const val MIN_MIX_INTERVAL_MS = 10 * 60 * 1000L
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
    private var cloudTts: CloudTtsClient? = null
    private var cloudTtsVoice: String = CloudTtsClient.DEFAULT_VOICE
    private var djLanguage: DjLanguage = DjLanguage.ENGLISH
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
    /**
     * Trivia audio generated ahead of time for a specific track, played when that track reaches
     * its boundary.
     *
     * Previously generation started AT the 15s boundary, which gave Gemini ~15 seconds to return
     * both a script and audio. Any retry (429/503) blew straight past the track end and the
     * segment was lost. Generating at track start gives the whole song's duration of headroom, so
     * transient failures have room to recover.
     *
     * NOTE: this does not by itself reduce quota usage - 429 is a rate limit, not a timing
     * problem - it just stops a slow-but-eventually-successful call from missing its window.
     * Quota pressure is addressed separately by GeminiClient's artist-list cache.
     */
    /**
     * Pre-generated trivia SCRIPTS keyed "artist|title", filled 5 at a time by one batched Gemini
     * call. Scripts batch cheaply; audio does not (TTS returns one stream per request), so audio
     * is still synthesized per track in [prefetchTriviaFor] using whatever script is waiting here.
     */
    private val scriptCache = mutableMapOf<String, String>()
    private var batchJob: Job? = null
    /** Keys already batched, so a refill doesn't regenerate scripts for the same tracks. */
    private val consumedScriptKeys = mutableSetOf<String>()

    /** Reusable generic lines, generated once and replayed - see GeminiClient.generateEvergreenLines. */
    private var evergreenLines: List<String> = emptyList()
    private var segmentCounter = 0

    private var preparedTrackUri: String? = null
    private var preparedAudio: ByteArray? = null
    private var prefetchJob: Job? = null

    /** Tracks played since the DJ last spoke, compared against the user's DJ frequency setting. */
    private var tracksSinceDj = 0
    private var djEveryNTracks = 2
    private var voiceMode: VoiceMode = VoiceMode.BALANCED

    /** Wall-clock time the radio should stop, or 0 when no sleep timer is set. */
    private var sleepAtMs = 0L

    private var remixCount = 0
    /** Guards against a double-tap queuing two overlapping rebuilds of the same playlist. */
    private var isRemixing = false
    // Accumulated milliseconds of actual playback since the last news flash.
    private var playbackMsSinceNews = 0L
    private var lastGenreSwitchHour = -1

    /**
     * Guards against re-initialising an already-running session. onStartCommand's else-branch
     * runs for ANY unrecognised intent - including the null intent Android redelivers under
     * START_STICKY after the service is killed and restarted. Without this guard each redelivery
     * built a second SpotifyManager, added a SECOND playback subscription (so every trigger fired
     * twice, then three times...), and rebuilt the playlist. That is why a new playlist appeared
     * roughly every song, and a large part of the Gemini 429s.
     */
    private var isInitialized = false
    private var playbackJob: Job? = null

    /**
     * When the current mix was built. Rebuilding is expensive (several Gemini + Spotify calls),
     * so a cooldown makes accidental repeat triggers cheap to absorb rather than catastrophic.
     * Explicit user remixes bypass it.
     */
    private var lastMixBuiltAtMs = 0L

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
            ACTION_SET_SLEEP_TIMER -> {
                setSleepTimer(intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0))
                return START_STICKY
            }
            ACTION_FORCE_NEWS -> {
                forceNewsFlash()
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
                if (isInitialized) {
                    // Almost always a START_STICKY redelivery; re-initialising would duplicate
                    // the Spotify connection and rebuild the playlist for no reason.
                    Log.d(TAG, "Already initialized - ignoring duplicate start command")
                    return START_STICKY
                }
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
        djEveryNTracks = settings.snapshotDjEveryNTracks()
        voiceMode = settings.snapshotVoiceMode()
        Log.d(DJ_TAG, "Voice mode: $voiceMode")

        if (clientId.isBlank()) {
            updateStatus("Missing Spotify client ID - open the app and enter your keys.")
            return
        }

        if (geminiKey.isBlank()) {
            Log.e(TAG, "Gemini API key is blank - DJ speech will be unavailable")
            updateStatus("Missing Gemini API key - the DJ can't speak. Add it in Settings.")
        }
        isInitialized = true
        geminiClient = GeminiClient(geminiKey, djLanguage)
        localTts = LocalTtsFallback(applicationContext)

        // Speech comes from Cloud TTS (separate, far larger quota); Gemini only writes scripts.
        val cloudKey = settings.snapshotCloudTtsKey()
        cloudTtsVoice = settings.snapshotCloudTtsVoice()
        cloudTts = if (cloudKey.isNotBlank()) CloudTtsClient(cloudKey) else null
        Log.d(DJ_TAG, "Cloud TTS ${if (cloudTts != null) "enabled (voice=$cloudTtsVoice)" else "not configured - using device voice"}")

        // Seed caches from disk: the mix is built from the user's top artists, so the same tracks
        // recur constantly and previously-written scripts are almost always reusable.
        scriptCache.putAll(settings.loadScriptCache())
        evergreenLines = settings.loadEvergreenLines()
        geminiClient?.primeArtistCache(settings.loadArtistListCache())
        Log.d(DJ_TAG, "Loaded ${scriptCache.size} cached scripts, ${evergreenLines.size} evergreen lines")
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
        // Cancel any previous subscription before starting a new one - two live subscriptions
        // would deliver every PlayerState twice and double every downstream trigger.
        playbackJob?.cancel()
        playbackJob = manager.observePlayerState()
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
            tracksSinceDj++
            // Only prepare audio for tracks the DJ will actually talk after - each segment costs
            // a Gemini TTS call, and this is the biggest single lever on quota usage.
            if (tracksSinceDj >= djEveryNTracks) {
                // Only prepare a real script when the upcoming segment will actually use one -
                // evergreen segments need nothing prepared, so prefetching for them would pay
                // Gemini for output that gets discarded.
                val willUseRealTrivia = (segmentCounter + 1) % REAL_TRIVIA_EVERY == 0
                if (willUseRealTrivia || evergreenLines.isEmpty()) {
                    prefetchTriviaFor(track)
                } else {
                    Log.d(DJ_TAG, "Next segment is evergreen - skipping prefetch")
                    preparedAudio = null
                    preparedTrackUri = null
                }
            } else {
                Log.d(DJ_TAG, "Skipping DJ for this track ($tracksSinceDj/$djEveryNTracks)")
                preparedAudio = null
                preparedTrackUri = null
            }
        }

        updateNotification("${track.artist} - ${track.title}", track.isPaused)
        if (isNewTrack) RadioServiceState.addHistory(isDjLine = false, text = "${track.artist} - ${track.title}")

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
                // Sleep timer is checked BEFORE the paused guard: it's a wall-clock promise to
                // stop, so pausing and walking away must still shut the radio down rather than
                // leaving the service running indefinitely with a frozen timer.
                if (sleepAtMs > 0L) {
                    val remainingMs = sleepAtMs - System.currentTimeMillis()
                    if (remainingMs <= 0L) {
                        Log.d(TAG, "Sleep timer elapsed - stopping radio")
                        sleepAtMs = 0L
                        RadioServiceState.setSleepMinutesRemaining(null)
                        spotifyManager?.pause()
                        stopSelf()
                        return@launch
                    }
                    RadioServiceState.setSleepMinutesRemaining((remainingMs / 60_000).toInt() + 1)
                }

                if (lastKnownIsPaused) continue // only count playback time for everything below

                RadioServiceState.setRateLimited(GeminiClient.isRateLimited())
                RadioServiceState.setDailyQuotaExhausted(GeminiClient.isLikelyDailyQuotaExhausted())
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

        // Respect the DJ frequency setting - nothing was prepared for skipped tracks.
        if (tracksSinceDj < djEveryNTracks) return

        if (!speakingMutex.tryLock()) {
            Log.d(DJ_TAG, "Step 1: boundary hit for '${track.title}' but DJ busy - skipping this one")
            return
        }

        // Reset ONLY after winning the mutex. Resetting before the lock meant a boundary that
        // collided with a news flash both lost its segment and restarted the counter, so the DJ
        // then stayed silent for another full N tracks - two penalties for one collision.
        tracksSinceDj = 0

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
     * Generates this track's outro audio at the START of the track, so it's ready and waiting by
     * the time the boundary arrives. See [preparedTrackUri] for why.
     */
    private fun prefetchTriviaFor(track: TrackInfo) {
        if (GeminiClient.isRateLimited()) {
            Log.d(DJ_TAG, "Skipping prefetch - rate limited for ${GeminiClient.rateLimitSecondsRemaining()}s")
            return
        }
        // Don't prefetch for very short tracks - the boundary may arrive before generation
        // finishes, and it would just waste a call.
        if (track.durationMs in 1..45_000) {
            Log.d(DJ_TAG, "Skipping prefetch for short track (${track.durationMs}ms)")
            return
        }

        prefetchJob?.cancel() // a new track supersedes any in-flight prefetch for the old one
        preparedTrackUri = null
        preparedAudio = null

        val gemini = geminiClient ?: return
        prefetchJob = lifecycleScope.launch {
            try {
                Log.d(DJ_TAG, "Step 0: PREFETCH START for '${track.artist} - ${track.title}'")

                // Prefer a script from the batch; only fall back to a dedicated call when this
                // track wasn't in the batch (e.g. it came from search rather than the known mix).
                val cacheKey = "${track.artist}|${track.title}"
                val cached = scriptCache.remove(cacheKey)
                val script = cached ?: gemini.generateTrackTransition(track.artist, track.title, nextTitle = null)
                    .getOrElse {
                        Log.w(DJ_TAG, "Step 0: prefetch script failed (${it.message}) - will retry live at the boundary")
                        return@launch
                    }
                Log.d(DJ_TAG, "Step 0: script source = ${if (cached != null) "BATCH CACHE" else "individual call"}")
                maybeRefillScriptBatch()
                if (!useGeminiVoice("Trivia")) {
                    // On-device voice needs no pre-synthesis; cache just the script and let
                    // speakLine render it at the boundary.
                    scriptCache["${track.artist}|${track.title}"] = script
                    Log.d(DJ_TAG, "Step 0: script ready, skipping Gemini TTS (mode=$voiceMode)")
                    return@launch
                }
                val tts = cloudTts
                if (tts == null) {
                    scriptCache["${track.artist}|${track.title}"] = script
                    Log.d(DJ_TAG, "Step 0: script ready, no Cloud TTS configured")
                    return@launch
                }
                val wav = tts.synthesize(script, cloudTtsVoice).getOrElse {
                    Log.w(DJ_TAG, "Step 0: prefetch TTS failed (${it.message}) - will retry live at the boundary")
                    return@launch
                }
                preparedTrackUri = track.uri
                preparedAudio = wav
                Log.d(DJ_TAG, "Step 0: PREFETCH READY (${wav.size} bytes) for '${track.title}'")
            } catch (e: Exception) {
                Log.e(DJ_TAG, "Step 0: prefetch threw", e)
            }
        }
    }

    /**
     * Tops up [scriptCache] with one batched request covering the next few tracks in the
     * published mix. Runs only when the cache is nearly empty, so it costs roughly one Gemini
     * call per five songs instead of one per song.
     */
    /** Generates the evergreen bank once, on first run. */
    private fun ensureEvergreenLines() {
        if (evergreenLines.isNotEmpty()) return
        if (GeminiClient.isRateLimited()) return
        val gemini = geminiClient ?: return
        lifecycleScope.launch {
            gemini.generateEvergreenLines().getOrNull()?.let { lines ->
                if (lines.isNotEmpty()) {
                    evergreenLines = lines
                    settings.saveEvergreenLines(lines)
                    Log.d(DJ_TAG, "Generated ${lines.size} evergreen lines (one-time)")
                }
            }
        }
    }

    private fun maybeRefillScriptBatch() {
        if (GeminiClient.isRateLimited()) return
        if (scriptCache.size >= SCRIPT_REFILL_THRESHOLD) return
        if (batchJob?.isActive == true) return // a refill is already in flight

        val gemini = geminiClient ?: return
        val upcoming = hourlyMixEngine?.lastPublishedTracks.orEmpty()
        if (upcoming.isEmpty()) {
            Log.d(DJ_TAG, "No published track list available - trivia stays per-track")
            return
        }

        // Skip anything already cached or already played this cycle.
        val candidates = upcoming
            .filterNot { scriptCache.containsKey("${it.first}|${it.second}") }
            .filterNot { "${it.first}|${it.second}" in consumedScriptKeys }
            .take(SCRIPT_BATCH_SIZE)
        if (candidates.isEmpty()) return

        batchJob = lifecycleScope.launch {
            try {
                Log.d(DJ_TAG, "Step 0b: BATCH REQUEST for ${candidates.size} upcoming tracks")
                val scripts = gemini.generateTrackTransitionBatch(candidates).getOrElse {
                    Log.w(DJ_TAG, "Step 0b: batch failed (${it.message}) - falling back to per-track generation")
                    return@launch
                }
                scriptCache.putAll(scripts)
                consumedScriptKeys += scripts.keys
                settings.saveScriptCache(scriptCache)
                geminiClient?.artistCacheSnapshot()?.let { settings.saveArtistListCache(it) }
                Log.d(DJ_TAG, "Step 0b: BATCH READY - ${scripts.size} scripts cached")
            } catch (e: Exception) {
                Log.e(DJ_TAG, "Step 0b: batch threw", e)
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
                val uri = engine.buildAndPublishMixForGenre(genre, variation = remixCount, segment = segment)
                    .getOrElse {
                        Log.e(TAG, "Remix failed", it)
                        updateStatus("Remix failed: ${it.message}")
                        return@launch
                    }
                lastMixBuiltAtMs = System.currentTimeMillis()
                RadioServiceState.setCurrentGenre(genre)
                // New playlist means the cached scripts describe tracks that are no longer queued.
                scriptCache.clear()
                consumedScriptKeys.clear()
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
     * Reads the news on demand. Resets the playback timer so a manual read doesn't get followed
     * moments later by the scheduled one.
     */
    private fun forceNewsFlash() {
        if (!speakingMutex.tryLock()) {
            Log.w(DJ_TAG, "FORCE NEWS: DJ already speaking, ignoring")
            return
        }
        playbackMsSinceNews = 0L
        Log.d(DJ_TAG, "FORCE NEWS: manual news flash requested")
        lifecycleScope.launch {
            try {
                runNewsFlash()
            } finally {
                speakingMutex.unlock()
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
        val newsLength = settings.snapshotNewsLength()
        val headlinesResult = newsRepository.fetchTopHeadlines(
            limit = newsLength.headlineCount,
            preferences = preferences
        )
        val headlines = headlinesResult.getOrElse {
            updateStatus("News fetch failed: ${it.message}")
            return
        }
        // Headlines are already in hand from RSS - if Gemini can't summarise them, read them
        // directly rather than dropping the bulletin. No AI needed for a news read to be useful.
        if (GeminiClient.isRateLimited()) {
            Log.w(DJ_TAG, "News: Gemini unavailable, reading headlines directly")
            updateStatus("News (headline mode)")
            speakLine(templatedNewsScript(headlines), label = "News flash")
            return
        }

        val scriptResult = gemini.generateHourlyNews(
            headlines,
            likedTopics = preferences.likedTopics,
            lengthHint = newsLength.promptHint
        )
        val script = scriptResult.getOrElse {
            // Same reasoning as the rate-limit branch above: we already have the headlines.
            Log.w(DJ_TAG, "News script failed (${it.message}) - reading headlines directly")
            updateStatus("News (headline mode)")
            speakLine(templatedNewsScript(headlines), label = "News flash")
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
        val sinceLastBuild = System.currentTimeMillis() - lastMixBuiltAtMs
        if (lastMixBuiltAtMs != 0L && sinceLastBuild < MIN_MIX_INTERVAL_MS) {
            Log.w(TAG, "Skipping mix rebuild - last one was ${sinceLastBuild / 1000}s ago (cooldown ${MIN_MIX_INTERVAL_MS / 1000}s)")
            return
        }
        val engine = hourlyMixEngine ?: return
        val manager = spotifyManager ?: return
        val rotation = settings.snapshotGenreRotation()
        val daySeed = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

        // Prefer the genres the user picked for THIS daypart (Settings > "Music genres by time of
        // day"). Those were previously stored and editable but never actually consulted here, so
        // changing them had no audible effect - the global rotation list won regardless. The
        // global list is now only the fallback for a daypart with nothing selected.
        val segment = DaySegment.forHour(hour)
        // A genre the user tuned to on the dial wins for this build, then is cleared.
        val tunedOverride = settings.consumeTunedGenreOverride()
        val segmentGenres = tunedOverride?.let { listOf(it) }
            ?: settings.snapshotSegmentGenres().genresFor(segment)
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
        // The hourly switch replaces the playlist, so previously batched scripts describe tracks
        // that are no longer queued - same reasoning as the remix path.
        scriptCache.clear()
        consumedScriptKeys.clear()

        updateStatus("Building $genre mix for this hour...")
        val playlistUriResult = engine.buildAndPublishMixForGenre(genre, segment = segment)
        val playlistUri = playlistUriResult.getOrElse {
            updateStatus("Genre mix build failed: ${it.message}")
            return
        }

        if (announce) {
            // Templated rather than generated: this line is heard once an hour and its content is
            // almost entirely the genre name, so a Gemini call bought very little.
            speakLine(templatedGenreLine(genre), label = "Genre change")
        }

        lastMixBuiltAtMs = System.currentTimeMillis()
        manager.playUri(playlistUri)
        ensureEvergreenLines()
        maybeRefillScriptBatch() // prime the first batch against the new playlist
        updateStatus("On air - $genre")
    }

    /**
     * Simple templated line used when Gemini is unavailable (rate limited or offline). It's not
     * witty, but a DJ that says the artist's name beats a DJ that says nothing - previously a
     * rate limit meant the segment was dropped entirely and the radio just went quiet.
     */
    /**
     * Reads the fetched headlines with a fixed intro. Used whenever Gemini is unavailable - the
     * RSS feed already gave us the actual news, so the bulletin still carries real information;
     * only the witty summarising is lost.
     */
    private fun templatedNewsScript(headlines: List<String>): String {
        val intro = "Top of the hour. Here's what's happening."
        val body = headlines.take(4).joinToString(" ") { headline ->
            // Ensure each headline ends with a full stop so TTS doesn't run them together.
            if (headline.endsWith(".") || headline.endsWith("!") || headline.endsWith("?")) headline
            else "$headline."
        }
        return "$intro $body That's your update."
    }

    private val genreChangeTemplates = listOf(
        "Switching things up - some %s coming your way.",
        "Let's change the mood. %s from here.",
        "Next hour belongs to %s.",
        "Shifting gears into some %s."
    )

    private fun templatedGenreLine(genre: String): String =
        genreChangeTemplates.random().format(genre)

    private fun fallbackTriviaLine(track: TrackInfo): String =
        "That was ${track.artist}, with ${track.title}. Let's keep it going."

    private suspend fun runTrackTrivia(track: TrackInfo) {
        // Fast path: audio prepared at the start of this track is ready to play immediately.
        val prepared = preparedAudio?.takeIf { preparedTrackUri == track.uri }
        if (prepared != null) {
            Log.d(DJ_TAG, "Step 2: using PREFETCHED audio for '${track.title}'")
            preparedAudio = null
            preparedTrackUri = null
            playPreparedAudio(prepared, label = "Trivia")
            return
        }
        segmentCounter++
        // Evergreen filler for most segments; a real, track-specific script every Nth. Cuts
        // trivia generation by two-thirds and reads like an actual station rather than a machine
        // that has something clever to say about literally every song.
        if (segmentCounter % REAL_TRIVIA_EVERY != 0 && evergreenLines.isNotEmpty()) {
            val line = evergreenLines.random()
            Log.d(DJ_TAG, "Step 2: using EVERGREEN line (segment $segmentCounter)")
            speakLine(line, label = "Trivia")
            return
        }

        Log.d(DJ_TAG, "Step 2: no prefetched audio - generating live (may miss the boundary)")
        val gemini = geminiClient ?: return
        Log.d(DJ_TAG, "Step 2: generating script for '${track.artist} - ${track.title}'")
        updateStatus("Writing trivia for ${track.title}...")
        // In a fuller implementation, look ahead at Spotify's queue/context to know the next
        // track title; here we pass null and let the DJ speak generically about "the next song".
        val scriptResult = gemini.generateTrackTransition(track.artist, track.title, nextTitle = null)
        val script = scriptResult.getOrElse {
            Log.w(DJ_TAG, "Step 2: script generation failed (${it.message}) - using templated line via on-device voice")
            updateStatus("DJ running in offline mode")
            // Speak SOMETHING rather than dropping the segment. speakLine will find Gemini TTS
            // unavailable too and route this straight to the on-device engine.
            speakLine(fallbackTriviaLine(track), label = "Trivia (fallback)")
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
    /** Plays already-synthesized audio, reusing the same ducking + telemetry path as speakLine. */
    private suspend fun playPreparedAudio(wav: ByteArray, label: String) {
        updateStatus("$label: speaking...")
        RadioServiceState.addHistory(isDjLine = true, text = script)
        try {
            val played = withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
                audioPlaybackManager.playDuckedAudio(wav, settings.snapshotDjVolume())
                true
            }
            if (played == null) Log.e(DJ_TAG, "Step 6: [$label] PLAYBACK TIMED OUT")
            else Log.d(DJ_TAG, "Step 6: [$label] PLAYBACK COMPLETE (prefetched)")
        } catch (e: Exception) {
            Log.e(DJ_TAG, "[$label] prepared playback threw", e)
        } finally {
            updateStatus("On air")
            spotifyManager?.play()
        }
    }

    /**
     * Whether this segment gets the premium Gemini voice. In BALANCED, trivia (the overwhelming
     * majority of segments) uses the on-device voice while news and genre changes keep the good
     * one - those are the moments where delivery carries the most weight.
     */
    private fun useGeminiVoice(label: String): Boolean = when (voiceMode) {
        VoiceMode.BEST -> true
        VoiceMode.OFFLINE -> false
        VoiceMode.BALANCED -> !label.startsWith("Trivia")
    }

    private suspend fun speakLine(script: String, label: String) {
        val gemini = geminiClient
        val startedAt = System.currentTimeMillis()
        Log.d(DJ_TAG, "Step 3: [$label] API REQUEST START - script: \"${script.take(60)}...\"")
        updateStatus("$label: speaking...")

        try {
            val wav = if (!useGeminiVoice(label) || cloudTts == null) {
                if (cloudTts == null) {
                    Log.d(DJ_TAG, "Step 3: [$label] no Cloud TTS key - using device voice")
                } else {
                    Log.d(DJ_TAG, "Step 3: [$label] using on-device voice (mode=$voiceMode)")
                }
                null
            } else {
                withTimeoutOrNull(SPEECH_TIMEOUT_MS) {
                    cloudTts?.synthesize(script, cloudTtsVoice)?.getOrNull()
                }
            }

            if (wav != null) {
                Log.d(DJ_TAG, "Step 4: [$label] API SUCCESS - ${wav.size} bytes in ${System.currentTimeMillis() - startedAt}ms")
                val played = withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
                    audioPlaybackManager.playDuckedAudio(wav, settings.snapshotDjVolume())
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

                // Also save to Spotify's Liked Songs. Saved tracks are Tier 1 of the mix engine,
                // so a thumbs-up now feeds back into future playlists rather than only living in
                // local storage where Spotify never sees it.
                if (liked) {
                    val webAuth = spotifyWebAuthManager
                    if (webAuth != null && webAuth.isConnected()) {
                        SpotifyWebApiClient(webAuth).saveTrackToLibrary(track.uri)
                            .onSuccess { Log.d(TAG, "Saved '${track.title}' to Spotify library") }
                            .onFailure { Log.w(TAG, "Could not save to Spotify library: ${it.message}") }
                    }
                }
                Log.d(TAG, "Recorded ${if (liked) "LIKE" else "DISLIKE"} for ${track.artist} - ${track.title}")
                updateStatus(if (liked) "Liked ${track.title}" else "Disliked ${track.title} - skipping")
                if (!liked) spotifyManager?.skipNext()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record feedback", e)
            }
        }
    }

    /** Sets the sleep timer; [minutes] of 0 or less cancels it. */
    private fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            sleepAtMs = 0L
            RadioServiceState.setSleepMinutesRemaining(null)
            updateStatus("Sleep timer cancelled")
            Log.d(TAG, "Sleep timer cancelled")
            return
        }
        sleepAtMs = System.currentTimeMillis() + minutes * 60_000L
        RadioServiceState.setSleepMinutesRemaining(minutes)
        updateStatus("Sleep timer: ${minutes}m")
        Log.d(TAG, "Sleep timer set for $minutes minutes")
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
        isInitialized = false
        playbackJob?.cancel()
        prefetchJob?.cancel()
        batchJob?.cancel()
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
