package example
import com.latenighthack.ktstore.*
import example.generated.verifyGeneratedStoreMigrations
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.io.File

fun main() = runBlocking {
    val directory = Files.createTempDirectory("ktstore-example").toFile()
    try {
        verifyGeneratedStoreMigrations({ config -> SqlStoreDelegate(JdbcDriver(File(directory, config.identity + ".db").absolutePath, "sqlite"), "BLOB", config) }, "users")
    } finally { directory.deleteRecursively() }
}
