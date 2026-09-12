package mihon.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Centralized set of [CoroutineDispatcher]s used across the app.
 *
 * Injecting [AppDispatchers] instead of referencing `Dispatchers.IO` / `Dispatchers.Main`
 * directly lets callers be unit-tested with a fake and lets the app tune parallelism in one
 * place. The specialized dispatchers below are derived from the shared IO/Default pools via
 * [kotlinx.coroutines.limitedParallelism], so they share worker threads while bounding
 * concurrency per concern.
 *
 * Plan mapping:
 * - S1 (reader page loading): [readers] bounds concurrent page fetches/decode.
 * - S2 (library update / backup / feed fan-out): [backgroundOps] bounds heavy batch work.
 * - S4 (database): [dbReader] allows concurrent WAL reads, [dbWriter] serializes writes.
 * - S5 (extensions / renderer): [extensions] bounds source fetches, [renderer] is a dedicated
 *   single thread that preserves thread identity for the WebGPU renderer.
 */
interface AppDispatchers {
    /** Unbounded IO pool for generic blocking work (network, disk). */
    val io: CoroutineDispatcher

    /** Default CPU-bound pool. */
    val default: CoroutineDispatcher

    /** Main/UI thread dispatcher. */
    val main: CoroutineDispatcher

    /** Main/UI thread dispatcher that runs immediately when already on the main thread. */
    val mainImmediate: CoroutineDispatcher

    /** Bounded reader pool for page loading and WebGPU decode (S1). */
    val readers: CoroutineDispatcher

    /** Bounded pool for library update, backup/restore and feed fan-out (S2). */
    val backgroundOps: CoroutineDispatcher

    /** Bounded pool for concurrent WAL reads (S4). */
    val dbReader: CoroutineDispatcher

    /** Single-slot pool that serializes database writes FIFO (S4). */
    val dbWriter: CoroutineDispatcher

    /** Bounded pool for source/extension fetches (S5). */
    val extensions: CoroutineDispatcher

    /** Dedicated single thread for WebGPU rendering; preserves thread identity (S5). */
    val renderer: CoroutineDispatcher
}
