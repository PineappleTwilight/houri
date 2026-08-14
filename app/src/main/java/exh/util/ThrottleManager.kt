package exh.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ThrottleManager(
    private val max: Duration = THROTTLE_MAX,
    private val inc: Duration = THROTTLE_INC,
    private val initial: Duration = Duration.ZERO,
    val concurrency: Int = DEFAULT_CONCURRENCY,
) {
    private var lastThrottleTime = Duration.ZERO
    private val mutex = Mutex()
    private val semaphore = Semaphore(concurrency)

    var throttleTime = initial
        private set

    suspend fun throttle() {
        mutex.withLock {
            val now = System.currentTimeMillis().milliseconds
            val timeDiff = now - lastThrottleTime
            if (timeDiff < throttleTime) {
                delay(throttleTime - timeDiff)
            }

            if (throttleTime < max) {
                throttleTime += inc
            }

            lastThrottleTime = System.currentTimeMillis().milliseconds
        }
    }

    suspend fun <T> throttleExec(block: suspend () -> T): T {
        semaphore.withPermit {
            throttle()
            return block()
        }
    }

    suspend fun <T> throttleAll(blocks: List<suspend () -> T>): List<T> = coroutineScope {
        blocks.map { block ->
            async(Dispatchers.IO) {
                throttleExec { block() }
            }
        }.map { it.await() }
    }

    fun resetThrottle() {
        lastThrottleTime = Duration.ZERO
        throttleTime = initial
    }

    companion object {
        val THROTTLE_MAX = 5.5.seconds
        val THROTTLE_INC = 20.milliseconds
        const val DEFAULT_CONCURRENCY = 3
    }
}
