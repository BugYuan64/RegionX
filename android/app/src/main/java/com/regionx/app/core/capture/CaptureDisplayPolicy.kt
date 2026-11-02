package com.regionx.app.core.capture

/** One policy per consent token; even a failed CREATE must never be retried with that token. */
class CaptureDisplayPolicy {
    enum class Action { CREATE, ATTACH, RESIZE }
    private data class Size(val width: Int, val height: Int, val densityDpi: Int)
    private var size: Size? = null
    private var closed = false

    fun configure(width: Int, height: Int, densityDpi: Int): Action {
        check(!closed) { "Projection session has stopped" }
        require(width > 0 && height > 0 && densityDpi > 0)
        val next = Size(width, height, densityDpi)
        val action = when {
            size == null -> Action.CREATE
            size == next -> Action.ATTACH
            else -> Action.RESIZE
        }
        size = next
        return action
    }

    fun close() { closed = true }
}
