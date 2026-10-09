# F25: finite PostgreSQL operations

An actual PostgreSQL advisory-lock wait reproduced a JDBC operation that could outlive coroutine cancellation indefinitely. The initial regression run also showed that the driver's default URL left network timeouts disabled.

`JdbcDriver` now applies finite connection, socket, cancellation-signal, statement, and lock limits through `PostgresJdbcLimits`. Positive stricter URL/server settings remain in effect. SQLite behavior is unchanged; the original two-argument JVM constructor remains available. A cancelled SQL operation rolls back before rethrowing its coroutine cancellation rather than replacing it with a timeout exception.

Cancellation of a blocked JDBC call can take until its configured server or transport deadline. Defaults are 5 seconds for locks, 15 seconds for statements and 30 seconds for socket reads. Applications can supply a validated finite policy for longer operations.

The regression uses two real PostgreSQL connections, waits until `pg_stat_activity` reports the blocked lock, cancels the coroutine, and verifies joined termination with a 250 ms lock limit. It also verifies preservation of a stricter 75 ms server lock setting and URL credentials. Initial run: 3 tests, 2 failures. Fixed full JVM run: 64 tests passed.

Configuration semantics: [pgJDBC connection parameters](https://jdbc.postgresql.org/documentation/use/) and [PostgreSQL statement and lock timeouts](https://www.postgresql.org/docs/current/runtime-config-client.html).
