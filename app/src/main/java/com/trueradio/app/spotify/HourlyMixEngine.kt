package com.trueradio.app.spotify

import android.util.Log
import com.trueradio.app.DaySegment
import com.trueradio.app.GenreStrictness
import com.trueradio.app.SecureSettings
import com.trueradio.app.SongLanguage
import com.trueradio.app.ai.GeminiClient

/**
 * Builds each hour's playlist by intersecting the target genre with the listener's own taste.
 *
 * ## Why this doesn't use Spotify's Recommendations API
 * `GET /v1/recommendations` - with `seed_genres`, `seed_artists`, `target_popularity` and
 * `target_energy`/`valence`/`acousticness` - was deprecated in November 2024 and 404s for all
 * current clients. `audio-features` (the source of those mood numbers) went with it, and the
 * February 2026 migration removed the `popularity` field from track and artist objects. None of
 * those knobs exist any more at any access level.
 *
 * ## The single rule
 * EVERY track in the mix comes from an artist that Spotify itself tags with the target genre.
 * That check is applied uniformly across all six tiers - see [artistMatchesGenre]. Tiers differ
 * only in *where candidates come from*, never in whether they're verified. This is what stops the
 * genre drift the old recommendations endpoint produced by treating seeds as mere hints.
 *
 * ## Tiers
 *  1. SAVED     - Liked Songs (deliberate choice, strongest signal)
 *  2. TOP       - most-played tracks, across all three time ranges
 *  3. ANCHORS   - artists the listener named for this genre
 *  4. DEEP CUTS - further catalogue from their genre-matching artists
 *  5. DISCOVERY - Gemini-suggested adjacent artists
 *  6. FILL      - mainstream artists, then general genre search
 */
class HourlyMixEngine(
    private val webApi: SpotifyWebApiClient,
    private val settings: SecureSettings,
    private val geminiClient: GeminiClient? = null
) {
    @Volatile
    var lastPublishedTracks: List<Pair<String, String>> = emptyList()
        private set

    /**
     * Per-build memo of artist name -> Spotify genre tags (null = no such artist).
     *
     * Verification needs one artist lookup per distinct name, and the same artists recur across
     * tiers constantly - a listener's saved tracks and top tracks overlap heavily, and genre
     * search returns the same acts repeatedly. Without memoisation a single mix would fire
     * hundreds of redundant lookups; with it, each distinct artist costs at most one.
     */
    private val genreTagCache = mutableMapOf<String, List<String>?>()

    companion object {
        private const val TAG = "HourlyMixEngine"
        private const val PLAYLIST_NAME = "TrueRadio Hourly Mix"
        private const val PLAYLIST_DESCRIPTION =
            "Auto-updated every hour by TrueRadio - do not add manual tracks, they'll be replaced."
        private const val TARGET_TRACK_COUNT = 30
        private const val SEARCH_PAGE_SIZE = 10 // Spotify's Feb 2026 search `limit` cap

        /**
         * Search is relevance-ranked, so page 1 holds recognisable artists and deeper pages get
         * progressively obscure. Staying shallow keeps fill material mainstream.
         */
        private const val MAX_SEARCH_OFFSET = 20
        private const val REMIX_OFFSET_STEP = 5

        /**
         * Ceiling on verification lookups per build. Verification is worth its cost, but an
         * unbounded loop over a long genre-search tail could fire a lot of requests for
         * diminishing returns; past this point the remaining fill is taken unverified rather than
         * hammering the API.
         */
        private const val MAX_VERIFICATIONS = 60

        private const val SHARE_SAVED = 0.30
        private const val SHARE_TOP = 0.25
        private const val SHARE_ANCHORS = 0.20
        private const val SHARE_DEEP_CUTS = 0.20
        private const val SHARE_DISCOVERY = 0.20
    }

    private var verificationCount = 0

    /**
     * Local genre-tag matching.
     *
     * Spotify's artist tags are hyper-specific ("permanent wave", "float house", "escape room"),
     * so exact equality matches almost nothing. Substring matching in either direction catches the
     * realistic cases: "rock" vs "art rock", "hip hop" vs "conscious hip hop". Word-overlap
     * matching was tried and removed - it produced false positives that were themselves a drift
     * source ("house" matching "house of worship").
     */
    private fun tagMatchesGenre(tag: String, genreLower: String): Boolean {
        val t = tag.lowercase().trim()
        return t == genreLower || t.contains(genreLower) || genreLower.contains(t)
    }

    /**
     * The one verification used by every tier: does Spotify tag this artist with the target genre?
     *
     * [knownTags] lets callers skip the network lookup when tags are already in hand (top artists
     * arrive with theirs attached), so verification is free for the tiers built from them.
     */
    private suspend fun artistMatchesGenre(
        artistName: String,
        genreLower: String,
        knownTags: List<String>? = null
    ): Boolean {
        val key = artistName.lowercase()
        val tags = knownTags
            ?: genreTagCache.getOrPut(key) {
                if (verificationCount >= MAX_VERIFICATIONS) return@getOrPut null
                verificationCount++
                webApi.searchArtistByName(artistName).getOrNull()?.genres
            }
        return tags?.any { tagMatchesGenre(it, genreLower) } == true
    }

    /**
     * Mood hint per daypart, folded into artist-selection prompts.
     *
     * Replaces the `target_energy`/`valence`/`acousticness` parameters of the deprecated
     * recommendations endpoint: no remaining Spotify API exposes per-track mood, so this steers at
     * artist-selection time instead of filtering numerically.
     */
    private fun moodHintFor(segment: DaySegment): String = when (segment) {
        DaySegment.MORNING -> "bright, upbeat, energising - good morning listening"
        DaySegment.AFTERNOON -> "steady, focused, mid-energy"
        DaySegment.EVENING -> "warmer, richer, winding down but still engaged"
        DaySegment.NIGHT -> "calm, atmospheric, low-energy late-night listening"
    }

    suspend fun buildAndPublishMixForGenre(
        genre: String,
        variation: Int = 0,
        segment: DaySegment = DaySegment.AFTERNOON
    ): Result<String> {
        val playlistId = ensurePlaylistExists().getOrElse { return Result.failure(it) }
        val genreLower = genre.lowercase().trim()
        val remixOffset = (variation * REMIX_OFFSET_STEP) % (MAX_SEARCH_OFFSET + REMIX_OFFSET_STEP)
        val strictness = settings.snapshotGenreStrictness()
        val songLanguage = SongLanguage.promptClause(settings.snapshotSongLanguages())
        val moodHint = moodHintFor(segment)

        genreTagCache.clear()
        verificationCount = 0

        // ---- Fetch personal data across all three time ranges ----
        val allTopArtists = listOf("short_term", "medium_term", "long_term")
            .flatMap { range -> webApi.getTopArtists(timeRange = range).getOrDefault(emptyList()) }
            .distinctBy { it.id }

        val allTopTracks = listOf("short_term", "medium_term", "long_term")
            .flatMap { range -> webApi.getTopTracks(timeRange = range).getOrDefault(emptyList()) }
            .distinctBy { it.uri }

        val savedTracks = webApi.getSavedTracks().getOrDefault(emptyList())

        if (allTopArtists.isEmpty() && allTopTracks.isEmpty() && savedTracks.isEmpty()) {
            Log.e(TAG, "No personal listening data available at all")
            return Result.failure(
                IllegalStateException("Couldn't read your Spotify listening history - try reconnecting Spotify in Settings")
            )
        }

        // Top artists arrive with their genre tags attached, so seed the cache from them - these
        // verifications then cost nothing.
        allTopArtists.forEach { genreTagCache[it.name.lowercase()] = it.genres }

        val genreArtists = allTopArtists.filter { artist ->
            artist.genres.any { tagMatchesGenre(it, genreLower) }
        }
        Log.d(TAG, "Intersection: ${genreArtists.size}/${allTopArtists.size} top artists match '$genre'" +
            if (genreArtists.isNotEmpty()) " (${genreArtists.take(5).joinToString { it.name }})" else "")

        val feedback = settings.snapshotTrackFeedback()
        val blockedArtists = feedback.blockedArtists()
        val dislikedUris = feedback.dislikedUris
        fun allowed(track: SpotifyTrack) =
            track.uri !in dislikedUris && track.artistName.lowercase() !in blockedArtists

        val anchors = settings.snapshotGenreAnchors().seedsFor(genre)
        val quota = { share: Double -> (TARGET_TRACK_COUNT * share).toInt().coerceAtLeast(1) }
        val curated = mutableListOf<String>()

        /** Verifies a batch of candidate tracks by artist genre tags, preserving order. */
        suspend fun verifiedTracks(candidates: List<SpotifyTrack>, cap: Int, tierName: String): List<String> {
            val out = mutableListOf<String>()
            var rejected = 0
            for (track in candidates) {
                if (out.size >= cap) break
                if (!allowed(track)) continue
                if (track.uri in curated || track.uri in out) continue
                if (artistMatchesGenre(track.artistName, genreLower)) {
                    out += track.uri
                } else {
                    rejected++
                }
            }
            Log.d(TAG, "Tier $tierName: ${out.size} accepted, $rejected rejected on genre tags")
            return out
        }

        // ---- Tier 1: saved tracks ----
        // Verified per-track rather than restricted to the top-artist list: a Liked Song by a
        // genre-matching artist who isn't in the listener's top 50 is still exactly the material
        // this tier wants, and the old name-matching approach discarded all of it.
        curated += verifiedTracks(savedTracks.shuffled(), quota(SHARE_SAVED), "SAVED")

        // ---- Tier 2: most-played tracks ----
        curated += verifiedTracks(allTopTracks.shuffled(), quota(SHARE_TOP), "TOP")

        // ---- Tier 3: anchors ----
        // Anchors are verified too. A listener may have named an artist under one genre who
        // Spotify tags differently, or the same anchor list may be reused across genres - either
        // way an unverified anchor would inject drift straight into the mix.
        val anchorTracks = mutableListOf<SpotifyTrack>()
        for (name in anchors.shuffled()) {
            if (anchorTracks.size >= quota(SHARE_ANCHORS) * 2) break
            if (name.lowercase() in blockedArtists) continue
            if (!artistMatchesGenre(name, genreLower)) {
                Log.d(TAG, "  anchor '$name' skipped - not tagged '$genre'")
                continue
            }
            anchorTracks += webApi.searchTracksByArtist(name, limit = 3, offset = remixOffset)
                .getOrDefault(emptyList())
        }
        curated += verifiedTracks(anchorTracks.shuffled(), quota(SHARE_ANCHORS), "ANCHORS")

        // ---- Tier 4: deeper catalogue from already-verified artists ----
        if (curated.size < TARGET_TRACK_COUNT) {
            val deepTracks = mutableListOf<SpotifyTrack>()
            for (artist in genreArtists.shuffled()) {
                if (deepTracks.size >= quota(SHARE_DEEP_CUTS) * 2) break
                if (artist.name.lowercase() in blockedArtists) continue
                deepTracks += webApi.searchTracksByArtist(artist.name, limit = 5, offset = remixOffset)
                    .getOrDefault(emptyList())
            }
            curated += verifiedTracks(deepTracks.shuffled(), quota(SHARE_DEEP_CUTS), "DEEP")
        }

        // ---- Tier 5: discovery ----
        if (curated.size < TARGET_TRACK_COUNT) {
            val seeds = (anchors + genreArtists.map { it.name }).distinct()
            curated += suggestedArtistTracks(
                seeds = seeds,
                genre = genre,
                genreLower = genreLower,
                moodHint = moodHint,
                limit = quota(SHARE_DISCOVERY),
                excludeUris = dislikedUris,
                excludeArtists = blockedArtists,
                songLanguage = songLanguage,
                already = curated
            )
        }

        // ---- Tier 6: fill ----
        if (curated.size < TARGET_TRACK_COUNT) {
            // Mainstream suggestions get the same verification as discovery - Gemini naming an
            // artist as "mainstream jazz" is not evidence that Spotify agrees.
            geminiClient?.suggestMainstreamArtists(genre, songLanguage = songLanguage)?.getOrNull()
                ?.let { mainstream ->
                    val mainstreamTracks = mutableListOf<SpotifyTrack>()
                    for (name in mainstream) {
                        if (curated.size + mainstreamTracks.size >= TARGET_TRACK_COUNT) break
                        if (name.lowercase() in blockedArtists) continue
                        if (!artistMatchesGenre(name, genreLower)) {
                            Log.d(TAG, "  mainstream '$name' rejected - not tagged '$genre'")
                            continue
                        }
                        mainstreamTracks += webApi.searchTracksByArtist(name, limit = 3, offset = remixOffset)
                            .getOrDefault(emptyList())
                    }
                    curated += verifiedTracks(mainstreamTracks, TARGET_TRACK_COUNT - curated.size, "MAINSTREAM")
                }

            // Genre search is itself genre-scoped, but Spotify's `genre:` filter matches loosely,
            // so results still pass through the same check as everything else.
            var offset = remixOffset
            while (curated.size < TARGET_TRACK_COUNT && offset <= MAX_SEARCH_OFFSET + remixOffset) {
                val page = webApi.searchTracksByGenre(genre, limit = SEARCH_PAGE_SIZE, offset = offset)
                    .getOrDefault(emptyList())
                if (page.isEmpty()) break
                curated += verifiedTracks(page, TARGET_TRACK_COUNT - curated.size, "GENRE-SEARCH")
                offset += page.size
            }
        }

        // ---- Relaxed-only fallback ----
        // The one place the genre rule is intentionally suspended, and only on request: in STRICT
        // mode a shorter, genre-pure playlist is the correct outcome.
        if (curated.size < TARGET_TRACK_COUNT && strictness == GenreStrictness.RELAXED) {
            val offGenre = (savedTracks + allTopTracks).filter(::allowed)
                .map { it.uri }.filterNot { it in curated }.shuffled()
            if (offGenre.isNotEmpty()) {
                Log.d(TAG, "RELAXED: adding ${minOf(offGenre.size, TARGET_TRACK_COUNT - curated.size)} off-genre personal tracks")
                curated += offGenre.take(TARGET_TRACK_COUNT - curated.size)
            }
        }

        val finalUris = curated.distinct().take(TARGET_TRACK_COUNT)
        if (finalUris.isEmpty()) {
            return Result.failure(
                IllegalStateException("No tracks found for '$genre' - try a broader genre name or switch to Relaxed mode")
            )
        }
        Log.d(TAG, "Publishing ${finalUris.size} tracks for '$genre' " +
            "(strictness=$strictness, $verificationCount artist lookups)")

        val nameByUri = (savedTracks + allTopTracks).associate { it.uri to (it.artistName to it.name) }
        lastPublishedTracks = finalUris.mapNotNull { nameByUri[it] }

        val replaceResult = webApi.replacePlaylistTracks(playlistId, finalUris)
        if (replaceResult.isFailure) {
            // The cached playlist may have been deleted outside the app; without this, every
            // future switch would fail forever against a dead playlist id.
            settings.saveSpotifyHourlyPlaylistId("")
            val newId = ensurePlaylistExists().getOrElse { return Result.failure(it) }
            webApi.replacePlaylistTracks(newId, finalUris).getOrElse { return Result.failure(it) }
            return Result.success("spotify:playlist:$newId")
        }
        return Result.success("spotify:playlist:$playlistId")
    }

    /**
     * Discovery tier: Gemini proposes artists adjacent to the listener's genre-matching
     * favourites; each is verified against Spotify's genre tags before any track is used.
     *
     * Artists with no Spotify match at all are almost certainly hallucinated names, and are
     * dropped for the same reason as genre mismatches.
     */
    private suspend fun suggestedArtistTracks(
        seeds: List<String>,
        genre: String,
        genreLower: String,
        moodHint: String,
        limit: Int,
        excludeUris: Set<String>,
        excludeArtists: Set<String>,
        songLanguage: String?,
        already: List<String>
    ): List<String> {
        val gemini = geminiClient ?: return emptyList()
        if (seeds.isEmpty()) return emptyList()

        val suggestions = gemini.suggestSimilarArtists(
            seedArtists = seeds,
            genre = genre,
            songLanguage = songLanguage,
            moodHint = moodHint
        ).getOrElse {
            Log.w(TAG, "Artist suggestion failed; skipping discovery tier", it)
            return emptyList()
        }

        val candidates = suggestions.filterNot { it.lowercase() in excludeArtists }
        if (candidates.isEmpty()) return emptyList()
        Log.d(TAG, "Gemini suggested: ${candidates.joinToString(", ")}")

        val uris = mutableListOf<String>()
        var verified = 0
        var rejected = 0

        for (name in candidates) {
            if (uris.size >= limit) break
            if (!artistMatchesGenre(name, genreLower)) {
                Log.d(TAG, "  rejected '$name' - no Spotify match or wrong genre tags")
                rejected++
                continue
            }
            verified++
            // limit=2, no offset: an artist's first search results are their best-known tracks, so
            // discovery stays recognisable rather than pulling deep cuts by unfamiliar acts.
            uris += webApi.searchTracksByArtist(name, limit = 2)
                .getOrDefault(emptyList())
                .filter {
                    it.uri !in excludeUris && it.uri !in already &&
                        it.artistName.lowercase() !in excludeArtists
                }
                .map { it.uri }
        }

        Log.d(TAG, "Tier DISCOVERY: $verified accepted, $rejected rejected on genre tags")
        return uris.take(limit)
    }

    private suspend fun ensurePlaylistExists(): Result<String> {
        val existing = settings.snapshotSpotifyHourlyPlaylistId()
        if (existing.isNotBlank()) return Result.success(existing)

        // POST /me/playlists infers the user from the token, so no user-id lookup is needed.
        val playlistId = webApi.createPlaylist(PLAYLIST_NAME, PLAYLIST_DESCRIPTION)
            .getOrElse { return Result.failure(it) }
        settings.saveSpotifyHourlyPlaylistId(playlistId)
        return Result.success(playlistId)
    }
}
