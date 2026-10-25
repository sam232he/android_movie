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
    val dimensionScale = minOf(1f, resolution.shortEdge.toFloat() / sourceShortEdge.toFloat())
    val scale = dimensionScale
    val width = evenAtLeastTwo((orientedWidth * dimensionScale).roundToInt(), orientedWidth)
    val height = evenAtLeastTwo((orientedHeight * dimensionScale).roundToInt(), orientedHeight)
    return OutputVideoSize(width, height, scale)
}

/**
 * Video encoders generally require even dimensions. Round to the nearest even
 * value, but never exceed the corresponding source dimension.
 */
private fun evenAtLeastTwo(value: Int, sourceDimension: Int): Int {
    val roundedEven = if (value.mod(2) == 0) value else value + 1
    val bounded = minOf(roundedEven, sourceDimension)
    return max(2, if (bounded.mod(2) == 0) bounded else bounded - 1)
}
