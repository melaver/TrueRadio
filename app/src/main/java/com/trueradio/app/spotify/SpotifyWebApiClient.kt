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

    /** Your top artists (medium_term = ~last 6 months), each with the genre tags Spotify assigns them. */
    suspend fun getTopArtists(limit: Int = 50): Result<List<SpotifyArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authedRequest("https://api.spotify.com/v1/me/top/artists?time_range=medium_term&limit=$limit")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Get top artists failed: ${response.code}")
                parseArtists(JSONObject(response.body?.string().orEmpty()).getJSONArray("items"))
            }
        }
    }

    /** Your top tracks (medium_term), used as a personalization signal and direct playback candidates. */
    suspend fun getTopTracks(limit: Int = 50): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authedRequest("https://api.spotify.com/v1/me/top/tracks?time_range=medium_term&limit=$limit")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Get top tracks failed: ${response.code}")
                parseTracks(JSONObject(response.body?.string().orEmpty()).getJSONArray("items"))
            }
        }
    }

    /**
     * Searches for tracks tagged with [genre] directly. Replaces the old "search artists by
     * genre, then fetch each artist's top tracks" approach - see the class-level doc comment for
     * why. [offset] supports pagination since the search `limit` cap is now 10 (was 50), so
     * gathering more than 10 results requires multiple calls.
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
