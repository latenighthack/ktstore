# Storage review consolidation for 0.2.2

Integrates the completed JDBC/indexed-operation and IndexedDB logical-owner review branches with the 0.2.1 legacy adoption changes. Initial adoption retains its frozen definition list, duplicate-key/corruption rollback coverage, PostgreSQL BIGINT conversion, and coroutine-scoped keyed transaction lifetime.

Adds bounded indexed operations within logical transactions, explicit external table ownership, database identity cleanup, finite PostgreSQL cancellation limits and atomic cross-store IndexedDB writers. Existing persisted payload and key encodings remain unchanged.

Local required PostgreSQL JVM, migration-tooling and migration-example verification passed on the integrated source. Full platform conformance remains a release gate.
