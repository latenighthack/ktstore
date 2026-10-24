package com.latenighthack.ktstore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
class AndroidObserverTest : ObserverConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate =
        SqlStoreDelegate(AndroidSqliteDriver(File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, config.identity + ".db")), "BLOB", config)
}
