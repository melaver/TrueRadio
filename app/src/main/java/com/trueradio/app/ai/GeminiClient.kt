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
 * Script generation only. Speech is handled by CloudTtsClient - Gemini's TTS models are
 * preview-only with limits this app kept exhausting, whereas Cloud TTS is a separate product with
 * a large permanent free allowance. Splitting them keeps Gemini on the cheap, batchable half.
 *
 */
class GeminiClient(
    private val apiKey: String,
    private val language: DjLanguage = DjLanguage.ENGLISH
) {
    /**
     * Per-process cache of generated artist lists. Genre->artists mappings are effectively static
     * between mixes, so re-asking on every playlist rebuild burns quota for no new information -
     * a meaningful cause of 429s on free-tier keys.
     */
    private val artistListCache = mutableMapOf<String, List<String>>()

    /** Seeded from disk at startup and flushed back on change, so restarts don't re-ask Gemini. */
    fun primeArtistCache(cached: Map<String, List<String>>) {
        artistListCache.putAll(cached)
    }

    fun artistCacheSnapshot(): Map<String, List<String>> = artistListCache.toMap()



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
        @Volatile
        private var consecutiveTrips = 0
        @Volatile
        private var lastTripAtMs = 0L

        /**
         * Escalating cooldowns. A fixed 5-minute pause works for a burst (per-minute) limit, but
         * if the DAILY quota is exhausted the very next call after the cooldown fails again -
         * an endless 429-wait-429 loop that never recovers and keeps burning failed requests.
         * Backing off further each time a trip recurs quickly turns that loop into a few attempts
         * spread over hours, which is the only sane behaviour against a daily cap.
         */
        private val COOLDOWN_LADDER_MS = longArrayOf(
            5 * 60 * 1000L,    // first trip: assume a burst limit
            15 * 60 * 1000L,
            60 * 60 * 1000L,
            3 * 60 * 60 * 1000L // repeated trips: almost certainly a daily cap
        )
        /** A trip this soon after the last one means the previous cooldown didn't help. */
        private const val ESCALATE_WINDOW_MS = 10 * 60 * 1000L

        fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs
        fun rateLimitSecondsRemaining(): Long =
            ((rateLimitedUntilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

        /** True once cooldowns have escalated past the burst-limit assumption. */
        fun isLikelyDailyQuotaExhausted(): Boolean = consecutiveTrips >= 2

        private fun tripRateLimit() {
            val now = System.currentTimeMillis()
            consecutiveTrips = if (now - lastTripAtMs < rateLimitedUntilMs - lastTripAtMs + ESCALATE_WINDOW_MS) {
                (consecutiveTrips + 1).coerceAtMost(COOLDOWN_LADDER_MS.size - 1)
            } else {
                0
            }
            lastTripAtMs = now
            rateLimitedUntilMs = now + COOLDOWN_LADDER_MS[consecutiveTrips]
        }

        /** Called after any success, so a recovered quota returns to short cooldowns. */
        private fun noteSuccess() {
            if (consecutiveTrips != 0 && System.currentTimeMillis() - lastTripAtMs > ESCALATE_WINDOW_MS) {
                consecutiveTrips = 0
            }
        }

        /**
         * Rolling alias Google maintains pointing at their current recommended Flash model, used
         * instead of a dated id (a hardcoded "gemini-2.5-flash" started 404ing for newly-created
         * API keys as Google phased it out ahead of its Oct 16 2026 shutdown). Avoids this
         * breaking again as the Flash lineup advances.
         */
        private const val TEXT_MODEL = "gemini-flash-latest"


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

        var lastFailure: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = block()
            if (result.isSuccess) {
                noteSuccess()
                return result
            }

            val error = result.exceptionOrNull()
            lastFailure = error
            // A 429 means quota, not transient load - stop retrying immediately and lock out
            // further calls, otherwise the retries deepen the hole.
            if (error?.message?.contains("429") == true) {
                tripRateLimit()
                Log.e(TAG, "[$label] RATE LIMITED (429) - pausing all Gemini calls for ${rateLimitSecondsRemaining() / 60} minutes" +
                    if (isLikelyDailyQuotaExhausted()) " (repeated trips suggest the DAILY quota is exhausted)" else "")
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

            Task: write ONE short radio transition for EACH of the following ${tracks.size} tracks.
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

    suspend fun generateHourlyNews(
        headlines: List<String>,
        likedTopics: List<String> = emptyList(),
        lengthHint: String = "About three short sentences."
    ): Result<String> = generateScript(hourlyNewsPrompt(headlines, likedTopics, lengthHint))

    /**
     * Generates a bank of reusable generic station lines in ONE call. Played at random between
     * real trivia segments, so most segments cost nothing at all.
     *
     * This is also arguably better radio: real DJs don't have a fresh anecdote for every single
     * song either - they fill with station identity most of the time.
     */
    suspend fun generateEvergreenLines(count: Int = 30): Result<List<String>> {
        val prompt = """
            ${persona()}

            Task: write $count SHORT standalone radio lines for a station called TrueRadio.
            These are generic filler between songs - they must NOT mention any specific artist,
            song, genre or time of day, because they'll be replayed at random.

            Mix of: station identity, wry observations about music or listening, light remarks
            about the moment. One or two sentences each, following all the persona rules above.

            Output ONLY the lines, one per line. No numbering, no quotes, no blank lines.
        """.trimIndent()

        return generateScript(prompt).map { raw ->
            raw.lines()
                .map { it.trim().removePrefix("-").removePrefix("*").replace(Regex("^\\d+[.)]\\s*"), "").trim() }
                .filter { it.length in 15..300 }
                .distinct()
                .take(count)
        }
    }

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


}
