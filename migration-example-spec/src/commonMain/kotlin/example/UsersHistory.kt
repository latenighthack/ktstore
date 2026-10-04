package example
import com.latenighthack.ktstore.*

object UsersHistory : MigrationSpecification {
    val v1 = DatabaseVersion(1, listOf(UsersV1), "example.UsersHistory.v1")
    val v2 = DatabaseVersion(2, listOf(UsersV2), "example.UsersHistory.v2")
    override val catalog = MigrationCatalog(listOf(v1, v2), listOf(MigrationTransition(v1, v2, listOf(usersMigration))))
    override val fixtures = listOf(usersFixture)
}
