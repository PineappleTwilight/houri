package exh.eh

import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_CENSORSHIP_NAMESPACE
import mihon.app.di.globalAppGraph

object EHTags {
    private val loadedTags: List<String> by lazy {
        globalAppGraph.context.assets.open("eh_tags.txt")
            .bufferedReader()
            .readLines()
            .filter { it.isNotEmpty() }
    }

    fun getAllTags(): List<String> = loadedTags

    fun getNamespaces(): List<String> = listOf(
        "reclass",
        "language",
        "parody",
        "character",
        "group",
        "artist",
        "cosplayer",
        "male",
        "female",
        "mixed",
        "location",
        "other",
        EH_CENSORSHIP_NAMESPACE,
    )
}
