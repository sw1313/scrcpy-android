package com.scrcpyandroid.video

/**
 * Computes a letterboxed/pillarboxed destination rect that fills by the longer
 * edge while preserving aspect ratio.
 */
object AspectFit {
    data class Rect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    fun compute(
        containerWidth: Float,
        containerHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): Rect {
        if (containerWidth <= 0f || containerHeight <= 0f ||
            contentWidth <= 0f || contentHeight <= 0f
        ) {
            return Rect(0f, 0f, containerWidth.coerceAtLeast(0f), containerHeight.coerceAtLeast(0f))
        }
        val containerRatio = containerWidth / containerHeight
        val contentRatio = contentWidth / contentHeight
        return if (contentRatio > containerRatio) {
            // Content is relatively wider: width fills, height letterboxed.
            val height = containerWidth / contentRatio
            val top = (containerHeight - height) / 2f
            Rect(0f, top, containerWidth, height)
        } else {
            // Content is relatively taller: height fills, width pillarboxed.
            val width = containerHeight * contentRatio
            val left = (containerWidth - width) / 2f
            Rect(left, 0f, width, containerHeight)
        }
    }

    /**
     * Maps a touch point in container coordinates to remote video coordinates.
     * Returns null if the point is outside the rendered video area.
     */
    fun mapTouchToVideo(
        containerX: Float,
        containerY: Float,
        containerWidth: Float,
        containerHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
    ): Pair<Int, Int>? {
        val rect = compute(
            containerWidth,
            containerHeight,
            videoWidth.toFloat(),
            videoHeight.toFloat(),
        )
        if (containerX < rect.left || containerX > rect.right ||
            containerY < rect.top || containerY > rect.bottom
        ) {
            return null
        }
        return project(containerX, containerY, rect, videoWidth, videoHeight)
    }

    /**
     * Always maps onto the video rect by clamping to edges.
     * Needed for MOVE/UP after the finger leaves the letterboxed area,
     * otherwise UP is lost and the remote finger stays stuck down.
     */
    fun mapTouchToVideoClamped(
        containerX: Float,
        containerY: Float,
        containerWidth: Float,
        containerHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
    ): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) return 0 to 0
        val rect = compute(
            containerWidth,
            containerHeight,
            videoWidth.toFloat(),
            videoHeight.toFloat(),
        )
        val x = containerX.coerceIn(rect.left, rect.right)
        val y = containerY.coerceIn(rect.top, rect.bottom)
        return project(x, y, rect, videoWidth, videoHeight)
    }

    private fun project(
        containerX: Float,
        containerY: Float,
        rect: Rect,
        videoWidth: Int,
        videoHeight: Int,
    ): Pair<Int, Int> {
        val nx = if (rect.width <= 0f) 0f else ((containerX - rect.left) / rect.width).coerceIn(0f, 1f)
        val ny = if (rect.height <= 0f) 0f else ((containerY - rect.top) / rect.height).coerceIn(0f, 1f)
        val x = (nx * (videoWidth - 1)).toInt().coerceIn(0, (videoWidth - 1).coerceAtLeast(0))
        val y = (ny * (videoHeight - 1)).toInt().coerceIn(0, (videoHeight - 1).coerceAtLeast(0))
        return x to y
    }
}
