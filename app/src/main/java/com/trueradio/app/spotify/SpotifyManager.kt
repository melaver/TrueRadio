package com.trueradio.app.spotify

import android.content.Context
import android.util.Log
import com.trueradio.app.DaySegment
import com.trueradio.app.TrackInfo
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Thin wrapper around the Spotify App Remote SDK.
 *
 * Requires:
 *  - The official Spotify app installed and the user logged in on-device.
 *  - A registered app + redirect URI in the Spotify Developer Dashboard (see README).
 *  - spotify-app-remote-release-x.x.x.aar dropped into app/libs (App Remote is not on Maven).
 */
class SpotifyManager(
    private val context: Context,
    private val clientId: String,
    private val redirectUri: String = "trueradio://callback"
) {
    private var appRemote: SpotifyAppRemote? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    fun connect(onResult: (success: Boolean, error: Throwable?) -> Unit) {
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                appRemote = spotifyAppRemote
                _connectionState.value = true
                onResult(true, null)
            }

            override fun onFailure(throwable: Throwable) {
                Log.e(TAG, "Spotify connection failed", throwable)
                _connectionState.value = false
                onResult(false, throwable)
            }
        })
    }

    fun disconnect() {
        appRemote?.let { SpotifyAppRemote.disconnect(it) }
        appRemote = null
        _connectionState.value = false
    }

    fun isConnected(): Boolean = appRemote?.isConnected == true

    /** Emits every PlayerState update pushed by the Spotify app in near real time. */
    fun observePlayerState(): Flow<TrackInfo> = callbackFlow {
        val remote = appRemote
        if (remote == null) {
            close(IllegalStateException("Spotify App Remote is not connected"))
            return@callbackFlow
        }
        val subscription = remote.playerApi.subscribeToPlayerState()
        subscription.setEventCallback { state: PlayerState ->
            val track = state.track
            if (track != null) {
                trySend(
                    TrackInfo(
                        uri = track.uri,
                        title = track.name,
                        artist = track.artist.name,
                        durationMs = track.duration,
                        positionMs = state.playbackPosition,
                        isPaused = state.isPaused
                    )
                )
            }
        }
        subscription.setErrorCallback { close(it) }
        awaitClose { subscription.cancel() }
    }

    fun play() = appRemote?.playerApi?.resume()
    fun pause() = appRemote?.playerApi?.pause()
    fun skipNext() = appRemote?.playerApi?.skipNext()

    /** Plays any Spotify URI directly - used by the hourly genre-mix engine to switch to the freshly built playlist. */
    fun playUri(uri: String) = appRemote?.playerApi?.play(uri)

    /** Plays a curated fallback playlist for the current daypart, used only until Spotify Web API is connected. */
    fun playForSegment(segment: DaySegment) {
        // Fallback for when the user hasn't connected the Spotify Web API yet (see
        // SpotifyWebAuthManager / HourlyMixEngine for the personalized, genre-aware version).
        // App Remote alone can only play known URIs directly, not resolve free-text queries.
        val playlistUri = when (segment) {
            DaySegment.MORNING -> "spotify:playlist:37i9dQZF1DX0UrRvztWcAU"
            DaySegment.AFTERNOON -> "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
            DaySegment.EVENING -> "spotify:playlist:37i9dQZF1DX4WYpdgoIcn6"
            DaySegment.NIGHT -> "spotify:playlist:37i9dQZF1DWZd79rJ6a7lp"
        }
        appRemote?.playerApi?.play(playlistUri)
    }

    companion object {
        private const val TAG = "SpotifyManager"
    }
}
