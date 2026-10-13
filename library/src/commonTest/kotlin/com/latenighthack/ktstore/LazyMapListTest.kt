package com.latenighthack.ktstore

import com.latenighthack.ktstore.collection.LazyMapList
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class LazyMapListTest {
    @Test fun accessIsLazyAndDecodedValuesAreSharedWithSubLists() {
        val decoded = mutableListOf<Int>()
        val rows = LazyMapList(listOf(1, 2, 3)) { decoded.add(it); Any() }
        assertEquals(3, rows.size)
        assertFalse(rows.isEmpty())
        val slice = rows.subList(1, 3)
        assertTrue(decoded.isEmpty())
        val second = slice[0]
        assertSame(second, rows[1])
        assertSame(second, rows.listIterator(1).next())
        assertEquals(listOf(2), decoded)
        assertFailsWith<IndexOutOfBoundsException> { rows[-1] }
        assertFailsWith<IndexOutOfBoundsException> { rows[3] }
        assertEquals(listOf(2), decoded)
    }

    @Test fun listOperationsHaveValueSemanticsAndDoNotRedecode() {
        var decodes = 0
        val rows = LazyMapList(listOf(1, 2, 2)) { decodes++; it.toString() }
        val expected = listOf("1", "2", "2")
        assertEquals(expected, rows)
        assertEquals(rows, expected)
        assertEquals(expected.hashCode(), rows.hashCode())
        assertTrue(rows.containsAll(expected))
        assertEquals(1, rows.indexOf("2"))
        assertEquals(2, rows.lastIndexOf("2"))
        assertEquals(expected, rows.listIterator().asSequence().toList())
        assertEquals(expected.drop(1), rows.subList(1, 3))
        assertEquals(3, decodes)
    }

    @Test fun concurrentAccessSharesOneDecode() = runTest {
        var decodes = 0
        val countLock = SpinLock()
        val rows = LazyMapList(listOf(1)) {
            countLock.withLock { decodes++ }
            Any()
        }
        val values = withContext(Dispatchers.Default) {
            (1..100).map { async { rows[0] } }.awaitAll()
        }
        assertEquals(1, decodes)
        values.forEach { assertSame(values.first(), it) }
    }

    @Test fun nullResultsAreCachedAndFailuresStayLazy() {
        var decodes = 0
        val rows = LazyMapList(listOf(0, 1)) { value ->
            decodes++
            if (value == 1) error("corrupt")
            null
        }
        assertEquals(0, decodes)
        assertNull(rows[0]); assertNull(rows[0])
        assertEquals(1, decodes)
        assertFailsWith<IllegalStateException> { rows[1] }
        assertEquals(2, decodes)
    }
}
