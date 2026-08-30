package com.trueradio.app.service

import com.trueradio.app.DaySegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between [RadioForegroundService] and the Compose UI.
 *
 * The service previously kept its status in a private StateFlow that nothing could observe, so
 * every failure it reported was invisible to the user. Binding the service to the Activity would
 * be the textbook fix, but it adds lifecycle complexity (bind/unbind across rotation, null
 * binders during teardown) for what is one-way, read-only status text. A process-scoped object is
 * simpler and safe here because the service and UI always live in the same process.
 *
 * IMPORTANT: [isRunning] reflects the service's *own* view of itself - set true in onCreate and
 * false in onDestroy - so it stays correct even when the service is stopped from the notification
 * or killed by the system, which a UI-local boolean could never track.
 */
object RadioServiceState {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()

    /** Current daypart, e.g. MORNING - drives the greeting on the dashboard. */
    private val _daySegment = MutableStateFlow(DaySegment.forHour(
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    ))
    val daySegment: StateFlow<DaySegment> = _daySegment.asStateFlow()

    /** Genre the current hour's mix was built around; null before the first mix is built. */
    private val _currentGenre = MutableStateFlow<String?>(null)
    val currentGenre: StateFlow<String?> = _currentGenre.asStateFlow()

    internal fun setDaySegment(segment: DaySegment) { _daySegment.value = segment }
    internal fun setCurrentGenre(genre: String?) { _currentGenre.value = genre }

    /** True while Gemini calls are locally suppressed after a 429; UI explains the degraded mode. */
    private val _isRateLimited = MutableStateFlow(false)
    val isRateLimited: StateFlow<Boolean> = _isRateLimited.asStateFlow()
    internal fun setRateLimited(limited: Boolean) { _isRateLimited.value = limited }

    internal fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _status.value = "Idle"
            _nowPlaying.value = null
            _currentGenre.value = null
        }
    }

    internal fun setStatus(status: String) {
        _status.value = status
    }

    internal fun setNowPlaying(track: String?) {
        _nowPlaying.value = track
    }
}
