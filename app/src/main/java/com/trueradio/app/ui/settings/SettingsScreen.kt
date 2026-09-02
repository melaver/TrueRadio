package com.trueradio.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trueradio.app.DaySegment
import com.trueradio.app.GenreStrictness
import com.trueradio.app.NewsLength
import com.trueradio.app.tts.CloudTtsClient
import com.trueradio.app.VoiceMode
import com.trueradio.app.GenreAnchors
import com.trueradio.app.NewsCategory
import com.trueradio.app.NewsPreferences
import com.trueradio.app.NewsSource
import com.trueradio.app.SecureSettings
import com.trueradio.app.SegmentGenres
import com.trueradio.app.SongLanguage
import com.trueradio.app.alarm.WakeAlarmReceiver
import com.trueradio.app.service.RadioServiceState
import com.trueradio.app.ThemeMode
import com.trueradio.app.ui.components.LikedArtistsEditor
import com.trueradio.app.ui.components.SectionHeader
import com.trueradio.app.ui.components.SelectableChipGrid
import com.trueradio.app.ui.components.SingleChoiceChipGrid
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SecureSettings,
    isSpotifyWebConnected: Boolean,
    isConnectingSpotifyWeb: Boolean,
    onConnectSpotifyWeb: (String) -> Unit,
    onDisconnectSpotifyWeb: () -> Unit,
    onForceDj: () -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenHealth: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // Force-DJ only makes sense while the service is live; there's no track to talk over otherwise.
    val isRadioRunning by RadioServiceState.isRunning.collectAsState()
    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val voiceMode by settings.voiceMode.collectAsState(initial = VoiceMode.BALANCED)
    val newsLength by settings.newsLength.collectAsState(initial = NewsLength.STANDARD)
    val genreStrictness by settings.genreStrictness.collectAsState(initial = GenreStrictness.STRICT)
    val sleepMinutes by RadioServiceState.sleepMinutesRemaining.collectAsState()
    val wakeAlarm by settings.wakeAlarmMinutes.collectAsState(initial = null)
    val context = androidx.compose.ui.platform.LocalContext.current
    // Reflects whether the OS currently permits exact alarms, checked on entry rather than
    // assumed - an alarm set in an earlier session can be silently invalidated if the user later
    // revokes "Alarms & reminders", and optimistically defaulting to true would hide that.
    var alarmScheduled by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(wakeAlarm) {
        alarmScheduled = wakeAlarm == null || WakeAlarmReceiver.canScheduleExact(context)
    }
    val djVolume by settings.djVolume.collectAsState(initial = 1.0f)
    val savedCloudKey by settings.cloudTtsKey.collectAsState(initial = "")
    val cloudTtsVoice by settings.cloudTtsVoice.collectAsState(initial = CloudTtsClient.DEFAULT_VOICE)
    val segmentGenres by settings.segmentGenres.collectAsState(initial = SegmentGenres())
    val newsPrefs by settings.newsPreferences.collectAsState(initial = NewsPreferences())
    val genreAnchors by settings.genreAnchors.collectAsState(initial = GenreAnchors())
    val songLanguages by settings.songLanguages.collectAsState(initial = emptySet())
    val djEveryN by settings.djEveryNTracks.collectAsState(initial = 2)
    val savedSpotifyId by settings.spotifyClientId.collectAsState(initial = "")
    val savedGeminiKey by settings.geminiApiKey.collectAsState(initial = "")

    var spotifyClientId by remember(savedSpotifyId) { mutableStateOf(savedSpotifyId) }
    var geminiKey by remember(savedGeminiKey) { mutableStateOf(savedGeminiKey) }
    var activeSegment by rememberSaveable { mutableStateOf(DaySegment.MORNING) }
    var likedTopicsText by remember(newsPrefs) { mutableStateOf(newsPrefs.likedTopics.joinToString(", ")) }
    var newSourceName by rememberSaveable { mutableStateOf("") }
    var newSourceUrl by rememberSaveable { mutableStateOf("") }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var cloudTtsKey by remember(savedCloudKey) { mutableStateOf(savedCloudKey) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader("Appearance")
            SingleChoiceChipGrid(
                options = ThemeMode.entries.toList(),
                selected = themeMode,
                onSelect = { scope.launch { settings.saveThemeMode(it) } },
                label = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
            )

            HorizontalDivider()
            SectionHeader(
                "How often the DJ speaks",
                "Every Nth track. Each segment costs a Gemini call, so raising this is the most " +
                    "effective fix if you're hitting rate limits (429)."
            )
            SingleChoiceChipGrid(
                options = listOf(1, 2, 3, 4, 5),
                selected = djEveryN,
                onSelect = { scope.launch { settings.saveDjEveryNTracks(it) } },
                label = { if (it == 1) "Every track" else "Every $it tracks" }
            )

            HorizontalDivider()
            SectionHeader(
                "DJ voice",
                "Gemini's voice sounds better but uses API quota on every segment. Balanced keeps " +
                    "it for news and uses your device's voice for track trivia - about 90% fewer calls."
            )
            SingleChoiceChipGrid(
                options = VoiceMode.entries.toList(),
                selected = voiceMode,
                onSelect = { scope.launch { settings.saveVoiceMode(it) } },
                label = { it.displayName }
            )
            Text(
                voiceMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()
            SectionHeader(
                "Cloud TTS voice",
                "Powers the premium voice selected above. Needs its own API key from Google Cloud " +
                    "Console (enable the Text-to-Speech API) - separate from the Gemini key below, " +
                    "with its own large free monthly allowance. Without one, the DJ always uses " +
                    "your device's built-in voice regardless of the mode chosen above."
            )
            OutlinedTextField(
                value = cloudTtsKey,
                onValueChange = { cloudTtsKey = it },
                label = { Text("Cloud TTS API Key") },
                supportingText = { Text("From console.cloud.google.com/apis/credentials") },
                visualTransformation = if (showKeys) androidx.compose.ui.text.input.VisualTransformation.None
                else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = { scope.launch { settings.saveCloudTtsKey(cloudTtsKey) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Cloud TTS key") }
            SingleChoiceChipGrid(
                options = CloudTtsClient.VOICE_OPTIONS.map { it.first },
                selected = cloudTtsVoice,
                onSelect = { voiceId -> scope.launch { settings.saveCloudTtsVoice(voiceId) } },
                label = { voiceId -> CloudTtsClient.VOICE_OPTIONS.firstOrNull { it.first == voiceId }?.second ?: voiceId }
            )

            HorizontalDivider()
            SectionHeader(
                "Song languages",
                "Pick any number. Biases artist selection toward these languages - Spotify has no " +
                    "language field, so it can't be exact, and your own saved/top tracks are never " +
                    "filtered out. Selecting none means no preference."
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

            HorizontalDivider()
            SectionHeader("Sleep timer", "Stops the radio after a set time, even if paused.")
            SingleChoiceChipGrid(
                options = listOf(0, 15, 30, 45, 60, 90),
                selected = sleepMinutes ?: 0,
                onSelect = { mins -> onSetSleepTimer(mins) },
                label = { if (it == 0) "Off" else "$it min" }
            )
            sleepMinutes?.let {
                Text(
                    "Stopping in about $it minute${if (it == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider()
            SectionHeader(
                "Wake to radio",
                "Starts the radio at this time with a morning mix and the news."
            )
            SingleChoiceChipGrid(
                options = listOf(-1, 360, 390, 420, 450, 480),
                selected = wakeAlarm ?: -1,
                onSelect = { mins ->
                    scope.launch {
                        if (mins < 0) {
                            settings.saveWakeAlarmMinutes(null)
                            WakeAlarmReceiver.cancel(context)
                        } else {
                            settings.saveWakeAlarmMinutes(mins)
                            alarmScheduled = WakeAlarmReceiver.schedule(context, mins)
                        }
                    }
                },
                label = { if (it < 0) "Off" else "%02d:%02d".format(it / 60, it % 60) }
            )
            if (wakeAlarm != null && !alarmScheduled) {
                Text(
                    "Android is blocking exact alarms. Enable Alarms & reminders for TrueRadio in " +
                        "system settings, or the wake time may be delayed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()
            SectionHeader("Diagnostics")
            OutlinedButton(onClick = onOpenHealth, modifier = Modifier.fillMaxWidth()) {
                Text("Setup check")
            }
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("History - tracks & DJ lines")
            }

            HorizontalDivider()
            SectionHeader(
                "Genre strictness",
                "Strict keeps every track inside the selected genre, but the mix may be shorter " +
                    "if little of your library matches. Relaxed fills the gap from your library " +
                    "even when it's off-genre."
            )
            SingleChoiceChipGrid(
                options = GenreStrictness.entries.toList(),
                selected = genreStrictness,
                onSelect = { scope.launch { settings.saveGenreStrictness(it) } },
                label = { it.displayName }
            )
            Text(
                genreStrictness.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()
            SectionHeader("Music genres by time of day")
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

            HorizontalDivider()
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

            SectionHeader("News topics")
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

            OutlinedTextField(
                value = likedTopicsText,
                onValueChange = { likedTopicsText = it },
                label = { Text("Liked topics (comma-separated)") },
                supportingText = { Text("Extra weight for headlines matching these.") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val topics = likedTopicsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    scope.launch { settings.saveNewsPreferences(newsPrefs.copy(likedTopics = topics)) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save topics") }

            HorizontalDivider()
            SectionHeader("News sources", "Tap to enable or disable a feed.")
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

            newsPrefs.sources.forEach { source ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            source.url,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        val updated = newsPrefs.sources.filterNot { it.id == source.id }
                        scope.launch { settings.saveNewsPreferences(newsPrefs.copy(sources = updated)) }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${source.name}")
                    }
                }
            }

            OutlinedTextField(
                value = newSourceName,
                onValueChange = { newSourceName = it },
                label = { Text("New source name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = newSourceUrl,
                onValueChange = { newSourceUrl = it },
                label = { Text("RSS feed URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = {
                    val name = newSourceName.trim()
                    val url = newSourceUrl.trim()
                    if (name.isNotBlank() && url.isNotBlank()) {
                        val updated = newsPrefs.sources + NewsSource(UUID.randomUUID().toString(), name, url)
                        scope.launch { settings.saveNewsPreferences(newsPrefs.copy(sources = updated)) }
                        newSourceName = ""
                        newSourceUrl = ""
                    }
                },
                enabled = newSourceName.isNotBlank() && newSourceUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add source") }

            HorizontalDivider()
            SectionHeader(
                "Spotify account",
                if (isSpotifyWebConnected) "Connected - the hourly mix uses your listening history."
                else "Not connected - the radio falls back to generic playlists."
            )
            Button(
                onClick = {
                    if (isSpotifyWebConnected) onDisconnectSpotifyWeb() else onConnectSpotifyWeb(spotifyClientId)
                },
                enabled = spotifyClientId.isNotBlank() && !isConnectingSpotifyWeb,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        isConnectingSpotifyWeb -> "Connecting..."
                        isSpotifyWebConnected -> "Disconnect Spotify"
                        else -> "Connect Spotify"
                    }
                )
            }

            HorizontalDivider()
            SectionHeader("API keys", "Stored on this device only.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show keys", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = showKeys, onCheckedChange = { showKeys = it })
            }
            OutlinedTextField(
                value = spotifyClientId,
                onValueChange = { spotifyClientId = it },
                label = { Text("Spotify Client ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = geminiKey,
                onValueChange = { geminiKey = it },
                label = { Text("Gemini API Key") },
                visualTransformation = if (showKeys) androidx.compose.ui.text.input.VisualTransformation.None
                else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = { scope.launch { settings.saveAll(spotifyClientId, geminiKey) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save keys") }

            HorizontalDivider()
            SectionHeader(
                "Debug",
                "Runs the full DJ flow immediately against the current track, ignoring position - " +
                    "so you can verify it without waiting for a song to end. Filter Logcat on the " +
                    "DJ_FLOW tag to trace each step."
            )
            OutlinedButton(
                onClick = onForceDj,
                enabled = isRadioRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRadioRunning) "Force DJ Transition" else "Start the radio first")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
