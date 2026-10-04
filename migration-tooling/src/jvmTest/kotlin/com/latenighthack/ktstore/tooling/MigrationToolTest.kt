package com.latenighthack.ktstore.tooling

import com.latenighthack.ktstore.*
import example.UsersHistory
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class MigrationToolTest {
    @Test fun deterministicArtifactsRequireIndependentFixturesAndQualifiedReferences() {
        val first = MigrationTool.generate(UsersHistory, "example.generated", "example.UsersHistory")
        assertEquals(first, MigrationTool.generate(UsersHistory, "example.generated", "example.UsersHistory"))
        assertTrue(first.getValue("main/GeneratedStoreMigrations.kt").contains("rebuildMappedStore(example.usersMigration.source"))
        assertTrue(first.getValue("test/GeneratedMigrationVerification.kt").contains("generatedStoreMigrations"))
        assertFailsWith<IllegalArgumentException> { MigrationTool.generate(UsersHistory, "example.generated", "not valid") }
        val missing = object : MigrationSpecification {
            override val catalog = UsersHistory.catalog
            override val fixtures = emptyList<MigrationFixture>()
        }
        assertFailsWith<IllegalArgumentException> { MigrationTool.generate(missing, "example.generated", "example.UsersHistory") }
    }
    @Test fun checkNeverRewritesAndGenerationCannotRebaselineHistoricalInputs() {
        val root = Files.createTempDirectory("history-lock").toFile()
        try {
            val historical = File(root, "v1.kt").apply { writeText("old bytes") }
            val output = File(root, "output")
            val artifacts = MigrationTool.generate(UsersHistory, "example.generated", "example.UsersHistory") +
                ("history.lock" to MigrationTool.historyHashes(root, listOf(historical)))
            MigrationTool.writeOrCheck(output, artifacts, false)
            MigrationTool.writeOrCheck(output, artifacts, true)
            val generated = File(output, "main/GeneratedStoreMigrations.kt")
            generated.appendText("// edited\n")
            val before = generated.readBytes()
            assertFailsWith<IllegalArgumentException> { MigrationTool.writeOrCheck(output, artifacts, true) }
            assertContentEquals(before, generated.readBytes())
            historical.writeText("different bytes")
            val changed = artifacts + ("history.lock" to MigrationTool.historyHashes(root, listOf(historical)))
            assertFailsWith<IllegalArgumentException> { MigrationTool.writeOrCheck(output, changed, false) }
            assertContentEquals(before, generated.readBytes())
            val v2 = File(root, "v2.kt").apply { writeText("new version") }
            historical.writeText("old bytes")
            val extended = artifacts + ("history.lock" to MigrationTool.historyHashes(root, listOf(v2, historical)))
            MigrationTool.writeOrCheck(output, extended, false)
            MigrationTool.writeOrCheck(output, extended, true)
        } finally { root.deleteRecursively() }
    }
}
