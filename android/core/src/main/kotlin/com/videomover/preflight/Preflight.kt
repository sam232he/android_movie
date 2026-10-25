package com.videomover.preflight

import com.videomover.destination.SelectedFileSize
import com.videomover.model.OutputResolution

data class PreflightEstimate(
    val fileCount: Int,
    val selectedBytes: Long?,
    val estimatedOutputBytes: Long?,
    val usbFreeBytes: Long?,
    val phoneFreeBytes: Long?,
    val warnings: List<String>,
) {
    val blocking: Boolean get() = warnings.any { it.startsWith("BLOCKING:") }
}

/** Conservative estimate only; the actual encoder result remains authoritative. */
fun estimatePreflight(
    selected: List<SelectedFileSize>,
    resolution: OutputResolution,
    usbFreeBytes: Long?,
    phoneFreeBytes: Long?,
): PreflightEstimate {
    val warnings = ArrayList<String>()
    val sourceTotal = sumKnown(selected.map { it.sizeBytes })
    val outputTotal = sumKnown(selected.map { it.sizeBytes?.let { size -> estimatedOutputBytes(size, resolution) } })
    if (selected.isEmpty()) warnings += "BLOCKING: Select at least one video."
    if (sourceTotal == null) warnings += "WARNING: Some selected source sizes are unavailable; USB space cannot be fully estimated."
    if (outputTotal == null) warnings += "WARNING: Some converted-output sizes are unavailable; phone space cannot be fully estimated."
    if (usbFreeBytes != null && sourceTotal != null && sourceTotal > usbFreeBytes) {
        warnings += "BLOCKING: The selected source files exceed the USB free space."
    }
    if (phoneFreeBytes != null && outputTotal != null && outputTotal > phoneFreeBytes) {
        warnings += "BLOCKING: The phone may not have enough free space for the converted outputs."
    }
    if (usbFreeBytes == null) warnings += "WARNING: USB free space is not reported."
    if (phoneFreeBytes == null) warnings += "WARNING: Phone free space is not reported."
    return PreflightEstimate(selected.size, sourceTotal, outputTotal, usbFreeBytes, phoneFreeBytes, warnings)
}

fun estimatedOutputBytes(sourceBytes: Long, resolution: OutputResolution): Long {
    val minimum = if (resolution == OutputResolution.P480) 8L * 1024 * 1024 else 12L * 1024 * 1024
    val ratio = if (resolution == OutputResolution.P480) 0.60 else 1.00
    val estimate = (sourceBytes.toDouble() * ratio).toLong().coerceAtLeast(minimum)
    return estimate.coerceAtMost(Long.MAX_VALUE)
}

private fun sumKnown(values: List<Long?>): Long? {
    if (values.any { it == null || it < 0L }) return null
    var total = 0L
    for (value in values.filterNotNull()) {
        if (Long.MAX_VALUE - total < value) return null
        total += value
    }
    return total
}
