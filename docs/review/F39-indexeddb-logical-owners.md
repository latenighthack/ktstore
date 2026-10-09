# F39 — logical owners on real IndexedDB

The Lockers historical archive fixture and a direct two-handle browser regression
both failed because the JavaScript delegate rejected logical transactions as
unsupported advisory locks. Cache-only browser reopen tests had not exercised SDK
journal, event/ACK, or push mutations.

A logical owner now opens one IndexedDB READ_WRITE transaction spanning all declared
data stores. IndexedDB serializes overlapping writers across handles/tabs, which is
stronger than keyed serialization. Nested logical calls join the existing transaction
under the same store/mode guard; lifecycle and cancellation remain owned by that
transaction. No network suspension, coroutine launch or transaction keepalive was
introduced. Participating-store transactions remain the narrower public restricted
API; read-only/nested access restrictions still apply.

The actual Chrome test runs 20 interleaved updates through two handles and nested
logical owners, checks both counters reached20, then forces rollback of both stores
and verifies they remain20. Caught read-only misuse aborts the restricted scope.
All45 browser tests pass with zero failures/skips. JVM, Android and Apple main variants
compile. Red/green logs: /tmp/ktstore-logical-indexeddb-red.log and
/tmp/ktstore-logical-indexeddb-green.log. Lockers SDK actual backend mutation and
migration gates plus Fullhouse consumer verification use the new unique private pin
and are recorded in the consumer repository. No external publication occurred.
