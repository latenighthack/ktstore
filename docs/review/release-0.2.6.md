# Sparse binary equality in the in-memory store for 0.2.6

`InMemoryStoreDelegate` equality lookups on a nullable binary (`SerializedKey`) index, or a composite key with a binary component, no longer throw `NullPointerException` when some rows hold null for that index. Null rows are sparse and simply do not match, as on the SQL and IndexedDB backends. Null equality remains rejected.

Persisted payloads, keys, schemas and delegate transaction ownership are unchanged. The library and migration Gradle plugin versions advance together to 0.2.6.

Validation covers JVM in-memory and SQLite, IndexedDB/JS (Chromium), Android instrumentation, macOS, and iOS simulator conformance, including a new sparse binary equality test. Fullhouse consumer verification was not run. The release workflow reruns conformance before Maven Central publication.
