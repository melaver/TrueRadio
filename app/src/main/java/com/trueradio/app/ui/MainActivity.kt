package com.trueradio.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.trueradio.app.GenreRotation
import com.trueradio.app.NewsCategory
import com.trueradio.app.NewsPreferences
import com.trueradio.app.NewsSource
import com.trueradio.app.SecureSettings
import com.trueradio.app.ThemeMode
import com.trueradio.app.service.RadioForegroundService
import com.trueradio.app.spotify.SpotifyManager
import com.trueradio.app.spotify.SpotifyWebAuthManager
import com.trueradio.app.ui.theme.TrueRadioTheme
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var settings: SecureSettings
    private var spotifyWebAuthManager: SpotifyWebAuthManager? = null
    private val webApiConnectedState = mutableStateOf(false)
    // Tracks an in-flight "Connect Spotify Account" attempt so a double-tap can't fire a second
    // beginAuthorization() before the first completes - that would overwrite the saved PKCE code
    // verifier, causing the FIRST browser tab's eventual token exchange to fail with a PKCE
    // mismatch if the user finishes that one instead of the second. Reset in onResume() rather
    // than only on success, so backing out of the browser without finishing doesn't leave the
    // button permanently disabled with no way to retry.
    private val isConnectingSpotifyWebState = mutableStateOf(false)

    // Tracks a pending pre-authorization SpotifyManager (see startDjService) so it can be
    // disconnected in onDestroy() if the Activity is torn down before its connect() callback
    // ever fires - e.g. "Don't keep activities" enabled, memory pressure, or the SDK genuinely
    // hanging. Without this, that instance (and the Activity Context it holds via SpotifyManager)
    // would stay referenced by the pending callback closure for the rest of the process's life.
    private var pendingPreAuthManager: SpotifyManager? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(applicationContext)
        requestNotificationPermissionIfNeeded()
        refreshWebApiConnectedState()

        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            TrueRadioTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DjScreen(
                        settings = settings,
                        isSpotifyWebConnected = webApiConnectedState.value,
                        isConnectingSpotifyWeb = isConnectingSpotifyWebState.value,
                        onStart = { spotifyClientId -> startDjService(spotifyClientId) },
                        onStop = { stopDjService() },
                        onConnectSpotifyWeb = { clientId -> beginSpotifyWebAuth(clientId) },
                        onDisconnectSpotifyWeb = { disconnectSpotifyWeb() }
                    )
                }
            }
        }

        handleIntentIfAuthRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        // See isConnectingSpotifyWebState's declaration: this covers both "user completed the
        // Spotify consent flow and got redirected back" (onNewIntent runs first, then onResume -
        // harmless to reset here since refreshWebApiConnectedState() already reflects the result)
        // and "user backed out of the browser without finishing" (plain onResume, no new intent -
        // this is the case that actually needs the reset, so the button isn't stuck disabled).
        isConnectingSpotifyWebState.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingPreAuthManager?.disconnect()
        pendingPreAuthManager = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentIfAuthRedirect(intent)
    }

    private fun handleIntentIfAuthRedirect(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme == "trueradio" && data.host == "spotify-web-callback") {
            lifecycleScope.launch {
                val clientId = settings.snapshotSpotifyClientId()
                if (clientId.isBlank()) return@launch
                val authManager = spotifyWebAuthManager ?: SpotifyWebAuthManager(applicationContext, settings, clientId)
                    .also { spotifyWebAuthManager = it }
                authManager.handleRedirect(data)
                refreshWebApiConnectedState()
            }
        }
    }

    private fun beginSpotifyWebAuth(clientId: String) {
        if (isConnectingSpotifyWebState.value) return // already in flight; ignore a rapid double-tap
        isConnectingSpotifyWebState.value = true
        lifecycleScope.launch {
            val authManager = SpotifyWebAuthManager(applicationContext, settings, clientId)
            spotifyWebAuthManager = authManager
            authManager.beginAuthorization()
        }
    }

    private fun disconnectSpotifyWeb() {
        lifecycleScope.launch {
            spotifyWebAuthManager?.disconnect()
            refreshWebApiConnectedState()
        }
    }

    private fun refreshWebApiConnectedState() {
        lifecycleScope.launch {
            val clientId = settings.snapshotSpotifyClientId()
            if (clientId.isBlank()) {
                webApiConnectedState.value = false
                return@launch
            }
            val authManager = spotifyWebAuthManager ?: SpotifyWebAuthManager(applicationContext, settings, clientId)
                .also { spotifyWebAuthManager = it }
            webApiConnectedState.value = authManager.isConnected()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startDjService(spotifyClientId: String) {
        // Spotify App Remote shows a one-time native authorization screen the first time a given
        // client id connects - which is an Activity launch, requiring a real Activity context to
        // display. RadioForegroundService's own connection uses applicationContext (correct for
        // a long-lived Service), but that means the very *first* authorization has nowhere to
        // show its permission screen from - the SDK doesn't surface a clean error for this, so
        // the connection just hangs forever with no visible feedback, matching the "notification
        // says starting, then nothing happens" symptom. Pre-authorizing here first, using this
        // Activity as context, lets that one-time prompt display correctly. Every subsequent
        // connection attempt (including the Service's own) then succeeds without needing to show
        // anything, since Spotify caches the grant once given.
        val preAuthManager = SpotifyManager(this, spotifyClientId)
        pendingPreAuthManager = preAuthManager
        preAuthManager.connect { _, _ ->
            // Whether this succeeded or failed, disconnect our temporary Activity-side
            // connection and let the Service establish its own long-lived one - if
            // authorization was actually denied (rather than just needing to be shown), the
            // Service's attempt will fail too, but will correctly report that failure via its
            // status/notification instead of hanging silently.
            preAuthManager.disconnect()
            pendingPreAuthManager = null
            launchForegroundService(spotifyClientId)
        }
    }

    private fun launchForegroundService(spotifyClientId: String) {
        val intent = Intent(this, RadioForegroundService::class.java).apply {
            action = RadioForegroundService.ACTION_START
            putExtra(RadioForegroundService.EXTRA_SPOTIFY_CLIENT_ID, spotifyClientId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopDjService() {
        val intent = Intent(this, RadioForegroundService::class.java).apply {
            action = RadioForegroundService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DjScreen(
    settings: SecureSettings,
    isSpotifyWebConnected: Boolean,
    isConnectingSpotifyWeb: Boolean,
    onStart: (spotifyClientId: String) -> Unit,
    onStop: () -> Unit,
    onConnectSpotifyWeb: (spotifyClientId: String) -> Unit,
    onDisconnectSpotifyWeb: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val savedSpotifyId by settings.spotifyClientId.collectAsState(initial = "")
    val savedGeminiKey by settings.geminiApiKey.collectAsState(initial = "")
    val savedElevenLabsKey by settings.elevenLabsApiKey.collectAsState(initial = "")
    val savedElevenLabsVoiceId by settings.elevenLabsVoiceId.collectAsState(initial = "")
    val savedNewsPreferences by settings.newsPreferences.collectAsState(initial = NewsPreferences())
    val savedGenreRotation by settings.genreRotation.collectAsState(initial = GenreRotation())
    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

    var spotifyClientId by remember(savedSpotifyId) { mutableStateOf(savedSpotifyId) }
    var geminiKey by remember(savedGeminiKey) { mutableStateOf(savedGeminiKey) }
    var elevenLabsKey by remember(savedElevenLabsKey) { mutableStateOf(savedElevenLabsKey) }
    var elevenLabsVoiceId by remember(savedElevenLabsVoiceId) { mutableStateOf(savedElevenLabsVoiceId) }

    var selectedCategories by remember(savedNewsPreferences) {
        mutableStateOf(savedNewsPreferences.selectedCategories)
    }
    var likedTopicsText by remember(savedNewsPreferences) {
        mutableStateOf(savedNewsPreferences.likedTopics.joinToString(", "))
    }
    var sources by remember(savedNewsPreferences) {
        mutableStateOf(savedNewsPreferences.sources)
    }
    var newSourceName by remember { mutableStateOf("") }
    var newSourceUrl by remember { mutableStateOf("") }

    var genreRotationList by remember(savedGenreRotation) { mutableStateOf(savedGenreRotation.genres) }
    var genreSequential by remember(savedGenreRotation) { mutableStateOf(savedGenreRotation.sequential) }
    var newGenreText by remember { mutableStateOf("") }

    // rememberSaveable (not remember) so this survives device rotation - with a plain `remember`,
    // rotating the screen while the DJ service is actually running in the background would reset
    // this back to false, showing "Idle" with an enabled "Connect & Start" button (inviting a
    // duplicate connection attempt) and a disabled "Disconnect" the user has no way to re-enable.
    // NOTE: this still doesn't reflect reality if the service is stopped by other means (the
    // notification's Stop button, the system killing the process, etc.) - a real fix needs the
    // service to expose its actual running state (e.g. a bound interface or a persisted flag)
    // rather than the UI tracking its own separate assumption of it.
    var isRunning by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("TrueRadio", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Status: ${if (isRunning) "On air" else "Idle"}",
            style = MaterialTheme.typography.bodyMedium
        )

        Text("Appearance", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ThemeMode.SYSTEM to "System",
                ThemeMode.LIGHT to "Light",
                ThemeMode.DARK to "Dark"
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { scope.launch { settings.saveThemeMode(mode) } },
                    label = { Text(label) }
                )
            }
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
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            value = elevenLabsKey,
            onValueChange = { elevenLabsKey = it },
            label = { Text("ElevenLabs API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            value = elevenLabsVoiceId,
            onValueChange = { elevenLabsVoiceId = it },
            label = { Text("ElevenLabs Voice ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = {
                scope.launch {
                    settings.saveAll(spotifyClientId, geminiKey, elevenLabsKey, elevenLabsVoiceId)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Keys")
        }

        Divider()
        Text("News preferences", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick the topics you want the hourly news flash to lean into.",
            style = MaterialTheme.typography.bodySmall
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NewsCategory.entries.forEach { category ->
                val isSelected = selectedCategories.contains(category)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedCategories = if (isSelected) {
                            (selectedCategories - category).ifEmpty { setOf(NewsCategory.GENERAL) }
                        } else {
                            selectedCategories + category
                        }
                    },
                    label = { Text(category.displayName) }
                )
            }
        }

        OutlinedTextField(
            value = likedTopicsText,
            onValueChange = { likedTopicsText = it },
            label = { Text("Liked topics (comma-separated)") },
            placeholder = { Text("e.g. מכבי תל אביב, בינה מלאכותית, טיילור סוויפט") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("The DJ will give extra attention to headlines matching these.") }
        )

        Button(
            onClick = {
                scope.launch {
                    val topics = likedTopicsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    settings.saveNewsPreferences(NewsPreferences(selectedCategories, topics, sources))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save News Preferences")
        }

        Divider()
        Text("News sources", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add any RSS feed URL you trust. Toggle a source off to stop pulling headlines " +
                "from it without deleting it.",
            style = MaterialTheme.typography.bodySmall
        )

        sources.forEach { source ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        source.url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = { checked ->
                        sources = sources.map { if (it.id == source.id) it.copy(enabled = checked) else it }
                    }
                )
                IconButton(onClick = { sources = sources.filterNot { it.id == source.id } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove ${source.name}")
                }
            }
        }

        Text("Add a source", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = newSourceName,
            onValueChange = { newSourceName = it },
            label = { Text("Source name") },
            placeholder = { Text("e.g. Walla, Globes, Times of Israel") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = newSourceUrl,
            onValueChange = { newSourceUrl = it },
            label = { Text("RSS feed URL") },
            placeholder = { Text("https://example.com/rss.xml") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            onClick = {
                val name = newSourceName.trim()
                val url = newSourceUrl.trim()
                if (name.isNotBlank() && url.isNotBlank()) {
                    sources = sources + NewsSource(id = UUID.randomUUID().toString(), name = name, url = url)
                    newSourceName = ""
                    newSourceUrl = ""
                }
            },
            enabled = newSourceName.isNotBlank() && newSourceUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Source")
        }
        Button(
            onClick = {
                scope.launch {
                    val topics = likedTopicsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    settings.saveNewsPreferences(NewsPreferences(selectedCategories, topics, sources))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save News Sources")
        }

        Divider()
        Text("Personalized hourly genre mix", style = MaterialTheme.typography.titleMedium)
        Text(
            if (isSpotifyWebConnected) {
                "Connected. Every hour the DJ rebuilds a private playlist from your own top " +
                    "artists/tracks filtered to that hour's genre, then switches Spotify to it."
            } else {
                "Connect your Spotify account (separate from the player connection below) so " +
                    "the hourly genre switch can pull from your actual listening history " +
                    "instead of a generic playlist. Needs read access to your top artists/tracks " +
                    "and permission to manage one private playlist."
            },
            style = MaterialTheme.typography.bodySmall
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
                    isSpotifyWebConnected -> "Disconnect Spotify Account"
                    else -> "Connect Spotify Account"
                }
            )
        }

        Text("Genre rotation", style = MaterialTheme.typography.labelLarge)
        Text(
            "The genre for hour H is genres[H mod size] when sequential, or a fixed-per-hour " +
                "random pick otherwise. Use genre names the way Spotify tags artists (e.g. " +
                "\"pop\", \"hip hop\", \"lo-fi\", \"reggaeton\", \"mizrahi\").",
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            genreRotationList.forEach { genre ->
                InputChip(
                    selected = false,
                    onClick = { genreRotationList = genreRotationList.filterNot { it == genre } },
                    label = { Text(genre) },
                    trailingIcon = { Icon(Icons.Default.Delete, contentDescription = "Remove $genre") }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newGenreText,
                onValueChange = { newGenreText = it },
                label = { Text("Add genre") },
                placeholder = { Text("e.g. house") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = {
                val genre = newGenreText.trim().lowercase()
                if (genre.isNotBlank() && genre !in genreRotationList) {
                    genreRotationList = genreRotationList + genre
                }
                newGenreText = ""
            }) {
                Text("Add")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Cycle in order (off = random per hour)", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = genreSequential, onCheckedChange = { genreSequential = it })
        }
        Button(
            onClick = {
                scope.launch {
                    settings.saveGenreRotation(GenreRotation(genreRotationList, genreSequential))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Genre Rotation")
        }

        Divider()

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    // Persist whatever is currently in all four fields before starting, rather
                    // than relying on the user having separately tapped "Save Keys" first. The
                    // service reads Gemini/ElevenLabs keys straight from persisted settings (only
                    // the Spotify Client ID gets passed as a live override) - skipping this meant
                    // someone who filled in the fields and went straight to "Connect & Start"
                    // would get music playing fine but the DJ silently never speaking, with no
                    // obvious reason why.
                    scope.launch {
                        settings.saveAll(spotifyClientId, geminiKey, elevenLabsKey, elevenLabsVoiceId)
                        isRunning = true
                        onStart(spotifyClientId)
                    }
                },
                enabled = !isRunning && spotifyClientId.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Connect & Start")
            }
            OutlinedButton(
                onClick = {
                    isRunning = false
                    onStop()
                },
                enabled = isRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("Disconnect")
            }
        }

        Divider()
        Text(
            "The DJ runs in a foreground service once started, so it keeps talking " +
                "between tracks and at the top of every hour even with the screen locked. " +
                "Pull down the notification shade for Play/Pause/Stop controls.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
