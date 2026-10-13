# Lazy reads and shared row caching for 0.2.5

`Store.getAll()` and `getMany()` return lazy `AbstractList` results. Standard equality, hashing, searching, iteration and sublists work without eagerly decoding all payloads.

`get`, `getAll`, and `getMany` populate one bounded row cache per store, shared by open handles with the same database identity. Fetched payloads and decoded objects use the same invalidation and eviction rules. Compatible typed store instances and result lists reuse decoded objects while resident. Backend primary-key metadata warms primary-key fetches even before list elements are decoded.

Primary-key hits and cached absences avoid fetching. Concurrent misses are batched, and `getMany` fetches only missing primary keys plus secondary-index queries in bounded batches, retaining relation order and duplicate matches. Whole-store and secondary-index queries still obtain membership from the backend. Transaction reads bypass the shared cache. Old result snapshots cannot resurrect invalidated rows, and overlapping writes cause mixed hit/miss reads to obtain a fresh backend snapshot.

Persisted payloads, keys, schemas and delegate transaction ownership are unchanged. The library and migration Gradle plugin versions advance together to 0.2.5.

Validation covers live PostgreSQL/JVM, IndexedDB/JS, Android instrumentation, macOS, iOS simulator, Chromium/Firefox/WebKit, and Fullhouse core/server tests plus Android, JS and Apple consumer compilation. The release workflow reruns conformance before Maven Central publication.
