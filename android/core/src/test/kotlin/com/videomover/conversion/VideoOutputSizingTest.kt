package com.videomover.conversion

import com.videomover.model.OutputResolution
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoOutputSizingTest {
    @Test fun landscape480Is854x480() {
        assertEquals(OutputVideoSize(854, 480, 480f / 1080f), outputVideoSize(SourceVideoInfo(1920, 1080, 0), OutputResolution.P480))
    }

    @Test fun portraitRotationSwapsDisplayOrientation() {
        assertEquals(OutputVideoSize(480, 854, 480f / 1080f), outputVideoSize(SourceVideoInfo(1920, 1080, 90), OutputResolution.P480))
    }

    @Test fun smallSourceIsNeverUpscaled() {
        assertEquals(OutputVideoSize(640, 360, 1f), outputVideoSize(SourceVideoInfo(640, 360, 0), OutputResolution.P720))
    }

    @Test fun nonSixteenByNineKeepsAspectRatio() {
        val output = outputVideoSize(SourceVideoInfo(1280, 720, 0), OutputResolution.P720)
        assertEquals(1280, output.width)
        assertEquals(720, output.height)
    }
}
