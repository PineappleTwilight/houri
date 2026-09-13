package mihon.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Default [AppDispatchers] implementation.
 *
 * The bounded dispatchers are derived from the shared IO/Default pools via
 * [kotlinx.coroutines.limitedParallelism], so they reuse existing worker threads while bounding
 * concurrency per concern. Only [renderer] owns a dedicated thread, because the WebGPU renderer
 * relies on a stable thread identity (`Thread.currentThread() === renderThread`) that a shared
 * pool cannot guarantee.
 *
 * The class is intentionally free of DI annotations; the app module's provider wires it into the
 * graph (T0.2).
 */
class AppDispatchersImpl : AppDispatchers {

    override val io: CoroutineDispatcher = Dispatchers.IO

    override val default: CoroutineDispatcher = Dispatchers.Default

    override val main: CoroutineDispatcher = Dispatchers.Main

    override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate

    override val readers: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    override val backgroundOps: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4)

    override val dbReader: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(8)

    override val dbWriter: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    override val extensions: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(5)

    private val rendererExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(NamedThreadFactory("KMK-renderer"))

    override val renderer: CoroutineDispatcher = rendererExecutor.asCoroutineDispatcher()

    /** Shuts down the dedicated renderer thread. Safe to call once at app teardown. */
    fun closeRenderer() {
        rendererExecutor.shutdown()
    }
}
