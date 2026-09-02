package com.trueradio.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.trueradio.app.SecureSettings
import com.trueradio.app.ThemeMode
import com.trueradio.app.service.RadioForegroundService
import com.trueradio.app.service.RadioServiceState
import com.trueradio.app.spotify.SpotifyManager
import com.trueradio.app.spotify.SpotifyWebAuthManager
import com.trueradio.app.ui.nav.TrueRadioNavHost
import com.trueradio.app.ui.theme.TrueRadioTheme
import kotlinx.coroutines.launch

/**
 * Thin shell: owns Android-level concerns (permissions, OAuth redirects, service start/stop,
 * App Remote pre-authorization) and delegates all UI to the nav graph. Screen content lives in
 * ui/onboarding, ui/dashboard and ui/settings.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var settings: SecureSettings
    private var spotifyWebAuthManager: SpotifyWebAuthManager? = null
    private val webApiConnectedState = mutableStateOf(false)

    // Guards against a double-tap firing two authorizations: the second would overwrite the saved
    // PKCE verifier and break the first flow's token exchange. Reset in onResume so backing out
    // of the browser doesn't leave the button permanently disabled.
    private val isConnectingSpotifyWebState = mutableStateOf(false)

    // Held so it can be disconnected if the Activity dies before its callback fires (e.g. "Don't
    // keep activities"), which would otherwise leak the Activity via the pending closure.
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
                    TrueRadioNavHost(
                        settings = settings,
                        isSpotifyWebConnected = webApiConnectedState.value,
                        isConnectingSpotifyWeb = isConnectingSpotifyWebState.value,
                        onConnectSpotifyWeb = { clientId -> beginSpotifyWebAuth(clientId) },
                        onDisconnectSpotifyWeb = { disconnectSpotifyWeb() },
                        onStartRadio = { clientId -> startDjService(clientId) },
                        onStopRadio = { stopDjService() },
                        onLike = { sendServiceAction(RadioForegroundService.ACTION_LIKE) },
                        onDislike = { sendServiceAction(RadioForegroundService.ACTION_DISLIKE) },
                        onForceDj = { sendServiceAction(RadioForegroundService.ACTION_FORCE_DJ) },
                        onRemix = { sendServiceAction(RadioForegroundService.ACTION_REMIX) },
                        onForceNews = { sendServiceAction(RadioForegroundService.ACTION_FORCE_NEWS) },
                        onSetSleepTimer = { minutes -> setSleepTimer(minutes) }
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

    override fun onResume() {
        super.onResume()
        isConnectingSpotifyWebState.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingPreAuthManager?.disconnect()
        pendingPreAuthManager = null
    }

    private fun handleIntentIfAuthRedirect(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme == "trueradio" && data.host == "spotify-web-callback") {
            lifecycleScope.launch {
                val clientId = settings.snapshotSpotifyClientId()
                if (clientId.isBlank()) {
                    Log.e(TAG, "OAuth redirect received but no client id is saved")
                    return@launch
                }
                val authManager = spotifyWebAuthManager
                    ?: SpotifyWebAuthManager(applicationContext, settings, clientId)
                        .also { spotifyWebAuthManager = it }
                authManager.handleRedirect(data)
                refreshWebApiConnectedState()
            }
        }
    }

    private fun beginSpotifyWebAuth(clientId: String) {
        if (isConnectingSpotifyWebState.value) return
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
            val authManager = spotifyWebAuthManager
                ?: SpotifyWebAuthManager(applicationContext, settings, clientId)
                    .also { spotifyWebAuthManager = it }
            webApiConnectedState.value = authManager.isConnected()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * App Remote shows a one-time native authorization screen on first connect, which is an
     * Activity launch and so needs an Activity context. The service connects with
     * applicationContext (correct for a long-lived service), leaving that first prompt nowhere to
     * display - the SDK surfaces no error, it just hangs. Pre-authorizing here with `this` lets
     * the prompt appear; later connections reuse the cached grant silently.
     */
    private fun startDjService(spotifyClientId: String) {
        if (spotifyClientId.isBlank()) {
            Log.e(TAG, "Refusing to start radio: no Spotify client id")
            return
        }
        Log.d(TAG, "Pre-authorizing App Remote before starting service")
        try {
            val preAuthManager = SpotifyManager(this, spotifyClientId)
            pendingPreAuthManager = preAuthManager
            // connect() itself - not just its callback below - calls straight into the Spotify
            // App Remote SDK (SpotifyAppRemote.connect) on this thread. A throw from THIS call
            // (as opposed to the async success/failure callback it registers) previously had no
            // guard at all - the callback's own try/catch only ever runs if connect() gets far
            // enough to register it.
            preAuthManager.connect { success, error ->
                Log.d(TAG, "Pre-auth finished (success=$success, error=${error?.message})")
                // This callback is unguarded SDK-invoked code, not something already wrapped by
                // the framework - an uncaught throw here crashes the whole app. In particular,
                // showAuthView(true) can briefly hand focus to Spotify's own consent screen on
                // first authorization; if the OS considers this Activity no longer eligible to
                // start a foreground service by the time this callback fires,
                // startForegroundService() below can throw ForegroundServiceStartNotAllowedException
                // (API 31+) - an instant crash right where the user just tapped "start". Catching
                // it trades that crash for a visible message and a chance to just tap again.
                try {
                    preAuthManager.disconnect()
                    pendingPreAuthManager = null
                    launchForegroundService(spotifyClientId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start the radio service after Spotify pre-auth", e)
                    Toast.makeText(
                        this,
                        "Couldn't start the radio: ${e.message}. Try tapping start again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to begin Spotify pre-authorization", e)
            pendingPreAuthManager = null
            Toast.makeText(this, "Couldn't connect to Spotify: ${e.message}", Toast.LENGTH_LONG).show()
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

    /** Forwards a like/dislike to the running service; ignored if the radio isn't on. */
    private fun sendServiceAction(action: String) {
        if (!RadioServiceState.isRunning.value) {
            Log.d(TAG, "Ignoring $action - service isn't running")
            return
        }
        startService(Intent(this, RadioForegroundService::class.java).apply { this.action = action })
    }

    /** Sleep timer only makes sense while the radio is live; 0 cancels. */
    private fun setSleepTimer(minutes: Int) {
        if (!RadioServiceState.isRunning.value) {
            Log.d(TAG, "Sleep timer ignored - service isn't running")
            return
        }
        startService(Intent(this, RadioForegroundService::class.java).apply {
            action = RadioForegroundService.ACTION_SET_SLEEP_TIMER
            putExtra(RadioForegroundService.EXTRA_SLEEP_MINUTES, minutes)
        })
    }

    private fun stopDjService() {
        // Guard: startService() on a dead service would CREATE it (running onCreate ->
        // startForeground) only to immediately stopSelf() on the STOP action - flashing a
        // notification and briefly reporting itself as running. Only send the stop if it's
        // actually alive.
        if (!RadioServiceState.isRunning.value) {
            Log.d(TAG, "Stop requested but service isn't running; ignoring")
            return
        }
        val intent = Intent(this, RadioForegroundService::class.java).apply {
            action = RadioForegroundService.ACTION_STOP
        }
        startService(intent)
    }
}
