# Definition-backed stores and generated migrations

`StoreDefinition<T>` owns a store's stable name, payload codec, indexes, and primary key. It has no connection. `Store(database, definition)` binds it to a handle and retains the existing protected query/write methods. Existing constructors and index helpers remain supported.

```kotlin
object UsersV2 : StoreDefinition<UserV2>(
    StoreName("users"), "user-v2", ::decodeUserV2, ::encodeUserV2,
) {
    val id = integerIndex(IndexName("id"), UserV2::id)
    val name = stringIndex(IndexName("name"), UserV2::name)
    val enabled = booleanIndex(IndexName("enabled"), UserV2::enabled)
    init { primaryKey(id) }
}

class UsersStore(database: Database) : Store<UserV2>(database, UsersV2) {
    suspend fun findByName(value: String) = getAll(UsersV2.name.eq(value))
}
```

Define the final configuration using `UsersV2.declaration`. Definition-backed stores cannot add local indexes or bind to a different configured schema. Definitions freeze on first use; declaration snapshots and composite key metadata are copied defensively. Declare explicit persisted names. Changing a Kotlin property name alone must not change stored names.

Primitive and nullable helpers support strings, integers, legacy longs, booleans, and bytes. `mappedIndex` and `nullableMappedIndex` accept a `StorageCodec` and an explicit encoding identifier. `bytesIndex` also requires an encoding identifier. `compositeIndex(name, components...)` derives components in declared order. As with existing APIs, ordered queries require integer, boolean, or sortable binary keys; use explicit binary encodings for ordered strings/longs.

## Preserve history and define conversions

Keep each supported historical record, schema, codec, and conversion in immutable version-specific source files. A format identifier describes representation; it does not replace the historical decoder. Do not reuse an identifier for different bytes. Treat historical fixtures and transitive codec resources as immutable too.

```kotlin
fun upgradeUser(old: UserV1) = UserV2(old.id, old.name, enabled = true)

val usersMigration = mappedMigration(
    source = UsersV1,
    target = UsersV2,
    mapping = ::upgradeUser,
    reference = "example.usersMigration",
)

object UsersHistory : MigrationSpecification {
    val v1 = DatabaseVersion(1, listOf(UsersV1), "example.UsersHistory.v1")
    val v2 = DatabaseVersion(2, listOf(UsersV2), "example.UsersHistory.v2")
    override val catalog = MigrationCatalog(
        listOf(v1, v2),
        listOf(MigrationTransition(v1, v2, listOf(usersMigration))),
    )
    override val fixtures = listOf(usersFixture)
}
```

The catalog requires consecutive versions and complete schemas. Changed stores need an explicit typed mapping; added stores need `CreateStoreMigration`, and removed stores need `RemoveStoreMigration`. Renames pair different source/target names explicitly. Renames that overwrite another source, filtering, split/merge, and staged rename cycles are unsupported.

Unchanged stores require no operation. Mappings with unchanged storage structure use a transform. Changed fields, representations, primary/composite keys, and names require a rebuild on both backends. Payload-format changes alone still require an explicit mapping, but can use a transform.

Generated and runtime catalog migrations share the same typed helpers. They validate source structure, decode historical bytes, invoke each mapping once, derive and validate destination keys, and reject duplicate destination primary keys by value. The complete converted store is validated before replacement. All operations remain inside the existing SQLite/IndexedDB upgrade transaction. A failed database operation marks the migration failed even if caught.

Migration callbacks cannot await network calls, launch children, or change dispatchers. Definitions and mappings must be deterministic synchronous code. Stores are currently materialized in memory during migration; account for peak memory and keep transformations bounded.

Database versions, payload-format identifiers, and encoding identifiers are separate. No new format metadata is added to persisted databases. Legacy adoption still requires an explicit version increase and, for unversioned SQLite databases, `legacyVersion`. Explicitly convert legacy blob-as-text, decimal long, or null-sentinel representations; they are never rewritten implicitly.

## Generate reviewed sources

The JVM tool reads a compiled `MigrationSpecification`. It does not parse arbitrary Kotlin stores or serialize lambdas. References identify accessible Kotlin objects/properties; generated code uses the entry's typed source, target, and mapping directly.

Use a separate JVM-capable migration-spec project that depends on ktstore and contains historical definitions, named entries, and fixtures. Keep it independent of generated output. Consumers can compile those same common sources for their platform targets.

Resolve the plugin from Maven Central:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// build.gradle.kts
plugins {
    id("com.latenighthack.ktstore.migrations") version "0.2.1"
}
```

Apply `com.latenighthack.ktstore.migrations` in the consumer. Configure `storeMigrations` with:

- `providerClass`: JVM object/class implementing `MigrationSpecification` (objects use `INSTANCE`; classes need a no-argument constructor).
- `specificationReference` and `packageName`: qualified Kotlin references/package for generated sources.
- `toolClasspath`: the migration tool's JVM runtime plus compiled specification and its dependencies; attach compilation dependencies through `builtBy`.
- `historicalRoot` and `historicalInputs`: a common root and explicit historical source/resource files, including transitive codecs and fixtures. Do not include generated output or mutable catalog/build configuration in this list. Add new version-specific files as history grows.
- `outputDirectory`: defaults to `src/migrations`, owned exclusively by the generator.
- `verificationClasspath` and `verificationMainClass`: compiled generated sources plus an entry point executing the generated verifier against SQLite. Attach its compilation dependencies through `builtBy`.

`migration-example/build.gradle.kts` is a complete in-repository configuration. The tooling artifact is `com.latenighthack.ktstore:ktstore-migration-tooling`; use the same development/release version as the library. For local plugin development, use `pluginManagement { includeBuild("/path/to/ktstore/migration-gradle-plugin") }` instead of a published plugin version. The release workflow publishes the library, tooling, and plugin to Maven Central.

```sh
./gradlew :migration-example:generateStoreMigrations
./gradlew :migration-example:checkStoreMigrations
./gradlew :migration-example:verifyStoreMigrations
```

Generation writes Kotlin migrations under `main/`, a shared verification function under `test/`, `schemas.manifest`, `operations.md`, and `history.lock`. Check these in and review the operation report. Include `main/` in commonMain and `test/` in commonTest; invoke the shared verifier from each platform's persistent-backend test suite. The example JVM runner compiles both outputs and invokes the generated verifier.

Checking regenerates into a temporary directory and compares every artifact without rewriting committed output. Generation refuses to change or delete existing historical hashes or schemas. Keep supported history intact; removing obsolete baselines is an explicit application compatibility decision and requires separately reviewed manifest retirement.

`verifyStoreMigrations` runs catalog fixtures on JVM SQLite, checks generated artifacts, and executes the consumer's compiled generated migrations. It fails if the compiled verification entry point is missing. Android, Apple, and browser execution must also be wired into their real platform suites; JVM verification does not establish platform parity.

## Independent verification

Supply captured historical bytes, independently specified expected payloads and keys, and query assertions:

```kotlin
val usersFixture = MigrationFixture(
    name = "v1-users",
    baseline = 1,
    oldPayloads = mapOf(StoreName("users") to listOf("1|Alice".encodeToByteArray())),
    expectedRows = mapOf(StoreName("users") to listOf(
        StoreRow("1|Alice|true".encodeToByteArray(), listOf(
            UsersV2.id.key.bind(1),
            UsersV2.name.key.bind("Alice"),
            UsersV2.enabled.key.bind(true),
        )),
    )),
    assertions = {
        check(getAll(StoreName("users"), UsersV2.enabled.eq(true)).size == 1)
    },
)
```

Every supported historical baseline needs a fixture. Include every old and new store in its corresponding map, using empty lists where expected. A nonempty historical schema requires populated data. Never compute expected results by invoking the conversion under test.

The verifier creates the baseline, inserts original payload bytes with historical key bindings, upgrades using the generated migration list, checks counts and exact payloads by expected primary keys, executes query assertions, and checks fresh creation. The library additionally tests rollback, corruption, duplicate binary/composite keys, source mismatch, intermediate definitions, and legacy adoption on its persistent backends.

The immutable source lock and schema manifests detect declared changes; they cannot prove arbitrary codec/mapping semantics. Independent fixtures, generated-source compilation, cross-platform tests, and review remain necessary.
