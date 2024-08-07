package com.latenighthack.ktstore.collection

class LazyMapList<T, U>(
    private val list: List<T>,
    private val transform: ((T) -> U)
) : List<U> {
    private inner class MappedIterator(private val wrapped: Iterator<T>) : Iterator<U> {
        override fun hasNext(): Boolean = wrapped.hasNext()

        override fun next(): U = transform(wrapped.next())
    }

    override fun contains(element: U): Boolean = TODO("unsupported")

    override fun containsAll(elements: Collection<U>): Boolean = TODO("unsupported")

    override fun get(index: Int): U = transform.invoke(list[index])

    override fun indexOf(element: U): Int = TODO("unsupported")

    override fun isEmpty(): Boolean = list.isEmpty()

    override fun iterator(): Iterator<U> = MappedIterator(list.iterator())

    override fun lastIndexOf(element: U): Int = TODO("unsupported")

    override fun listIterator(): ListIterator<U> = TODO("unsupported")

    override fun listIterator(index: Int): ListIterator<U> = TODO("unsupported")

    override fun subList(fromIndex: Int, toIndex: Int): List<U> =
        LazyMapList(list.subList(fromIndex, toIndex), transform)

    override val size: Int = list.size
}
