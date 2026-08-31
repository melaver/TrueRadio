package com.trueradio.app.tts

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google Cloud Text-to-Speech.
 *
 * WHY THIS EXISTS SEPARATELY FROM GeminiClient: Cloud TTS is a different product with its own
 * quota, even though both are Google. Gemini's TTS models are preview-only with tight limits that
 * this app kept exhausting; Cloud TTS has a large permanent monthly free allowance. Splitting the
 * work means Gemini writes the scripts (cheap, batched text) while speech comes from a quota that
 * a radio station's usage won't realistically exhaust.
 *
 * Unlike Gemini TTS, this returns base64 **MP3** (or LINEAR16 if requested) rather than raw PCM,
 * so no WAV header needs to be synthesised - ExoPlayer decodes MP3 directly.
 *
 * Auth uses an API key as a query parameter, which is what Cloud TTS supports for simple clients.
 * The key therefore appears in the request URL, so this client deliberately does NOT install an
 * HTTP logging interceptor - the equivalent Gemini logging leaked its key into Logcat until it was
 * moved to a header, and Cloud TTS has no header-based key equivalent.
 */
class CloudTtsClient(private val apiKey: String) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val TAG = "CloudTtsClient"
        private const val ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"

        /**
         * Chirp 3 HD is Google's newest and most natural voice tier and carries its own 1M
         * characters/month free allowance. If a voice name is ever retired, a 400 with an
         * INVALID_ARGUMENT about the voice is the signal to update this.
         */
        const val DEFAULT_VOICE = "en-US-Chirp3-HD-Charon"
        const val DEFAULT_LANGUAGE_CODE = "en-US"

        /** A few alternatives the user can pick between in Settings. */
        val VOICE_OPTIONS = listOf(
            "en-US-Chirp3-HD-Charon" to "Charon (warm, low)",
            "en-US-Chirp3-HD-Kore" to "Kore (bright, mid)",
            "en-US-Chirp3-HD-Puck" to "Puck (energetic)",
            "en-US-Chirp3-HD-Aoede" to "Aoede (smooth)",
            "en-US-Neural2-J" to "Neural2 J (classic broadcast)"
        )
    }

    /**
     * Synthesizes [text] and returns MP3 bytes ready for ExoPlayer.
     *
     * [speakingRate] slightly above 1.0 suits radio delivery - the default pace reads a little
     * slow against music. Returns failure rather than throwing so callers can fall back to the
     * on-device engine.
     */
    suspend fun synthesize(
        text: String,
        voiceName: String = DEFAULT_VOICE,
        speakingRate: Double = 1.05
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("input", JSONObject().put("text", text))
                put("voice", JSONObject().apply {
                    put("languageCode", DEFAULT_LANGUAGE_CODE)
                    put("name", voiceName)
                })
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "MP3")
                    put("speakingRate", speakingRate)
                    // Cloud TTS output sits low against music for the same reason Gemini's did.
                    // volumeGainDb is applied server-side, which is cleaner than rescaling PCM
                    // ourselves; +4dB roughly matches typical music loudness without clipping.
                    put("volumeGainDb", 4.0)
                })
            }

            val request = Request.Builder()
                .url("$ENDPOINT?key=$apiKey")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Cloud TTS failed: HTTP ${response.code} - ${body.take(400)}")
                    return@withContext Result.failure(
                        IOException("Cloud TTS error ${response.code}")
                    )
                }
                val base64Audio = JSONObject(body).optString("audioContent")
                    .takeIf { it.isNotBlank() }
                if (base64Audio == null) {
                    Log.e(TAG, "Cloud TTS returned no audioContent. Body: ${body.take(400)}")
                    return@withContext Result.failure(IOException("Cloud TTS returned no audio"))
                }
                val mp3 = Base64.decode(base64Audio, Base64.DEFAULT)
                if (mp3.isEmpty()) {
                    return@withContext Result.failure(IOException("Cloud TTS returned empty audio"))
                }
                Log.d(TAG, "Cloud TTS produced ${mp3.size} MP3 bytes for ${text.length} chars")
                Result.success(mp3)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud TTS threw", e)
            Result.failure(e)
        }
    }
}
