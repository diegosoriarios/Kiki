package com.diego.kiki.automation

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import com.diego.kiki.browser.TabState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Swipe-gesture auto-scroll: the user places two pins (start/end as fractions
 * of the WebView size) on an overlay, and the engine replays a synthetic touch
 * swipe between them every [State.intervalMs]. Because the events are real
 * MotionEvents, galleries and carousels that ignore scrollTop/scrollLeft react
 * exactly as they would to a finger. Kotlin drives the loop so stop is
 * deterministic; each tick reports a swipe, letting the caller implement a
 * smart stop ("no new media for N swipes").
 */
class AutoScroller(
    private val scope: CoroutineScope,
    private val tabResolver: (tabId: String) -> TabState?
) {

    enum class StopReason { MANUAL, TAB_GONE, SMART_STOP }

    data class State(
        val tabId: String? = null,
        val running: Boolean = false,
        val intervalMs: Long = DEFAULT_INTERVAL_MS,
        val swipeCount: Int = 0,
        val stalledSwipes: Int = 0,
        val awaitingPins: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Fired after each completed gesture swipe. */
    var onSwipe: ((tabId: String) -> Unit)? = null

    /** Fired once when the loop ends, with the reason. */
    var onFinished: ((tabId: String, StopReason) -> Unit)? = null

    private var job: Job? = null

    fun beginSetup(tabId: String, intervalMs: Long) {
        stop(silent = true)
        _state.value = State(
            tabId = tabId,
            intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            awaitingPins = true
        )
    }

    fun cancelSetup() {
        if (!_state.value.awaitingPins) return
        _state.value = State(intervalMs = _state.value.intervalMs)
    }

    fun start(tabId: String, intervalMs: Long, pins: GesturePins) {
        stop(silent = true)
        _state.value = State(
            tabId = tabId,
            running = true,
            intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            swipeCount = 0,
            stalledSwipes = 0
        )
        job = scope.launch { runLoop(tabId, pins) }
    }

    fun setInterval(intervalMs: Long) {
        _state.value = _state.value.copy(
            intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        )
    }

    /** Caller-tracked stall counter (swipes without new captures). */
    fun setStalled(value: Int) {
        _state.value = _state.value.copy(stalledSwipes = value)
    }

    fun stop(reason: StopReason = StopReason.MANUAL, silent: Boolean = false) {
        job?.cancel()
        job = null
        val previous = _state.value
        val tabId = previous.tabId
        _state.value = State(intervalMs = previous.intervalMs)
        if (!silent && tabId != null && previous.running) {
            onFinished?.invoke(tabId, reason)
        }
    }

    private suspend fun runLoop(tabId: String, pins: GesturePins) {
        while (true) {
            val current = _state.value
            if (!current.running) return
            val webView = tabResolver(tabId)?.webView ?: run {
                finish(tabId, StopReason.TAB_GONE)
                return
            }
            performSwipe(webView, pins)
            _state.value = current.copy(swipeCount = current.swipeCount + 1)
            onSwipe?.invoke(tabId)
            delay(_state.value.intervalMs)
        }
    }

    /** Replays one synthetic swipe (DOWN → interpolated MOVEs → UP). */
    private suspend fun performSwipe(webView: WebView, pins: GesturePins) {
        val x0 = (pins.startX * webView.width).coerceIn(0f, webView.width.toFloat())
        val y0 = (pins.startY * webView.height).coerceIn(0f, webView.height.toFloat())
        val x1 = (pins.endX * webView.width).coerceIn(0f, webView.width.toFloat())
        val y1 = (pins.endY * webView.height).coerceIn(0f, webView.height.toFloat())

        val points = interpolateSwipe(x0, y0, x1, y1, SWIPE_STEPS)
        val downTime = SystemClock.uptimeMillis()
        var elapsed = 0L

        points.forEachIndexed { index, (x, y) ->
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            dispatchTouch(webView, action, downTime + elapsed, x, y)
            if (index != points.lastIndex) {
                delay(SWIPE_STEP_DELAY_MS)
                elapsed += SWIPE_STEP_DELAY_MS
            }
        }
    }

    private fun dispatchTouch(webView: WebView, action: Int, eventTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(eventTime, eventTime, action, x, y, 0)
        try {
            webView.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun finish(tabId: String, reason: StopReason) {
        job?.cancel()
        job = null
        val previous = _state.value
        _state.value = State(intervalMs = previous.intervalMs)
        onFinished?.invoke(tabId, reason)
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1000L
        const val MIN_INTERVAL_MS = 400L
        const val MAX_INTERVAL_MS = 5000L

        /** Swipe duration ≈ SWIPE_STEPS × SWIPE_STEP_DELAY_MS (~190ms, human-like). */
        const val SWIPE_STEPS = 12
        const val SWIPE_STEP_DELAY_MS = 16L
        const val MIN_PIN_DISTANCE_PX = 40f
    }
}

/**
 * A swipe path defined by two pins, in fractions of the WebView size
 * (0..1) so it survives rotation and layout changes.
 */
data class GesturePins(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

/** Pure geometry: points along the pin-to-pin path, inclusive of both ends. */
fun interpolateSwipe(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    steps: Int
): List<Pair<Float, Float>> {
    require(steps >= 1) { "steps must be >= 1" }
    return (0..steps).map { i ->
        val t = i.toFloat() / steps
        (startX + (endX - startX) * t) to (startY + (endY - startY) * t)
    }
}

/** Pin distance in pixels — used to reject near-tap placements. */
fun pinDistancePx(pins: GesturePins, widthPx: Float, heightPx: Float): Float =
    hypot((pins.endX - pins.startX) * widthPx, (pins.endY - pins.startY) * heightPx)
