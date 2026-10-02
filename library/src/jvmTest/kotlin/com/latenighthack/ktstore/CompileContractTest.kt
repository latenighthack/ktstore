package com.latenighthack.ktstore

import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.*

class CompileContractTest {
    private fun compile(code: String): Pair<ExitCode, String> {
        val directory = Files.createTempDirectory("ktstore-contract").toFile()
        try {
            val source = directory.resolve("Contract.kt").apply { writeText(code) }
            val dependencies = listOf(Store::class.java, Unit::class.java, kotlinx.coroutines.Job::class.java)
                .map { it.protectionDomain.codeSource.location.toURI().path }.distinct().joinToString(java.io.File.pathSeparator)
            val output = ByteArrayOutputStream()
            val exit = K2JVMCompiler().exec(PrintStream(output), "-no-stdlib", "-no-reflect", "-classpath", dependencies, "-d", directory.resolve("classes").path, source.path)
            return exit to output.toString()
        } finally { directory.deleteRecursively() }
    }
    @Test fun nominalIndexRejectsPrimitiveAndOtherNominalTypes() {
        val prefix = """
            import com.latenighthack.ktstore.*
            data class RequestId(val value: String)
            data class AccountId(val value: String)
            fun check(index: TypedIndex<Unit, RequestId>) {
        """.trimIndent()
        val valid = compile(prefix + "index.eq(RequestId(\"one\")) }")
        assertEquals(ExitCode.OK, valid.first, valid.second)
        for (argument in listOf("\"one\"", "AccountId(\"one\")")) {
            val invalid = compile(prefix + "index.eq($argument) }")
            assertEquals(ExitCode.COMPILATION_ERROR, invalid.first, invalid.second)
            assertTrue(invalid.second.contains("type mismatch", ignoreCase = true), invalid.second)
        }
    }
    @Test fun portableTransactionsRejectArbitrarySuspension() {
        val invalid = compile("""
            import com.latenighthack.ktstore.*
            import kotlinx.coroutines.delay
            suspend fun check(db: Database) {
                db.transaction(setOf(StoreName("records"))) { delay(1) }
            }
        """.trimIndent())
        assertEquals(ExitCode.COMPILATION_ERROR, invalid.first, invalid.second)
        assertTrue(invalid.second.contains("restricted", ignoreCase = true), invalid.second)
    }
}
