package example

import com.latenighthack.ktstore.*

data class UserV1(val id: Int, val name: String)
fun decodeUserV1(bytes: ByteArray): UserV1 {
    val fields = bytes.decodeToString().split('|')
    require(fields.size == 2)
    return UserV1(fields[0].toInt(), fields[1])
}
fun encodeUserV1(user: UserV1) = "${user.id}|${user.name}".encodeToByteArray()
object UsersV1 : StoreDefinition<UserV1>(StoreName("users"), "user-v1", ::decodeUserV1, ::encodeUserV1) {
    val id = integerIndex(IndexName("id"), UserV1::id)
    val name = stringIndex(IndexName("name"), UserV1::name)
    init { primaryKey(id) }
}
