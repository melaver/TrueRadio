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

    private const val MAX_HISTORY = 200


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

    /** True when repeated 429s suggest the daily quota is gone, not just a momentary burst. */
    private val _dailyQuotaExhausted = MutableStateFlow(false)
    val dailyQuotaExhausted: StateFlow<Boolean> = _dailyQuotaExhausted.asStateFlow()
    internal fun setDailyQuotaExhausted(v: Boolean) { _dailyQuotaExhausted.value = v }

    /**
     * Rolling log of what played and what the DJ said. Exists because spoken content is
     * otherwise unrecoverable - if the DJ mentions something interesting while you're driving,
     * there's no way back to it. Capped so a long session can't grow memory unbounded.
     */
    data class HistoryEntry(
        val timestampMs: Long,
        val isDjLine: Boolean,
        val text: String
    )

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    internal fun addHistory(isDjLine: Boolean, text: String) {
        if (text.isBlank()) return
        val entry = HistoryEntry(System.currentTimeMillis(), isDjLine, text)
        // Skip consecutive duplicates - PlayerState can report the same track repeatedly.
        if (_history.value.firstOrNull()?.text == text) return
        _history.value = (listOf(entry) + _history.value).take(MAX_HISTORY)
    }

    /** Minutes remaining on the sleep timer, or null when no timer is set. */
    private val _sleepMinutesRemaining = MutableStateFlow<Int?>(null)
    val sleepMinutesRemaining: StateFlow<Int?> = _sleepMinutesRemaining.asStateFlow()
    internal fun setSleepMinutesRemaining(v: Int?) { _sleepMinutesRemaining.value = v }

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
