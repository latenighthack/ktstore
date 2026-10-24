package com.latenighthack.ktstore

import java.io.File

class JdbcObserverTest : ObserverConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate =
        SqlStoreDelegate(JdbcDriver(File(System.getProperty("java.io.tmpdir"), config.identity + ".db").absolutePath, "sqlite"), "BLOB", config)
}
