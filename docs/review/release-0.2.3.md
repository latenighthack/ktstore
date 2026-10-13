# Eager whole-store reads and a batched read cache for 0.2.3

`Store.getAll()` on serialized backends (SQLite on Android, Apple and JVM; IndexedDB on JS) now decodes every row before returning and returns a standard list. Previously it returned `LazyMapList`, which decoded on every access, compared by identity, and threw `NotImplementedError` from `contains`, `containsAll`, `indexOf`, `lastIndexOf` and `listIterator`. Results are now value-equal across reads, support every `List` operation, and work with `distinctUntilChanged()`. In-memory stores are unchanged.

Behaviour change: a corrupt payload now throws `StoreFailure.CorruptRecord` from `getAll()` itself, inside the reading transaction, rather than later when the affected element is first accessed.

`com.latenighthack.ktstore.collection.LazyMapList` is no longer used by the library and is deprecated. It now extends `AbstractList`, so remaining callers get correct equality and the full `List` API. Persisted payload and key encodings are unchanged.

## Batched, cached primary-key reads

Typed `Store.get` lookups by primary key outside a transaction now go through a local read cache. Concurrent misses for one store are loaded together with one `getMany` per batch. `ReadCacheConfiguration(maxEntriesPerStore = 1_000, batchSize = 50)` is the default and can be passed to the `Database` constructor or `createDatabase`. `ReadCacheConfiguration.Disabled` restores direct reads. Handles with the same identity in one process share the cache, and every ktstore write path invalidates it: `Store` writes, transaction-scope writes, logical transactions, indexed batch deletes, clears and migrations. Tables with an open writer bypass the cache, so uncommitted and rolled-back rows are never served. A batch that cannot attribute a row to its key, for example a corrupt payload, reads those keys individually, so only the affected caller fails.

The cache assumes every writer is a ktstore handle in the same process. `createPostgresDatabase` disables it by default because other server processes may write. In-memory delegates are not cached. Applications whose database is also written by another browser tab, worker or process should pass `ReadCacheConfiguration.Disabled`.

SQLite `getMany` now reads all relations in one transaction, and IndexedDB `getMany` uses one IndexedDB transaction.

Conformance coverage runs the list contract on memory, JDBC SQLite, IndexedDB, iOS simulator, macOS and Android instrumented backends, and the corrupt-read and read-cache cases (batching, eviction, invalidation by every write path, rollback, shared handles, corrupt rows within a batch, disabled cache) on every serialized backend.
