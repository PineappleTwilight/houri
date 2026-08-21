package mihon.app.di

import android.content.Context
import mihon.core.metro.metroGraph

val Context.appGraph get() = metroGraph<AppGraph>()

// KMK -->
/**
 * Process-wide [AppGraph] access for classes that are neither Contexts nor
 * graph-constructed themselves (e.g. tracker services). Assigned during
 * App.onCreate; accessing it before bootstrap crashes deliberately.
 */
lateinit var globalAppGraph: AppGraph
    internal set
// KMK <--
