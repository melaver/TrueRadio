package com.trueradio.app.news

import com.trueradio.app.NewsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches and parses one or more RSS 2.0 feeds into a flat list of headline strings suitable
 * for feeding into the hourly-news Gemini prompt.
 *
 * The set of feeds is fully user-controlled (see [com.trueradio.app.NewsSource] /
 * [NewsPreferences.enabledSourceUrls]) rather than hardcoded, since RSS endpoints for most
 * outlets aren't reliably documented or stable enough to bake in as verified defaults beyond
 * Ynet's public feed. Add any RSS URL you trust from the app's News Sources screen.
 *
 * When [NewsPreferences] are supplied, headlines are re-ordered so that any headline matching
 * a selected category's keywords or one of the user's free-text "liked topics" is prioritized
 * to the front of the list, before falling back to natural (per-feed chronological) order.
 */
class NewsRepository {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchTopHeadlines(
        limit: Int = 5,
        preferences: NewsPreferences = NewsPreferences()
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val sourceUrls = preferences.enabledSourceUrls()
        if (sourceUrls.isEmpty()) {
            return@withContext Result.failure(IOException("No news sources are enabled - add or enable one first"))
        }

        // Fetch every enabled feed in parallel; a failure in one feed doesn't sink the others.
        val perFeedResults = sourceUrls.map { url -> async { fetchSingleFeed(url) } }.awaitAll()

        val allHeadlines = perFeedResults.flatMap { it.getOrDefault(emptyList()) }

        if (allHeadlines.isEmpty()) {
            val firstError = perFeedResults.firstNotNullOfOrNull { it.exceptionOrNull() }
            return@withContext Result.failure(
                IOException("No headlines could be fetched from any enabled source${firstError?.message?.let { ": $it" } ?: ""}")
            )
        }

        val deduped = allHeadlines.distinctBy { it.trim().lowercase() }
        val ordered = prioritizeByPreferences(deduped, preferences)
        Result.success(ordered.take(limit))
    }

    private fun fetchSingleFeed(feedUrl: String): Result<List<String>> {
        return try {
            val request = Request.Builder().url(feedUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("RSS fetch failed for $feedUrl: ${response.code}"))
                }
                val xml = response.body?.string() ?: return Result.failure(IOException("Empty RSS body from $feedUrl"))
                // Parse as XML so <title> / <item> tags are read correctly (RSS is XML, not HTML).
                val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                val items = doc.select("item")
                val headlines = items.mapNotNull { it.selectFirst("title")?.text()?.trim() }
                    .filter { it.isNotBlank() }
                if (headlines.isEmpty()) {
                    Result.failure(IOException("No headlines parsed from $feedUrl"))
                } else {
                    Result.success(headlines)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Puts headlines matching the user's keywords first, preserving relative order within each group. */
    private fun prioritizeByPreferences(headlines: List<String>, preferences: NewsPreferences): List<String> {
        val keywords = preferences.allKeywords().map { it.lowercase() }
        if (keywords.isEmpty()) return headlines

        val (matched, unmatched) = headlines.partition { headline ->
            val lower = headline.lowercase()
            keywords.any { keyword -> keyword.isNotBlank() && lower.contains(keyword) }
        }
        return matched + unmatched
    }
}
