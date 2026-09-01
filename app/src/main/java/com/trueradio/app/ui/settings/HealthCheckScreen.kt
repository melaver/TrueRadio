package com.trueradio.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trueradio.app.SecureSettings
import com.trueradio.app.ai.GeminiClient
import com.trueradio.app.service.RadioServiceState

/**
 * One screen showing whether each dependency is actually configured and working.
 *
 * Exists because diagnosing setup problems previously meant reading Logcat over USB - the app
 * knew perfectly well which piece was missing but never said so. Everything here is read from
 * existing state; nothing makes a network call, so opening this screen costs no quota.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCheckScreen(
    settings: SecureSettings,
    isSpotifyWebConnected: Boolean,
    onBack: () -> Unit
) {
    val spotifyClientId by settings.spotifyClientId.collectAsState(initial = "")
    val geminiKey by settings.geminiApiKey.collectAsState(initial = "")
    val cloudTtsKey by settings.cloudTtsKey.collectAsState(initial = "")
    val isRunning by RadioServiceState.isRunning.collectAsState()
    val isRateLimited by RadioServiceState.isRateLimited.collectAsState()
    val dailyQuotaExhausted by RadioServiceState.dailyQuotaExhausted.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup check") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CheckRow(
                label = "Spotify Client ID",
                ok = spotifyClientId.isNotBlank(),
                okText = "Configured",
                failText = "Missing - the radio can't start without it"
            )
            CheckRow(
                label = "Spotify account (Web API)",
                ok = isSpotifyWebConnected,
                okText = "Connected - mixes use your listening history",
                failText = "Not connected - falling back to generic playlists"
            )
            CheckRow(
                label = "Gemini API key",
                ok = geminiKey.isNotBlank(),
                okText = "Configured - DJ writes its own lines",
                failText = "Missing - DJ will only use templated lines"
            )
            CheckRow(
                label = "Gemini quota",
                ok = !isRateLimited,
                okText = "Available",
                failText = if (dailyQuotaExhausted) "Daily quota exhausted - DJ in offline mode"
                else "Temporarily rate limited - recovering"
            )
            CheckRow(
                label = "Google Cloud TTS",
                ok = cloudTtsKey.isNotBlank(),
                okText = "Configured - premium DJ voice",
                failText = "Not set - using your device's built-in voice"
            )
            CheckRow(
                label = "Radio service",
                ok = isRunning,
                okText = "Running",
                failText = "Stopped"
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Anything marked with a warning still lets the app run - it just falls back to a " +
                    "simpler behaviour. Only a missing Spotify Client ID prevents starting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, okText: String, failText: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                if (ok) okText else failText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
