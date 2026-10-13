package com.latenighthack.ktstore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Typed `Store.get` lookups by primary key outside transactions are served from a local cache, and
 * concurrent misses are loaded together in batches of at most [batchSize] keys. Handles sharing an
 * identity share the cache, and every write made through any of them invalidates it, so all writers
 * of the underlying database must be ktstore handles in this process.
 */
data class ReadCacheConfiguration(val maxEntriesPerStore: Int = 1_000, val batchSize: Int = 50) {
    init { require(maxEntriesPerStore >= 0 && batchSize > 0) }
    companion object {
        /** Every read reaches the delegate individually. */
        val Disabled = ReadCacheConfiguration(maxEntriesPerStore = 0, batchSize = 1)
    }
}

/** Guards short, non-suspending updates only, so JS never contends and IndexedDB never yields. */
internal class SpinLock {
    private val held = AtomicBoolean(false)
    fun <T> withLock(block: () -> T): T {
        while (!held.compareAndSwap(false, true)) { }
        try { return block() } finally { held.value = false }
    }
}

/**
 * Committed rows by primary key. A table with an open writer is bypassed so uncommitted or
 * rolled-back data is never cached; generations reject fetches that overlapped any write.
 */
internal class SharedReadCache {
    private object Absent
    private class Table {
        var generation = 0L
        var writers = 0
        val entries = LinkedHashMap<Any?, Any>()
    }
    sealed class Lookup {
        class Hit(val data: Any?) : Lookup()
        object Miss : Lookup()
        object Bypass : Lookup()
    }
    private val lock = SpinLock()
    private val tables = mutableMapOf<String, Table>()
    private var exclusive = 0

    private fun table(name: String) = tables.getOrPut(name) { Table() }

    fun lookup(table: String, key: Any?): Lookup = lock.withLock {
        val state = table(table)
        if (exclusive > 0 || state.writers > 0) return@withLock Lookup.Bypass
        val entry = state.entries.remove(key) ?: return@withLock Lookup.Miss
        state.entries[key] = entry
        Lookup.Hit(if (entry === Absent) null else entry)
    }

    fun generation(table: String): Long = lock.withLock { table(table).generation }

    fun store(table: String, generation: Long, rows: Map<Any?, Any?>, capacity: Int) = lock.withLock {
        val state = table(table)
        if (capacity == 0 || exclusive > 0 || state.writers > 0 || state.generation != generation) return@withLock
        rows.forEach { (key, data) -> state.entries.remove(key); state.entries[key] = data ?: Absent }
        val keys = state.entries.keys.iterator()
        while (state.entries.size > capacity) { keys.next(); keys.remove() }
    }

    private fun invalidate(state: Table, keys: Collection<Any?>?) {
        state.generation++
        if (keys == null) state.entries.clear() else keys.forEach { state.entries.remove(it) }
    }

    /** [keys] are the written primary keys, or null when the affected rows are unknown. */
    fun beginWrite(table: String, keys: Collection<Any?>?) = lock.withLock {
        val state = table(table)
        state.writers++
        invalidate(state, keys)
    }

    fun endWrite(table: String) = lock.withLock {
        val state = table(table)
        state.writers--
        state.generation++
    }

    /** Transactions hold each written table until [endWrites], after commit or rollback. */
    fun joinWrite(written: MutableSet<String>, table: String, keys: Collection<Any?>?) = lock.withLock {
        val state = table(table)
        if (written.add(table)) state.writers++
        invalidate(state, keys)
    }

    fun endWrites(written: MutableSet<String>) = lock.withLock {
        written.forEach { val state = table(it); state.writers--; state.generation++ }
        written.clear()
    }

    /** Schema registration and migrations may rewrite any table. */
    fun beginExclusive() = lock.withLock {
        exclusive++
        tables.values.forEach { invalidate(it, null) }
    }

    fun endExclusive() = lock.withLock {
        exclusive--
        tables.values.forEach { it.generation++ }
    }
}

/** Coalesces concurrent primary-key misses for one store into batched delegate reads. */
internal class ReadBatcher {
    class Request(val relation: StoreRelation.Eq) {
        val result = CompletableDeferred<Loaded>()
    }
    /** [value] holds the decoded record when attribution already decoded it. */
    class Loaded(val data: Any?, val value: Any?, val decoded: Boolean)
    private class Batch {
        val requests = LinkedHashMap<Any?, Request>()
    }
    private val lock = SpinLock()
    private var open: Batch? = null

    suspend fun load(key: Any?, relation: StoreRelation.Eq, batchSize: Int, run: suspend (Map<Any?, Request>) -> Unit): Loaded {
        val (request, batch) = lock.withLock {
            val current = open
            if (current != null) {
                val joined = current.requests.getOrPut(key) { Request(relation) }
                if (current.requests.size >= batchSize) open = null
                joined to null
            } else {
                val created = Batch()
                val first = Request(relation)
                created.requests[key] = first
                if (batchSize > 1) open = created
                first to created
            }
        }
        if (batch != null) {
            // Let concurrently started lookups join before the batch is read; members depend on
            // this coroutine, so the batch still runs if it is cancelled meanwhile.
            try { if (batchSize > 1) yield() } finally {
                lock.withLock { if (open === batch) open = null }
                withContext(NonCancellable) { run(batch.requests) }
            }
        }
        return request.result.await()
    }
}
