package com.diego.kiki.automation

/**
 * Smart-stop rule for capture sweeps during auto-scroll: when N consecutive
 * swipes surface no new media capture, the feed has likely ended.
 */
object SwipeSmartStop {
    const val STALLED_LIMIT = 5

    /** Stalled counter after a swipe: reset to 0 whenever new media appeared. */
    fun nextStalled(current: Int, captureGrew: Boolean): Int = if (captureGrew) 0 else current + 1

    fun shouldStop(stalled: Int): Boolean = stalled >= STALLED_LIMIT
}
