package com.videomover.preflight

import com.videomover.destination.SelectedFileSize
import com.videomover.model.OutputResolution
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreflightTest {
    @Test fun blocksWhenUsbOrPhoneSpaceIsInsufficient() {
        val result = estimatePreflight(
            selected = listOf(SelectedFileSize("1", "clip.mov", 100L * 1024 * 1024)),
            resolution = OutputResolution.P720,
            usbFreeBytes = 50L * 1024 * 1024,
            phoneFreeBytes = 50L * 1024 * 1024,
        )
        assertTrue(result.blocking)
    }

    @Test fun unknownSizesWarnWithoutPretendingSpaceWasChecked() {
        val result = estimatePreflight(
            selected = listOf(SelectedFileSize("1", "clip.mov", null)),
            resolution = OutputResolution.P480,
            usbFreeBytes = 1_000_000_000L,
            phoneFreeBytes = 1_000_000_000L,
        )
        assertFalse(result.blocking)
        assertTrue(result.warnings.any { it.contains("unavailable") })
    }
}
