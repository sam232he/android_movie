package com.videomover.output

/** Allocates stable provider-safe names without overwriting destination files. */
fun nextAvailableName(preferred: String, exists: (String) -> Boolean): String {
    if (!exists(preferred)) return preferred
    val dot = preferred.lastIndexOf('.')
    val stem = if (dot > 0) preferred.substring(0, dot) else preferred
    val extension = if (dot > 0) preferred.substring(dot) else ""
    var index = 1
    while (true) {
        val candidate = "${truncateStem(stem, " ($index)$extension")} ($index)$extension"
        if (!exists(candidate)) return candidate
        index += 1
    }
}

private fun truncateStem(stem: String, suffix: String): String = stem.take((255 - suffix.length).coerceAtLeast(1))
