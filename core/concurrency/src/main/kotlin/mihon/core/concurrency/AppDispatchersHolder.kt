package mihon.core.concurrency

/**
 * Backward-compat seam for top-level coroutine helpers that cannot use DI.
 *
 * [tachiyomi.core.common.util.lang.launchIO] / `withIOContext` resolve dispatchers
 * through this holder so the ~600 existing call sites pick up the tuned pools
 * without modification. Defaults to a process-wide [AppDispatchersImpl]; tests
 * may replace it via [set] and restore via [reset].
 */
object AppDispatchersHolder {

    @Volatile
    private var instance: AppDispatchers = AppDispatchersImpl()

    fun get(): AppDispatchers = instance

    fun set(dispatchers: AppDispatchers) {
        instance = dispatchers
    }

    fun reset() {
        instance = AppDispatchersImpl()
    }
}
