package eu.kanade.tachiyomi.data.track.core

data class TrackerCapabilities(
    val supportsReadingDates: Boolean = false,
    val supportsPrivateTracking: Boolean = false,
    val supportsRereadCount: Boolean = false,
    val supportsScore: Boolean = true,
    val supportsStatusChange: Boolean = true,
    val isEnhanced: Boolean = false,
)
