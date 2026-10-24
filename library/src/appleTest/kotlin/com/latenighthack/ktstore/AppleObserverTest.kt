package com.latenighthack.ktstore
import platform.Foundation.NSTemporaryDirectory
class AppleObserverTest : ObserverConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate =
        SqlStoreDelegate(SqliteDriver(NSTemporaryDirectory() + config.identity + ".db"), "BLOB", config)
}
