package com.videomover.error

import com.videomover.machine.AttentionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FailureCatalogTest {
    @Test fun disconnectSuggestsReconnectWithoutChoosingAnotherDrive() {
        val failure = describeFailure(AttentionReason.USB_DISCONNECTED)
        assertEquals(FailureAction.RECONNECT_USB, failure.action)
        assertTrue(failure.message.contains("same USB"))
    }

    @Test fun collisionSuggestsAnExplicitFilenameDecision() {
        assertEquals(FailureAction.RESOLVE_FILENAME, describeFailure(AttentionReason.NAME_CONFLICT).action)
    }

    @Test fun detailIsPreservedForResultsScreen() {
        val failure = describeFailure(AttentionReason.OTHER, "Encoder initialization failed.")
        assertTrue(failure.message.contains("Encoder initialization failed."))
    }
}
