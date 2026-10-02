import com.latenighthack.ktstore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.await
import kotlin.js.Promise

private val registration = StoreName("registration")
private val journal = StoreName("journal")
private val receipt = StoreName("receipt")
private val id = StoreKey.StringKey("id")
private val config = DatabaseConfiguration("worker-conformance", 1, listOf(registration, journal, receipt).map { StoreDeclaration(it, listOf(id), id) })
private fun record(value: String) = StoreRow(value.encodeToByteArray(), listOf(id.bind("one")))
private fun operation(action: String): Promise<String> = CoroutineScope(Dispatchers.Default).promise {
    val db = createDatabase(config)
    try {
        db.open()
        when (action) {
            "register" -> db.transaction(setOf(registration)) { save(registration, record("installation-1")) }
            "action" -> db.transaction(setOf(registration, journal)) {
                check((get(registration) as ByteArray).decodeToString() == "installation-1")
                save(journal, record("operation-1"))
            }
            "receipt" -> db.transaction(setOf(journal, receipt)) {
                check((get(journal) as ByteArray).decodeToString() == "operation-1")
                save(receipt, record("receipt-1"))
            }
            "reconcile" -> db.transaction(setOf(journal, receipt), TransactionMode.READ_ONLY) {
                check((get(journal) as ByteArray).decodeToString() == "operation-1")
                check((get(receipt) as ByteArray).decodeToString() == "receipt-1")
            }
            else -> error("Unknown test operation")
        }
        action
    } finally { db.close() }
}
fun main() {
    val global = js("globalThis")
    global.ktstoreConformance = {
        CoroutineScope(Dispatchers.Default).promise {
            val tests = object : PersistentConformance() {
                override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate = IndexDB(config)
            }
            tests.wholeReadsUseLogicalPrimaryOrdering().unsafeCast<Promise<Unit>>().await()
            tests.commitRollbackAndSecondaryDeletion().unsafeCast<Promise<Unit>>().await()
            tests.readonlyAndUndeclaredStoreAccessRollback().unsafeCast<Promise<Unit>>().await()
            tests.boundedQueriesUsePrimaryTieBreakerAndSparseNulls().unsafeCast<Promise<Unit>>().await()
            tests.binaryCompositeKeysAndAtomicBatches().unsafeCast<Promise<Unit>>().await()
            tests.concurrentReadModifyWriteIsSerialized().unsafeCast<Promise<Unit>>().await()
            tests.migrationFailurePreservesOldDataAndVersion().unsafeCast<Promise<Unit>>().await()
            tests.independentConnectionsAndDeletionInvalidateOldHandles().unsafeCast<Promise<Unit>>().await()
            tests.typedWrappersPreserveNominalKeysAndNulls().unsafeCast<Promise<Unit>>().await()
            tests.orderedLongBoundaries()
            "ok"
        }
    }
    global.ktstoreTest = { action: String -> operation(action) }
    if (js("typeof ServiceWorkerGlobalScope !== 'undefined' && globalThis instanceof ServiceWorkerGlobalScope") as Boolean) {
        global.addEventListener("install", { event: dynamic -> event.waitUntil(global.skipWaiting()) })
        global.addEventListener("activate", { event: dynamic -> event.waitUntil(global.clients.claim()) })
        global.addEventListener("message", { event: dynamic ->
            event.waitUntil(operation(event.data as String).then({ result -> event.ports[0].postMessage(result); result }, { error -> event.ports[0].postMessage("error:" + error.message); "error" }))
        })
        global.addEventListener("push", { event: dynamic ->
            val action = event.data.text() as String
            event.waitUntil(operation(action).then({ result -> global.fetch("/result?value=" + result) }, { error -> global.fetch("/result?value=error:" + error.message) }))
        })
    }
}
