package com.videomover.conversion

import com.videomover.model.OutputResolution
import kotlin.math.max
import kotlin.math.roundToInt

data class SourceVideoInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
) {
    init {
        require(width > 0 && height > 0) { "Video dimensions must be positive." }
        require(rotationDegrees % 90 == 0) { "Video rotation must be a multiple of 90 degrees." }
    }
}

data class OutputVideoSize(val width: Int, val height: Int, val scale: Float) {
    val isPortrait: Boolean get() = height > width
}

/** Computes a proportional, even-sized output without ever upscaling either axis. */
fun outputVideoSize(source: SourceVideoInfo, resolution: OutputResolution): OutputVideoSize {
    val rotated = source.rotationDegrees.mod(360) == 90 || source.rotationDegrees.mod(360) == 270
    val orientedWidth = if (rotated) source.height else source.width
    val orientedHeight = if (rotated) source.width else source.height
    val sourceShortEdge = minOf(orientedWidth, orientedHeight)
    val scale = minOf(1f, resolution.shortEdge.toFloat() / sourceShortEdge.toFloat())
    val width = evenAtLeastTwo((orientedWidth * scale).roundToInt())
    val height = evenAtLeastTwo((orientedHeight * scale).roundToInt())
    return OutputVideoSize(width, height, scale)
}

private fun evenAtLeastTwo(value: Int): Int = max(2, value - value.mod(2))
