package com.trueradio.app.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.trueradio.app.DaySegment
import com.trueradio.app.DjLanguage
import com.trueradio.app.NewsCategory
import com.trueradio.app.NewsPreferences
import com.trueradio.app.GenreAnchors
import com.trueradio.app.SecureSettings
import com.trueradio.app.SegmentGenres
import com.trueradio.app.SongLanguage
import com.trueradio.app.ui.components.LikedArtistsEditor
import com.trueradio.app.ui.components.SectionHeader
import com.trueradio.app.ui.components.SelectableChipGrid
import com.trueradio.app.ui.components.SingleChoiceChipGrid
import kotlinx.coroutines.launch

private const val STEP_WELCOME = 0
private const val STEP_KEYS = 1
private const val STEP_PREFS = 2
private const val STEP_COUNT = 3

/**
 * Three-step first-run setup. Step state is [rememberSaveable] so rotating mid-onboarding doesn't
 * dump the user back to step one with their typed keys lost.
 */
@Composable
fun OnboardingFlow(
    settings: SecureSettings,
    isSpotifyWebConnected: Boolean,
    isConnectingSpotifyWeb: Boolean,
    onConnectSpotifyWeb: (String) -> Unit,
    onFinished: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(STEP_WELCOME) }

    val savedSpotifyId by settings.spotifyClientId.collectAsState(initial = "")
    val savedGeminiKey by settings.geminiApiKey.collectAsState(initial = "")
    var spotifyClientId by remember(savedSpotifyId) { mutableStateOf(savedSpotifyId) }
    var geminiKey by remember(savedGeminiKey) { mutableStateOf(savedGeminiKey) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LinearProgressIndicator(
            progress = { (step + 1f) / STEP_COUNT },
            modifier = Modifier.fillMaxWidth()
        )
        Text("Step ${step + 1} of $STEP_COUNT", style = MaterialTheme.typography.labelMedium)

        when (step) {
            STEP_WELCOME -> WelcomeStep(
                spotifyClientId = spotifyClientId,
                onSpotifyClientIdChange = { spotifyClientId = it },
                isConnected = isSpotifyWebConnected,
                isConnecting = isConnectingSpotifyWeb,
                onConnect = {
                    // Persist before launching the browser: the OAuth redirect re-enters the app
                    // and reads the client id back from settings, so an unsaved field would fail.
                    scope.launch {
                        settings.saveAll(spotifyClientId, geminiKey)
                        onConnectSpotifyWeb(spotifyClientId)
                    }
                }
            )
            STEP_KEYS -> ApiKeysStep(
                spotifyClientId = spotifyClientId,
                geminiKey = geminiKey,
                onSpotifyClientIdChange = { spotifyClientId = it },
                onGeminiKeyChange = { geminiKey = it }
            )
            STEP_PREFS -> PreferencesStep(settings = settings)
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > STEP_WELCOME) {
                OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("Back") }
            }
            Button(
                onClick = {
                    if (step < STEP_PREFS) {
                        scope.launch { settings.saveAll(spotifyClientId, geminiKey) }
                        step++
                    } else {
                        scope.launch {
                            settings.saveAll(spotifyClientId, geminiKey)
                            settings.setOnboardingComplete(true)
                            onFinished()
                        }
                    }
                },
                // Keys are required to advance past the keys step: letting someone through with
                // blanks produces an app that plays music but whose DJ silently never speaks,
                // which is far more confusing than being blocked here.
                enabled = when (step) {
                    STEP_KEYS -> spotifyClientId.isNotBlank() && geminiKey.isNotBlank()
                    else -> true
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (step == STEP_PREFS) "Finish" else "Next")
            }
        }
    }
}

@Composable
private fun WelcomeStep(
    spotifyClientId: String,
    onSpotifyClientIdChange: (String) -> Unit,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome to TrueRadio", style = MaterialTheme.typography.headlineMedium)
        Text(
            "TrueRadio turns your own Spotify into a real radio station: an AI host introduces " +
                "tracks with sharp trivia, reads a news flash at the top of every hour, and " +
                "rotates the genre hour by hour - all built from your actual listening history.",
            style = MaterialTheme.typography.bodyMedium
        )
        SectionHeader(
            "Connect Spotify",
            "Needs permission to read your top artists and tracks, and to manage one private " +
                "playlist for the hourly mix."
        )
        OutlinedTextField(
            value = spotifyClientId,
            onValueChange = onSpotifyClientIdChange,
            label = { Text("Spotify Client ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            onClick = onConnect,
            enabled = spotifyClientId.isNotBlank() && !isConnecting && !isConnected,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    isConnected -> "Spotify connected ✓"
                    isConnecting -> "Connecting..."
                    else -> "Connect Spotify"
                }
            )
        }
        if (!isConnected) {
            Text(
                "You can also do this later from Settings - the radio will fall back to generic " +
                    "playlists until you connect.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ApiKeysStep(
    spotifyClientId: String,
    geminiKey: String,
    onSpotifyClientIdChange: (String) -> Unit,
    onGeminiKeyChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("API keys", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Stored only on this device. The Gemini key powers both the DJ's script writing and " +
                "its voice.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = spotifyClientId,
            onValueChange = onSpotifyClientIdChange,
            label = { Text("Spotify Client ID") },
            supportingText = { Text("From developer.spotify.com/dashboard") },
            isError = spotifyClientId.isBlank(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = geminiKey,
            onValueChange = onGeminiKeyChange,
            label = { Text("Gemini API Key") },
            supportingText = { Text("From aistudio.google.com/app/apikey") },
            isError = geminiKey.isBlank(),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
private fun PreferencesStep(settings: SecureSettings) {
    val scope = rememberCoroutineScope()

    val djLanguage by settings.djLanguage.collectAsState(initial = DjLanguage.HEBREW)
    val segmentGenres by settings.segmentGenres.collectAsState(initial = SegmentGenres())
    val newsPrefs by settings.newsPreferences.collectAsState(initial = NewsPreferences())
    val genreAnchors by settings.genreAnchors.collectAsState(initial = GenreAnchors())
    val songLanguages by settings.songLanguages.collectAsState(initial = emptySet())

    var activeSegment by rememberSaveable { mutableStateOf(DaySegment.MORNING) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your preferences", style = MaterialTheme.typography.headlineSmall)

        SectionHeader("DJ language", "Sets the DJ's speaking language and voice.")
        SingleChoiceChipGrid(
            options = DjLanguage.entries.toList(),
            selected = djLanguage,
            onSelect = { scope.launch { settings.saveDjLanguage(it) } },
            label = { it.displayName }
        )

        SectionHeader(
            "Song languages",
            "Optional, pick any number. Biases which artists get picked; not an exact filter."
        )
        SelectableChipGrid(
            options = SongLanguage.entries.toList(),
            selected = songLanguages,
            onToggle = { lang ->
                val updated = if (lang in songLanguages) songLanguages - lang else songLanguages + lang
                scope.launch { settings.saveSongLanguages(updated) }
            },
            label = { it.displayName }
        )

        SectionHeader("Music genres by time of day", "Pick what should play in each daypart.")
        SingleChoiceChipGrid(
            options = DaySegment.entries.toList(),
            selected = activeSegment,
            onSelect = { activeSegment = it },
            label = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
        )
        SelectableChipGrid(
            options = SegmentGenres.ALL_GENRES,
            selected = segmentGenres.genresFor(activeSegment).toSet(),
            onToggle = { genre ->
                val isSelected = genre in segmentGenres.genresFor(activeSegment)
                scope.launch {
                    settings.saveSegmentGenres(segmentGenres.withGenre(activeSegment, genre, !isSelected))
                }
            },
            label = { it }
        )

        SectionHeader(
            "Your favourite artists",
            "The strongest signal for matching your taste. These steer every mix without limiting it to just them."
        )
        LikedArtistsEditor(
            artists = genreAnchors.globalArtists(),
            maxArtists = GenreAnchors.MAX_GLOBAL,
            onAdd = { artist ->
                scope.launch { settings.saveGenreAnchors(genreAnchors.withArtist(GenreAnchors.GLOBAL_KEY, artist)) }
            },
            onRemove = { artist ->
                scope.launch { settings.saveGenreAnchors(genreAnchors.withoutArtist(GenreAnchors.GLOBAL_KEY, artist)) }
            }
        )

        SectionHeader("News topics", "Headlines matching these get priority in the hourly flash.")
        SelectableChipGrid(
            options = NewsCategory.entries.toList(),
            selected = newsPrefs.selectedCategories,
            onToggle = { category ->
                val updated = if (category in newsPrefs.selectedCategories) {
                    (newsPrefs.selectedCategories - category).ifEmpty { setOf(NewsCategory.GENERAL) }
                } else {
                    newsPrefs.selectedCategories + category
                }
                scope.launch { settings.saveNewsPreferences(newsPrefs.copy(selectedCategories = updated)) }
            },
            label = { it.displayName }
        )

        SectionHeader("News sources", "Toggle which feeds the DJ pulls headlines from.")
        SelectableChipGrid(
            options = newsPrefs.sources,
            selected = newsPrefs.sources.filter { it.enabled }.toSet(),
            onToggle = { source ->
                val updated = newsPrefs.sources.map {
                    if (it.id == source.id) it.copy(enabled = !it.enabled) else it
                }
                scope.launch { settings.saveNewsPreferences(newsPrefs.copy(sources = updated)) }
            },
            label = { it.name }
        )
        Text(
            "Add your own RSS feeds later in Settings.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
