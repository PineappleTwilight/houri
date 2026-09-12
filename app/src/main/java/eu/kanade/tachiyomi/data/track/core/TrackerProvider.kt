package eu.kanade.tachiyomi.data.track.core

import eu.kanade.tachiyomi.data.track.Tracker

interface TrackerProvider {
    val definition: TrackerDefinition
    fun create(): Tracker
}
