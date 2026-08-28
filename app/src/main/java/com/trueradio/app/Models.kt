package com.trueradio.app

/** Snapshot of what Spotify is currently playing, mapped from the App Remote SDK's PlayerState. */
data class TrackInfo(
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPaused: Boolean
)

/** The four daypart segments the DJ uses to pick music moods / search queries. */
enum class DaySegment(val startHour: Int, val endHour: Int, val searchQuery: String) {
    MORNING(6, 12, "morning energy hits playlist"),
    AFTERNOON(12, 18, "afternoon focus pop playlist"),
    EVENING(18, 23, "evening chill israeli playlist"),
    NIGHT(23, 6, "late night lofi playlist");

    companion object {
        fun forHour(hour: Int): DaySegment = when {
            hour in MORNING.startHour until MORNING.endHour -> MORNING
            hour in AFTERNOON.startHour until AFTERNOON.endHour -> AFTERNOON
            hour in EVENING.startHour until EVENING.endHour -> EVENING
            else -> NIGHT // covers 23-24 and 0-6
        }
    }
}

/**
 * Predefined news categories the user can toggle on/off. `matchKeywords` are used for simple
 * contains-based matching against RSS headline text (Hebrew + English) so we can prioritize
 * headlines relevant to what the user cares about without needing a per-category RSS endpoint.
 */
enum class NewsCategory(val displayName: String, val matchKeywords: List<String>) {
    GENERAL("כללי", emptyList()), // no filtering; always considered a match
    SPORTS("ספורט", listOf("כדורגל", "כדורסל", "ספורט", "נבחרת", "ליגה", "אולימפיאדה", "sport", "football", "basketball")),
    ECONOMY("כלכלה", listOf("כלכלה", "שוק ההון", "בורסה", "אינפלציה", "שקל", "דולר", "economy", "market", "inflation")),
    TECH("טכנולוגיה", listOf("טכנולוגיה", "הייטק", "סטארטאפ", "בינה מלאכותית", "אפליקציה", "tech", "startup", "ai")),
    ENTERTAINMENT("בידור", listOf("בידור", "קולנוע", "מוזיקה", "טלוויזיה", "סלבריטי", "entertainment", "celebrity", "movie")),
    WORLD("עולם", listOf("עולם", "ארה\"ב", "וושינגטון", "אירופה", "world", "international")),
    HEALTH("בריאות", listOf("בריאות", "רפואה", "חולים", "בית חולים", "health", "medical")),
    MOTORSPORT(
        "ספורט מוטורי",
        listOf(
            "פורמולה 1", "פורמולה1", "פורמולה 2", "פורמולה 3", "פורמולה E", "פורמולה אי",
            "ראלי", "מרוץ מכוניות", "מוטו GP",
            "f1", "formula 1", "formula one", "formula 2", "formula 3", "formula e",
            "wrc", "world rally championship", "rally", "wec", "world endurance championship",
            "motogp", "moto2", "moto3", "indycar", "nascar", "le mans", "grand prix", "gp ",
            "verstappen", "hamilton", "leclerc", "norris", "ferrari f1", "red bull racing",
            "mercedes f1", "mclaren f1"
        )
    ),
    PHOTOGRAPHY(
        "צילום",
        listOf(
            "צילום", "מצלמה", "עדשה", "צלם", "צלמת", "פוטושופ",
            "photography", "photographer", "camera", "lens", "dslr", "mirrorless",
            "aperture", "shutter", "photo editing", "lightroom", "photoshop"
        )
    );

    companion object {
        fun fromNames(names: Collection<String>): Set<NewsCategory> =
            names.mapNotNull { name -> entries.firstOrNull { it.name == name } }.toSet()
    }
}

/**
 * A single RSS feed the user has added as a news source, with a stable [id] so it can be
 * toggled/removed reliably even if its name or URL is edited later.
 */
data class NewsSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true
) {
    /**
     * Serialized as "id\u0001name\u0001url\u0001enabled", joined across sources with a newline.
     * Strips any stray \u0001 or newline characters from the fields first - both are vanishingly
     * unlikely in a real name/URL, but a corrupted clipboard paste containing either would
     * otherwise desync field counts on the next load and silently drop or garble a source.
     */
    fun serialize(): String = listOf(id, name, url, enabled.toString())
        .joinToString("\u0001") { it.replace("\u0001", "").replace("\n", " ") }

    companion object {
        val DEFAULT_SOURCES = listOf(
            NewsSource(id = "ynet", name = "Ynet", url = "https://www.ynet.co.il/Integration/StoryRss2.xml"),
            NewsSource(id = "formula1-official", name = "Formula1.com", url = "https://www.formula1.com/en/latest/all.xml"),
            NewsSource(id = "racefans", name = "RaceFans (F1, IndyCar, WEC, F2)", url = "https://www.racefans.net/feed/"),
            NewsSource(id = "petapixel", name = "PetaPixel (Photography)", url = "https://feeds.feedburner.com/PetaPixel")
        )

        fun deserializeList(blob: String): List<NewsSource> {
            if (blob.isBlank()) return DEFAULT_SOURCES
            return blob.split("\n").mapNotNull { line ->
                val parts = line.split("\u0001")
                if (parts.size != 4) return@mapNotNull null
                NewsSource(id = parts[0], name = parts[1], url = parts[2], enabled = parts[3].toBoolean())
            }
        }

        fun serializeList(sources: List<NewsSource>): String = sources.joinToString("\n") { it.serialize() }
    }
}

/**
 * The user's news preferences: which predefined categories to prioritize, plus free-text
 * "liked topics" keywords (e.g. a favorite team, a company, a public figure) for finer-grained
 * personalization than the fixed category list allows, plus the set of RSS sources to pull from.
 */
data class NewsPreferences(
    val selectedCategories: Set<NewsCategory> = setOf(NewsCategory.GENERAL),
    val likedTopics: List<String> = emptyList(),
    val sources: List<NewsSource> = NewsSource.DEFAULT_SOURCES
) {
    /** All keyword strings (category + custom) used to score/prioritize headlines. */
    fun allKeywords(): List<String> =
        selectedCategories.flatMap { it.matchKeywords } + likedTopics.map { it.trim() }.filter { it.isNotBlank() }

    fun enabledSourceUrls(): List<String> = sources.filter { it.enabled }.map { it.url }

    fun toSerializedCategories(): String = selectedCategories.joinToString(",") { it.name }
    fun toSerializedTopics(): String = likedTopics.joinToString(",")
    fun toSerializedSources(): String = NewsSource.serializeList(sources)

    companion object {
        fun deserialize(categoriesCsv: String, topicsCsv: String, sourcesBlob: String): NewsPreferences {
            val categories = if (categoriesCsv.isBlank()) {
                setOf(NewsCategory.GENERAL)
            } else {
                NewsCategory.fromNames(categoriesCsv.split(",").map { it.trim() }).ifEmpty { setOf(NewsCategory.GENERAL) }
            }
            val topics = topicsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val sources = NewsSource.deserializeList(sourcesBlob)
            return NewsPreferences(categories, topics, sources)
        }
    }
}

/**
 * A rotating genre pool the user curates for the hourly music-genre switch. Genre strings should
 * be values Spotify's artist/track metadata actually tags things with (e.g. "pop", "hip hop",
 * "rock", "house", "lo-fi", "jazz") since they're matched against artist genre tags fetched from
 * the Web API, not against a fixed enum - Spotify's own genre vocabulary is large and shifts
 * over time, so a free-text, user-editable list is more robust than a hardcoded set.
 */
data class GenreRotation(
    val genres: List<String> = DEFAULT_GENRES,
    /** If true, the hour's genre is picked in fixed round-robin order; if false, picked at random each hour. */
    val sequential: Boolean = true
) {
    /**
     * Deterministic pick for a given hour-of-day when [sequential]. Otherwise a pick that's
     * stable *within* that hour (so it doesn't change if the service restarts mid-hour) but
     * still varies day to day, by folding [daySeed] (e.g. day-of-year) into the random seed -
     * seeding on hour alone would otherwise pick the exact same "random" genre at, say, 3pm
     * every single day forever, defeating the point of random mode.
     */
    fun genreForHour(hour: Int, daySeed: Int = 0): String? {
        if (genres.isEmpty()) return null
        return if (sequential) {
            genres[hour % genres.size]
        } else {
            genres[kotlin.random.Random(hour * 31 + daySeed).nextInt(genres.size)]
        }
    }

    fun toSerialized(): String = (if (sequential) "1" else "0") + "\u0001" + genres.joinToString(",") { it.replace(",", " ").trim() }

    companion object {
        val DEFAULT_GENRES = listOf("pop", "hip hop", "rock", "electronic", "chill", "indie", "r&b", "dance")

        fun deserialize(blob: String): GenreRotation {
            if (blob.isBlank()) return GenreRotation()
            val parts = blob.split("\u0001")
            if (parts.size != 2) return GenreRotation()
            val sequential = parts[0] == "1"
            val genres = parts[1].split(",").map { it.trim() }.filter { it.isNotBlank() }
            return GenreRotation(genres.ifEmpty { DEFAULT_GENRES }, sequential)
        }
    }
}

/** High-level state the UI observes. */
data class DjUiState(
    val isConnectedToSpotify: Boolean = false,
    val isServiceRunning: Boolean = false,
    val currentTrack: TrackInfo? = null,
    val lastDjLine: String = "",
    val statusMessage: String = "Idle"
)
