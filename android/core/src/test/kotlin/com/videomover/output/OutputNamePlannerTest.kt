package com.videomover.output

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputNamePlannerTest {
    @Test fun numbersPastExistingSuffixes() {
        val occupied = setOf("Beach - converted.mp4", "Beach - converted (1).mp4")
        assertEquals(
            "Beach - converted (2).mp4",
            nextAvailableName("Beach - converted.mp4") { it in occupied },
        )
    }
}
