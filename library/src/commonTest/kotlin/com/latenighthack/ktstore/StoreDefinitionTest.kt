package com.latenighthack.ktstore

import example.*
import kotlin.test.*

class StoreDefinitionTest {
    private data class Record(val id: Int, val note: String?, val bytes: ByteArray)
    private class Definition : StoreDefinition<Record>(StoreName("records"), "record-1", { Record(1, null, it) }, { it.bytes }) {
        val id = integerIndex(IndexName("id"), Record::id)
        val note = nullableStringIndex(IndexName("note"), Record::note)
        val bytes = bytesIndex(IndexName("bytes"), Record::bytes, "raw-1")
        val compound = compositeIndex(IndexName("compound"), id, bytes)
        init { primaryKey(compound) }
        fun extend() = integerIndex(IndexName("late"), Record::id)
    }
    @Test fun definitionFreezesAndReturnsDefensiveCompositeDeclarations() {
        val definition = Definition()
        val declaration = definition.declaration
        (declaration.keys as MutableList).clear()
        val composite = definition.declaration.primaryKey as StoreKey.CompositeKey
        (composite.names as MutableList).clear()
        assertEquals(listOf("id", "bytes"), (definition.declaration.primaryKey as StoreKey.CompositeKey).names)
        assertEquals(4, definition.declaration.keys.size)
        assertFailsWith<IllegalStateException> { definition.extend() }
        val row = definition.encodeRow(Record(1, null, byteArrayOf(2)))
        assertTrue(row.keys.single { it.name == "note" } is BoundStoreKey.NullKey)
        assertEquals(2, (row.keys.single { it.name == "compound" } as BoundStoreKey.CompositeKey).values.size)
    }
    @Test fun definitionBackedStoreRejectsSchemaDriftAndLocalIndexes() {
        val config = UsersHistory.v2.configuration("definitions")
        val db = Database(config, InMemoryStoreDelegate())
        UsersStore(db)
        assertFailsWith<IllegalStateException> {
            object : Store<UserV2>(db, UsersV2) {
                val extra = stringIndex(UserV2::name)
            }
        }
        val mismatch = Database(UsersHistory.v1.configuration("old"), InMemoryStoreDelegate())
        assertFailsWith<IllegalArgumentException> { UsersStore(mismatch) }
    }
    @Test fun catalogsRequireExplicitChangesAndConsecutiveSteps() {
        assertFailsWith<IllegalArgumentException> { MigrationTransition(UsersHistory.v1, UsersHistory.v2, emptyList()) }
        val third = DatabaseVersion(3, listOf(UsersV2))
        assertFailsWith<IllegalArgumentException> { MigrationTransition(UsersHistory.v1, third, listOf(usersMigration)) }
        assertFailsWith<IllegalArgumentException> { MigrationCatalog(listOf(UsersHistory.v1, UsersHistory.v2), emptyList()) }
        assertFailsWith<IllegalArgumentException> { MigrationTransition(UsersHistory.v1, UsersHistory.v2, listOf(usersMigration, usersMigration)) }
        assertFailsWith<IllegalArgumentException> { MigrationVerifier(UsersHistory.catalog, emptyList()).validateFixtures() }
    }
    @Test fun decoderFailuresHaveTypedCauses() {
        val error = assertFailsWith<StoreFailure.CorruptRecord> { UsersV1.decode("broken".encodeToByteArray()) }
        assertNotNull(error.cause)
    }    @Test fun catalogRejectsRenameCollisionsAndRequiresDestructiveEntries() {
        fun definition(name: String) = object : StoreDefinition<UserV1>(StoreName(name), "user-v1", ::decodeUserV1, ::encodeUserV1) {
            val id = integerIndex(IndexName("id"), UserV1::id)
            init { primaryKey(id) }
        }
        val a = definition("a")
        val b = definition("b")
        val destination = definition("b")
        val old = DatabaseVersion(1, listOf(a, b))
        val new = DatabaseVersion(2, listOf(destination))
        assertFailsWith<IllegalArgumentException> {
            MigrationTransition(old, new, listOf(mappedMigration(a, destination, { it }), RemoveStoreMigration(b)))
        }
        val empty = DatabaseVersion(2, emptyList())
        assertFailsWith<IllegalArgumentException> { MigrationTransition(old, empty, emptyList()) }
        MigrationTransition(old, empty, listOf(RemoveStoreMigration(a), RemoveStoreMigration(b)))
    }
    @Test fun encodedRowsSnapshotPayloadsAndBinaryKeys() {
        val definition = Definition()
        val bytes = byteArrayOf(2)
        val row = definition.encodeRow(Record(1, null, bytes))
        bytes[0] = 9
        assertContentEquals(byteArrayOf(2), row.data as ByteArray)
        assertContentEquals(byteArrayOf(2), (row.keys.single { it.name == "bytes" } as BoundStoreKey.SerializedKey).value)
        val composite = row.keys.single { it.name == "compound" } as BoundStoreKey.CompositeKey
        assertContentEquals(byteArrayOf(2), (composite.values[1] as BoundStoreKey.SerializedKey).value)
    }

}
