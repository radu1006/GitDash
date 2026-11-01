package com.agentroost.gitdashboard.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses GitHub's ISO-8601 timestamps (`2024-05-31T12:34:56Z`).
 * Returns null for absent/malformed values so the UI can simply omit the row item.
 */
fun parseGitHubTimestamp(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value)
    } catch (exception: Exception) {
        null
    }
}

/**
 * Compact relative timestamp used in repository rows ("just now", "12m ago", "3h ago", "5d ago",
 * then an absolute date). Strings are intentionally English-only for now; the rest of the UI is
 * resource-based, so this is the single place to localise later.
 */
fun relativeTime(
    isoTimestamp: String?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val instant = parseGitHubTimestamp(isoTimestamp) ?: return UNKNOWN
    val seconds = Duration.between(instant, now).seconds

    return when {
        seconds < MINUTE -> "just now"
        seconds < HOUR -> "${seconds / MINUTE}m ago"
        seconds < DAY -> "${seconds / HOUR}h ago"
        seconds < WEEK -> "${seconds / DAY}d ago"
        seconds < YEAR -> DateTimeFormatter
            .ofPattern("MMM d", Locale.getDefault())
            .format(instant.atZone(zone))

        else -> DateTimeFormatter
            .ofPattern("MMM d, yyyy", Locale.getDefault())
            .format(instant.atZone(zone))
    }
}

/** Formats a `X-RateLimit-Reset` epoch second value as a local wall-clock time. */
fun formatClockTime(
    epochSeconds: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter
    .ofPattern("HH:mm", Locale.getDefault())
    .format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

private const val MINUTE = 60L
private const val HOUR = 3_600L
private const val DAY = 86_400L
private const val WEEK = 604_800L
private const val YEAR = 31_536_000L
private const val UNKNOWN = "unknown"
