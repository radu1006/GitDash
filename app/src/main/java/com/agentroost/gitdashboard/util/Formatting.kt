package com.agentroost.gitdashboard.util

import java.util.Locale

/**
 * Human-readable file size for the browser rows (`0 B`, `812 B`, `3.4 KB`, `12 MB`).
 * Kept separate from [RelativeTime] so both can be tested without Android.
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"

    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }

    return if (value >= 10) {
        String.format(Locale.getDefault(), "%.0f %s", value, units[unit])
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }
}

/** Compact counts for the detail header (`987`, `1.2k`, `3.4M`). */
fun formatCount(count: Int): String {
    if (count < 0) return "0"
    if (count < 1_000) return count.toString()

    return when {
        count < 1_000_000 -> String.format(Locale.getDefault(), "%.1fk", count / 1_000.0)
        else -> String.format(Locale.getDefault(), "%.1fM", count / 1_000_000.0)
    }
}
