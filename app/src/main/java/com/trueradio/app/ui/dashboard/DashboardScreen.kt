package com.trueradio.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trueradio.app.SecureSettings
import com.trueradio.app.service.RadioServiceState

/**
 * Deliberately sparse: one large primary action, with status underneath. Everything configurable
 * lives behind the gear icon so the main screen stays a single obvious decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    settings: SecureSettings,
    onStartRadio: (String) -> Unit,
    onStopRadio: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // Sourced from the service itself, so it stays accurate across rotation and when the service
    // is stopped from its notification - a UI-local flag could track neither.
    val isRunning by RadioServiceState.isRunning.collectAsState()
    val status by RadioServiceState.status.collectAsState()
    val nowPlaying by RadioServiceState.nowPlaying.collectAsState()

    val spotifyClientId by settings.spotifyClientId.collectAsState(initial = "")
    val geminiKey by settings.geminiApiKey.collectAsState(initial = "")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TrueRadio") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = { if (isRunning) onStopRadio() else onStartRadio(spotifyClientId) },
                enabled = spotifyClientId.isNotBlank(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.size(180.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isRunning) "Stop Radio" else "Start Radio",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                nowPlaying ?: if (isRunning) "Waiting for Spotify..." else "Not playing",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Feedback controls only appear while something is actually playing - there's
            // nothing to rate otherwise, and a dead button invites confusion.
            if (isRunning && nowPlaying != null) {
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalButton(onClick = onLike) {
                        Icon(Icons.Default.ThumbUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Like")
                    }
                    FilledTonalButton(onClick = onDislike) {
                        Icon(Icons.Default.ThumbDown, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Skip")
                    }
                }
                Text(
                    "Likes shape future mixes; skips stop that track coming back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Surface setup gaps here rather than letting the radio start and fail silently later.
            if (spotifyClientId.isBlank() || geminiKey.isBlank()) {
                Spacer(Modifier.height(24.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            when {
                                spotifyClientId.isBlank() -> "Spotify Client ID missing"
                                else -> "Gemini API key missing - the DJ won't speak"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                    }
                }
            }
        }
    }
}
