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
    private val settings: SecureSettings
) {
    companion object {
        private const val PLAYLIST_NAME = "TrueRadio Hourly Mix"
        private const val PLAYLIST_DESCRIPTION = "Auto-updated every hour by TrueRadio - do not add manual tracks, they'll be replaced."
        private const val TARGET_TRACK_COUNT = 30
        private const val SEARCH_PAGE_SIZE = 10 // Spotify's Feb 2026 search `limit` cap
        private const val MAX_TRACKS_PER_ARTIST = 10 // cap so Tier 2 doesn't exhaust the mix on one artist
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
        val matchingTopArtists = topArtists.filter { artist ->
            artist.genres.any { it.lowercase().contains(genreLower) }
        }
        val matchingTopArtistIds = matchingTopArtists.map { it.id }.toSet()

        // Tier 1: the user's own top tracks whose (top-track) artist is in the genre-matching set.
        // We don't have per-track genre tags directly, so we approximate via the track's artist name
        // matching a genre-tagged top artist - imperfect but keeps this within available endpoints.
        val personalizedTracks = topTracks.filter { track ->
            topArtists.any { it.id in matchingTopArtistIds && it.name.equals(track.artistName, ignoreCase = true) }
        }

        val allUris = personalizedTracks.map { it.uri }.shuffled().toMutableList()

        // Tier 2: more tracks from those SAME already-known artists, via direct catalog search -
        // see the class doc comment for why this (not genre-wide search) is the main lever for
        // "more known songs" now that popularity scores aren't available to sort by.
        if (allUris.size < TARGET_TRACK_COUNT && matchingTopArtists.isNotEmpty()) {
            val knownArtistUris = mutableListOf<String>()
            for (artist in matchingTopArtists.shuffled()) {
                if (allUris.size + knownArtistUris.size >= TARGET_TRACK_COUNT) break
                val artistTracks = webApi.searchTracksByArtist(artist.name, limit = MAX_TRACKS_PER_ARTIST)
                    .getOrDefault(emptyList())
                knownArtistUris += artistTracks.map { it.uri }
            }
            allUris += knownArtistUris.shuffled()
        }

        // Tier 3, last resort: genre-wide search across any artist, paginated via offset since
        // each call returns at most SEARCH_PAGE_SIZE results. Only reached if the user doesn't
        // yet have enough of their own genre-matching listening history for tiers 1-2 to fill
        // the mix (e.g. a genre they're just getting into).
        if (allUris.size < TARGET_TRACK_COUNT) {
            val discoveredUris = mutableListOf<String>()
            var offset = 0
            while (allUris.size + discoveredUris.size < TARGET_TRACK_COUNT) {
                val page = webApi.searchTracksByGenre(genre, limit = SEARCH_PAGE_SIZE, offset = offset)
                    .getOrDefault(emptyList())
                if (page.isEmpty()) break // no more results available for this genre
                discoveredUris += page.map { it.uri }
                offset += page.size
            }
            allUris += discoveredUris.shuffled()
        }

        val finalUris = allUris.distinct().take(TARGET_TRACK_COUNT)
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
