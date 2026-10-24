package com.latenighthack.ktstore

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Receives database events synchronously in the caller's coroutine; keep handlers short and
 * non-blocking. Anything a handler throws is discarded. Events carry names, kinds, counts, sizes
 * and durations only: key values are reachable solely through [StoreRelation] references and
 * row contents never leave the database.
 */
fun interface StoreObserver {
    fun onEvent(event: StoreEvent)
}

/** How an observed operation ended. Cancellation is reported as such and still propagates. */
sealed class StoreOutcome {
    object Completed : StoreOutcome()
    class Failed(val error: Throwable) : StoreOutcome()
    class Cancelled(val cause: kotlinx.coroutines.CancellationException) : StoreOutcome()
}

/** [KEY] matches the primary key, [INDEX] matches equality on a secondary index (any number of rows). */
enum class RelationKind { KEY, INDEX, ALL }

enum class ReadOperation { GET, GET_ALL, GET_MANY, GET_MANY_GROUPED }
enum class WriteOperation { SAVE, SAVE_ALL }
enum class DeleteOperation { DELETE, DELETE_MANY, DELETE_ALL, CLEAR_STORES }
/** [FRESH] creates an empty database; [CURRENT] already stores the target version. */
enum class MigrationKind { FRESH, CURRENT, UPGRADE }
enum class MigrationOperationKind { CREATE_STORE, REMOVE_STORE, ADD_INDEX, REMOVE_INDEX, TRANSFORM, REBUILD }

sealed class StoreEvent {
    /** The database identity. */
    abstract val database: String
    /** The caller's context, so applications can attribute events to their own operations. */
    abstract val context: CoroutineContext

    /**
     * One `Store`/`TransactionScope` read, or one batched backend load ([ReadOperation.GET_MANY_GROUPED])
     * serving cache misses. Lazily decoded lists report when their rows are fetched, never per element.
     * [cachedRows] were served by the shared read cache and [fetchedRows] by the backend.
     */
    class Read(
        override val database: String, override val context: CoroutineContext,
        val operation: ReadOperation, val store: String, val index: String?, val relation: RelationKind?,
        val relations: List<StoreRelation>, val rows: Int, val cachedRows: Int, val fetchedRows: Int,
        val bytes: Long?, val inTransaction: Boolean, val duration: Duration, val outcome: StoreOutcome,
    ) : StoreEvent()

    class Write(
        override val database: String, override val context: CoroutineContext,
        val operation: WriteOperation, val store: String, val rows: Int, val bytes: Long?,
        val inTransaction: Boolean, val duration: Duration, val outcome: StoreOutcome,
    ) : StoreEvent()

    /** [rowsRemoved] is null when the backend does not report it. */
    class Delete(
        override val database: String, override val context: CoroutineContext,
        val operation: DeleteOperation, val stores: Set<String>, val index: String?, val relation: RelationKind,
        val relations: List<StoreRelation>, val rowsRemoved: Int?,
        val inTransaction: Boolean, val duration: Duration, val outcome: StoreOutcome,
    ) : StoreEvent()

    /** Scoped transactions have no [lockKey]; logical ones cover every store. */
    class Transaction(
        override val database: String, override val context: CoroutineContext,
        val lockKey: String?, val stores: Set<String>, val mode: TransactionMode,
        val duration: Duration, val outcome: StoreOutcome,
    ) : StoreEvent() {
        val committed: Boolean get() = outcome === StoreOutcome.Completed
    }

    /** Reported by persistent backends once the stored version is known. */
    class MigrationStarted(
        override val database: String, override val context: CoroutineContext,
        val storedVersion: Int, val targetVersion: Int, val kind: MigrationKind,
    ) : StoreEvent()

    /** One configured [DatabaseMigration], including its schema validation. */
    class MigrationStep(
        override val database: String, override val context: CoroutineContext,
        val fromVersion: Int, val toVersion: Int, val duration: Duration, val outcome: StoreOutcome,
    ) : StoreEvent()

    /** One [MigrationScope] operation; [rowsTransformed] is set for transforms and rebuilds. */
    class MigrationOperation(
        override val database: String, override val context: CoroutineContext,
        val fromVersion: Int?, val toVersion: Int?, val operation: MigrationOperationKind,
        val store: String, val index: String?, val rowsTransformed: Int?,
        val duration: Duration, val outcome: StoreOutcome,
    ) : StoreEvent()

    /** Validation of the opened schema against [version]. */
    class MigrationVerification(
        override val database: String, override val context: CoroutineContext,
        val version: Int, val duration: Duration, val outcome: StoreOutcome,
    ) : StoreEvent() {
        val passed: Boolean get() = outcome === StoreOutcome.Completed
    }

    /**
     * Terminal event of `Database.open()`. Version fields are null when the backend does not
     * report them; on failure the failed step and operation are named when known.
     */
    class MigrationFinished(
        override val database: String, override val context: CoroutineContext,
        val storedVersion: Int?, val targetVersion: Int, val kind: MigrationKind?,
        val failedFromVersion: Int?, val failedToVersion: Int?,
        val failedOperation: MigrationOperationKind?, val failedStore: String?,
        val duration: Duration, val outcome: StoreOutcome,
    ) : StoreEvent()
}

internal fun StoreObserver.emit(event: StoreEvent) {
    try { onEvent(event) } catch (_: Throwable) { }
}

internal fun outcomeOf(error: Throwable): StoreOutcome =
    if (error is kotlinx.coroutines.CancellationException) StoreOutcome.Cancelled(error) else StoreOutcome.Failed(error)

/** Runs [block] unchanged without an observer; otherwise times it and reports its outcome. */
internal suspend inline fun <T> StoreObserver?.observe(
    event: (context: CoroutineContext, duration: Duration, outcome: StoreOutcome, result: T?) -> StoreEvent,
    block: () -> T,
): T = if (this == null) block() else observe(kotlin.coroutines.coroutineContext, event, block)

internal inline fun <T> StoreObserver?.observe(
    context: CoroutineContext,
    event: (context: CoroutineContext, duration: Duration, outcome: StoreOutcome, result: T?) -> StoreEvent,
    block: () -> T,
): T {
    if (this == null) return block()
    val mark = TimeSource.Monotonic.markNow()
    val result = try { block() } catch (error: Throwable) {
        val outcome = outcomeOf(error)
        try { emit(event(context, mark.elapsedNow(), outcome, null)) } catch (_: Throwable) { }
        throw error
    }
    try { emit(event(context, mark.elapsedNow(), StoreOutcome.Completed, result)) } catch (_: Throwable) { }
    return result
}

/** Mutable read statistics, allocated only when an observer is present. */
internal class ReadStats {
    var cachedRows = 0
    var fetchedRows = 0
    var bytes = 0L
    fun fetched(rows: List<Any>) { fetchedRows += rows.size; bytes += payloadBytes(rows) }
    fun fetchedOne(data: Any?) { if (data != null) { fetchedRows++; bytes += (data as? ByteArray)?.size ?: 0 } }
    fun cached(data: Any?) { if (data != null) { cachedRows++; bytes += (data as? ByteArray)?.size ?: 0 } }
}

internal fun payloadBytes(rows: List<Any>): Long = rows.sumOf { (it as? ByteArray)?.size?.toLong() ?: 0L }
internal fun rowBytes(rows: List<StoreRow>): Long = rows.sumOf { (it.data as? ByteArray)?.size?.toLong() ?: 0L }

internal fun relationKind(primary: StoreKey<*>?, relation: StoreRelation?): RelationKind? {
    if (relation == null) return RelationKind.ALL
    val key = try { relation.key } catch (_: Throwable) { return null }
    return if (primary != null && key.name == primary.name) RelationKind.KEY else RelationKind.INDEX
}
internal fun relationKind(primary: StoreKey<*>?, relations: List<StoreRelation>): RelationKind? {
    val kinds = relations.map { relationKind(primary, it) ?: return null }.toSet()
    return kinds.singleOrNull() ?: if (kinds.isEmpty()) RelationKind.KEY else RelationKind.INDEX
}
internal fun relationIndex(relations: List<StoreRelation>): String? =
    relations.map { try { it.key.name } catch (_: Throwable) { return null } }.distinct().singleOrNull()
internal fun relationIndex(relation: StoreRelation?): String? =
    relation?.let { try { it.key.name } catch (_: Throwable) { null } }

internal fun DatabaseConfiguration.primaryKey(store: String): StoreKey<*>? =
    stores.singleOrNull { it.name.value == store }?.primaryKey

/**
 * Carried in the coroutine context of `Database.open()` only when observed, so persistent
 * backends report migration progress without any change to their unobserved path.
 */
internal class MigrationReporter(
    private val observer: StoreObserver,
    private val database: String,
    private val context: CoroutineContext,
    private val targetVersion: Int,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MigrationReporter>
    var storedVersion: Int? = null
    private var kind: MigrationKind? = null
    var step: DatabaseMigration? = null
    var failedStep: DatabaseMigration? = null
    private var failedOperation: MigrationOperationKind? = null
    private var failedStore: String? = null
    /** Whether the backend ran its open/migration path at all. */
    var ran = false
    var rows = 0

    fun started(stored: Int, kind: MigrationKind) {
        storedVersion = stored; this.kind = kind; ran = true
        observer.emit(StoreEvent.MigrationStarted(database, context, stored, targetVersion, kind))
    }

    inline fun <T> step(step: DatabaseMigration, block: () -> T): T {
        val mark = TimeSource.Monotonic.markNow()
        this.step = step
        try {
            val result = block()
            stepFinished(step, mark.elapsedNow(), StoreOutcome.Completed)
            return result
        } catch (error: Throwable) {
            if (failedStep == null) failedStep = step
            stepFinished(step, mark.elapsedNow(), outcomeOf(error))
            throw error
        } finally { this.step = null }
    }
    fun stepFinished(step: DatabaseMigration, duration: Duration, outcome: StoreOutcome) =
        observer.emit(StoreEvent.MigrationStep(database, context, step.fromVersion, step.toVersion, duration, outcome))

    fun operation(operation: MigrationOperationKind, store: String, index: String?, duration: Duration, outcome: StoreOutcome) {
        if (outcome !== StoreOutcome.Completed && failedOperation == null) { failedOperation = operation; failedStore = store }
        val rowsTransformed = if (operation == MigrationOperationKind.TRANSFORM || operation == MigrationOperationKind.REBUILD) rows else null
        observer.emit(StoreEvent.MigrationOperation(database, context, step?.fromVersion, step?.toVersion,
            operation, store, index, rowsTransformed, duration, outcome))
    }

    inline fun <T> verify(version: Int, block: () -> T): T {
        ran = true
        val mark = TimeSource.Monotonic.markNow()
        try {
            val result = block()
            verified(version, mark.elapsedNow(), StoreOutcome.Completed)
            return result
        } catch (error: Throwable) { verified(version, mark.elapsedNow(), outcomeOf(error)); throw error }
    }
    fun verified(version: Int, duration: Duration, outcome: StoreOutcome) =
        observer.emit(StoreEvent.MigrationVerification(database, context, version, duration, outcome))

    fun finished(duration: Duration, outcome: StoreOutcome) = observer.emit(StoreEvent.MigrationFinished(
        database, context, storedVersion, targetVersion, kind,
        failedStep?.fromVersion, failedStep?.toVersion, failedOperation, failedStore, duration, outcome,
    ))
}

/** Times one version step, or runs it unchanged when the open is unobserved. */
internal inline fun <T> MigrationReporter?.step(step: DatabaseMigration, block: () -> T): T =
    if (this == null) block() else step(step, block)

internal inline fun <T> MigrationReporter?.verify(version: Int, block: () -> T): T =
    if (this == null) block() else verify(version, block)
