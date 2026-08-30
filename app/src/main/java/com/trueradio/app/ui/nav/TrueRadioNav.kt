package com.trueradio.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trueradio.app.SecureSettings
import com.trueradio.app.ui.dashboard.DashboardScreen
import com.trueradio.app.ui.onboarding.OnboardingFlow
import com.trueradio.app.ui.settings.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
}

/**
 * Top-level navigation. The start destination depends on whether onboarding has been completed,
 * which lives in DataStore and therefore arrives asynchronously - so the NavHost isn't composed
 * at all until that value resolves. Composing with a guessed default and correcting later would
 * flash the wrong screen and push a bogus entry onto the back stack.
 */
@Composable
fun TrueRadioNavHost(
    settings: SecureSettings,
    isSpotifyWebConnected: Boolean,
    isConnectingSpotifyWeb: Boolean,
    onConnectSpotifyWeb: (String) -> Unit,
    onDisconnectSpotifyWeb: () -> Unit,
    onStartRadio: (String) -> Unit,
    onStopRadio: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onForceDj: () -> Unit,
    onRemix: () -> Unit
) {
    // null = still loading; avoids rendering a start destination we might have to change.
    val onboardingComplete by produceState<Boolean?>(initialValue = null) {
        value = settings.snapshotOnboardingComplete()
    }
    val resolved = onboardingComplete
    if (resolved == null) {
        // Brief, but returning nothing here would flash a blank screen on every cold start.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = if (resolved) Routes.DASHBOARD else Routes.ONBOARDING
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingFlow(
                settings = settings,
                isSpotifyWebConnected = isSpotifyWebConnected,
                isConnectingSpotifyWeb = isConnectingSpotifyWeb,
                onConnectSpotifyWeb = onConnectSpotifyWeb,
                onFinished = {
                    navController.navigate(Routes.DASHBOARD) {
                        // Drop onboarding from the back stack so Back from the dashboard exits
                        // the app rather than replaying setup.
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                settings = settings,
                onStartRadio = onStartRadio,
                onStopRadio = onStopRadio,
                onLike = onLike,
                onDislike = onDislike,
                onRemix = onRemix,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = settings,
                isSpotifyWebConnected = isSpotifyWebConnected,
                isConnectingSpotifyWeb = isConnectingSpotifyWeb,
                onConnectSpotifyWeb = onConnectSpotifyWeb,
                onDisconnectSpotifyWeb = onDisconnectSpotifyWeb,
                onForceDj = onForceDj,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
