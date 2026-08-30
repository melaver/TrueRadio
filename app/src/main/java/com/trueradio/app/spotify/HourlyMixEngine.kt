package com.trueradio.app.spotify

import android.util.Log
import com.trueradio.app.SecureSettings
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
    companion object {
        private const val TAG = "HourlyMixEngine"
        private const val PLAYLIST_NAME = "TrueRadio Hourly Mix"
        private const val PLAYLIST_DESCRIPTION = "Auto-updated every hour by TrueRadio - do not add manual tracks, they'll be replaced."
        private const val TARGET_TRACK_COUNT = 30
        private const val SEARCH_PAGE_SIZE = 10 // Spotify's Feb 2026 search `limit` cap

        // Target share of the mix per tier. Familiar material dominates so the hour feels like
        // *your* station, with a deliberate slice reserved for discovery so it isn't just a
        // rotation of things you've already heard to death.
        private const val SHARE_SAVED = 0.35        // your Liked Songs
        private const val SHARE_TOP = 0.30          // your most-played
        private const val SHARE_DEEP_CUTS = 0.15    // more from artists you already love
        private const val SHARE_DISCOVERY = 0.20    // Gemini-suggested adjacent artists
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
    suspend fun buildAndPublishMixForGenre(genre: String): Result<String> {
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
        val genreArtists = allTopArtists.filter { a -> a.genres.any { it.lowercase().contains(genreLower) } }
        val genreArtistNames = genreArtists.map { it.name }.toSet()
        fun matchesGenre(track: SpotifyTrack) =
            genreArtistNames.any { it.equals(track.artistName, ignoreCase = true) }

        val quota = { share: Double -> (TARGET_TRACK_COUNT * share).toInt().coerceAtLeast(1) }

        // --- Tier 1: saved tracks in this genre
        val savedTier = savedTracks.filter(::matchesGenre).map { it.uri }.shuffled().take(quota(SHARE_SAVED))
        Log.d(TAG, "Tier SAVED: ${savedTier.size}")

        // --- Tier 2: most-played tracks in this genre
        val topTier = allTopTracks.filter(::matchesGenre).map { it.uri }
            .filterNot { it in savedTier }.shuffled().take(quota(SHARE_TOP))
        Log.d(TAG, "Tier TOP: ${topTier.size}")

        // --- Tier 3: deeper catalog from artists already established as favourites
        val deepTier = mutableListOf<String>()
        for (artist in genreArtists.shuffled()) {
            if (deepTier.size >= quota(SHARE_DEEP_CUTS)) break
            deepTier += webApi.searchTracksByArtist(artist.name, limit = 5)
                .getOrDefault(emptyList()).map { it.uri }
        }
        Log.d(TAG, "Tier DEEP: ${deepTier.size}")

        // --- Tier 4: discovery via Gemini-suggested adjacent artists
        val discoveryTier = suggestedArtistTracks(
            seeds = genreArtists.map { it.name }.ifEmpty { allTopArtists.map { it.name } },
            genre = genre,
            limit = quota(SHARE_DISCOVERY)
        )
        Log.d(TAG, "Tier DISCOVERY: ${discoveryTier.size}")

        val curated = (savedTier + topTier + deepTier.shuffled() + discoveryTier).distinct().toMutableList()

        // --- Tier 5: genre-search fill, only if the personal tiers came up short
        if (curated.size < TARGET_TRACK_COUNT) {
            var offset = 0
            while (curated.size < TARGET_TRACK_COUNT) {
                val page = webApi.searchTracksByGenre(genre, limit = SEARCH_PAGE_SIZE, offset = offset)
                    .getOrDefault(emptyList())
                if (page.isEmpty()) break
                curated += page.map { it.uri }.filterNot { it in curated }
                offset += page.size
            }
            Log.d(TAG, "After genre fill: ${curated.size}")
        }

        val finalUris = curated.distinct().take(TARGET_TRACK_COUNT)
        if (finalUris.isEmpty()) {
            return Result.failure(IllegalStateException("Could not find any tracks for genre '$genre' - try a broader genre name"))
        }
        Log.d(TAG, "Publishing ${finalUris.size} tracks for genre '$genre'")

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
    private suspend fun suggestedArtistTracks(seeds: List<String>, genre: String, limit: Int): List<String> {
        val gemini = geminiClient ?: return emptyList()
        if (seeds.isEmpty()) return emptyList()

        val suggestions = gemini.suggestSimilarArtists(seeds, genre).getOrElse {
            Log.w(TAG, "Artist suggestion failed; skipping discovery tier", it)
            return emptyList()
        }
        if (suggestions.isEmpty()) return emptyList()
        Log.d(TAG, "Gemini suggested: ${suggestions.joinToString(", ")}")

        val uris = mutableListOf<String>()
        for (name in suggestions) {
            if (uris.size >= limit) break
            // 2 tracks per suggested artist keeps discovery varied across several new artists
            // rather than dropping a block of one unfamiliar act into the hour.
            uris += webApi.searchTracksByArtist(name, limit = 2).getOrDefault(emptyList()).map { it.uri }
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
