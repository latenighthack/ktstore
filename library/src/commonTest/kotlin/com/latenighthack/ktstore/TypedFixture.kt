package com.latenighthack.ktstore

internal data class RequestId(val value: String)
internal data class RequestRecord(val id: RequestId, val account: RequestId?)
internal object RequestIdCodec : StorageCodec<RequestId, String> {
    override fun encode(value: RequestId) = value.value
    override fun key(name: IndexName) = StoreKey.StringKey(name.value)
}
internal fun writeRequest(record: RequestRecord) = (record.id.value + ":" + (record.account?.value ?: "")).encodeToByteArray()
internal fun readRequest(data: ByteArray): RequestRecord {
    val fields = data.decodeToString().split(':')
    require(fields.size == 2)
    return RequestRecord(RequestId(fields[0]), fields[1].takeIf { it.isNotEmpty() }?.let { RequestId(it) })
}
internal class RequestStore(db: Database, name: StoreName) : Store<RequestRecord>(db, name, ::writeRequest, ::readRequest) {
    val idIndex = mappedIndex(IndexName("id"), RequestRecord::id, RequestIdCodec)
    val accountIndex = nullableMappedIndex(IndexName("account"), RequestRecord::account, RequestIdCodec)
    init { primaryKey(idIndex) }
}
