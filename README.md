# Ktstore

Passive Kotlin Multiplatform persistence for IndexedDB and SQLite. Repositories await persistence, update their own caches, and publish their own changes. Ktstore does not synchronize networks, replay commands, or emit record-change events.

## Database handles and transactions

```kotlin
val records = StoreName("records")
val receipts = StoreName("receipts")
val id = StoreKey.StringKey("id")
val configuration = DatabaseConfiguration(
    identity = "client-storage",
    version = 1,
    stores = listOf(records, receipts).map {
        StoreDeclaration(it, listOf(id), id)
    },
)
val database = createDatabase(configuration) // browser: origin-scoped IndexedDB name
// Android/Apple: createDatabase(configuration, location = absoluteAppOwnedFile)
database.open()

database.transaction(setOf(records, receipts)) {
    val previous = get(records, StoreRelation.Eq(id.bind("operation-1")))
    save(records, StoreRow(payload, listOf(id.bind("operation-1"))))
    save(receipts, StoreRow(receiptPayload, listOf(id.bind("operation-1"))))
}
// Only now update repository caches and publish application changes.
```

All store participants are declared before the transaction starts. Transaction and migration scopes use Kotlin restricted suspension: database operations and bounded synchronous computation are allowed; network calls, arbitrary suspension, child coroutines, and dispatcher changes are not. Typed `Store<T>` instances constructed with the database can be passed to scope `get`, `getAll`, `save`, `saveAll`, and `delete`.

Nested scope transactions join the outer transaction, can narrow the participants, and cannot upgrade a read-only transaction. A failed nested block or database operation makes the transaction rollback-only even when caught. Escaped scopes and stores owned by another handle are rejected. Backend isolation coordinates separate connections; a runtime-local mutex does not coordinate page/worker writes.

Operation results inside a transaction are provisional. Only successful completion of the outer transaction establishes commit. Standalone writes and whole bulk mutations await commit, including all internal chunks. Duplicate primary keys in a write batch use the last value. `getMany` concatenates matches in relation order, retaining duplicates across relations.

Cancellation before commit aborts work when possible. Cancellation racing with or following commit cannot undo a persisted write. Cancellation is not evidence of rollback: read an application-owned operation identifier to reconcile the outcome. The library never automatically reruns transaction callbacks.

## Lifecycle

- `open()` prepares a handle. Concurrent store preparation waits for one registration attempt; failed preparation can be retried.
- `clearStores(setOf(...))` clears those stores atomically without changing schema.
- `close()` stops new work, cancels active handle transactions, releases resources, and is idempotent. Old handles remain closed.
- `deleteDatabase()` closes locally registered handles and deletes the database. Create a new handle to reopen, including after logout in the same process.

IndexedDB uses `globalThis.indexedDB` and `globalThis.IDBKeyRange`; no DOM setup is required. Page and service worker handles are independent. `versionchange` closes the old connection. The application must arrange worker event lifetime, for example with `event.waitUntil(...)`. Browser `localStorage` key/value storage remains page-only and must not be used for worker-shared journals.

Blocked upgrades/deletions raise `StoreFailure.Blocked`. Browser open/delete requests cannot be cancelled. A blocked or timed-out deletion may still complete after another connection closes; retained terminal handlers prevent reopening ahead of that deletion in the same runtime. Open late-success connections are closed, and abandoned upgrades are aborted. Browser operations default to a 30-second deadline; timeouts report an indeterminate outcome rather than successful deletion or rollback.

Native providers supply an absolute, app-owned file location and choose backup exclusions. Android/Apple SQLite use serial background execution, preserve transaction ownership, and default to a five-second busy timeout. SQLite deletion includes auxiliary files after owned connections close. Applications coordinate external processes, tabs, workers, credentials, and non-database caches.

## Schema and migrations

Database schema version, serialized record format version, and remote resource revision are independent. Payload codecs and domain migrations belong to the application.

```kotlin
val migration = DatabaseMigration(1, 2) {
    rebuildStore(records, newDeclaration) { oldBytes ->
        val record = applicationDecodeOldRecord(oldBytes)
        StoreRow(applicationEncodeRecord(record), applicationBuildNewKeys(record))
    }
}
```

Configuration declares the final schema and each consecutive upgrade step. Fresh databases create the final schema directly. Existing databases upgrade in one backend transaction covering data, schema, schema fingerprint, and version. Missing steps, downgrades, incompatible declarations, and failed transforms fail without destroying the old database.

Migration scopes create/remove stores, add/remove/rebuild indexes, transform rows, and rebuild stores when key structure changes. For SQL, adding a new scalar indexed field or removing its stored column uses `rebuildStore`; adding an index over existing columns can use `addIndex`. Migration transforms currently materialize the affected store's payloads; size migration workloads accordingly.

Legacy IndexedDB databases start at version 1 and legacy Android databases at version 2. Import them through an explicit version increase. Unversioned JDBC/Apple databases require `legacyVersion` identifying the application's baseline. New configured databases record their schema fingerprint in `ktstore_schema`; adopting a legacy database requires a migration to establish it.

Existing payloads, names, decimal-string IndexedDB `Long` keys, and legacy Android blob-literal text are not rewritten implicitly. The legacy Android delegate continues binding blob keys as their existing text representation; the new configured database path binds actual blobs. Convert existing records through an explicit migration before switching representations. Recovering legacy null sentinels requires application decoding because the library cannot infer whether an empty string or zero originally represented null.

Definition-backed stores and typed migration generation are available through `StoreDefinition<T>`, `MigrationCatalog`, and the migration Gradle plugin. See [the authoring and verification guide](docs/migrations.md) and the complete `migration-example-spec` / `migration-example` projects.

## Typed indexes and nullable values

```kotlin
object RequestIdCodec : StorageCodec<RequestId, String> {
    override fun encode(value: RequestId) = value.value
    override fun key(name: IndexName) = StoreKey.StringKey(name.value)
}

// Inside a Store<StoredRequest> subclass:
val requestIdIndex = mappedIndex(
    IndexName("request_id"), StoredRequest::requestId, RequestIdCodec,
)
// requestIdIndex.eq(requestId) accepts RequestId; String and other ID types fail compilation.
```

New index helpers require explicit stable names. Renaming a Kotlin property or codec function does not rename persisted storage. Deprecated mapped helpers retain their old naming and primitive query signatures for migration compatibility.

Use `nullableMappedIndex` for nullable properties and nullable keys in the declaration. Null secondary values are sparse: they do not match equality or ordered queries. Null equality and null primary keys are rejected. Composite keys use ordered scalar components; a composite secondary index is sparse when a component is null. Legacy primitive helpers retain historical null coercion; migrate to the explicit nullable API to remove it.

Decode failures become `StoreFailure.CorruptRecord`, not absence. Other failures distinguish closed/unavailable databases, blocked lifecycle operations, quota, constraints, busy conditions, timeout, abort, migration, and unsupported usage. Underlying causes are retained; library messages do not include record contents.

## Bounded indexed queries

`IndexedQuery` supports ascending/descending order, inclusive/exclusive bounds, a positive limit, counts, and `deleteBatch`. `TypedIndex.query(...)` accepts the index's nominal type for bounds. Ordering uses the indexed value followed by the primary key. `LocalContinuation` is an exclusive local keyset token tied to database identity, schema version, store, index, bounds, and direction; it is not a server cursor or a snapshot across separate transactions.

Portable ordered queries accept integer, boolean, or sortable binary indexes and primary-key components. Raw string/legacy `Long` ordering and composite range predicates are rejected. Use binary codecs for ordered strings and `Long`: `OrderedKeyEncoding.string` preserves UTF-16 code-unit order, and `OrderedKeyEncoding.long` uses sign-bit-flipped big-endian bytes for signed numeric order. Binary comparison is unsigned. Migrate existing indexed values explicitly before using those representations. Composite equality remains supported.

## Verification

Use the repository wrapper:

```sh
./gradlew :library:jvmTest :library:jsBrowserTest
./gradlew :library:connectedDebugAndroidTest
./gradlew :library:macosArm64Test :library:iosSimulatorArm64Test
./gradlew :browser-tests:jsBrowserDevelopmentWebpack
npm ci --prefix browser-tests
browser-tests/node_modules/.bin/playwright install chromium firefox webkit
npm test --prefix browser-tests
```

The common conformance suite runs against memory, JDBC SQLite, Android SQLite, Apple SQLite, and real IndexedDB. The browser harness runs common conformance and page/worker persistence in Chromium, Firefox, and WebKit. Chromium additionally closes all application pages, delivers a worker event, stops the worker, restarts it, verifies the persisted operation, writes a receipt transactionally, and explicitly reconciles from a reopened page. Native tests run on an Android emulator and iOS simulator; compilation alone is not a runtime gate.

The release workflow depends on conformance. Publication remains separate from local validation; never replace an already published version with different bytes. Consumer repositories and server PostgreSQL persistence are outside this change.

## Coordinated legacy definition adoption

`definitionDatabaseConfiguration(identity, definitions)` is the explicit initial
adoption configuration for unchanged legacy schemas. It advances browser 1 and
SQL/Android baseline 2 to version 3, reconstructs keys from historical definitions,
keeps original payload bytes, creates optional previously unregistered stores, and
rejects duplicate derived primary keys. Keep the supplied V1 codecs and index
encodings as historical contracts. Future payload/schema changes require new
versioned definitions and consecutive migration steps.

JVM PostgreSQL hosts use `createPostgresDatabase(configuration, jdbcUrl)`;
configured opening serializes schema changes with a PostgreSQL advisory lock and
validates columns, indexes and primary keys. Long columns use BIGINT after adoption.
The optional lifecycle delegate decorator supports metrics without bypassing the
configured handle. `Database.transaction(lockKey) { ... }` supports existing typed
repository operations in one backend transaction with a process advisory lock.
Callbacks must do sequential local database work only; no network awaits or child
coroutines. A caught backend failure remains rollback-only. Handle owners close
the database after stopping all consumers.

Configured databases reject undeclared tables. Applications sharing one physical
schema with another owner must explicitly list its optional tables in
`DatabaseConfiguration.externalTables`. Names are canonical lowercase SQL identifiers
(up to 63 characters), cannot overlap any owned migration schema, and cannot use
reserved metadata names. These tables are excluded only from ownership inventory;
ktstore neither creates them nor includes them in the owned schema fingerprint.
Unexpected unlisted tables still cause migration/open failure.

Indexed repositories can call `Database.query`, `count`, and `deleteBatch` inside a
logical-keyed transaction to make admission, bounded reads, and pruning atomic with
their writes. Outside a transaction each helper opens a restricted transaction.
Helpers retain the current handle, declared store set, and read-only mode, including
nested scopes. Perform sequential local database work only: overlapping helper calls
are rejected, and catching an invalid helper operation does not permit a partial commit.
Local continuations are tied to database identity, schema version, store and query;
they are not suitable as public network pagination tokens.
