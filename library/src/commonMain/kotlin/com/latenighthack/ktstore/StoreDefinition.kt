package com.latenighthack.ktstore

import kotlin.reflect.KProperty1

/** An app-owned historical schema and codec. No database connection is needed. */
open class StoreDefinition<T>(
    val storeName: StoreName,
    val payloadFormat: String,
    private val reader: (ByteArray) -> T,
    private val writer: (T) -> ByteArray,
) {
    init { require(payloadFormat.isNotBlank()) }
    private data class Field<T>(val index: TypedIndex<T, *>, val encoding: String, val value: (T) -> BoundStoreKey)
    private val fields = mutableListOf<Field<T>>()
    private var primary: TypedIndex<T, *>? = null
    private var frozen: StoreDeclaration? = null

    private fun mutable() { check(frozen == null) { "Store definition is frozen" } }
    private fun <V> index(key: StoreKey<*>, encoding: String, bind: (V) -> BoundStoreKey, value: (T) -> V): TypedIndex<T, V> {
        mutable()
        require(encoding.isNotBlank() && fields.none { it.index.name.value == key.name })
        val index = TypedIndex<T, V>(IndexName(key.name), key, bind)
        fields.add(Field(index, encoding) { bind(value(it)) })
        return index
    }
    protected fun stringIndex(name: IndexName, property: KProperty1<T, String>) =
        index(StoreKey.StringKey(name.value), "string", { value: String -> StoreKey.StringKey(name.value).bind(value) }, property::get)
    protected fun integerIndex(name: IndexName, property: KProperty1<T, Int>) =
        index(StoreKey.IntegerKey(name.value), "int", { value: Int -> StoreKey.IntegerKey(name.value).bind(value) }, property::get)
    protected fun longIndex(name: IndexName, property: KProperty1<T, Long>) =
        index(StoreKey.LongKey(name.value), "legacyLong", { value: Long -> StoreKey.LongKey(name.value).bind(value) }, property::get)
    protected fun booleanIndex(name: IndexName, property: KProperty1<T, Boolean>) =
        index(StoreKey.BooleanKey(name.value), "boolean", { value: Boolean -> StoreKey.BooleanKey(name.value).bind(value) }, property::get)
    protected fun bytesIndex(name: IndexName, property: KProperty1<T, ByteArray>, encoding: String) =
        index(StoreKey.SerializedKey(name.value), encoding, { value: ByteArray -> StoreKey.SerializedKey(name.value).bind(value) }, property::get)
    protected fun <V, R> mappedIndex(name: IndexName, property: KProperty1<T, V>, codec: StorageCodec<V, R>, encoding: String): TypedIndex<T, V> {
        val key = codec.key(name)
        require(key.name == name.value && key !is StoreKey.CompositeKey)
        return index(key, encoding, { value: V -> key.bind(codec.encode(value)) }, property::get)
    }
    protected fun <V : Any, R> nullableMappedIndex(name: IndexName, property: KProperty1<T, V?>, codec: StorageCodec<V, R>, encoding: String): TypedIndex<T, V> {
        val original = codec.key(name)
        require(original.name == name.value)
        val key = original.nullableCopy()
        mutable()
        require(encoding.isNotBlank() && fields.none { it.index.name == name })
        val index = TypedIndex<T, V>(name, key) { key.bind(codec.encode(it)) }
        fields.add(Field(index, encoding) { record -> key.bind(property.get(record)?.let(codec::encode)) })
        return index
    }
    protected fun nullableStringIndex(name: IndexName, property: KProperty1<T, String?>): TypedIndex<T, String> =
        nullableMappedIndex(name, property, object : StorageCodec<String, String> {
            override fun encode(value: String) = value
            override fun key(name: IndexName) = StoreKey.StringKey(name.value)
        }, "string")
    protected fun nullableIntegerIndex(name: IndexName, property: KProperty1<T, Int?>): TypedIndex<T, Int> =
        nullableMappedIndex(name, property, object : StorageCodec<Int, Int> {
            override fun encode(value: Int) = value
            override fun key(name: IndexName) = StoreKey.IntegerKey(name.value)
        }, "int")
    protected fun nullableBooleanIndex(name: IndexName, property: KProperty1<T, Boolean?>): TypedIndex<T, Boolean> =
        nullableMappedIndex(name, property, object : StorageCodec<Boolean, Boolean> {
            override fun encode(value: Boolean) = value
            override fun key(name: IndexName) = StoreKey.BooleanKey(name.value)
        }, "boolean")
    protected fun nullableLongIndex(name: IndexName, property: KProperty1<T, Long?>): TypedIndex<T, Long> =
        nullableMappedIndex(name, property, object : StorageCodec<Long, Long> {
            override fun encode(value: Long) = value
            override fun key(name: IndexName) = StoreKey.LongKey(name.value)
        }, "legacyLong")
    protected fun nullableBytesIndex(name: IndexName, property: KProperty1<T, ByteArray?>, encoding: String): TypedIndex<T, ByteArray> =
        nullableMappedIndex(name, property, object : StorageCodec<ByteArray, ByteArray> {
            override fun encode(value: ByteArray) = value
            override fun key(name: IndexName) = StoreKey.SerializedKey(name.value)
        }, encoding)
    protected fun compositeIndex(name: IndexName, vararg components: TypedIndex<T, *>): TypedIndex<T, List<BoundStoreKey>> {
        require(components.isNotEmpty() && components.all { component -> fields.any { it.index === component } && component.key !is StoreKey.CompositeKey })
        val key = StoreKey.CompositeKey(name.value, components.map { it.name.value })
        val getters = components.map { component -> fields.single { it.index === component }.value }
        return index(key, "composite", { value: List<BoundStoreKey> -> key.bind(value) }) { record -> getters.map { it(record) } }
    }
    protected fun primaryKey(index: TypedIndex<T, *>) {
        mutable()
        require(fields.any { it.index === index })
        primary = index
    }
    val declaration: StoreDeclaration get() {
        val snapshot = frozen ?: StoreDeclaration(storeName, fields.map { it.index.key }, requireNotNull(primary) { "Primary key is required" }.key).snapshot().also { frozen = it }
        return snapshot.snapshot()
    }
    val indexEncodings: Map<String, String> get() { declaration; return fields.associate { it.index.name.value to it.encoding } }
    fun encodePayload(value: T): ByteArray { declaration; return writer(value).copyOf() }
    fun decode(bytes: ByteArray): T {
        declaration
        try { return reader(bytes.copyOf()) }
        catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw StoreFailure.CorruptRecord(error)
        }
    }
    fun encodeRow(value: T): StoreRow {
        val schema = declaration
        return StoreRow(encodePayload(value), copyKeys(normalizeKeys(schema.keys, fields.map { it.value(value) })))
    }
}

internal fun StoreKey<*>.nullableCopy(): StoreKey<*> = when (this) {
    is StoreKey.StringKey -> StoreKey.StringKey(name, true)
    is StoreKey.IntegerKey -> StoreKey.IntegerKey(name, true)
    is StoreKey.LongKey -> StoreKey.LongKey(name, true)
    is StoreKey.BooleanKey -> StoreKey.BooleanKey(name, true)
    is StoreKey.SerializedKey -> StoreKey.SerializedKey(name, true)
    is StoreKey.CompositeKey -> throw StoreFailure.InvalidUsage("Declare nullable composite components separately")
}
internal fun StoreDeclaration.snapshot(): StoreDeclaration {
    val copied = keys.map { if (it is StoreKey.CompositeKey) StoreKey.CompositeKey(it.name, it.names.toList()) else it }
    return StoreDeclaration(name, copied, copied.single { it.name == primaryKey.name })
}
internal fun StoreDeclaration.signature(): String = DatabaseConfiguration("schema", 1, listOf(this)).fingerprint()
