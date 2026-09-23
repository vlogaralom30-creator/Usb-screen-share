package com.example.usb.model

/**
 * Calculates coordinate mapping between Phone 2's display viewport and Phone 1's native screen dimensions.
 * Eliminates letterboxing / pillarboxing padding, normalizes to 0.0–1.0, and accurately maps back to Host pixels.
 * Correctly accounts for portrait, landscape, varying resolutions, and display scaling.
 */
data class ViewportBounds(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val streamWidth: Float,
    val streamHeight: Float
) {
    val contentLeft: Float
    val contentTop: Float
    val contentWidth: Float
    val contentHeight: Float

    init {
        val safeStreamW = if (streamWidth > 0f) streamWidth else 1080f
        val safeStreamH = if (streamHeight > 0f) streamHeight else 1920f
        val streamAspect = safeStreamW / safeStreamH

        val safeVpW = if (viewportWidth > 0f) viewportWidth else safeStreamW
        val safeVpH = if (viewportHeight > 0f) viewportHeight else safeStreamH
        val viewportAspect = safeVpW / safeVpH

        if (viewportAspect > streamAspect) {
            // Viewport is wider than stream: Pillarboxed (black borders on left and right)
            contentHeight = safeVpH
            contentWidth = safeVpH * streamAspect
            contentTop = 0f
            contentLeft = (safeVpW - contentWidth) / 2f
        } else {
            // Viewport is taller than stream: Letterboxed (black borders on top and bottom)
            contentWidth = safeVpW
            contentHeight = safeVpW / streamAspect
            contentLeft = 0f
            contentTop = (safeVpH - contentHeight) / 2f
        }
    }

    /**
     * Converts a local touch coordinate (x, y) on the Phone 2 viewport
     * into normalized (0.0 to 1.0) coordinates within Phone 1's active stream rectangle.
     * Clamps within [0.0, 1.0].
     */
    fun toNormalized(touchX: Float, touchY: Float): Pair<Float, Float> {
        val normX = if (contentWidth > 0f) {
            ((touchX - contentLeft) / contentWidth).coerceIn(0f, 1f)
        } else 0f

        val normY = if (contentHeight > 0f) {
            ((touchY - contentTop) / contentHeight).coerceIn(0f, 1f)
        } else 0f

        return Pair(normX, normY)
    }

    /**
     * Converts normalized coordinates back to host display pixels.
     */
    fun toHostPixels(normX: Float, normY: Float, hostWidth: Int, hostHeight: Int): Pair<Float, Float> {
        val hx = (normX * hostWidth).coerceIn(0f, hostWidth.toFloat())
        val hy = (normY * hostHeight).coerceIn(0f, hostHeight.toFloat())
        return Pair(hx, hy)
    }
}

object CoordinateMapper {
    fun toHostPixel(normX: Float, normY: Float, hostWidth: Int, hostHeight: Int): Pair<Float, Float> {
        val hx = (normX * hostWidth).coerceIn(0f, hostWidth.toFloat())
        val hy = (normY * hostHeight).coerceIn(0f, hostHeight.toFloat())
        return Pair(hx, hy)
    }
}
