# Indexed work in logical-keyed transactions

The red regression demonstrated that quota/count and bounded pruning required a new scoped transaction, which was rejected inside a logical-keyed transaction. Public Database.query/count/deleteBatch now join the same handle's current transaction; calls outside one open the appropriate restricted transaction. Owner tags retain declared stores and read-only mode, including narrowed nested scopes. Index/continuation validation, lifecycle cancellation, rejected overlap, and a shared failure cause prevent caught invalid accesses from committing partial writes.

Verified common regressions for logical commit/rollback, read-only and nested-store bounds, undeclared indexes and concurrent helper rejection; an actual SQLite regression confirms pruning rolls back physically. Full JVM tests passed; JS, Android and Apple production source compilations passed. Helpers require sequential local database work and do not authorize network awaits. Existing unrestricted typed Store operations are unchanged.

Red log: /tmp/ktstore-review-F20-F33-red.log. Green: /tmp/ktstore-review-F20-F33-platforms.log.
