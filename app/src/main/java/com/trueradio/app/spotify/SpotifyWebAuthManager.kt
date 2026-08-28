package com.trueradio.app.spotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.trueradio.app.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Handles OAuth 2.0 Authorization Code + PKCE for the Spotify **Web API** - a separate grant
 * from the App Remote SDK's device-control connection in [SpotifyManager]. Web API access is
 * what lets the app read your top artists/tracks and manage playlists on your behalf, which the
 * hourly genre rotation needs; App Remote alone only lets you *control playback*, not read taste
 * data.
 *
 * PKCE is used (no client secret) since this is a public, installed-app client - the current
 * recommended flow for mobile apps per Spotify's own docs, and avoids embedding any secret.
 *
 * Required scopes: `user-top-read` (read your top artists/tracks), `playlist-modify-private`
 * and `playlist-read-private` (create/update the hourly mix playlist), `user-read-private`
 * (resolve your Spotify user id for playlist creation).
 */
class SpotifyWebAuthManager(
    private val context: Context,
    private val settings: SecureSettings,
    private val clientId: String,
    private val redirectUri: String = "trueradio://spotify-web-callback"
) {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // Guards token refresh so concurrent callers coalesce onto a single refresh instead of each
    // firing their own refresh_token grant. Not currently reachable since HourlyMixEngine calls
    // Web API methods sequentially, but some OAuth providers rotate/invalidate the refresh token
    // on use - if a future change ever parallelizes these calls (e.g. via async{}), two
    // simultaneous refreshes using the same stale refresh token would otherwise race, with the
    // loser failing with invalid_grant. Cheap to guard against now rather than debug it later.
    private val refreshMutex = Mutex()

    companion object {
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        val REQUIRED_SCOPES = listOf(
            "user-top-read",
            "playlist-modify-private",
            "playlist-read-private",
            "user-read-private"
        )
    }

    /** Step 1: launch the system browser (Custom Tab) to Spotify's consent screen. */
    suspend fun beginAuthorization() {
        val verifier = generateCodeVerifier()
        settings.savePkceCodeVerifier(verifier)
        val challenge = codeChallenge(verifier)

        val authUri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", REQUIRED_SCOPES.joinToString(" "))
            .build()

        val customTabsIntent = CustomTabsIntent.Builder().build()
        // Android requires FLAG_ACTIVITY_NEW_TASK to start an activity (which launching a
        // Custom Tab does) from anything other than an Activity context - e.g. applicationContext.
        // MainActivity constructs this class with applicationContext (to avoid leaking an Activity
        // reference across a suspend call), so this flag is required here or the app crashes with
        // "Calling startActivity() from outside of an Activity context requires FLAG_ACTIVITY_NEW_TASK".
        if (context !is Activity) {
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        customTabsIntent.launchUrl(context, authUri)
    }

    /**
     * Step 2: call this from the activity that receives the `trueradio://spotify-web-callback`
     * redirect intent, passing the intent's data URI. Extracts the auth code and exchanges it
     * for an access + refresh token pair.
     */
    suspend fun handleRedirect(redirectUriWithCode: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val code = redirectUriWithCode.getQueryParameter("code")
            ?: return@withContext Result.failure(IOException("No authorization code in redirect"))
        val verifier = settings.consumePkceCodeVerifier()
        if (verifier.isBlank()) {
            return@withContext Result.failure(IOException("Missing PKCE code verifier - restart the Spotify login"))
        }

        try {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
                .build()
            val request = Request.Builder().url(TOKEN_URL).post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Token exchange failed: ${response.code}"))
                }
                val json = JSONObject(response.body?.string().orEmpty())
                persistTokenResponse(json)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Returns a valid access token, transparently refreshing it first if it has expired. */
    suspend fun getValidAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        val expiresAt = settings.snapshotSpotifyWebTokenExpiresAt()
        val current = settings.snapshotSpotifyWebAccessToken()
        if (current.isNotBlank() && System.currentTimeMillis() < expiresAt - 30_000) {
            return@withContext Result.success(current)
        }
        refreshMutex.withLock {
            // Re-check after acquiring the lock: another caller may have already refreshed
            // while we were waiting, in which case we can just use that result.
            val recheckExpiresAt = settings.snapshotSpotifyWebTokenExpiresAt()
            val recheckCurrent = settings.snapshotSpotifyWebAccessToken()
            if (recheckCurrent.isNotBlank() && System.currentTimeMillis() < recheckExpiresAt - 30_000) {
                Result.success(recheckCurrent)
            } else {
                refreshAccessToken()
            }
        }
    }

    private suspend fun refreshAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        val refreshToken = settings.snapshotSpotifyWebRefreshToken()
        if (refreshToken.isBlank()) {
            return@withContext Result.failure(IOException("Not connected to Spotify Web API - authorize first"))
        }
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build()
            val request = Request.Builder().url(TOKEN_URL).post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Token refresh failed: ${response.code}"))
                }
                val json = JSONObject(response.body?.string().orEmpty())
                // Spotify may omit refresh_token on refresh responses; keep the existing one if so.
                if (!json.has("refresh_token")) json.put("refresh_token", refreshToken)
                persistTokenResponse(json)
                Result.success(json.getString("access_token"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun persistTokenResponse(json: JSONObject) {
        val accessToken = json.getString("access_token")
        val refreshToken = if (json.has("refresh_token")) json.getString("refresh_token") else ""
        val expiresInSeconds = json.optInt("expires_in", 3600)
        val expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L
        settings.saveSpotifyWebTokens(accessToken, refreshToken, expiresAt)
    }

    suspend fun isConnected(): Boolean = settings.snapshotSpotifyWebRefreshToken().isNotBlank()

    suspend fun disconnect() = settings.clearSpotifyWebTokens()

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlEncode(digest)
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
}
