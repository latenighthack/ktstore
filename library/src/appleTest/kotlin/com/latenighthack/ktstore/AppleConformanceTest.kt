package com.latenighthack.ktstore
import platform.Foundation.NSTemporaryDirectory
class AppleConformanceTest : PersistentConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate =
        SqlStoreDelegate(SqliteDriver(NSTemporaryDirectory() + config.identity + ".db"), "BLOB", config)
}
