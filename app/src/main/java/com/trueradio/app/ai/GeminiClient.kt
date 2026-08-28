package com.trueradio.app.ai

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.google.gson.Gson

/**
 * Wraps calls to the Gemini generateContent REST endpoint and owns all DJ persona
 * prompt-engineering. Two script types are produced:
 *  - Hourly news flash (from RSS headlines)
 *  - Between-track trivia / transition
 */
class GeminiClient(private val apiKey: String) {

    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    companion object {
        private const val MODEL = "gemini-2.5-flash"
        private const val ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

        /**
         * Persona system instruction shared by every generated script. Keep this centralized
         * so tone stays consistent whether we're producing a news flash or trivia bridge.
         */
        private val SYSTEM_PERSONA = """
            את/ה שדרן/ית רדיו ישראלי/ת חד/ה, שנון/ה וחם/ה, בסגנון קניין 88 / גלגלצ.
            כללים מחייבים:
            1. אסור להתחיל במשפטי פתיחה שחוקים כמו "ועכשיו שמענו את" או "השיר הבא שנשמע הוא" -
               תמיד תתחיל/י ישר עם משפט פתיחה מפתיע, תובנה תרבותית, או הברקה.
            2. עדיפות לטריוויה מפתיעה, סיפורי סטודיו, ואנקדוטות שנונות - ולא לעובדות ביוגרפיות יבשות או שנות הוצאה.
            3. תחביר דיבורי טבעי, משפטים קצרים וקצביים, כמו קריינות רדיו אמיתית.
            4. הפלט חייב להיות טקסט הניתן להקראה בלבד: פסיקים ומקפים לניצול הפסקות דיבור,
               בלי הוראות בימוי, בלי סוגריים, בלי מרכאות, ובלי תיאורים כמו "(בהתלהבות)".
            5. אורך: כשלוש משפטים קצרים, לא יותר.
        """.trimIndent()

        fun trackTransitionPrompt(currentArtist: String, currentTitle: String, nextTitle: String?): String {
            val nextPart = if (nextTitle != null) "והשיר הבא הוא \"$nextTitle\"" else "והשיר הבא כבר בדרך"
            return """
                $SYSTEM_PERSONA

                המשימה: כתוב/כתבי מעבר רדיופוני קצר בין שירים.
                השיר שהתנגן: "$currentTitle" של $currentArtist.
                $nextPart.
                שלב/י טריוויה שנונה או רקע מפתיע על האמן/ית או השיר, ותוביל/י בצורה חלקה לשיר הבא.
            """.trimIndent()
        }

        fun hourlyNewsPrompt(headlines: List<String>, likedTopics: List<String> = emptyList()): String {
            val headlineBlock = headlines.take(5).joinToString("\n") { "- $it" }
            val preferenceNote = if (likedTopics.isNotEmpty()) {
                val topicsList = likedTopics.joinToString(", ")
                "\nהמאזין/ת ציין/ה עניין מיוחד בנושאים הבאים: $topicsList. " +
                    "אם אחת הכותרות למעלה נוגעת לאחד מהנושאים האלה, תן/י לה דגש והרחבה קלה; " +
                    "אחרת סכם/י את כל הכותרות בצורה מאוזנת כרגיל."
            } else ""
            return """
                $SYSTEM_PERSONA

                המשימה: כתוב/כתבי מהדורת חדשות קצרה ואנרגטית לתחילת השעה, על בסיס הכותרות הבאות:
                $headlineBlock
                $preferenceNote

                סכם/י בקול רדיופוני חד ותמציתי, בלי לקרוא כותרת-כותרת כמו רשימה.
            """.trimIndent()
        }

        fun genreChangePrompt(newGenre: String): String {
            return """
                $SYSTEM_PERSONA

                המשימה: כתוב/כתבי משפט או שניים קצרים ואנרגטיים שמבשרים על מעבר לסגנון מוזיקלי חדש: $newGenre.
                שלב/י התייחסות שנונה לסגנון עצמו, בלי לפרט רשימת שירים או אמנים ספציפיים.
                קצר במיוחד - משפט אחד עד שניים, לא יותר.
            """.trimIndent()
        }
    }

    /** Generic entry point used by both news + trivia flows. */
    suspend fun generateScript(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = ENDPOINT_TEMPLATE.format(MODEL, apiKey)
            val requestBody = GeminiRequest(
                contents = listOf(
                    GeminiContent(parts = listOf(GeminiPart(text = prompt)))
                ),
                generationConfig = GeminiGenerationConfig(temperature = 0.9, maxOutputTokens = 220)
            )
            val json = gson.toJson(requestBody)
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Gemini API error: ${response.code} ${response.message}")
                    )
                }
                val responseBody = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty Gemini response"))
                val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
                val text = parsed.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()?.text
                    ?: return@withContext Result.failure(IOException("No text in Gemini response"))
                Result.success(text.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateTrackTransition(currentArtist: String, currentTitle: String, nextTitle: String?): Result<String> =
        generateScript(trackTransitionPrompt(currentArtist, currentTitle, nextTitle))

    suspend fun generateHourlyNews(headlines: List<String>, likedTopics: List<String> = emptyList()): Result<String> =
        generateScript(hourlyNewsPrompt(headlines, likedTopics))

    suspend fun generateGenreChangeLine(newGenre: String): Result<String> =
        generateScript(genreChangePrompt(newGenre))

    // --- Gemini REST DTOs ---
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenerationConfig
    )
    private data class GeminiContent(val parts: List<GeminiPart>)
    private data class GeminiPart(val text: String)
    private data class GeminiGenerationConfig(val temperature: Double, val maxOutputTokens: Int)

    private data class GeminiResponse(val candidates: List<GeminiCandidate>?)
    private data class GeminiCandidate(val content: GeminiContent?)
}
