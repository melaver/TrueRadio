package com.trueradio.app.spotify

import com.trueradio.app.SecureSettings

/**
 * Builds the track list for "this hour's genre" out of the user's own Spotify taste data, then
 * writes it into a single reusable private playlist that [SpotifyManager] plays via App Remote.
 *
 * Approach (see [SpotifyWebApiClient] for why this doesn't use `/recommendations`):
 *  1. Pull the user's top artists + top tracks (this *is* "their algorithm" - it's the same
 *     taste signal Spotify's own Discover/Radio features are built from).
 *  2. Keep the top artists/tracks whose genre tags match this hour's target genre - these go
 *     first in the mix, since they're both genre-correct and personally proven.
 *  3. If that's thin (a genre the user doesn't currently listen to much), widen the pool via
 *     artist search for the genre and pull each match's top tracks.
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

        // Tier 2: widen via genre-tagged artist search when the personalized pool is thin.
        if (personalizedUris.size < TARGET_TRACK_COUNT) {
            val discoveredArtists = webApi.searchArtistsByGenre(genre).getOrDefault(emptyList())
            val discoveredUris = mutableListOf<String>()
            for (artist in discoveredArtists) {
                if (personalizedUris.size + discoveredUris.size >= TARGET_TRACK_COUNT) break
                val artistTracks = webApi.getArtistTopTracks(artist.id).getOrDefault(emptyList())
                discoveredUris += artistTracks.map { it.uri }
            }
            personalizedUris += discoveredUris.shuffled()
        }

        val finalUris = personalizedUris.distinct().take(TARGET_TRACK_COUNT)
        if (finalUris.isEmpty()) {
            return Result.failure(IllegalStateException("Could not find any tracks for genre '$genre' - try a broader genre name"))
        }

        webApi.replacePlaylistTracks(playlistId, finalUris).getOrElse { return Result.failure(it) }
        return Result.success("spotify:playlist:$playlistId")
    }

    private suspend fun ensurePlaylistExists(): Result<String> {
        val existing = settings.snapshotSpotifyHourlyPlaylistId()
        if (existing.isNotBlank()) return Result.success(existing)

        val userId = webApi.getCurrentUserId().getOrElse { return Result.failure(it) }
        val playlistId = webApi.createPlaylist(userId, PLAYLIST_NAME, PLAYLIST_DESCRIPTION)
            .getOrElse { return Result.failure(it) }
        settings.saveSpotifyHourlyPlaylistId(playlistId)
        return Result.success(playlistId)
    }
}
