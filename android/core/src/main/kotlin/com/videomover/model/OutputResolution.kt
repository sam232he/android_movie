package com.videomover.model

/**
 * The only output sizes the product may produce.
 * Do not add another entry.
 */
enum class OutputResolution(val label: String, val shortEdge: Int) {
    P480("480p", 480),
    P720("720p", 720),
}

/** Move is the only transfer mode. There is no copy mode to select. */
enum class TransferMode {
    MOVE,
}
