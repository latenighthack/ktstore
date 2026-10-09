package com.latenighthack.ktstore

import kotlin.test.Test

class IndexedDbConformanceTest : PersistentConformance() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate = IndexDB(config)

    @Test fun verifyDefinitionAdoption() = definitionAdoptionPreservesOriginalPayloadAndReopens()
    @Test fun verifyOptionalDefinitionAdoption() = definitionAdoptionCreatesPreviouslyUnregisteredStores()
    @Test fun verifyDefinitionAdoptionCollision() = definitionAdoptionRejectsDuplicateDerivedKeysWithoutLosingOldRows()

    @Test fun verifyAdoptionBinaryCollisions() = definitionAdoptionRejectsBinaryAndCompositeKeyCollisions()
    @Test fun verifyAdoptionCorruptionRollback() = definitionAdoptionCorruptionRollsBackOptionalStoresAndVersion()

    // Register these explicitly: the JS test adapter does not discover every inherited test.
    @Test fun verifyGeneratedDefinitionMigrations() = generatedMigrationsVerifyHistoricalFixtures()
    @Test fun verifyDefinitionBackedStoreUsage() = definitionBackedStoresShareEncodingAndQueries()
    @Test fun verifyTypedFailureRollback() = typedMigrationCollisionAndCorruptionRollBack()
    @Test fun verifyIntermediateDefinitions() = typedMultiStepMigrationUsesIntermediateDefinitions()
    @Test fun verifyInvalidSourceAndCaughtFailure() = typedMigrationRejectsIncorrectSourceWithoutRunningMapping()
    @Test fun verifyBinaryCompositeCollision() = typedBinaryCompositeCollisionsComparePersistedValues()
    @Test fun verifySchemaCreationRemoval() = typedCreateAndRemoveVerifyEmptyDatabaseSchemas()
}
