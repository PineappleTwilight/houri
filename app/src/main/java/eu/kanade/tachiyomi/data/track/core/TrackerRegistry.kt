package eu.kanade.tachiyomi.data.track.core

import eu.kanade.tachiyomi.data.track.Tracker

object TrackerRegistry {

    private val definitions = linkedMapOf<Long, TrackerDefinition>()
    private val instances = linkedMapOf<Long, Tracker>()

    fun register(definition: TrackerDefinition) {
        require(definition.id !in definitions) { "Duplicate tracker id ${definition.id} for ${definition.name}" }
        require(definition.name.isNotBlank()) { "Tracker name must not be blank" }
        definitions[definition.id] = definition
        instances[definition.id] = definition.factory()
    }

    fun getDefinition(id: Long): TrackerDefinition? = definitions[id]

    fun getTracker(id: Long): Tracker? = instances[id]

    fun getAllDefinitions(): List<TrackerDefinition> = definitions.values.toList()

    fun getAllTrackers(): List<Tracker> = instances.values.toList()

    fun contains(id: Long): Boolean = id in definitions

    fun validate() {
        val ids = definitions.keys
        check(ids.size == ids.toSet().size) { "Tracker ids must be unique" }
        check(ids.all { it > 0 }) { "Tracker ids must be positive" }
    }

    fun clearForTest() {
        definitions.clear()
        instances.clear()
    }
}
