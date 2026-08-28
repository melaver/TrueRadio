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
 * after November 27, 2024, and access was not restored; they now 404 for new API clients. This
 * class instead composes the same outcome from endpoints that remain generally available:
 * your top artists/tracks (the actual output of Spotify's own taste model for you), genre tags
 * on artist objects, artist search, and per-artist top tracks - then the caller blends and
 * shuffles the results itself. If Spotify changes availability again, this is the file to revisit.
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

    suspend fun getCurrentUserId(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authedRequest("https://api.spotify.com/v1/me")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Get current user failed: ${response.code}")
                JSONObject(response.body?.string().orEmpty()).getString("id")
            }
        }
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

    /** Finds artists tagged with [genre] via artist search, to widen the pool beyond your existing top artists. */
    suspend fun searchArtistsByGenre(genre: String, limit: Int = 10): Result<List<SpotifyArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            val query = java.net.URLEncoder.encode("genre:\"$genre\"", "UTF-8")
            val request = authedRequest("https://api.spotify.com/v1/search?q=$query&type=artist&limit=$limit")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Artist search failed: ${response.code}")
                val artistsObj = JSONObject(response.body?.string().orEmpty()).getJSONObject("artists")
                parseArtists(artistsObj.getJSONArray("items"))
            }
        }
    }

    /** Top tracks for a specific artist - still a live, non-deprecated endpoint. */
    suspend fun getArtistTopTracks(artistId: String, market: String = "US"): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authedRequest("https://api.spotify.com/v1/artists/$artistId/top-tracks?market=$market")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Artist top tracks failed: ${response.code}")
                parseTracks(JSONObject(response.body?.string().orEmpty()).getJSONArray("tracks"))
            }
        }
    }

    /** Creates a new private playlist and returns its id. Call once and persist the id; reuse via [replacePlaylistTracks]. */
    suspend fun createPlaylist(userId: String, name: String, description: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = authManager.getValidAccessToken().getOrThrow()
            val payload = JSONObject().apply {
                put("name", name)
                put("description", description)
                put("public", false)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/users/$userId/playlists")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Create playlist failed: ${response.code}")
                JSONObject(response.body?.string().orEmpty()).getString("id")
            }
        }
    }

    /** Replaces a playlist's full track list in one call - used every hour to swap in the new genre's mix. */
    suspend fun replacePlaylistTracks(playlistId: String, trackUris: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = authManager.getValidAccessToken().getOrThrow()
            val payload = JSONObject().apply { put("uris", JSONArray(trackUris)) }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/playlists/$playlistId/tracks")
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
