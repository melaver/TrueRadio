package com.trueradio.app.spotify

import com.trueradio.app.SecureSettings

/**
 * Builds the track list for "this hour's genre" out of the user's own Spotify taste data, then
 * writes it into a single reusable private playlist that [SpotifyManager] plays via App Remote.
 *
 * Approach (see [SpotifyWebApiClient] for why this doesn't use `/recommendations`, and for the
 * February 2026 Development Mode endpoint changes this class was updated to follow):
 *  1. Pull the user's top artists + top tracks (this *is* "their algorithm" - it's the same
 *     taste signal Spotify's own Discover/Radio features are built from).
 *  2. Keep the top artists/tracks whose genre tags match this hour's target genre - these go
 *     first in the mix, since they're both genre-correct and personally proven.
 *  3. If that's thin (a genre the user doesn't currently listen to much), widen the pool by
 *     searching for tracks tagged with the genre directly, paginating via `offset` since search
 *     results are capped at 10 per call.
 *  4. Shuffle within (not across) those two tiers so personalized picks still lead, write the
 *     result into the hourly playlist, and hand the playlist URI back to the caller to play.
 */
class HourlyMixEngine(
    private val webApi: SpotifyWebApiClient,
    private val settings: SecureSettings
) {
    companion object {
        private const val PLAYLIST_NAME = "TrueRadio Hourly Mix"
        private const val PLAYLIST_DESCRIPTION = "Auto-updated every hour by TrueRadio - do not add manual tracks, they'll be replaced."
        private const val TARGET_TRACK_COUNT = 30
        private const val SEARCH_PAGE_SIZE = 10 // Spotify's Feb 2026 search `limit` cap
    }

    /**
     * Builds and publishes the mix for [genre], returning the playlist's Spotify URI to play.
     * Reuses the same playlist across hours (creating it once) so App Remote just re-plays the
     * same context with new contents rather than switching between many playlist objects.
     */
    suspend fun buildAndPublishMixForGenre(genre: String): Result<String> {
        val playlistId = ensurePlaylistExists().getOrElse { return Result.failure(it) }

        val topArtistsResult = webApi.getTopArtists()
        val topTracksResult = webApi.getTopTracks()

        // If both basic personalization calls failed outright (as opposed to just returning
        // empty lists), it's almost certainly an auth/network problem affecting the whole
        // session - surface that real error rather than masking it behind a generic "no tracks
        // found for this genre" message further down, which would send the user chasing the
        // wrong problem (e.g. trying broader genre names when the actual issue is an expired
        // Spotify connection).
        if (topArtistsResult.isFailure && topTracksResult.isFailure) {
            return Result.failure(
                topArtistsResult.exceptionOrNull() ?: topTracksResult.exceptionOrNull()
                ?: IllegalStateException("Failed to read Spotify listening history")
            )
        }

        val topArtists = topArtistsResult.getOrDefault(emptyList())
        val topTracks = topTracksResult.getOrDefault(emptyList())

        val genreLower = genre.lowercase()
        val matchingTopArtistIds = topArtists.filter { artist ->
            artist.genres.any { it.lowercase().contains(genreLower) }
        }.map { it.id }.toSet()

        // Tier 1: the user's own top tracks whose (top-track) artist is in the genre-matching set.
        // We don't have per-track genre tags directly, so we approximate via the track's artist name
        // matching a genre-tagged top artist - imperfect but keeps this within available endpoints.
        val personalizedTracks = topTracks.filter { track ->
            topArtists.any { it.id in matchingTopArtistIds && it.name.equals(track.artistName, ignoreCase = true) }
        }

        val personalizedUris = personalizedTracks.map { it.uri }.shuffled().toMutableList()

        // Tier 2: widen via direct genre-tagged track search when the personalized pool is thin.
        // Paginated via offset since each call returns at most SEARCH_PAGE_SIZE results.
        if (personalizedUris.size < TARGET_TRACK_COUNT) {
            val discoveredUris = mutableListOf<String>()
            var offset = 0
            while (personalizedUris.size + discoveredUris.size < TARGET_TRACK_COUNT) {
                val page = webApi.searchTracksByGenre(genre, limit = SEARCH_PAGE_SIZE, offset = offset)
                    .getOrDefault(emptyList())
                if (page.isEmpty()) break // no more results available for this genre
                discoveredUris += page.map { it.uri }
                offset += page.size
            }
            personalizedUris += discoveredUris.shuffled()
        }

        val finalUris = personalizedUris.distinct().take(TARGET_TRACK_COUNT)
        if (finalUris.isEmpty()) {
            return Result.failure(IllegalStateException("Could not find any tracks for genre '$genre' - try a broader genre name"))
        }

        val replaceResult = webApi.replacePlaylistTracks(playlistId, finalUris)
        if (replaceResult.isFailure) {
            // The cached playlist may have been deleted or unfollowed by the user outside the
            // app - without this fallback, every future hourly switch would fail forever against
            // a dead playlist ID with no way to recover short of clearing app data. Drop the
            // cached id and create a fresh playlist once before giving up.
            settings.saveSpotifyHourlyPlaylistId("")
            val newPlaylistId = ensurePlaylistExists().getOrElse { return Result.failure(it) }
            webApi.replacePlaylistTracks(newPlaylistId, finalUris).getOrElse { return Result.failure(it) }
            return Result.success("spotify:playlist:$newPlaylistId")
        }

        return Result.success("spotify:playlist:$playlistId")
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
