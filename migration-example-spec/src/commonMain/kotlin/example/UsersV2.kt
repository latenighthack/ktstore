package example

import com.latenighthack.ktstore.*

data class UserV2(val id: Int, val name: String, val enabled: Boolean)
fun decodeUserV2(bytes: ByteArray): UserV2 {
    val fields = bytes.decodeToString().split('|')
    require(fields.size == 3)
    return UserV2(fields[0].toInt(), fields[1], fields[2].toBooleanStrict())
}
fun encodeUserV2(user: UserV2) = "${user.id}|${user.name}|${user.enabled}".encodeToByteArray()
object UsersV2 : StoreDefinition<UserV2>(StoreName("users"), "user-v2", ::decodeUserV2, ::encodeUserV2) {
    val id = integerIndex(IndexName("id"), UserV2::id)
    val name = stringIndex(IndexName("name"), UserV2::name)
    val enabled = booleanIndex(IndexName("enabled"), UserV2::enabled)
    init { primaryKey(id) }
}
class UsersStore(database: Database) : Store<UserV2>(database, UsersV2) {
    suspend fun findByName(value: String): List<UserV2> = getAll(UsersV2.name.eq(value))
    suspend fun put(value: UserV2) = save(value)
}
