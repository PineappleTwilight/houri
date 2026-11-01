// Harness for ChapterCacheRetention - the pure math behind the chapter cache's sliding expiry
// and the discovery screen's countdown. Run with scripts/kotlinc-check/run-harness.sh.
import eu.kanade.tachiyomi.data.cache.ChapterCacheRetention

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    val ok = actual == expected
    println("${if (ok) "PASS" else "FAIL"}  $name: expected=$expected actual=$actual")
    if (!ok) failures++
}

fun main() {
    val m = ChapterCacheRetention.MINUTE_MS
    val h = ChapterCacheRetention.HOUR_MS
    val d = ChapterCacheRetention.DAY_MS

    // --- isExpired ---
    check("expired 0 retention never", ChapterCacheRetention.isExpired(0L, 10_000L, 0L), false)
    check("expired negative retention never", ChapterCacheRetention.isExpired(0L, 10_000L, -1L), false)
    check("fresh entry not expired", ChapterCacheRetention.isExpired(9_000L, 10_000L, h), false)
    check("aged entry expired", ChapterCacheRetention.isExpired(0L, h + 1, h), true)
    check("exactly at retention not expired", ChapterCacheRetention.isExpired(0L, h, h), false)

    // --- remainingMs ---
    check("remaining off = max", ChapterCacheRetention.remainingMs(0L, 10_000L, 0L), Long.MAX_VALUE)
    check("remaining positive", ChapterCacheRetention.remainingMs(0L, 1_000L, h), h - 1_000L)
    check("remaining negative when past", ChapterCacheRetention.remainingMs(0L, h + 5, h), -5L)

    // --- ageMs ---
    check("age positive", ChapterCacheRetention.ageMs(0L, 5_000L), 5_000L)
    check("age clamps clock skew", ChapterCacheRetention.ageMs(9_000L, 1_000L), 0L)

    // --- displayParts ---
    check("parts days+hours", ChapterCacheRetention.displayParts(2 * d + 5 * h + 3 * m), ChapterCacheRetention.DisplayParts(2, 5, 0))
    check("parts hours+minutes", ChapterCacheRetention.displayParts(5 * h + 3 * m + 12_000L), ChapterCacheRetention.DisplayParts(0, 5, 3))
    check("parts minutes only", ChapterCacheRetention.displayParts(3 * m), ChapterCacheRetention.DisplayParts(0, 0, 3))
    check("parts sub-minute floors to zero", ChapterCacheRetention.displayParts(30_000L), ChapterCacheRetention.DisplayParts(0, 0, 0))
    check("parts negative clamps", ChapterCacheRetention.displayParts(-5 * m), ChapterCacheRetention.DisplayParts(0, 0, 0))

    println(if (failures == 0) "\nALL PASS" else "\n$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}
