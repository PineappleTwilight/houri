package mihon.core.concurrency

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A [ThreadFactory] that creates threads with a stable, human-readable name derived from
 * [prefix] and a monotonically increasing counter (e.g. `KMK-renderer-1`).
 *
 * Threads are created with normal priority and an optional daemon flag, and any uncaught
 * exception is logged so that failures on background threads are not silently swallowed.
 */
class NamedThreadFactory(
    private val prefix: String,
    private val isDaemon: Boolean = false,
) : ThreadFactory {

    private val counter = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
            isDaemon = this@NamedThreadFactory.isDaemon
            priority = Thread.NORM_PRIORITY
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
                logger.log(Level.SEVERE, "Uncaught exception in thread ${thread.name}", throwable)
            }
        }

    private companion object {
        val logger = Logger.getLogger(NamedThreadFactory::class.java.name)
    }
}
