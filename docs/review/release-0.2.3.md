# Eager typed whole-store reads for 0.2.3

`Store.getAll()` on serialized backends (SQLite on Android, Apple and JVM; IndexedDB on JS) now decodes every row before returning and returns a standard list. Previously it returned `LazyMapList`, which decoded on every access, compared by identity, and threw `NotImplementedError` from `contains`, `containsAll`, `indexOf`, `lastIndexOf` and `listIterator`. Results are now value-equal across reads, support every `List` operation, and work with `distinctUntilChanged()`. In-memory stores are unchanged.

Behaviour change: a corrupt payload now throws `StoreFailure.CorruptRecord` from `getAll()` itself, inside the reading transaction, rather than later when the affected element is first accessed.

`com.latenighthack.ktstore.collection.LazyMapList` is no longer used by the library and is deprecated. It now extends `AbstractList`, so remaining callers get correct equality and the full `List` API. Persisted payload and key encodings are unchanged.

Conformance coverage runs the list contract on memory, JDBC SQLite, IndexedDB, iOS simulator, macOS and Android instrumented backends, and the corrupt-read case on every serialized backend.
