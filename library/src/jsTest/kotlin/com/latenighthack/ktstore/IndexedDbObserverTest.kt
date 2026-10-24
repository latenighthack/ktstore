package com.latenighthack.ktstore

import kotlin.test.Test

class IndexedDbObserverTest : ObserverConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate = IndexDB(config)

    // Register these explicitly: the JS test adapter does not discover every inherited test.
    @Test fun verifyMigrationEvents() = migrationEventsCoverFreshCurrentAndEachHistoricalUpgrade()
    @Test fun verifyFailingMigrationStep() = failingMigrationStepIsNamedAndRethrownUnchanged()
    @Test fun verifyOperationEvents() = operationsReportStoresKindsCountsAndOutcomes()
    @Test fun verifyObserverFailures() = observerFailuresAreSwallowed()
    @Test fun verifyCancellation() = cancellationMidTransactionIsReportedAndPropagates()
    @Test fun verifySamePaths() = observedOperationsTakeTheSamePaths()
}
