package com.trueradio.app.audio

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Requests transient-ducking audio focus so Spotify's own volume drops automatically while the
 * DJ line plays (Spotify listens for AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK the same way any other
 * well-behaved media app does), plays the DJ's mp3 through ExoPlayer, and releases focus so
 * Spotify's volume is restored the moment playback ends.
 */
class AudioPlaybackManager(private val context: Context) {

    companion object {
        private const val TAG = "DJ_FLOW"
        /** Time allowed for Spotify's volume ramp before the DJ starts speaking. */
        private const val DUCK_SETTLE_MS = 300L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var exoPlayer: ExoPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    private fun buildExoPlayer(): ExoPlayer {
        val attrs = AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(context)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false) // we manage focus manually below
            .build()
    }

    /**
     * Writes Gemini's WAV bytes to a temp cache file and plays them ducked, cleaning up after.
     * Wrapped in a timeout by the caller; the temp file is always deleted even on failure so the
     * cache can't grow unbounded across a long-running session.
     */
    suspend fun playDuckedAudio(wavBytes: ByteArray, volume: Float = 1.0f) {
        val file = File(context.cacheDir, "dj_line_${System.currentTimeMillis()}.wav")
        try {
            file.writeBytes(wavBytes)
            Log.d(TAG, "Step 4b: wrote ${wavBytes.size} bytes to ${file.name}")
            playDuckedLine(file, volume)
        } finally {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete temp audio ${file.name}")
            }
        }
    }

    /**
     * Requests ducking focus, waits for Spotify to actually duck, plays the DJ line to
     * completion, then abandons focus so Spotify returns to full volume.
     *
     * Ordering is deliberate and was the source of two bugs:
     *  - The listener is attached BEFORE prepare(). It used to be attached after, which meant an
     *    immediate failure during prepare() (corrupt/short audio, unsupported format) fired
     *    onPlayerError with no listener attached - the coroutine never resumed, and Spotify stayed
     *    ducked until the caller's 90s timeout. That is the "music stays quiet forever" symptom.
     *  - A short settle delay separates the focus request from playback. Ducking is asynchronous:
     *    the request only *notifies* Spotify, which then ramps its own volume down. Starting
     *    instantly meant the DJ's first word landed over full-volume music.
     */
    suspend fun playDuckedLine(file: File, volume: Float = 1.0f) {
        val granted = requestDuckingFocus()
        Log.d(TAG, "Step 5a: audio focus ${if (granted) "GRANTED" else "DENIED (playing anyway)"}")
        // Let Spotify's volume ramp complete before the first word. Skipped if focus was denied,
        // since nothing is going to duck in that case.
        if (granted) delay(DUCK_SETTLE_MS)

        try {
            awaitPlayback(file, volume)
        } finally {
            // Focus is abandoned here, after playback has genuinely finished (or failed, or been
            // cancelled) - never earlier, so music can't come back up mid-sentence.
            abandonDuckingFocus()
            Log.d(TAG, "Step 5c: audio focus released, Spotify restored")
        }
    }

    private suspend fun awaitPlayback(file: File, volume: Float = 1.0f) = suspendCancellableCoroutine<Unit> { cont ->
        // Defensive: release any player left over from a cancelled previous segment before
        // overwriting the field, which would otherwise leak it.
        exoPlayer?.release()

        val player = buildExoPlayer().also { exoPlayer = it }

        // Attach BEFORE prepare() - see the doc comment above.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    Log.d(TAG, "Step 5b: ExoPlayer STATE_ENDED")
                    releasePlayer(player)
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Step 5b: ExoPlayer ERROR - aborting segment", error)
                releasePlayer(player)
                if (cont.isActive) cont.resume(Unit)
            }
        })

        // ExoPlayer volume caps at 1.0, so this can only attenuate - the real loudness gain comes
        // from normalising the PCM in GeminiClient before it ever reaches here.
        player.volume = volume.coerceIn(0f, 1f)
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare()
        player.playWhenReady = true
        Log.d(TAG, "Step 5: ExoPlayer START")

        cont.invokeOnCancellation {
            Log.w(TAG, "Step 5b: playback cancelled - releasing player")
            releasePlayer(player)
        }
    }

    /**
     * Releases the player only. Focus is deliberately NOT abandoned here - that happens in
     * playDuckedLine's finally block, so there is exactly one place that restores Spotify's
     * volume and it can't run while audio is still playing.
     */
    private fun releasePlayer(player: ExoPlayer) {
        player.release()
        if (exoPlayer === player) exoPlayer = null
    }

    private fun requestDuckingFocus(): Boolean {
        // No SDK-version branching needed here: minSdk is already 26 (Build.VERSION_CODES.O),
        // required by the Spotify App Remote SDK, so the AudioFocusRequest API (added in O) is
        // always available - a version check here would just be permanently-dead code.
        val platformAttrs = PlatformAudioAttributes.Builder()
            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
            .setContentType(PlatformAudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(platformAttrs)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonDuckingFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        abandonDuckingFocus()
    }
}
