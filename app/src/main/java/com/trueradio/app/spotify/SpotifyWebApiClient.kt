package com.trueradio.app.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SpotifyArtist(val id: String, val name: String, val genres: List<String>)
data class SpotifyTrack(val uri: String, val name: String, val artistName: String)

/**
 * Thin client for the parts of the Spotify Web API this app needs to build a personalized,
 * genre-targeted hourly playlist.
 *
 * IMPORTANT: this deliberately does NOT call `GET /v1/recommendations` or use
 * `audio-features`/`audio-analysis` - Spotify deprecated those endpoints for all apps created
 * after November 27, 2024, and access was not restored; they now 404 for new API clients.
 *
 * It also follows Spotify's February 2026 Development Mode migration
 * (developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide), which
 * changed several endpoints this app depends on:
 *  - `POST /users/{user_id}/playlists` (create playlist) was replaced by `POST /me/playlists` -
 *    the old endpoint now returns 403 for every Development Mode caller, which is what a
 *    "create playlist failed 403" error means.
 *  - `PUT /playlists/{id}/tracks` (replace playlist contents) was renamed to
 *    `PUT /playlists/{id}/items`.
 *  - `GET /artists/{id}/top-tracks` was removed entirely with **no replacement**. The old
 *    "search artists by genre, then fetch each one's top tracks" widening approach used that
 *    endpoint and would 404 on every call post-migration; it's been replaced with searching for
 *    *tracks* by genre directly ([searchTracksByGenre]), which remains available (search itself
 *    wasn't removed, only its `limit` cap was reduced from 50 to 10 - handled here by clamping
 *    and paginating via `offset` instead of requesting a larger page).
 *
 * If Spotify changes Web API availability again, this is the file to revisit.
 */
class SpotifyWebApiClient(private val authManager: SpotifyWebAuthManager) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun authedRequest(url: String): Request {
        val token = authManager.getValidAccessToken().getOrThrow()
        return Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
    }

    /**
     * Your top artists for a given window, each with the genre tags Spotify assigns them.
     * [timeRange] is one of short_term (~4 weeks), medium_term (~6 months), long_term (years) -
     * the mix pulls all three so it reflects both current obsessions and long-standing favourites
     * rather than only the last few months.
     */
    suspend fun getTopArtists(limit: Int = 50, timeRange: String = "medium_term"): Result<List<SpotifyArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authedRequest("https://api.spotify.com/v1/me/top/artists?time_range=$timeRange&limit=$limit")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Get top artists failed: ${response.code}")
                parseArtists(JSONObject(response.body?.string().orEmpty()).getJSONArray("items"))
            }
        }
    }

    /** Your top tracks for a given window. See [getTopArtists] for why multiple ranges are used. */
    suspend fun getTopTracks(limit: Int = 50, timeRange: String = "medium_term"): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authedRequest("https://api.spotify.com/v1/me/top/tracks?time_range=$timeRange&limit=$limit")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Get top tracks failed: ${response.code}")
                parseTracks(JSONObject(response.body?.string().orEmpty()).getJSONArray("items"))
            }
        }
    }

    /**
     * Your saved / "Liked Songs" library. This is the strongest taste signal available: saving a
     * track is a deliberate choice, whereas top-tracks merely reflects what you happened to play
     * most. Requires the `user-library-read` scope - if you authorized before that scope was
     * added, this returns 403 until you reconnect Spotify.
     *
     * [offset] paginates; Spotify caps this endpoint at 50 per page.
     */
    suspend fun getSavedTracks(limit: Int = 50, offset: Int = 0): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val clamped = limit.coerceIn(1, 50)
            val request = authedRequest("https://api.spotify.com/v1/me/tracks?limit=$clamped&offset=$offset")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Get saved tracks failed: ${response.code}")
                // Saved-tracks items wrap each track in a { added_at, track } envelope, unlike
                // top-tracks which returns bare track objects.
                val items = JSONObject(response.body?.string().orEmpty()).getJSONArray("items")
                val tracks = JSONArray()
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.optJSONObject("track")?.let { tracks.put(it) }
                }
                parseTracks(tracks)
            }
        }
    }

    /**
     * Looks up a single artist by name and returns their Spotify metadata, including genre tags.
     *
     * Used to VERIFY Gemini's artist suggestions rather than trusting them: the model is asked for
     * artists in a genre, but whether an artist actually carries that genre tag is a fact only
     * Spotify can settle. Checking here is what stops the discovery tier drifting off-genre.
     */
    suspend fun searchArtistByName(name: String): Result<SpotifyArtist?> = withContext(Dispatchers.IO) {
        runCatching {
            val sanitized = name.replace("\"", "")
            val query = java.net.URLEncoder.encode("artist:\"$sanitized\"", "UTF-8")
            val request = authedRequest("https://api.spotify.com/v1/search?q=$query&type=artist&limit=1")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Artist lookup failed: ${response.code}")
                val artists = JSONObject(response.body?.string().orEmpty()).getJSONObject("artists")
                parseArtists(artists.getJSONArray("items")).firstOrNull()
            }
        }
    }

    /**
     * Searches an artist's catalog directly via general search rather than the now-removed
     * `GET /artists/{id}/top-tracks` endpoint. Used to pull *more* tracks from an artist the user
     * already listens to (beyond whatever happened to land in their top-tracks snapshot), which
     * is a much stronger "this will actually be recognizable" signal than genre-only search
     * turning up an unfamiliar artist who merely shares the tag - especially now that Spotify
     * removed the `popularity` field from track/artist objects, so there's no numeric popularity
     * signal left to sort by directly.
     */
    suspend fun searchTracksByArtist(artistName: String, limit: Int = 10, offset: Int = 0): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val sanitizedName = artistName.replace("\"", "")
            val query = java.net.URLEncoder.encode("artist:\"$sanitizedName\"", "UTF-8")
            val clampedLimit = limit.coerceIn(1, 10) // Spotify's Feb 2026 search limit cap
            val request = authedRequest(
                "https://api.spotify.com/v1/search?q=$query&type=track&limit=$clampedLimit&offset=$offset"
            )
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Artist track search failed: ${response.code}")
                val tracksObj = JSONObject(response.body?.string().orEmpty()).getJSONObject("tracks")
                parseTracks(tracksObj.getJSONArray("items"))
            }
        }
    }

    /**
     * Searches for tracks tagged with [genre] directly. Replaces the old "search artists by
     * genre, then fetch each artist's top tracks" approach - see the class-level doc comment for
     * why. [offset] supports pagination since the search `limit` cap is now 10 (was 50), so
     * gathering more than 10 results requires multiple calls. This is the last-resort tier in
     * [HourlyMixEngine] since it can surface artists the user has never heard of - prefer
     * [searchTracksByArtist] against the user's own top artists first.
     */
    suspend fun searchTracksByGenre(genre: String, limit: Int = 10, offset: Int = 0): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            // Strip embedded quotes so a stray " in user-entered genre text can't break out of
            // the quoted field-filter syntax Spotify's search expects.
            val sanitizedGenre = genre.replace("\"", "")
            val query = java.net.URLEncoder.encode("genre:\"$sanitizedGenre\"", "UTF-8")
            val clampedLimit = limit.coerceIn(1, 10) // Spotify's Feb 2026 search limit cap
            val request = authedRequest(
                "https://api.spotify.com/v1/search?q=$query&type=track&limit=$clampedLimit&offset=$offset"
            )
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Track search failed: ${response.code}")
                val tracksObj = JSONObject(response.body?.string().orEmpty()).getJSONObject("tracks")
                parseTracks(tracksObj.getJSONArray("items"))
            }
        }
    }

    /**
     * Creates a new private playlist for the current user and returns its id. Call once and
     * persist the id; reuse via [replacePlaylistTracks]. Uses `POST /me/playlists` (the
     * February-2026-current endpoint) rather than the removed `POST /users/{user_id}/playlists`,
     * so no user id needs to be resolved first.
     */
    suspend fun createPlaylist(name: String, description: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = authManager.getValidAccessToken().getOrThrow()
            val payload = JSONObject().apply {
                put("name", name)
                put("description", description)
                put("public", false)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Create playlist failed: ${response.code}")
                JSONObject(response.body?.string().orEmpty()).getString("id")
            }
        }
    }

    /**
     * Replaces a playlist's full track list in one call - used every hour to swap in the new
     * genre's mix. Uses `PUT /playlists/{id}/items` (the February-2026-current endpoint) rather
     * than the renamed-away `PUT /playlists/{id}/tracks`. The request body's `uris` field name
     * is unchanged by that migration - only the URL path segment and this endpoint's response
     * field naming for reads were affected, per Spotify's migration guide.
     */
    suspend fun replacePlaylistTracks(playlistId: String, trackUris: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = authManager.getValidAccessToken().getOrThrow()
            val payload = JSONObject().apply { put("uris", JSONArray(trackUris)) }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/playlists/$playlistId/items")
                .addHeader("Authorization", "Bearer $token")
                .put(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Replace playlist tracks failed: ${response.code}")
            }
        }
    }

    private fun parseArtists(array: JSONArray): List<SpotifyArtist> = (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val genresArray = obj.optJSONArray("genres")
        val genres = if (genresArray != null) (0 until genresArray.length()).map { genresArray.getString(it) } else emptyList()
        SpotifyArtist(id = obj.getString("id"), name = obj.getString("name"), genres = genres)
    }

    private fun parseTracks(array: JSONArray): List<SpotifyTrack> = (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val artistsArray = obj.optJSONArray("artists")
        val artistName = artistsArray?.optJSONObject(0)?.optString("name") ?: ""
        SpotifyTrack(uri = obj.getString("uri"), name = obj.getString("name"), artistName = artistName)
    }
}
