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

/**
 * Genres selected per daypart, so the morning mix can differ from the late-night one. Stored as
 * segment -> genre list; [GenreRotation] still drives the hour-by-hour rotation *within* whatever
 * the active segment allows.
 */
data class SegmentGenres(
    val bySegment: Map<DaySegment, List<String>> = DEFAULTS
) {
    fun genresFor(segment: DaySegment): List<String> =
        bySegment[segment]?.takeIf { it.isNotEmpty() } ?: DEFAULTS[segment].orEmpty()

    fun withGenre(segment: DaySegment, genre: String, selected: Boolean): SegmentGenres {
        val current = bySegment[segment] ?: DEFAULTS[segment].orEmpty()
        val updated = if (selected) (current + genre).distinct() else current - genre
        return copy(bySegment = bySegment + (segment to updated))
    }

    /** Serialized as "SEGMENT:g1,g2|SEGMENT:g1,g2" - see NewsSource for why delimiters are stripped. */
    fun serialize(): String = bySegment.entries.joinToString("|") { (seg, genres) ->
        seg.name + ":" + genres.joinToString(",") { it.replace(",", " ").replace("|", " ").trim() }
    }

    companion object {
        val ALL_GENRES = listOf(
            "pop", "rock", "hip hop", "r&b", "electronic", "house", "techno", "indie",
            "alternative", "jazz", "soul", "funk", "classical", "metal", "punk", "reggae",
            "latin", "reggaeton", "country", "folk", "lo-fi", "ambient", "disco", "blues",
            "mizrahi", "israeli rock", "mediterranean"
        )

        val DEFAULTS: Map<DaySegment, List<String>> = mapOf(
            DaySegment.MORNING to listOf("pop", "indie", "soul"),
            DaySegment.AFTERNOON to listOf("pop", "hip hop", "r&b"),
            DaySegment.EVENING to listOf("rock", "alternative", "electronic"),
            DaySegment.NIGHT to listOf("lo-fi", "ambient", "jazz")
        )

        fun deserialize(blob: String): SegmentGenres {
            if (blob.isBlank()) return SegmentGenres()
            val parsed = blob.split("|").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 2) return@mapNotNull null
                val segment = DaySegment.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
                val genres = parts[1].split(",").map { it.trim() }.filter { it.isNotBlank() }
                segment to genres
            }.toMap()
            return if (parsed.isEmpty()) SegmentGenres() else SegmentGenres(DEFAULTS + parsed)
        }
    }
}

/**
 * One like/dislike judgement. The artist is stored alongside the track URI because feedback is
 * useful at both levels: skipping one disliked song is track-level, but disliking several tracks
 * by the same artist is a signal to stop surfacing that artist at all.
 */
data class TrackVerdict(val uri: String, val artist: String)

/**
 * Accumulated like/dislike history, used to bias future mixes.
 *
 * Kept as an append-only record rather than a score per track: it's small (a few hundred entries
 * at most for realistic use), and keeping the raw judgements means the weighting rules can be
 * changed later without having lost the underlying data.
 */
data class TrackFeedback(
    val liked: List<TrackVerdict> = emptyList(),
    val disliked: List<TrackVerdict> = emptyList()
) {
    val likedUris: Set<String> get() = liked.map { it.uri }.toSet()
    val dislikedUris: Set<String> get() = disliked.map { it.uri }.toSet()

    /** Artists with at least one like, most-liked first - used as extra seeds for the mix. */
    fun favouriteArtists(): List<String> =
        liked.groupingBy { it.artist }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }
            .filter { it.isNotBlank() }

    /**
     * Artists disliked [threshold]+ times, excluded from the mix entirely. Requires more than one
     * dislike so a single skip of one bad song doesn't blacklist an artist you otherwise like.
     */
    fun blockedArtists(threshold: Int = 2): Set<String> =
        disliked.groupingBy { it.artist.lowercase() }.eachCount()
            .filterValues { it >= threshold }.keys

    fun withLike(verdict: TrackVerdict): TrackFeedback = copy(
        // A like overrides any previous dislike of the same track, and vice versa, so changing
        // your mind actually takes effect instead of leaving contradictory entries on both lists.
        liked = (liked.filterNot { it.uri == verdict.uri } + verdict).takeLast(MAX_ENTRIES),
        disliked = disliked.filterNot { it.uri == verdict.uri }
    )

    fun withDislike(verdict: TrackVerdict): TrackFeedback = copy(
        liked = liked.filterNot { it.uri == verdict.uri },
        disliked = (disliked.filterNot { it.uri == verdict.uri } + verdict).takeLast(MAX_ENTRIES)
    )

    fun serialize(): String {
        fun enc(list: List<TrackVerdict>) = list.joinToString("\n") { v ->
            v.uri.replace("\u0001", "").replace("\n", "") + "\u0001" +
                v.artist.replace("\u0001", "").replace("\n", " ")
        }
        return enc(liked) + "\u0002" + enc(disliked)
    }

    companion object {
        /** Cap so the store can't grow without bound over months of listening. */
        private const val MAX_ENTRIES = 500

        fun deserialize(blob: String): TrackFeedback {
            if (blob.isBlank()) return TrackFeedback()
            val halves = blob.split("\u0002")
            fun dec(part: String) = part.split("\n").mapNotNull { line ->
                val parts = line.split("\u0001")
                if (parts.size != 2 || parts[0].isBlank()) null else TrackVerdict(parts[0], parts[1])
            }
            return TrackFeedback(
                liked = dec(halves.getOrElse(0) { "" }),
                disliked = dec(halves.getOrElse(1) { "" })
            )
        }
    }
}

/**
 * Per-genre "anchor" artists: 3-5 acts the user names as defining the vibe they want from a
 * genre. These are seeds, NOT a whitelist - the mix uses them to steer similarity expansion, and
 * deliberately caps how much of the playlist they occupy directly (see HourlyMixEngine), so the
 * hour stays varied instead of looping the same five artists.
 */
data class GenreAnchors(
    val byGenre: Map<String, List<String>> = emptyMap()
) {
    fun artistsFor(genre: String): List<String> =
        byGenre[genre.lowercase()].orEmpty()

    fun withArtist(genre: String, artist: String): GenreAnchors {
        val key = genre.lowercase()
        val trimmed = artist.trim()
        if (trimmed.isBlank()) return this
        val current = byGenre[key].orEmpty()
        // Case-insensitive dedupe so "Radiohead" and "radiohead" don't both get stored.
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return this
        return copy(byGenre = byGenre + (key to (current + trimmed).take(MAX_PER_GENRE)))
    }

    fun withoutArtist(genre: String, artist: String): GenreAnchors {
        val key = genre.lowercase()
        val current = byGenre[key].orEmpty()
        return copy(byGenre = byGenre + (key to current.filterNot { it.equals(artist, ignoreCase = true) }))
    }

    /** Serialized as "genre\u0001artist,artist|genre\u0001artist" - delimiters stripped from values. */
    fun serialize(): String = byGenre.entries
        .filter { it.value.isNotEmpty() }
        .joinToString("|") { (genre, artists) ->
            genre.replace("|", "").replace("\u0001", "") + "\u0001" +
                artists.joinToString(",") { it.replace(",", " ").replace("|", " ").trim() }
        }

    companion object {
        const val MAX_PER_GENRE = 5
        const val RECOMMENDED_MIN = 3

        fun deserialize(blob: String): GenreAnchors {
            if (blob.isBlank()) return GenreAnchors()
            val map = blob.split("|").mapNotNull { entry ->
                val parts = entry.split("\u0001")
                if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
                val artists = parts[1].split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (artists.isEmpty()) null else parts[0] to artists
            }.toMap()
            return GenreAnchors(map)
        }
    }
}

/**
 * Optional constraint on the language songs are sung in.
 *
 * NOTE: Spotify exposes no language field on tracks or artists, so this cannot be a hard filter.
 * It works by steering artist selection (Gemini is asked for artists who perform in this
 * language) and by tagging genre searches. Tracks coming from your own saved/top library are NOT
 * filtered - they're already your taste, and there'd be no reliable way to detect their language
 * anyway. Treat it as a strong bias, not a guarantee.
 */
enum class SongLanguage(val displayName: String, val promptName: String?) {
    ANY("Any language", null),
    HEBREW("עברית / Hebrew", "Hebrew"),
    ENGLISH("English", "English"),
    SPANISH("Español / Spanish", "Spanish"),
    FRENCH("Français / French", "French"),
    ARABIC("العربية / Arabic", "Arabic"),
    RUSSIAN("Русский / Russian", "Russian")
}

/** Language the DJ speaks. Drives both the Gemini system prompt and the TTS voice selection. */
enum class DjLanguage(val displayName: String) {
    HEBREW("עברית"),
    ENGLISH("English")
}

/** User's preferred UI appearance. SYSTEM follows the device's light/dark setting automatically. */
enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

/** High-level state the UI observes. */
data class DjUiState(
    val isConnectedToSpotify: Boolean = false,
    val isServiceRunning: Boolean = false,
    val currentTrack: TrackInfo? = null,
    val lastDjLine: String = "",
    val statusMessage: String = "Idle"
)
