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

    companion object {
        /** Roughly a few weeks of listening; keeps the DataStore entry small. */
        private const val MAX_CACHED_SCRIPTS = 400
    }

    private object Keys {
        val SPOTIFY_CLIENT_ID = stringPreferencesKey("spotify_client_id")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val NEWS_CATEGORIES = stringPreferencesKey("news_categories") // comma-separated NewsCategory names
        val NEWS_LIKED_TOPICS = stringPreferencesKey("news_liked_topics") // comma-separated free-text keywords
        val NEWS_SOURCES = stringPreferencesKey("news_sources") // serialized NewsSource list, see NewsSource.serializeList
        val GENRE_ROTATION = stringPreferencesKey("genre_rotation") // serialized GenreRotation
        val SPOTIFY_WEB_ACCESS_TOKEN = stringPreferencesKey("spotify_web_access_token")
        val SPOTIFY_WEB_REFRESH_TOKEN = stringPreferencesKey("spotify_web_refresh_token")
        val SPOTIFY_WEB_TOKEN_EXPIRES_AT = stringPreferencesKey("spotify_web_token_expires_at") // epoch millis, as string
        val SPOTIFY_HOURLY_PLAYLIST_ID = stringPreferencesKey("spotify_hourly_playlist_id")
        val SPOTIFY_PKCE_CODE_VERIFIER = stringPreferencesKey("spotify_pkce_code_verifier") // transient, cleared after token exchange
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DJ_LANGUAGE = stringPreferencesKey("dj_language")
        val ONBOARDING_COMPLETE = stringPreferencesKey("onboarding_complete")
        val SEGMENT_GENRES = stringPreferencesKey("segment_genres")
        val TRACK_FEEDBACK = stringPreferencesKey("track_feedback")
        val GENRE_ANCHORS = stringPreferencesKey("genre_anchors")
        val SONG_LANGUAGE = stringPreferencesKey("song_language")
        val TUNED_GENRE_OVERRIDE = stringPreferencesKey("tuned_genre_override")
        val DJ_EVERY_N_TRACKS = stringPreferencesKey("dj_every_n_tracks")
        val VOICE_MODE = stringPreferencesKey("voice_mode")
        val SCRIPT_CACHE = stringPreferencesKey("script_cache")
        val EVERGREEN_LINES = stringPreferencesKey("evergreen_lines")
        val ARTIST_LIST_CACHE = stringPreferencesKey("artist_list_cache")
    }

    val spotifyClientId: Flow<String> = context.dataStore.data.map { it[Keys.SPOTIFY_CLIENT_ID] ?: "" }
    val geminiApiKey: Flow<String> = context.dataStore.data.map { it[Keys.GEMINI_API_KEY] ?: "" }

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
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        ThemeMode.entries.firstOrNull { it.name == prefs[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM
    }
    val djLanguage: Flow<DjLanguage> = context.dataStore.data.map { prefs ->
        DjLanguage.entries.firstOrNull { it.name == prefs[Keys.DJ_LANGUAGE] } ?: DjLanguage.ENGLISH
    }
    val segmentGenres: Flow<SegmentGenres> = context.dataStore.data.map {
        SegmentGenres.deserialize(it[Keys.SEGMENT_GENRES] ?: "")
    }
    val trackFeedback: Flow<TrackFeedback> = context.dataStore.data.map {
        TrackFeedback.deserialize(it[Keys.TRACK_FEEDBACK] ?: "")
    }
    val genreAnchors: Flow<GenreAnchors> = context.dataStore.data.map {
        GenreAnchors.deserialize(it[Keys.GENRE_ANCHORS] ?: "")
    }
    /** Empty set = no language preference; see SongLanguage.promptClause. */
    val songLanguages: Flow<Set<SongLanguage>> = context.dataStore.data.map { prefs ->
        SongLanguage.fromNames(prefs[Keys.SONG_LANGUAGE] ?: "")
    }
    /**
     * Speak between every Nth track. Each DJ segment costs a Gemini TTS call, so this is the most
     * direct lever on quota usage - and a DJ that talks after every single song is arguably too
     * chatty for a radio station anyway. Default 2.
     */
    val djEveryNTracks: Flow<Int> = context.dataStore.data.map {
        it[Keys.DJ_EVERY_N_TRACKS]?.toIntOrNull()?.coerceIn(1, 10) ?: 2
    }
    val voiceMode: Flow<VoiceMode> = context.dataStore.data.map { prefs ->
        VoiceMode.entries.firstOrNull { it.name == prefs[Keys.VOICE_MODE] } ?: VoiceMode.BALANCED
    }
    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_COMPLETE] == "true" }

    suspend fun saveAll(spotifyClientId: String, geminiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SPOTIFY_CLIENT_ID] = spotifyClientId
            prefs[Keys.GEMINI_API_KEY] = geminiKey
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

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode.name }
    }

    suspend fun saveDjLanguage(language: DjLanguage) {
        context.dataStore.edit { prefs -> prefs[Keys.DJ_LANGUAGE] = language.name }
    }

    suspend fun saveSegmentGenres(genres: SegmentGenres) {
        context.dataStore.edit { prefs -> prefs[Keys.SEGMENT_GENRES] = genres.serialize() }
    }

    suspend fun saveTrackFeedback(feedback: TrackFeedback) {
        context.dataStore.edit { prefs -> prefs[Keys.TRACK_FEEDBACK] = feedback.serialize() }
    }

    suspend fun saveGenreAnchors(anchors: GenreAnchors) {
        context.dataStore.edit { prefs -> prefs[Keys.GENRE_ANCHORS] = anchors.serialize() }
    }

    /** One-shot: the genre the user tuned to before pressing start. Consumed by the service. */
    suspend fun saveTunedGenreOverride(genre: String) {
        context.dataStore.edit { prefs -> prefs[Keys.TUNED_GENRE_OVERRIDE] = genre }
    }

    suspend fun consumeTunedGenreOverride(): String? {
        val value = context.dataStore.data.map { it[Keys.TUNED_GENRE_OVERRIDE] }.first()
        if (value != null) context.dataStore.edit { it.remove(Keys.TUNED_GENRE_OVERRIDE) }
        return value
    }

    /**
     * Trivia scripts keyed by "artist|title", persisted across restarts.
     *
     * The mix is deliberately built from the user's top artists, so the same tracks recur
     * constantly - an in-memory cache threw all that work away on every service restart and paid
     * Gemini again for songs it had already written about. Capped so it can't grow without bound.
     */
    suspend fun saveScriptCache(cache: Map<String, String>) {
        val trimmed = cache.entries.toList().takeLast(MAX_CACHED_SCRIPTS)
        val blob = trimmed.joinToString("\n") { (k, v) ->
            k.replace("\u0001", "").replace("\n", " ") + "\u0001" + v.replace("\n", " ")
        }
        context.dataStore.edit { prefs -> prefs[Keys.SCRIPT_CACHE] = blob }
    }

    suspend fun loadScriptCache(): MutableMap<String, String> {
        val blob = context.dataStore.data.map { it[Keys.SCRIPT_CACHE] ?: "" }.first()
        if (blob.isBlank()) return mutableMapOf()
        return blob.split("\n").mapNotNull { line ->
            val parts = line.split("\u0001")
            if (parts.size != 2 || parts[0].isBlank()) null else parts[0] to parts[1]
        }.toMap().toMutableMap()
    }

    /** Reusable generic DJ lines, generated once and replayed - see EvergreenLines in the service. */
    suspend fun saveEvergreenLines(lines: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EVERGREEN_LINES] = lines.joinToString("\n") { it.replace("\n", " ") }
        }
    }

    suspend fun loadEvergreenLines(): List<String> {
        val blob = context.dataStore.data.map { it[Keys.EVERGREEN_LINES] ?: "" }.first()
        return blob.split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }

    /** Genre -> artist-name lists, stable for weeks; persisted so restarts don't re-ask Gemini. */
    suspend fun saveArtistListCache(cache: Map<String, List<String>>) {
        val blob = cache.entries.joinToString("\n") { (k, v) ->
            k.replace("\u0001", "") + "\u0001" + v.joinToString(",") { a -> a.replace(",", " ") }
        }
        context.dataStore.edit { prefs -> prefs[Keys.ARTIST_LIST_CACHE] = blob }
    }

    suspend fun loadArtistListCache(): MutableMap<String, List<String>> {
        val blob = context.dataStore.data.map { it[Keys.ARTIST_LIST_CACHE] ?: "" }.first()
        if (blob.isBlank()) return mutableMapOf()
        return blob.split("\n").mapNotNull { line ->
            val parts = line.split("\u0001")
            if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
            val artists = parts[1].split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (artists.isEmpty()) null else parts[0] to artists
        }.toMap().toMutableMap()
    }

    suspend fun saveVoiceMode(mode: VoiceMode) {
        context.dataStore.edit { prefs -> prefs[Keys.VOICE_MODE] = mode.name }
    }

    suspend fun saveDjEveryNTracks(n: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.DJ_EVERY_N_TRACKS] = n.coerceIn(1, 10).toString() }
    }

    suspend fun saveSongLanguages(languages: Set<SongLanguage>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SONG_LANGUAGE] = languages.joinToString(",") { it.name }
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.ONBOARDING_COMPLETE] = complete.toString() }
    }

    suspend fun snapshotGeminiKey(): String = geminiApiKey.first()
    suspend fun snapshotSpotifyClientId(): String = spotifyClientId.first()
    suspend fun snapshotNewsPreferences(): NewsPreferences = newsPreferences.first()
    suspend fun snapshotGenreRotation(): GenreRotation = genreRotation.first()
    suspend fun snapshotSpotifyWebAccessToken(): String = spotifyWebAccessToken.first()
    suspend fun snapshotSpotifyWebRefreshToken(): String = spotifyWebRefreshToken.first()
    suspend fun snapshotSpotifyWebTokenExpiresAt(): Long = spotifyWebTokenExpiresAt.first()
    suspend fun snapshotSpotifyHourlyPlaylistId(): String = spotifyHourlyPlaylistId.first()
    suspend fun snapshotThemeMode(): ThemeMode = themeMode.first()
    suspend fun snapshotDjLanguage(): DjLanguage = djLanguage.first()
    suspend fun snapshotOnboardingComplete(): Boolean = onboardingComplete.first()
    suspend fun snapshotSegmentGenres(): SegmentGenres = segmentGenres.first()
    suspend fun snapshotTrackFeedback(): TrackFeedback = trackFeedback.first()
    suspend fun snapshotGenreAnchors(): GenreAnchors = genreAnchors.first()
    suspend fun snapshotSongLanguages(): Set<SongLanguage> = songLanguages.first()
    suspend fun snapshotDjEveryNTracks(): Int = djEveryNTracks.first()
    suspend fun snapshotVoiceMode(): VoiceMode = voiceMode.first()
}
