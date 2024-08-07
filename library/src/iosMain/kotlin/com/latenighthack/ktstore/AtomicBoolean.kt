@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.latenighthack.ktstore

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference

actual class AtomicBoolean actual constructor(initialValue: Boolean) {
    private val internal = AtomicInt(if (initialValue) 1 else 0)

    actual var value: Boolean
        get() = internal.value == 1
        set(value) {
            internal.value = if (value) 1 else 0
        }

    actual fun compareAndSwap(expected: Boolean, new: Boolean): Boolean {
        return internal.compareAndExchange(if (expected) 1 else 0, if (new) 1 else 0) == if (expected) 1 else 0
    }
}

actual class AtomicReference<T> actual constructor(initialValue: T) {
    private val internal = AtomicReference(initialValue)

    actual var value: T
        get() = internal.value
        set(value) {
            internal.value = value
        }

    actual fun compareAndSwap(expected: T, new: T): T {
        return internal.compareAndExchange(expected, new)
    }
}

actual class AtomicInt actual constructor(initialValue: Int) {
    private val internal = AtomicInt(initialValue)

    actual var value: Int
        get() = internal.value
        set(value) {
            internal.value = value
        }

    actual fun compareAndSwap(expected: Int, new: Int): Int {
        return internal.compareAndExchange(expected, new)
    }
}
