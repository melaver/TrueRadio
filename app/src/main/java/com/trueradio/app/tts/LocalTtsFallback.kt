package com.trueradio.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.trueradio.app.DjLanguage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device speech fallback, used only when Gemini TTS fails (offline, quota, API change).
 * ElevenLabs has been removed entirely; Gemini native audio is the primary path.
 *
 * A single TextToSpeech instance is created lazily and reused. An earlier version created a new
 * engine per utterance without ever releasing the previous one, which leaked a bound system
 * engine on every DJ line - fatal for a service meant to run for hours.
 */
class LocalTtsFallback(private val context: Context) {

    companion object {
        private const val TAG = "LocalTtsFallback"
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val SPEAK_TIMEOUT_MS = 60_000L
    }

    private var tts: TextToSpeech? = null
    private var ready = false

    /**
     * Speaks [text] and suspends until playback finishes. Returns true on success, false on any
     * failure - never throws, because the caller's job is to keep music playing regardless.
     * Both init and speak are time-boxed so a wedged system engine can't leave music ducked
     * indefinitely waiting on a callback that never arrives.
     */
    suspend fun speak(text: String, language: DjLanguage): Boolean {
        return try {
            val engine = withTimeoutOrNull(INIT_TIMEOUT_MS) { getOrInit() }
            if (engine == null) {
                Log.e(TAG, "Local TTS init timed out")
                return false
            }
            engine.language = when (language) {
                DjLanguage.HEBREW -> Locale("iw", "IL")
                DjLanguage.ENGLISH -> Locale.US
            }
            val spoke = withTimeoutOrNull(SPEAK_TIMEOUT_MS) { speakOnce(engine, text) }
            if (spoke == null) Log.e(TAG, "Local TTS speak timed out")
            spoke == true
        } catch (e: Exception) {
            Log.e(TAG, "Local TTS failed", e)
            false
        }
    }

    private suspend fun getOrInit(): TextToSpeech {
        tts?.takeIf { ready }?.let { return it }
        return suspendCancellableCoroutine { cont ->
            // The init callback can fire either BEFORE or AFTER the TextToSpeech constructor
            // returns - both orderings genuinely happen (the early one is common when the system
            // TTS service is already warm). Handling only one ordering leaves the coroutine
            // hanging until the caller's timeout, which shows up as the fallback failing
            // intermittently for no visible reason.
            //
            // So: both the callback and the post-constructor code funnel through resolve(), which
            // resumes only once both the engine reference and the init status are known,
            // whichever arrives second.
            val lock = Any()
            var engineRef: TextToSpeech? = null
            var initStatus: Int? = null
            var resumed = false

            fun resolve() = synchronized(lock) {
                if (resumed) return@synchronized
                val engine = engineRef
                val status = initStatus
                if (engine == null || status == null) return@synchronized // wait for the other half
                resumed = true
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    if (cont.isActive) cont.resume(engine)
                } else if (cont.isActive) {
                    cont.resumeWithException(IOException("Local TTS init failed: $status"))
                }
            }

            val engine = TextToSpeech(context) { status ->
                synchronized(lock) { initStatus = status }
                resolve()
            }
            synchronized(lock) { engineRef = engine }
            tts = engine
            resolve()
        }
    }

    private suspend fun speakOnce(engine: TextToSpeech, text: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(true)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            cont.invokeOnCancellation { engine.stop() }
        }

    fun release() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
