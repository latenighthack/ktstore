package example
import com.latenighthack.ktstore.*

// Historical bytes are literals, not re-encoded using today's historical writer.
val usersFixture = MigrationFixture(
    name = "v1-users",
    baseline = 1,
    oldPayloads = mapOf(StoreName("users") to listOf("1|Alice".encodeToByteArray(), "2|Bob".encodeToByteArray())),
    expectedRows = mapOf(StoreName("users") to listOf(
        StoreRow("1|Alice|true".encodeToByteArray(), listOf(UsersV2.id.key.bind(1), UsersV2.name.key.bind("Alice"), UsersV2.enabled.key.bind(true))),
        StoreRow("2|Bob|true".encodeToByteArray(), listOf(UsersV2.id.key.bind(2), UsersV2.name.key.bind("Bob"), UsersV2.enabled.key.bind(true))),
    )),
    assertions = {
        check(count(StoreName("users"), UsersV2.enabled.query(10, lower = true, upper = true)) == 2L)
        check(getAll(StoreName("users"), UsersV2.name.eq("Alice")).size == 1)
        val page = query(StoreName("users"), UsersV2.enabled.query(1))
        check((page.records.single() as ByteArray).contentEquals("1|Alice|true".encodeToByteArray()))
        val next = query(StoreName("users"), UsersV2.enabled.query(1, after = page.continuation))
        check((next.records.single() as ByteArray).contentEquals("2|Bob|true".encodeToByteArray()))
    },
)
