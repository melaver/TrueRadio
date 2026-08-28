package com.trueradio.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class TtsResult {
    data class AudioFile(val file: File) : TtsResult()
    object SpokenLocally : TtsResult()
}

/**
 * Produces spoken audio for a DJ script.
 *
 * Primary path: ElevenLabs REST TTS -> returns an mp3 File the caller plays through
 * AudioPlaybackManager (so audio-ducking/Media3 playback rules apply uniformly).
 *
 * Fallback path: if the network call fails (no connectivity, quota, bad key, etc.),
 * falls back to Android's on-device TextToSpeech engine and speaks directly -
 * in that case there is no audio file to hand back to the caller.
 */
class TtsManager(
    private val context: Context,
    private val elevenLabsApiKey: String,
    private val voiceId: String
) {
    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var androidTts: TextToSpeech? = null

    companion object {
        private const val TAG = "TtsManager"
        private const val MODEL_ID = "eleven_multilingual_v2" // or "eleven_turbo_v2_5" for lower latency
    }

    /** Attempts ElevenLabs first; on any failure, speaks via the local engine and returns SpokenLocally. */
    suspend fun synthesize(text: String): TtsResult {
        val remoteFile = synthesizeWithElevenLabs(text)
        if (remoteFile != null) return TtsResult.AudioFile(remoteFile)

        Log.w(TAG, "Falling back to local Android TextToSpeech")
        speakLocally(text)
        return TtsResult.SpokenLocally
    }

    private suspend fun synthesizeWithElevenLabs(text: String): File? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"
            val payload = ElevenLabsRequest(
                text = text,
                model_id = MODEL_ID,
                voice_settings = VoiceSettings(
                    stability = 0.4,
                    similarity_boost = 0.8,
                    style = 0.2,
                    use_speaker_boost = true
                )
            )
            val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", elevenLabsApiKey)
                .addHeader("Accept", "audio/mpeg")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("ElevenLabs error: ${response.code} ${response.message}")
                }
                val bytes = response.body?.bytes() ?: throw IOException("Empty ElevenLabs audio body")
                val outFile = File(context.cacheDir, "dj_line_${UUID.randomUUID()}.mp3")
                FileOutputStream(outFile).use { it.write(bytes) }
                outFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs synthesis failed, will fall back", e)
            null
        }
    }

    private suspend fun speakLocally(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        val utteranceId = UUID.randomUUID().toString()

        fun speakNow(tts: TextToSpeech) {
            tts.language = Locale("iw", "IL") // Hebrew; falls back silently if unsupported on device
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resumeWithException(IOException("Local TTS error"))
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }

        androidTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTts?.let { speakNow(it) }
            } else if (cont.isActive) {
                cont.resumeWithException(IOException("Local TTS init failed"))
            }
        }

        cont.invokeOnCancellation {
            androidTts?.stop()
        }
    }

    fun release() {
        androidTts?.shutdown()
        androidTts = null
    }

    private data class ElevenLabsRequest(
        val text: String,
        val model_id: String,
        val voice_settings: VoiceSettings
    )

    private data class VoiceSettings(
        val stability: Double,
        val similarity_boost: Double,
        val style: Double,
        val use_speaker_boost: Boolean
    )
}
