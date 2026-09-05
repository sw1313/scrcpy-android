package com.scrcpyandroid.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AspectFitTest {
    @Test
    fun widerContentLetterboxesVertically() {
        // 1920x1080 into 1080x1920 portrait phone
        val rect = AspectFit.compute(1080f, 1920f, 1920f, 1080f)
        assertEquals(0f, rect.left, 0.1f)
        assertEquals(1080f, rect.width, 0.1f)
        // height = 1080 / (1920/1080) = 607.5
        assertEquals(1080f * 1080f / 1920f, rect.height, 0.5f)
    }

    @Test
    fun tallerContentPillarboxesHorizontally() {
        // 1080x1920 into 1920x1080 landscape
        val rect = AspectFit.compute(1920f, 1080f, 1080f, 1920f)
        assertEquals(0f, rect.top, 0.1f)
        assertEquals(1080f, rect.height, 0.1f)
        assertEquals(1080f * 1080f / 1920f, rect.width, 0.5f)
    }

    @Test
    fun touchOutsideVideoReturnsNull() {
        val mapped = AspectFit.mapTouchToVideo(
            containerX = 10f,
            containerY = 10f,
            containerWidth = 1920f,
            containerHeight = 1080f,
            videoWidth = 1080,
            videoHeight = 1920,
        )
        assertNull(mapped)
    }

    @Test
    fun touchCenterMapsToVideoCenter() {
        val rect = AspectFit.compute(1920f, 1080f, 1080f, 1920f)
        val cx = rect.left + rect.width / 2f
        val cy = rect.top + rect.height / 2f
        val mapped = AspectFit.mapTouchToVideo(cx, cy, 1920f, 1080f, 1080, 1920)!!
        assertEquals(539, mapped.first)
        assertEquals(959, mapped.second)
    }
}
