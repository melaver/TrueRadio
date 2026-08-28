package com.trueradio.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dj_settings")

/**
 * Local, on-device storage for the keys the user enters in the UI.
 *
 * NOTE: DataStore Preferences is NOT encrypted at rest. For a production release,
 * wrap these values with androidx.security:security-crypto (EncryptedSharedPreferences)
 * or a Keystore-backed solution instead of storing raw strings here.
 */
class SecureSettings(private val context: Context) {

    private object Keys {
        val SPOTIFY_CLIENT_ID = stringPreferencesKey("spotify_client_id")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val ELEVENLABS_API_KEY = stringPreferencesKey("elevenlabs_api_key")
        val ELEVENLABS_VOICE_ID = stringPreferencesKey("elevenlabs_voice_id")
        val NEWS_CATEGORIES = stringPreferencesKey("news_categories") // comma-separated NewsCategory names
        val NEWS_LIKED_TOPICS = stringPreferencesKey("news_liked_topics") // comma-separated free-text keywords
        val NEWS_SOURCES = stringPreferencesKey("news_sources") // serialized NewsSource list, see NewsSource.serializeList
        val GENRE_ROTATION = stringPreferencesKey("genre_rotation") // serialized GenreRotation
        val SPOTIFY_WEB_ACCESS_TOKEN = stringPreferencesKey("spotify_web_access_token")
        val SPOTIFY_WEB_REFRESH_TOKEN = stringPreferencesKey("spotify_web_refresh_token")
        val SPOTIFY_WEB_TOKEN_EXPIRES_AT = stringPreferencesKey("spotify_web_token_expires_at") // epoch millis, as string
        val SPOTIFY_HOURLY_PLAYLIST_ID = stringPreferencesKey("spotify_hourly_playlist_id")
        val SPOTIFY_PKCE_CODE_VERIFIER = stringPreferencesKey("spotify_pkce_code_verifier") // transient, cleared after token exchange
    }

    val spotifyClientId: Flow<String> = context.dataStore.data.map { it[Keys.SPOTIFY_CLIENT_ID] ?: "" }
    val geminiApiKey: Flow<String> = context.dataStore.data.map { it[Keys.GEMINI_API_KEY] ?: "" }
    val elevenLabsApiKey: Flow<String> = context.dataStore.data.map { it[Keys.ELEVENLABS_API_KEY] ?: "" }
    val elevenLabsVoiceId: Flow<String> = context.dataStore.data.map { it[Keys.ELEVENLABS_VOICE_ID] ?: "" }

    val newsCategoriesCsv: Flow<String> = context.dataStore.data.map { it[Keys.NEWS_CATEGORIES] ?: NewsCategory.GENERAL.name }
    val newsLikedTopicsCsv: Flow<String> = context.dataStore.data.map { it[Keys.NEWS_LIKED_TOPICS] ?: "" }
    val newsPreferences: Flow<NewsPreferences> = context.dataStore.data.map { prefs ->
        NewsPreferences.deserialize(
            categoriesCsv = prefs[Keys.NEWS_CATEGORIES] ?: NewsCategory.GENERAL.name,
            topicsCsv = prefs[Keys.NEWS_LIKED_TOPICS] ?: "",
            sourcesBlob = prefs[Keys.NEWS_SOURCES] ?: ""
        )
    }
    val genreRotation: Flow<GenreRotation> = context.dataStore.data.map { GenreRotation.deserialize(it[Keys.GENRE_ROTATION] ?: "") }
    val spotifyWebAccessToken: Flow<String> = context.dataStore.data.map { it[Keys.SPOTIFY_WEB_ACCESS_TOKEN] ?: "" }
    val spotifyWebRefreshToken: Flow<String> = context.dataStore.data.map { it[Keys.SPOTIFY_WEB_REFRESH_TOKEN] ?: "" }
    val spotifyWebTokenExpiresAt: Flow<Long> = context.dataStore.data.map { it[Keys.SPOTIFY_WEB_TOKEN_EXPIRES_AT]?.toLongOrNull() ?: 0L }
    val spotifyHourlyPlaylistId: Flow<String> = context.dataStore.data.map { it[Keys.SPOTIFY_HOURLY_PLAYLIST_ID] ?: "" }

    suspend fun saveAll(spotifyClientId: String, geminiKey: String, elevenLabsKey: String, elevenLabsVoiceId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SPOTIFY_CLIENT_ID] = spotifyClientId
            prefs[Keys.GEMINI_API_KEY] = geminiKey
            prefs[Keys.ELEVENLABS_API_KEY] = elevenLabsKey
            prefs[Keys.ELEVENLABS_VOICE_ID] = elevenLabsVoiceId
        }
    }

    suspend fun saveNewsPreferences(preferences: NewsPreferences) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NEWS_CATEGORIES] = preferences.toSerializedCategories()
            prefs[Keys.NEWS_LIKED_TOPICS] = preferences.toSerializedTopics()
            prefs[Keys.NEWS_SOURCES] = preferences.toSerializedSources()
        }
    }

    suspend fun saveGenreRotation(rotation: GenreRotation) {
        context.dataStore.edit { prefs -> prefs[Keys.GENRE_ROTATION] = rotation.toSerialized() }
    }

    suspend fun savePkceCodeVerifier(verifier: String) {
        context.dataStore.edit { prefs -> prefs[Keys.SPOTIFY_PKCE_CODE_VERIFIER] = verifier }
    }

    suspend fun consumePkceCodeVerifier(): String {
        val verifier = context.dataStore.data.map { it[Keys.SPOTIFY_PKCE_CODE_VERIFIER] ?: "" }.first()
        context.dataStore.edit { prefs -> prefs.remove(Keys.SPOTIFY_PKCE_CODE_VERIFIER) }
        return verifier
    }

    suspend fun saveSpotifyWebTokens(accessToken: String, refreshToken: String, expiresAtEpochMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SPOTIFY_WEB_ACCESS_TOKEN] = accessToken
            prefs[Keys.SPOTIFY_WEB_REFRESH_TOKEN] = refreshToken
            prefs[Keys.SPOTIFY_WEB_TOKEN_EXPIRES_AT] = expiresAtEpochMillis.toString()
        }
    }

    suspend fun clearSpotifyWebTokens() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.SPOTIFY_WEB_ACCESS_TOKEN)
            prefs.remove(Keys.SPOTIFY_WEB_REFRESH_TOKEN)
            prefs.remove(Keys.SPOTIFY_WEB_TOKEN_EXPIRES_AT)
        }
    }

    suspend fun saveSpotifyHourlyPlaylistId(playlistId: String) {
        context.dataStore.edit { prefs -> prefs[Keys.SPOTIFY_HOURLY_PLAYLIST_ID] = playlistId }
    }

    suspend fun snapshotGeminiKey(): String = geminiApiKey.first()
    suspend fun snapshotElevenLabsKey(): String = elevenLabsApiKey.first()
    suspend fun snapshotElevenLabsVoiceId(): String = elevenLabsVoiceId.first()
    suspend fun snapshotSpotifyClientId(): String = spotifyClientId.first()
    suspend fun snapshotNewsPreferences(): NewsPreferences = newsPreferences.first()
    suspend fun snapshotGenreRotation(): GenreRotation = genreRotation.first()
    suspend fun snapshotSpotifyWebAccessToken(): String = spotifyWebAccessToken.first()
    suspend fun snapshotSpotifyWebRefreshToken(): String = spotifyWebRefreshToken.first()
    suspend fun snapshotSpotifyWebTokenExpiresAt(): Long = spotifyWebTokenExpiresAt.first()
    suspend fun snapshotSpotifyHourlyPlaylistId(): String = spotifyHourlyPlaylistId.first()
}
