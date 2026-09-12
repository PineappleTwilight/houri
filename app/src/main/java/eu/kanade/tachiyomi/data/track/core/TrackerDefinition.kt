package eu.kanade.tachiyomi.data.track.core

import androidx.annotation.DrawableRes
import eu.kanade.tachiyomi.data.track.Tracker

data class TrackerDefinition(
    val id: Long,
    val name: String,
    @DrawableRes val logoRes: Int,
    val authType: TrackerAuthType,
    val capabilities: TrackerCapabilities = TrackerCapabilities(),
    val factory: () -> Tracker,
)
