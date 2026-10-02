package com.latenighthack.ktstore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
class AndroidConformanceTest : PersistentConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate =
        SqlStoreDelegate(AndroidSqliteDriver(File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, config.identity + ".db")), "BLOB", config)
}
