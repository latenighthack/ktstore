package com.latenighthack.ktstore
class IndexedDbConformanceTest : PersistentConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate = IndexDB(config)
}
