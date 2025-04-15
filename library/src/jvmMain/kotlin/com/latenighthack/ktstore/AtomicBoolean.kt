package com.latenighthack.ktstore

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

actual class AtomicBoolean actual constructor(initialValue: Boolean) {
    private val internal = AtomicBoolean(initialValue)

    actual var value: Boolean
        get() = internal.get()
        set(value) {
            internal.set(value)
        }

    actual fun compareAndSwap(expected: Boolean, new: Boolean): Boolean {
        return internal.compareAndSet(expected, new)
    }
}

actual class AtomicReference<T> actual constructor(initialValue: T) {
    private val internal = AtomicReference(initialValue)

    actual var value: T
        get() = internal.get()
        set(value) {
            internal.set(value)
        }

    actual fun compareAndSwap(expected: T, new: T): T {
        return if (internal.compareAndSet(expected, new)) {
            new
        } else {
            expected
        }
    }
}

actual class AtomicInt actual constructor(initialValue: Int) {
    private val internal = AtomicInteger(initialValue)

    actual var value: Int
        get() = internal.get()
        set(value) {
            internal.set(value)
        }

    actual fun compareAndSwap(expected: Int, new: Int): Int {
        return if (internal.compareAndSet(expected, new)) {
            new
        } else {
            expected
        }
    }
}
