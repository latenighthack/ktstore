package com.latenighthack.ktstore

import java.io.File
class MemoryConformanceTest : DatabaseConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate = InMemoryStoreDelegate()
}
class JdbcConformanceTest : PersistentConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate =
        SqlStoreDelegate(JdbcDriver(File(System.getProperty("java.io.tmpdir"), config.identity + ".db").absolutePath, "sqlite"), "BLOB", config)
}
