package example
import com.latenighthack.ktstore.*

fun upgradeUser(old: UserV1) = UserV2(old.id, old.name, true)
val usersMigration = mappedMigration(UsersV1, UsersV2, ::upgradeUser, reference = "example.usersMigration")
