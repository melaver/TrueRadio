package com.trueradio.app.audio

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

    /** Requests ducking focus, plays the DJ line to completion, then abandons focus. */
    suspend fun playDuckedLine(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val granted = requestDuckingFocus()
        if (!granted) {
            // Still attempt playback - better a full-volume DJ line than silence.
        }

        val player = buildExoPlayer().also { exoPlayer = it }
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare()
        player.playWhenReady = true

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    cleanupAfterPlayback(player)
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                cleanupAfterPlayback(player)
                if (cont.isActive) cont.resume(Unit)
            }
        })

        cont.invokeOnCancellation {
            cleanupAfterPlayback(player)
        }
    }

    private fun cleanupAfterPlayback(player: ExoPlayer) {
        player.release()
        if (exoPlayer === player) exoPlayer = null
        abandonDuckingFocus()
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
