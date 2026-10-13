package com.latenighthack.ktstore.collection

@Deprecated("Store.getAll now decodes eagerly; map the source list instead.", ReplaceWith("list.map(transform)"))
class LazyMapList<T, U>(
    private val list: List<T>,
    private val transform: ((T) -> U)
) : AbstractList<U>() {
    override val size: Int get() = list.size

    override fun get(index: Int): U = transform(list[index])
}
