package com.trueradio.app.ai

import android.util.Base64
import android.util.Log
import com.trueradio.app.DjLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Owns both halves of the DJ's voice:
 *  1. Script generation (text) via a general Flash model, using the persona prompts below.
 *  2. Speech generation (audio) via Gemini's dedicated TTS model.
 *
 * WHY TWO CALLS, NOT ONE: Gemini's native audio output only exists on dedicated TTS models
 * (currently `gemini-3.1-flash-tts-preview`). Those models *read text aloud* - they don't author
 * creative copy, so asking one to invent witty radio trivia produces poor scripts. Google's own
 * speech-generation docs use exactly this two-step shape (creative model writes, TTS model
 * voices). General Flash models do NOT accept `responseModalities: ["AUDIO"]`.
 *
 * WHY THE AUDIO NEEDS POST-PROCESSING: the TTS response is base64 **raw PCM** (signed 16-bit
 * little-endian, 24 kHz, mono) - not a container format. Handing those bytes straight to
 * ExoPlayer fails silently (it can't infer a format), an easy "no audio, no error" bug.
 * [pcmToWav] prepends a 44-byte RIFF/WAVE header so the result is a real playable .wav.
 */
class GeminiClient(
    private val apiKey: String,
    private val language: DjLanguage = DjLanguage.HEBREW
) {
    /**
     * Per-process cache of generated artist lists. Genre->artists mappings are effectively static
     * between mixes, so re-asking on every playlist rebuild burns quota for no new information -
     * a meaningful cause of 429s on free-tier keys.
     */
    private val artistListCache = mutableMapOf<String, List<String>>()



    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS) // TTS synthesis is slower than text generation
            .addInterceptor(logging)
            .build()
    }

    companion object {
        private const val TAG = "GeminiClient"

        /**
         * Process-wide 429 backoff. Retrying into a rate limit makes it WORSE - every retry
         * counts against the same quota - so once Gemini returns 429, all calls are refused
         * locally until this expires. Failing instantly also beats three slow retries that end
         * in silence anyway.
         */
        @Volatile
        private var rateLimitedUntilMs = 0L
        private const val RATE_LIMIT_COOLDOWN_MS = 5 * 60 * 1000L

        fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs
        fun rateLimitSecondsRemaining(): Long =
            ((rateLimitedUntilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        private fun tripRateLimit() { rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS }

        /**
         * Rolling alias Google maintains pointing at their current recommended Flash model, used
         * instead of a dated id (a hardcoded "gemini-2.5-flash" started 404ing for newly-created
         * API keys as Google phased it out ahead of its Oct 16 2026 shutdown). Avoids this
         * breaking again as the Flash lineup advances.
         */
        private const val TEXT_MODEL = "gemini-flash-latest"

        /**
         * Dedicated TTS model. NOTE: unlike TEXT_MODEL there is no rolling "-latest" alias for
         * TTS, so this IS a pinned preview id and WILL eventually need updating - if DJ audio
         * starts failing with a 404 while text scripts still work, this constant is the first
         * thing to check against ai.google.dev/gemini-api/docs/speech-generation.
         */
        private const val TTS_MODEL = "gemini-3.1-flash-tts-preview"

        private const val ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

        /**
         * Transient-failure retry policy. Gemini returns 503 (UNAVAILABLE / "model overloaded")
         * fairly regularly on free-tier and preview models at busy times, and 429 when rate
         * limited - neither means anything is misconfigured, and both usually succeed moments
         * later. Without retries a single blip silently costs the listener a whole DJ segment.
         */
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 1_000L
        // 429 deliberately excluded: it's a quota limit, handled by the circuit breaker above.
        // Retrying it just consumes more quota.
        private val RETRYABLE_CODES = setOf(500, 502, 503, 504)

        // Gemini TTS output format, fixed by the API contract - used to build the WAV header.
        private const val TTS_SAMPLE_RATE = 24_000
        private const val TTS_CHANNELS = 1
        private const val TTS_BITS_PER_SAMPLE = 16

        private val HEBREW_PERSONA = """
            את/ה שדרן/ית רדיו ישראלי/ת חד/ה, שנון/ה וחם/ה, בסגנון כאן 88 / גלגלצ.
            כללים מחייבים:
            1. אסור להתחיל במשפטי פתיחה שחוקים כמו "ועכשיו שמענו את" או "השיר הבא שנשמע הוא" -
               תמיד תתחיל/י ישר עם משפט פתיחה מפתיע, תובנה תרבותית, או הברקה.
            2. עדיפות לטריוויה מפתיעה, סיפורי סטודיו, ואנקדוטות שנונות - ולא לעובדות ביוגרפיות יבשות.
            3. תחביר דיבורי טבעי, משפטים קצרים וקצביים, כמו קריינות רדיו אמיתית.
            4. הפלט חייב להיות טקסט הניתן להקראה בלבד: פסיקים ומקפים להפסקות דיבור,
               בלי הוראות בימוי, בלי סוגריים, בלי מרכאות, ובלי תיאורים כמו "(בהתלהבות)".
            5. אורך: כשלוש משפטים קצרים, לא יותר.
        """.trimIndent()

        private val ENGLISH_PERSONA = """
            You are a sharp, witty, warm radio host in the style of BBC Radio 6 Music or KCRW.
            Hard rules:
            1. Never open with worn-out radio cliches like "and that was" or "coming up next we have" -
               always start straight into a surprising hook, a cultural observation, or a sharp line.
            2. Favour unexpected trivia, studio stories and witty asides over dry biographical
               facts or release years.
            3. Natural spoken syntax, short punchy clauses, real broadcast rhythm.
            4. Output must be strictly speakable text: commas and dashes for breath pauses, no
               stage directions, no brackets, no quotation marks, no cues like "(excitedly)".
            5. Length: about three short sentences, no more.
        """.trimIndent()

        /**
         * Prebuilt Gemini voice per language. Both are warm mid-range voices suited to a radio
         * read; the full roster (Zephyr, Kore, Charon, Puck, Aoede, ...) is in Google's TTS docs.
         * Gemini TTS models are multilingual, so the same voice can speak Hebrew or English - the
         * language comes from the script text, not the voice id.
         */
        fun voiceFor(language: DjLanguage): String = when (language) {
            DjLanguage.HEBREW -> "Kore"
            DjLanguage.ENGLISH -> "Charon"
        }
    }

    private fun persona(): String = when (language) {
        DjLanguage.HEBREW -> HEBREW_PERSONA
        DjLanguage.ENGLISH -> ENGLISH_PERSONA
    }

    // ---------------------------------------------------------------- prompts

    private fun trackTransitionPrompt(currentArtist: String, currentTitle: String, nextTitle: String?): String =
        when (language) {
            DjLanguage.HEBREW -> {
                val nextPart = if (nextTitle != null) "והשיר הבא הוא \"$nextTitle\"" else "והשיר הבא כבר בדרך"
                """
                ${persona()}

                המשימה: כתוב/כתבי מעבר רדיופוני קצר בין שירים.
                השיר שהתנגן: "$currentTitle" של $currentArtist.
                $nextPart.
                שלב/י טריוויה שנונה או רקע מפתיע על האמן/ית או השיר, ותוביל/י בצורה חלקה לשיר הבא.
                """.trimIndent()
            }
            DjLanguage.ENGLISH -> {
                val nextPart = if (nextTitle != null) "The next track is \"$nextTitle\"." else "The next track is coming up."
                """
                ${persona()}

                Task: write a short radio transition between songs.
                Just played: "$currentTitle" by $currentArtist.
                $nextPart
                Work in a witty piece of trivia or surprising background about the artist or track,
                then lead smoothly into what's next.
                """.trimIndent()
            }
        }

    private fun hourlyNewsPrompt(headlines: List<String>, likedTopics: List<String>): String {
        val headlineBlock = headlines.take(5).joinToString("\n") { "- $it" }
        return when (language) {
            DjLanguage.HEBREW -> {
                val preferenceNote = if (likedTopics.isNotEmpty()) {
                    "\nהמאזין/ת ציין/ה עניין מיוחד בנושאים: ${likedTopics.joinToString(", ")}. " +
                        "אם אחת הכותרות נוגעת לאחד מהם, תן/י לה דגש והרחבה קלה."
                } else ""
                """
                ${persona()}

                המשימה: כתוב/כתבי מהדורת חדשות קצרה ואנרגטית לתחילת השעה, על בסיס הכותרות:
                $headlineBlock
                $preferenceNote

                סכם/י בקול רדיופוני חד ותמציתי, בלי לקרוא כותרת-כותרת כמו רשימה.
                """.trimIndent()
            }
            DjLanguage.ENGLISH -> {
                val preferenceNote = if (likedTopics.isNotEmpty()) {
                    "\nThe listener has flagged special interest in: ${likedTopics.joinToString(", ")}. " +
                        "If any headline touches those, give it a little more weight and detail."
                } else ""
                """
                ${persona()}

                Task: write a short, energetic top-of-the-hour news update from these headlines:
                $headlineBlock
                $preferenceNote

                Summarise in a sharp broadcast voice - don't read them out one by one like a list.
                """.trimIndent()
            }
        }
    }

    private fun genreChangePrompt(newGenre: String): String = when (language) {
        DjLanguage.HEBREW -> """
            ${persona()}

            המשימה: משפט או שניים קצרים ואנרגטיים שמבשרים על מעבר לסגנון מוזיקלי חדש: $newGenre.
            שלב/י התייחסות שנונה לסגנון עצמו, בלי לפרט רשימת שירים או אמנים.
            קצר במיוחד - משפט אחד עד שניים.
        """.trimIndent()
        DjLanguage.ENGLISH -> """
            ${persona()}

            Task: one or two short, energetic lines announcing a switch to a new musical style: $newGenre.
            Land a witty observation about the style itself; don't list songs or artists.
            Very short - one or two sentences.
        """.trimIndent()
    }

    // ---------------------------------------------------------------- request plumbing

    /**
     * Executes [block] with exponential backoff on transient failures. Retries only on the codes
     * in [RETRYABLE_CODES] plus raw network errors - a 400 (bad request) or 404 (model retired)
     * would fail identically every time, so retrying those just wastes the listener's time and
     * delays the fallback.
     *
     * Backoff includes jitter so that if several DJ segments fail at once they don't all retry in
     * lockstep and hit the overloaded backend at the same instant.
     */
    private suspend fun <T> withRetries(label: String, block: suspend () -> Result<T>): Result<T> {
        if (isRateLimited()) {
            Log.w(TAG, "[$label] skipped - rate limited for another ${rateLimitSecondsRemaining()}s")
            return Result.failure(IOException("Gemini rate limited (${rateLimitSecondsRemaining()}s remaining)"))
        }
        var lastFailure: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = block()
            if (result.isSuccess) return result

            val error = result.exceptionOrNull()
            lastFailure = error
            // A 429 means quota, not transient load - stop retrying immediately and lock out
            // further calls, otherwise the retries deepen the hole.
            if (error?.message?.contains("429") == true) {
                tripRateLimit()
                Log.e(TAG, "[$label] RATE LIMITED (429) - pausing all Gemini calls for 5 minutes")
                return Result.failure(error)
            }
            val retryable = (error as? RetryableHttpException) != null
            if (!retryable || attempt == MAX_ATTEMPTS - 1) {
                if (!retryable) Log.e(TAG, "[$label] non-retryable failure; giving up", error)
                else Log.e(TAG, "[$label] still failing after $MAX_ATTEMPTS attempts", error)
                return Result.failure(error ?: IOException("$label failed"))
            }

            val backoff = INITIAL_BACKOFF_MS * (1L shl attempt) + Random.nextLong(250)
            Log.w(TAG, "[$label] transient failure (${error?.message}); retrying in ${backoff}ms")
            delay(backoff)
        }
        return Result.failure(lastFailure ?: IOException("$label failed"))
    }

    /** Marker so [withRetries] can distinguish "try again" from "this will never work". */
    private class RetryableHttpException(message: String) : IOException(message)

    // ---------------------------------------------------------------- text generation

    /** Generates a DJ script. Returns failure rather than throwing so callers can fall back. */
    suspend fun generateScript(prompt: String): Result<String> =
        withRetries("script") { generateScriptOnce(prompt) }

    private suspend fun generateScriptOnce(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put(
                    "contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    )
                )
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.9)
                    put("maxOutputTokens", 220)
                })
            }
            val request = Request.Builder()
                .url(ENDPOINT_TEMPLATE.format(TEXT_MODEL))
                // Key in a header, not a ?key= query param: the logging interceptor records full
                // request lines, so a query-param key would land in Logcat in plaintext.
                .addHeader("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Script generation failed: HTTP ${response.code} - ${body.take(400)}")
                    val msg = "Gemini text error ${response.code}"
                    return@withContext Result.failure(
                        if (response.code in RETRYABLE_CODES) RetryableHttpException(msg) else IOException(msg)
                    )
                }
                val text = JSONObject(body)
                    .optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                if (text == null) {
                    Log.e(TAG, "Script generation returned no text. Body: ${body.take(400)}")
                    return@withContext Result.failure(IOException("Gemini returned no text"))
                }
                Log.d(TAG, "Script generated (${text.length} chars)")
                Result.success(text.trim())
            }
        } catch (e: java.io.IOException) {
            // Raw network failures (timeout, DNS, connection reset) are worth retrying too.
            Log.e(TAG, "Script generation network error", e)
            Result.failure(RetryableHttpException(e.message ?: "network error"))
        } catch (e: Exception) {
            Log.e(TAG, "Script generation threw", e)
            Result.failure(e)
        }
    }

    suspend fun generateTrackTransition(currentArtist: String, currentTitle: String, nextTitle: String?): Result<String> =
        generateScript(trackTransitionPrompt(currentArtist, currentTitle, nextTitle))

    /**
     * Generates transition scripts for several upcoming tracks in ONE request.
     *
     * Script generation is the easiest place to cut Gemini call volume: five separate trivia
     * calls become one. (TTS cannot be batched - each request returns a single audio stream - so
     * audio is still synthesized per track, just later and lazily.)
     *
     * Returns a map of "artist|title" -> script. Keyed on the input pair rather than array
     * position because a model that drops or reorders an entry would otherwise silently attach
     * the wrong trivia to the wrong song, which is worse than having no trivia.
     */
    suspend fun generateTrackTransitionBatch(
        tracks: List<Pair<String, String>> // artist to title
    ): Result<Map<String, String>> {
        if (tracks.isEmpty()) return Result.success(emptyMap())

        val numbered = tracks.mapIndexed { i, (artist, title) -> "${i + 1}. \"$title\" by $artist" }
            .joinToString("\n")
        val prompt = """
            ${persona()}

            המשימה / Task: write ONE short radio transition for EACH of the following ${tracks.size} tracks.
            Each one must follow all the persona rules above, in the same language as those rules.

            $numbered

            Output format - this is critical:
            - Return ONLY a JSON array of ${tracks.size} objects, nothing else. No markdown fences,
              no commentary before or after.
            - Each object: {"n": <track number>, "script": "<the spoken text>"}
            - The "script" value must be plain speakable text with no quotation marks inside it.
        """.trimIndent()

        return generateScript(prompt).mapCatching { raw ->
            // Models frequently wrap JSON in markdown fences despite instructions to the contrary.
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val array = JSONArray(cleaned)
            val result = mutableMapOf<String, String>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val n = obj.optInt("n", -1)
                val script = obj.optString("script").takeIf { it.isNotBlank() } ?: continue
                // Map back by the number the model was given, so a dropped or reordered entry
                // can't misalign scripts with tracks.
                val track = tracks.getOrNull(n - 1) ?: continue
                result["${track.first}|${track.second}"] = script.trim()
            }
            if (result.isEmpty()) throw IOException("Batch returned no usable scripts")
            Log.d(TAG, "Batch generated ${result.size}/${tracks.size} scripts in one call")
            result
        }
    }

    suspend fun generateHourlyNews(headlines: List<String>, likedTopics: List<String> = emptyList()): Result<String> =
        generateScript(hourlyNewsPrompt(headlines, likedTopics))

    suspend fun generateGenreChangeLine(newGenre: String): Result<String> =
        generateScript(genreChangePrompt(newGenre))

    /**
     * Names well-known, mainstream artists for a genre.
     *
     * This exists as a POPULARITY PROXY. Spotify's February 2026 migration stripped the
     * `popularity` field from track and artist objects, and `/recommendations` (which accepted
     * min_popularity/target_popularity) was removed in November 2024 - so there is no longer any
     * API-side way to ask for "well-known artists only". Gemini knows which acts are household
     * names, and every name it returns is resolved through Spotify search, so a hallucinated
     * artist simply yields no results and is skipped.
     */
    suspend fun suggestMainstreamArtists(
        genre: String,
        count: Int = 10,
        songLanguage: String? = null
    ): Result<List<String>> {
        // Artist lists for a genre barely change between mixes, so caching them removes a whole
        // API call per playlist rebuild. That call volume is a direct contributor to 429 rate
        // limiting on free-tier keys, and it competes with the trivia calls that actually matter.
        val cacheKey = "mainstream:${genre.lowercase()}:${songLanguage ?: "any"}"
        artistListCache[cacheKey]?.let {
            Log.d(TAG, "Using cached mainstream artists for '$genre'")
            return Result.success(it)
        }

        val languageRule = songLanguage?.let {
            "\n            - CRITICAL: only artists who primarily perform in $it."
        } ?: ""
        val prompt = """
            List exactly $count well-known, mainstream, widely-recognised recording artists in the
            "$genre" genre - the kind of names a general listener would recognise, not obscure or
            underground acts.

            Hard rules:
            - Only real, existing recording artists.
            - Favour commercially successful and widely played artists.$languageRule
            - Output ONLY the artist names, one per line. No numbering, no commentary, no blank lines.
        """.trimIndent()
        return generateScript(prompt).map { parseArtistList(it, count).also { list -> artistListCache[cacheKey] = list } }
    }

    /**
     * Suggests artists the listener is likely to enjoy, given artists they already love.
     *
     * This exists because Spotify has **no similarity endpoint anymore** - both
     * `/v1/recommendations` and `/v1/artists/{id}/related-artists` were deprecated in November
     * 2024 and return 404 for current API clients. Without them there is no way to ask Spotify
     * "who is like this artist". Gemini has broad music knowledge, so it stands in as the
     * similarity engine; the names it returns are then resolved to real playable tracks via
     * Spotify search, so nothing hallucinated can reach the playlist - an artist that doesn't
     * exist simply returns no search results and is skipped.
     *
     * Returns a plain list of artist names. Asks for names only, since anything conversational
     * would have to be stripped back out.
     */
    suspend fun suggestSimilarArtists(
        seedArtists: List<String>,
        genre: String,
        count: Int = 8,
        songLanguage: String? = null
    ): Result<List<String>> {
        if (seedArtists.isEmpty()) return Result.success(emptyList())
        val cacheKey = "similar:${genre.lowercase()}:${songLanguage ?: "any"}:${seedArtists.take(5).joinToString(",").lowercase()}"
        artistListCache[cacheKey]?.let {
            Log.d(TAG, "Using cached similar artists for '$genre'")
            return Result.success(it)
        }
        val prompt = """
            You are a music recommendation engine. A listener's favourite artists include:
            ${seedArtists.take(12).joinToString(", ")}

            Suggest exactly $count OTHER artists in or adjacent to the "$genre" genre that this
            listener would likely enjoy, based on the sound, era and sensibility of the artists above.

            Hard rules:
            - Do NOT include any artist already listed above.
            - Only suggest real, existing recording artists.${songLanguage?.let { "\n            - CRITICAL: only artists who primarily perform in $it." } ?: ""}
            - Output ONLY the artist names, one per line. No numbering, no commentary, no genres,
              no explanations, no blank lines.
        """.trimIndent()

        return generateScript(prompt).map { raw ->
            parseArtistList(raw, count)
                // Guard against the model echoing a seed artist back despite being told not to.
                .filterNot { suggestion -> seedArtists.any { it.equals(suggestion, ignoreCase = true) } }
                .also { artistListCache[cacheKey] = it }
        }
    }

    /**
     * Shared parser for the artist-name list responses. Models sometimes ignore "no
     * numbering/bullets" instructions, so common list prefixes are stripped rather than trusting
     * compliance.
     */
    private fun parseArtistList(raw: String, count: Int): List<String> =
        raw.lines()
            .map { line ->
                line.trim()
                    .removePrefix("-").removePrefix("*").removePrefix("•")
                    .replace(Regex("^\\d+[.)]\\s*"), "")
                    .trim()
            }
            .filter { it.isNotBlank() && it.length in 2..60 }
            .distinct()
            .take(count)

    // ---------------------------------------------------------------- speech generation

    /**
     * Synthesizes [text] to speech via Gemini's TTS model and returns ready-to-play WAV bytes
     * (PCM already wrapped in a RIFF header - see the class doc). Returns failure on any error so
     * the caller can fall back to on-device TTS rather than leaving music ducked forever.
     */
    suspend fun synthesizeSpeech(text: String): Result<ByteArray> =
        withRetries("tts") { synthesizeSpeechOnce(text) }

    private suspend fun synthesizeSpeechOnce(text: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put(
                    "contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                )
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put(
                        "speechConfig", JSONObject().put(
                            "voiceConfig", JSONObject().put(
                                "prebuiltVoiceConfig", JSONObject().put("voiceName", voiceFor(language))
                            )
                        )
                    )
                })
            }
            val request = Request.Builder()
                .url(ENDPOINT_TEMPLATE.format(TTS_MODEL))
                .addHeader("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // A 404 here while text still works almost certainly means TTS_MODEL has been
                    // retired - see the constant's comment.
                    Log.e(TAG, "TTS failed: HTTP ${response.code} - ${body.take(400)}")
                    val msg = "Gemini TTS error ${response.code}"
                    return@withContext Result.failure(
                        if (response.code in RETRYABLE_CODES) RetryableHttpException(msg) else IOException(msg)
                    )
                }
                val base64Audio = JSONObject(body)
                    .optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)
                    ?.optJSONObject("inlineData")
                    ?.optString("data")
                    ?.takeIf { it.isNotBlank() }
                if (base64Audio == null) {
                    Log.e(TAG, "TTS returned no inlineData. Body: ${body.take(400)}")
                    return@withContext Result.failure(IOException("Gemini TTS returned no audio"))
                }

                val pcm = Base64.decode(base64Audio, Base64.DEFAULT)
                if (pcm.isEmpty()) {
                    return@withContext Result.failure(IOException("Gemini TTS returned empty audio"))
                }
                Log.d(TAG, "TTS produced ${pcm.size} PCM bytes, wrapping as WAV")
                Result.success(pcmToWav(pcm))
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "TTS network error", e)
            Result.failure(RetryableHttpException(e.message ?: "network error"))
        } catch (e: Exception) {
            Log.e(TAG, "TTS threw", e)
            Result.failure(e)
        }
    }

    /**
     * Wraps raw signed-16-bit little-endian mono PCM in a 44-byte RIFF/WAVE header so standard
     * players (ExoPlayer, MediaPlayer) can decode it. Without this the bytes are unplayable and
     * ExoPlayer cannot infer a format - typically producing silence with a confusing error.
     */
    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val byteRate = TTS_SAMPLE_RATE * TTS_CHANNELS * TTS_BITS_PER_SAMPLE / 8
        val blockAlign = TTS_CHANNELS * TTS_BITS_PER_SAMPLE / 8
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) = out.write(
            byteArrayOf(
                (v and 0xff).toByte(),
                ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(),
                ((v shr 24) and 0xff).toByte()
            )
        )
        fun writeShortLE(v: Int) = out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))

        writeAscii("RIFF")
        writeIntLE(36 + pcm.size)   // chunk size = header remainder + payload
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeIntLE(16)              // PCM subchunk size
        writeShortLE(1)             // audio format 1 = PCM
        writeShortLE(TTS_CHANNELS)
        writeIntLE(TTS_SAMPLE_RATE)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(TTS_BITS_PER_SAMPLE)
        writeAscii("data")
        writeIntLE(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
