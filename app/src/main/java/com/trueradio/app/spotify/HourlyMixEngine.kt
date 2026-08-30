package com.trueradio.app.spotify

import android.util.Log
import com.trueradio.app.SecureSettings
import com.trueradio.app.SongLanguage
import com.trueradio.app.ai.GeminiClient

/**
 * Builds the track list for "this hour's genre" out of the user's own Spotify taste data, then
 * writes it into a single reusable private playlist that [SpotifyManager] plays via App Remote.
 *
 * Approach (see [SpotifyWebApiClient] for why this doesn't use `/recommendations`, and for the
 * February 2026 Development Mode endpoint changes this class was updated to follow):
 *  1. Pull the user's top artists + top tracks (this *is* "their algorithm" - it's the same
 *     taste signal Spotify's own Discover/Radio features are built from).
 *  2. **Tier 1:** the user's own top tracks whose artist matches this hour's target genre - both
 *     genre-correct and already-known-to-them by definition.
 *  3. **Tier 2:** if that's thin, pull *more* tracks from those same genre-matching top artists
 *     via direct catalog search (not just whatever happened to land in their top-tracks
 *     snapshot) - still artists the user already listens to, so still likely recognizable, just
 *     deeper into each artist's catalog. This is the main lever for "more known songs": Spotify
 *     removed the `popularity` field from track/artist objects in the Feb 2026 changes, so there's
 *     no numeric popularity score left to sort candidates by - leaning harder on the user's own
 *     established artists (rather than genre-wide search across strangers) is the best available
 *     substitute signal for "will this be recognizable" without that field.
 *  4. **Tier 3, last resort:** widen to genre-tagged search across *any* artist, which can surface
 *     completely unfamiliar artists - only used if tiers 1-2 still haven't filled the mix.
 *  5. Shuffle within (not across) tiers so personalized/known picks still lead, write the result
 *     into the hourly playlist, and hand the playlist URI back to the caller to play.
 */
class HourlyMixEngine(
    private val webApi: SpotifyWebApiClient,
    private val settings: SecureSettings,
    /**
     * Optional: used only to suggest similar artists. Null disables the discovery tier and the
     * mix falls back to genre search for unfamiliar material - see [suggestedArtistTracks].
     */
    private val geminiClient: GeminiClient? = null
) {
    /**
     * Ordered artist/title pairs of the most recently published mix. App Remote exposes no queue
     * API, so this is the only way the service can know what's coming up in order to pre-generate
     * trivia for it.
     */
    @Volatile
    var lastPublishedTracks: List<Pair<String, String>> = emptyList()
        private set

    companion object {
        private const val TAG = "HourlyMixEngine"
        private const val PLAYLIST_NAME = "TrueRadio Hourly Mix"
        private const val PLAYLIST_DESCRIPTION = "Auto-updated every hour by TrueRadio - do not add manual tracks, they'll be replaced."
        private const val TARGET_TRACK_COUNT = 30
        private const val SEARCH_PAGE_SIZE = 10 // Spotify's Feb 2026 search `limit` cap

        /**
         * Hard cap on how deep genre search paginates. Search results are relevance-ranked, so
         * page 1 holds the recognisable artists and each further page is progressively more
         * obscure. The fill loop previously paged without limit (offset 10, 20, 30...), which -
         * with the Feb 2026 cap dropping search `limit` from 50 to 10 - routinely reached page 3+
         * and was the main reason mixes filled up with anonymous artists. Staying shallow keeps
         * the fill material mainstream; if that can't fill the hour, a shorter playlist of decent
         * tracks beats a full one padded with obscurities.
         */
        private const val MAX_SEARCH_OFFSET = 20

        /** How far each successive remix shifts into the candidate pool. */
        private const val REMIX_OFFSET_STEP = 5

        // Target share of the mix per tier. Familiar material dominates so the hour feels like
        // *your* station, with a deliberate slice reserved for discovery so it isn't just a
        // rotation of things you've already heard to death.
        private const val SHARE_SAVED = 0.35        // your Liked Songs
        private const val SHARE_TOP = 0.30          // your most-played
        private const val SHARE_DEEP_CUTS = 0.15    // more from artists you already love
        private const val SHARE_DISCOVERY = 0.20    // Gemini-suggested adjacent artists

        /**
         * Cap on how much of the mix the user's own anchor artists may occupy DIRECTLY. They are
         * seeds that define a vibe, not a whitelist - letting them fill the hour would turn a
         * radio station into a 5-artist loop, which is exactly what the feature is meant to
         * avoid. Their real influence is much larger than this number suggests, because they also
         * drive the similarity expansion that feeds the discovery tier.
         */
        private const val SHARE_ANCHORS = 0.20
    }

    /**
     * Builds and publishes the mix for [genre], returning the playlist's Spotify URI to play.
     *
     * Curation model - five signals, strongest first. Spotify removed both `/recommendations`
     * and `/artists/{id}/related-artists` in Nov 2024, so there is no similarity API to lean on;
     * everything below is assembled from endpoints that still exist, plus Gemini standing in as
     * the "who else would they like" engine.
     *
     *  1. SAVED    - tracks from your Liked Songs whose artist matches the genre. Deliberately
     *                saved, so the strongest signal available.
     *  2. TOP      - your most-played tracks across short/medium/long term, so both current
     *                obsessions and long-standing favourites are represented.
     *  3. DEEP CUT - other catalog tracks by artists you already listen to, so a familiar artist
     *                can show up with a song you haven't worn out.
     *  4. DISCOVERY- tracks by artists Gemini suggests based on your actual top artists, resolved
     *                through Spotify search so nothing hallucinated can reach the playlist.
     *  5. FILL     - plain genre search, only if the tiers above can't fill the hour.
     *
     * Tiers are shuffled internally but concatenated in order, so familiar material leads.
     */
    /**
     * @param variation bumped on each manual remix. Tier shuffles alone would re-order the *same*
     * candidate pool, so a remix would return largely the same 30 tracks in a new order. Varying
     * the search offset actually reaches different material, which is what "change things up"
     * needs to mean. Kept small (see REMIX_OFFSET_STEP) so remixes don't drift into obscurity -
     * the same relevance-ranking problem documented on MAX_SEARCH_OFFSET applies here.
     */
    suspend fun buildAndPublishMixForGenre(genre: String, variation: Int = 0): Result<String> {
        val remixOffset = (variation * REMIX_OFFSET_STEP) % (MAX_SEARCH_OFFSET + REMIX_OFFSET_STEP)
        if (variation > 0) Log.d(TAG, "Remix #$variation for '$genre' (search offset $remixOffset)")
        val playlistId = ensurePlaylistExists().getOrElse { return Result.failure(it) }
        val genreLower = genre.lowercase()

        // Top artists across all three windows: a long_term favourite you haven't played lately
        // is still a favourite, and short_term catches what you're into right now.
        val allTopArtists = listOf("short_term", "medium_term", "long_term")
            .flatMap { range -> webApi.getTopArtists(timeRange = range).getOrDefault(emptyList()) }
            .distinctBy { it.id }

        val allTopTracks = listOf("short_term", "medium_term", "long_term")
            .flatMap { range -> webApi.getTopTracks(timeRange = range).getOrDefault(emptyList()) }
            .distinctBy { it.uri }

        val savedTracks = webApi.getSavedTracks().getOrDefault(emptyList())

        // Explicit like/dislike feedback outranks every inferred signal below: the user pressed a
        // button, which is far less ambiguous than "played this a lot" (which can just mean it
        // was on a playlist they left running).
        // Genre-specific anchor artists the user named for this genre (Settings > Favourite
        // artists per genre). Primary steering signal for what this hour should sound like.
        // Language preference steers artist selection only - see SongLanguage for why it can't
        // be a hard filter (Spotify exposes no language field on tracks or artists).
        val songLanguage = SongLanguage.promptClause(settings.snapshotSongLanguages())
        if (songLanguage != null) Log.d(TAG, "Song language preference: $songLanguage")

        // Global liked artists + any named specifically for this genre.
        val anchors = settings.snapshotGenreAnchors().seedsFor(genre)
        if (anchors.isNotEmpty()) Log.d(TAG, "Anchors for '$genre': ${anchors.joinToString(", ")}")

        val feedback = settings.snapshotTrackFeedback()
        val blockedArtists = feedback.blockedArtists()
        val dislikedUris = feedback.dislikedUris
        Log.d(TAG, "Feedback: ${feedback.liked.size} liked, ${feedback.disliked.size} disliked, ${blockedArtists.size} artists blocked")

        if (allTopArtists.isEmpty() && allTopTracks.isEmpty() && savedTracks.isEmpty()) {
            // Everything personal came back empty - almost always an auth problem (or a brand-new
            // account with no history). Surface it rather than silently shipping a generic mix.
            Log.e(TAG, "No personal listening data available at all")
            return Result.failure(
                IllegalStateException("Couldn't read your Spotify listening history - try reconnecting Spotify in Settings")
            )
        }

        // Genre matching is via artist tags: Spotify tags artists, not tracks, and removed the
        // per-track `popularity` field, so an artist's tags are the only genre signal left.
        /**
         * Genre tag matching, deliberately loose.
         *
         * Spotify's artist tags are hyper-specific ("permanent wave", "float house", "escape
         * room"), so a strict `tag.contains("rock")` test matched almost nothing - which left the
         * personal tiers empty and silently pushed the whole mix onto generic search. That is why
         * the playlist stopped feeling like it came from the user's account.
         *
         * Now matches if either string contains the other, or if they share any word - so "rock"
         * matches "permanent wave"? No, but it does match "art rock", "indie rock", "rock and
         * roll", which is the realistic case. Word-level overlap also catches "hip hop" vs
         * "conscious hip hop".
         */
        fun tagMatches(tag: String): Boolean {
            val t = tag.lowercase()
            if (t.contains(genreLower) || genreLower.contains(t)) return true
            val genreWords = genreLower.split(" ", "-", "&").filter { it.length > 2 }.toSet()
            val tagWords = t.split(" ", "-", "&").filter { it.length > 2 }.toSet()
            return genreWords.isNotEmpty() && genreWords.intersect(tagWords).isNotEmpty()
        }

        val genreArtists = allTopArtists.filter { a -> a.genres.any { tagMatches(it) } }
        val genreArtistNames = genreArtists.map { it.name }.toSet()
        fun matchesGenre(track: SpotifyTrack) =
            genreArtistNames.any { it.equals(track.artistName, ignoreCase = true) }

        // All artists the user actually listens to, regardless of tag - used as the fallback
        // below when genre matching still comes up short.
        val allTopArtistNames = allTopArtists.map { it.name }.toSet()
        fun isPersonalArtist(track: SpotifyTrack) =
            allTopArtistNames.any { it.equals(track.artistName, ignoreCase = true) }

        Log.d(TAG, "Genre '$genre' matched ${genreArtists.size}/${allTopArtists.size} of your top artists")

        val quota = { share: Double -> (TARGET_TRACK_COUNT * share).toInt().coerceAtLeast(1) }

        /**
         * Applied to every tier: drops individually disliked tracks and anything by an artist
         * blocked after repeated dislikes. Filtering centrally here (rather than per tier) means
         * a disliked track can't sneak back in through the discovery or genre-fill paths.
         */
        fun allowed(track: SpotifyTrack): Boolean =
            track.uri !in dislikedUris && track.artistName.lowercase() !in blockedArtists

        // --- Tier 1: saved tracks in this genre
        val savedTier = savedTracks.filter { matchesGenre(it) && allowed(it) }
            .map { it.uri }.shuffled().take(quota(SHARE_SAVED))
        Log.d(TAG, "Tier SAVED: ${savedTier.size}")

        // --- Tier 2: most-played tracks in this genre
        val topTier = allTopTracks.filter { matchesGenre(it) && allowed(it) }.map { it.uri }
            .filterNot { it in savedTier }.shuffled().take(quota(SHARE_TOP))
        Log.d(TAG, "Tier TOP: ${topTier.size}")

        // --- Tier 3: deeper catalog from artists already established as favourites
        val deepTier = mutableListOf<String>()
        // Artists the user explicitly liked go first, then the rest of their genre-matching top
        // artists - so a thumbs-up directly increases how often that artist reappears.
        val likedFirst = genreArtists.sortedByDescending { a ->
            if (feedback.favouriteArtists().any { it.equals(a.name, ignoreCase = true) }) 1 else 0
        }
        for (artist in likedFirst) {
            if (artist.name.lowercase() in blockedArtists) continue
            if (deepTier.size >= quota(SHARE_DEEP_CUTS)) break
            deepTier += webApi.searchTracksByArtist(artist.name, limit = 5, offset = remixOffset)
                .getOrDefault(emptyList()).filter(::allowed).map { it.uri }
        }
        Log.d(TAG, "Tier DEEP: ${deepTier.size}")

        // --- Tier 3b: a bounded slice of the anchor artists themselves, so the vibe the user
        // described is audibly present without dominating (see SHARE_ANCHORS).
        val anchorTier = mutableListOf<String>()
        for (name in anchors.shuffled()) {
            if (anchorTier.size >= quota(SHARE_ANCHORS)) break
            if (name.lowercase() in blockedArtists) continue
            anchorTier += webApi.searchTracksByArtist(name, limit = 3, offset = remixOffset)
                .getOrDefault(emptyList()).filter(::allowed).map { it.uri }
        }
        Log.d(TAG, "Tier ANCHORS: ${anchorTier.size}")

        // --- Tier 4: discovery via Gemini-suggested adjacent artists
        // Seed discovery with explicitly-liked artists first: "find me more like the things I
        // gave a thumbs-up" is a better prompt than "more like whatever I happened to play".
        // Seed order matters - it's the priority the similarity model sees. Anchors first
        // (explicitly chosen FOR this genre), then thumbs-up artists, then inferred top artists.
        // This is where anchors do most of their work: they define the neighbourhood the mix
        // explores, which is how the playlist stays varied while still sounding like the user's
        // taste rather than being limited to the anchors themselves.
        val discoverySeeds = (anchors +
            feedback.favouriteArtists() +
            genreArtists.map { it.name }.ifEmpty { allTopArtists.map { it.name } }).distinct()
        val discoveryTier = suggestedArtistTracks(
            seeds = discoverySeeds,
            genre = genre,
            // With anchors present the similarity expansion is far better targeted, so it earns
            // a larger share of the hour than it would from inferred seeds alone.
            limit = if (anchors.isNotEmpty()) quota(SHARE_DISCOVERY * 1.5) else quota(SHARE_DISCOVERY),
            excludeUris = dislikedUris,
            excludeArtists = blockedArtists,
            songLanguage = songLanguage
        )
        Log.d(TAG, "Tier DISCOVERY: ${discoveryTier.size}")

        val curated = (savedTier + topTier + anchorTier.shuffled() + deepTier.shuffled() + discoveryTier)
            .distinct().toMutableList()

        // Personal fallback, BEFORE any generic search. If genre matching left the mix thin (a
        // genre whose Spotify tags don't line up with this user's artists), fill from their own
        // saved and top tracks regardless of tag. A track the user actually listens to is a far
        // better outcome than a tag-correct track by a stranger - the point of linking the
        // account is that the music feels like theirs.
        if (curated.size < TARGET_TRACK_COUNT) {
            val personalFallback = (savedTracks + allTopTracks)
                .filter { allowed(it) && (isPersonalArtist(it) || it in savedTracks) }
                .map { it.uri }
                .filterNot { it in curated }
                .shuffled()
            if (personalFallback.isNotEmpty()) {
                Log.d(TAG, "Personal fallback contributing ${minOf(personalFallback.size, TARGET_TRACK_COUNT - curated.size)} tracks")
                curated += personalFallback.take(TARGET_TRACK_COUNT - curated.size)
            }
        }

        // --- Tier 5: genre-search fill, only if the personal tiers came up short
        if (curated.size < TARGET_TRACK_COUNT) {
            // Tier 5a: mainstream artists named by Gemini, as a stand-in for the popularity
            // filter Spotify no longer offers (see suggestMainstreamArtists).
            geminiClient?.suggestMainstreamArtists(genre, songLanguage = songLanguage)?.getOrNull()?.let { mainstream ->
                Log.d(TAG, "Mainstream fill artists: ${mainstream.joinToString(", ")}")
                for (name in mainstream) {
                    if (curated.size >= TARGET_TRACK_COUNT) break
                    if (name.lowercase() in blockedArtists) continue
                    curated += webApi.searchTracksByArtist(name, limit = 3)
                        .getOrDefault(emptyList()).filter(::allowed).map { it.uri }
                        .filterNot { it in curated }
                }
            }

            // Tier 5b: plain genre search, deliberately shallow (see MAX_SEARCH_OFFSET).
            var offset = remixOffset
            while (curated.size < TARGET_TRACK_COUNT && offset <= MAX_SEARCH_OFFSET + remixOffset) {
                val page = webApi.searchTracksByGenre(genre, limit = SEARCH_PAGE_SIZE, offset = offset)
                    .getOrDefault(emptyList())
                if (page.isEmpty()) break
                curated += page.filter(::allowed).map { it.uri }.filterNot { it in curated }
                offset += page.size
            }
            Log.d(TAG, "After genre fill: ${curated.size}")
        }

        // Lookup so the published URIs can be reported back with artist/title for trivia
        // pre-generation. Built from every track object seen while assembling the mix.
        val nameByUri = (savedTracks + allTopTracks).associate { it.uri to (it.artistName to it.name) }

        val finalUris = curated.distinct().take(TARGET_TRACK_COUNT)
        if (finalUris.isEmpty()) {
            return Result.failure(IllegalStateException("Could not find any tracks for genre '$genre' - try a broader genre name"))
        }
        Log.d(TAG, "Publishing ${finalUris.size} tracks for genre '$genre'")
        // Only tracks whose names we captured can be pre-generated for; search-sourced URIs
        // simply fall back to live generation at their boundary.
        lastPublishedTracks = finalUris.mapNotNull { nameByUri[it] }

        val replaceResult = webApi.replacePlaylistTracks(playlistId, finalUris)
        if (replaceResult.isFailure) {
            // The cached playlist may have been deleted or unfollowed outside the app; without
            // this, every future hourly switch would fail forever against a dead playlist id.
            settings.saveSpotifyHourlyPlaylistId("")
            val newPlaylistId = ensurePlaylistExists().getOrElse { return Result.failure(it) }
            webApi.replacePlaylistTracks(newPlaylistId, finalUris).getOrElse { return Result.failure(it) }
            return Result.success("spotify:playlist:$newPlaylistId")
        }
        return Result.success("spotify:playlist:$playlistId")
    }

    /**
     * Asks Gemini which artists this listener would likely enjoy, then resolves each name to real
     * tracks via Spotify search. The search step is what makes this safe: a suggestion that
     * doesn't correspond to a real artist simply returns nothing and is skipped, so a
     * hallucinated name can never end up in the playlist.
     */
    private suspend fun suggestedArtistTracks(
        seeds: List<String>,
        genre: String,
        limit: Int,
        excludeUris: Set<String> = emptySet(),
        excludeArtists: Set<String> = emptySet(),
        songLanguage: String? = null
    ): List<String> {
        val gemini = geminiClient ?: return emptyList()
        if (seeds.isEmpty()) return emptyList()

        val suggestions = gemini.suggestSimilarArtists(seeds, genre, songLanguage = songLanguage).getOrElse {
            Log.w(TAG, "Artist suggestion failed; skipping discovery tier", it)
            return emptyList()
        }
        val filtered = suggestions.filterNot { it.lowercase() in excludeArtists }
        if (filtered.isEmpty()) return emptyList()
        Log.d(TAG, "Gemini suggested: ${filtered.joinToString(", ")}")

        val uris = mutableListOf<String>()
        for (name in filtered) {
            if (uris.size >= limit) break
            // 2 tracks per suggested artist keeps discovery varied across several new artists
            // rather than dropping a block of one unfamiliar act into the hour.
            // limit=2 and no offset: the first search results for an artist name are their
            // best-known tracks, so this stays recognisable rather than pulling deep cuts from
            // an artist the listener has never heard of.
            uris += webApi.searchTracksByArtist(name, limit = 2)
                .getOrDefault(emptyList())
                .filter { it.uri !in excludeUris && it.artistName.lowercase() !in excludeArtists }
                .map { it.uri }
        }
        return uris.take(limit)
    }

    private suspend fun ensurePlaylistExists(): Result<String> {
        val existing = settings.snapshotSpotifyHourlyPlaylistId()
        if (existing.isNotBlank()) return Result.success(existing)

        // POST /me/playlists (current, post-Feb-2026 endpoint) infers the user from the access
        // token, so no separate "get current user id" lookup is needed first.
        val playlistId = webApi.createPlaylist(PLAYLIST_NAME, PLAYLIST_DESCRIPTION)
            .getOrElse { return Result.failure(it) }
        settings.saveSpotifyHourlyPlaylistId(playlistId)
        return Result.success(playlistId)
    }
}
