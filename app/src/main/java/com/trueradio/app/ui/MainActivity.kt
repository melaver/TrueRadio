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
import com.trueradio.app.service.RadioForegroundService
import com.trueradio.app.spotify.SpotifyWebAuthManager
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var settings: SecureSettings
    private var spotifyWebAuthManager: SpotifyWebAuthManager? = null
    private val webApiConnectedState = mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(applicationContext)
        requestNotificationPermissionIfNeeded()
        refreshWebApiConnectedState()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DjScreen(
                        settings = settings,
                        isSpotifyWebConnected = webApiConnectedState.value,
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

    var isRunning by remember { mutableStateOf(false) }

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
            enabled = spotifyClientId.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSpotifyWebConnected) "Disconnect Spotify Account" else "Connect Spotify Account")
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
                    isRunning = true
                    onStart(spotifyClientId)
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
