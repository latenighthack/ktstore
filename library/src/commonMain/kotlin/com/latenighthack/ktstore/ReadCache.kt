package com.latenighthack.ktstore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Typed reads outside transactions populate a shared cache of fetched rows and lazily decoded
 * objects. Primary-key hits avoid fetching; misses are loaded in batches of at most [batchSize]
 * keys. Handles sharing an identity share the cache. Every ktstore write invalidates it, so all
 * writers of the underlying database must be ktstore handles in this process.
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

/** Carries backend primary keys alongside payloads without decoding application records. */
internal class FetchedRows(private val rows: List<Any>, val primaryKeys: List<BoundStoreKey?>) : AbstractList<Any>() {
    init { require(rows.size == primaryKeys.size) }
    override val size: Int get() = rows.size
    override fun get(index: Int): Any = rows[index]
}

internal fun concatenateFetchedRows(groups: List<List<Any>>): List<Any> = FetchedRows(
    groups.flatMap { it },
    groups.flatMap { group -> (group as? FetchedRows)?.primaryKeys ?: List(group.size) { null } },
)

/**
 * Committed rows by primary key. A table with an open writer is bypassed so uncommitted or
 * rolled-back data is never cached; generations reject fetches that overlapped any write.
 */
internal class SharedReadCache {
    /** One fetched payload and its lazily decoded representations, shared by all result lists. */
    class Row(val data: Any?) {
        internal val primaryKeys = mutableSetOf<Any?>()
        internal val decoded = mutableMapOf<Any, Lazy<Any?>>()
    }
    private class Table {
        var generation = 0L
        var writers = 0
        val primary = mutableMapOf<Any?, Row>()
        val payloads = mutableMapOf<Any, Row>()
        val entries = LinkedHashMap<Row, Unit>()
        val batcher = ReadBatcher()
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
    private fun payloadKey(data: Any): Any = if (data is ByteArray) data.toComparable() else data
    private fun available(state: Table) = exclusive == 0 && state.writers == 0
    private fun touch(state: Table, row: Row) { state.entries.remove(row); state.entries[row] = Unit }
    private fun remove(state: Table, row: Row) {
        state.entries.remove(row)
        row.primaryKeys.forEach { if (state.primary[it] === row) state.primary.remove(it) }
        row.data?.let { if (state.payloads[payloadKey(it)] === row) state.payloads.remove(payloadKey(it)) }
    }
    private fun trim(state: Table, capacity: Int) {
        while (state.entries.size > capacity) remove(state, state.entries.keys.first())
    }
    private fun row(state: Table, data: Any?): Row {
        val result = data?.let { state.payloads[payloadKey(it)] } ?: Row(data).also {
            if (data != null) state.payloads[payloadKey(data)] = it
        }
        touch(state, result)
        return result
    }
    private fun associate(state: Table, key: Any?, row: Row) {
        val previous = state.primary.put(key, row)
        if (previous !== row) {
            previous?.primaryKeys?.remove(key)
            if (previous?.data == null && previous != null) remove(state, previous)
        }
        row.primaryKeys.add(key)
    }

    fun lookup(table: String, key: Any?): Lookup = lock.withLock {
        val state = table(table)
        if (!available(state)) return@withLock Lookup.Bypass
        val row = state.primary[key] ?: return@withLock Lookup.Miss
        touch(state, row)
        Lookup.Hit(row.data)
    }

    fun batcher(table: String): ReadBatcher = lock.withLock { table(table).batcher }

    fun generation(table: String): Long = lock.withLock { table(table).generation }

    fun store(table: String, generation: Long, rows: Map<Any?, Any?>, capacity: Int) = lock.withLock {
        val state = table(table)
        if (capacity == 0 || !available(state) || state.generation != generation) return@withLock
        rows.forEach { (key, data) -> associate(state, key, row(state, data)) }
        trim(state, capacity)
    }

    /** Fetching lists warms payloads without decoding them just to discover their primary keys. */
    fun storeRows(table: String, generation: Long, rows: List<Any>, capacity: Int) = lock.withLock {
        val state = table(table)
        if (capacity == 0 || !available(state) || state.generation != generation) return@withLock
        rows.forEachIndexed { index, data ->
            val row = row(state, data)
            (rows as? FetchedRows)?.primaryKeys?.get(index)?.let { associate(state, it.toAny(), row) }
            trim(state, capacity)
        }
    }

    /** Codec identities keep different typed views of a table from sharing incompatible objects. */
    fun decode(table: String, generation: Long, data: Any, codec: Any, capacity: Int,
               transform: () -> Any?, primaryKey: (Any?) -> Any?): Any? {
        val entry = lock.withLock {
            val state = table(table)
            if (capacity == 0 || !available(state)) return@withLock null
            val existing = state.payloads[payloadKey(data)]
            // Old result snapshots may reuse identical current payloads, but cannot resurrect rows.
            if (existing == null && state.generation != generation) return@withLock null
            val row = existing ?: row(state, data)
            touch(state, row)
            val decoded = row.decoded.getOrPut(codec) { lazy(transform) }
            trim(state, capacity)
            row to decoded
        } ?: return transform()
        // Never execute application codecs while holding the cache lock.
        val value = entry.second.value
        val key = primaryKey(value)
        lock.withLock {
            val state = table(table)
            if (available(state) && state.generation == generation && entry.first in state.entries) {
                if (key != null) associate(state, key, entry.first)
            }
        }
        return value
    }

    private fun invalidate(state: Table, keys: Collection<Any?>?) {
        state.generation++
        if (keys == null) {
            state.entries.clear(); state.primary.clear(); state.payloads.clear()
        } else {
            // Unaccessed payloads have no primary-key attribution yet, so any write invalidates them.
            state.entries.keys.filter { it.primaryKeys.isEmpty() }.forEach { remove(state, it) }
            keys.forEach { key -> state.primary[key]?.let { remove(state, it) } }
        }
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
    private class Batch(val batchSize: Int) {
        val requests = LinkedHashMap<Any?, Request>()
    }
    private val lock = SpinLock()
    private var open: Batch? = null

    suspend fun load(key: Any?, relation: StoreRelation.Eq, batchSize: Int, run: suspend (Map<Any?, Request>) -> Unit): Loaded {
        val (request, batch) = lock.withLock {
            val current = open
            if (current != null && current.batchSize == batchSize) {
                val joined = current.requests.getOrPut(key) { Request(relation) }
                if (current.requests.size >= batchSize) open = null
                joined to null
            } else {
                val created = Batch(batchSize)
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
