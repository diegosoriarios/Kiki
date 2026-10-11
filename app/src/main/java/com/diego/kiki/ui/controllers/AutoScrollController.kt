package com.diego.kiki.ui.controllers

import com.diego.kiki.automation.AutoScroller
import com.diego.kiki.automation.GesturePins
import com.diego.kiki.automation.SwipeSmartStop
import com.diego.kiki.browser.TabManager
import com.diego.kiki.download.MediaSniffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the AutoScroller and the capture-sweep smart stop: every swipe re-scans
 * the page DOM (slide shows mount/unmount their videos as you scroll) and N
 * stagnant swipes end the run. Panel visibility is the ViewModel's concern.
 */
class AutoScrollController(
    scope: CoroutineScope,
    private val tabManager: TabManager,
    private val activeTabId: StateFlow<String>
) {
    val autoScroller = AutoScroller(scope) { tabId ->
        tabManager.tabs.value.find { it.id == tabId }
    }

    private var lastCaptureCount = 0

    fun resetCaptureTracking() {
        lastCaptureCount = 0
    }

    fun setInterval(intervalMs: Long) = autoScroller.setInterval(intervalMs)

    fun beginSetup() {
        val tabId = activeTabId.value
        if (tabId.isEmpty()) return
        autoScroller.beginSetup(tabId, autoScroller.state.value.intervalMs)
    }

    fun cancelSetup() = autoScroller.cancelSetup()

    fun start(pins: GesturePins) {
        val tabId = activeTabId.value
        if (tabId.isEmpty()) return
        autoScroller.start(tabId, autoScroller.state.value.intervalMs, pins)
    }

    fun stop() = autoScroller.stop(AutoScroller.StopReason.MANUAL)

    /** Wire to [AutoScroller.onSwipe]. */
    fun onSwipe(tabId: String) {
        // Re-scan the DOM each swipe so the current slide's video is
        // captured while it is still mounted (blob URLs get revoked later)
        tabManager.tabs.value.find { it.id == tabId }?.webView
            ?.evaluateJavascript(MediaSniffer.INJECT_JS, null)

        val count = captureCountFor(tabId)
        val stalled = SwipeSmartStop.nextStalled(
            autoScroller.state.value.stalledSwipes,
            captureGrew = count > lastCaptureCount
        )
        autoScroller.setStalled(stalled)
        if (SwipeSmartStop.shouldStop(stalled)) {
            autoScroller.stop(AutoScroller.StopReason.SMART_STOP)
        }
        lastCaptureCount = count
    }

    private fun captureCountFor(tabId: String): Int {
        val domCount = tabManager.mediaCandidates.value[tabId]?.size ?: 0
        val capture = tabManager.segmentRegistry.captureFor(tabId)
        return domCount + capture.playlists.size + capture.mediaGroups.size + capture.segmentGroups.size
    }
}
