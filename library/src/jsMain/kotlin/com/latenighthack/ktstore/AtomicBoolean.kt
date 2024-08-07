package com.latenighthack.ktstore

actual class AtomicBoolean actual constructor(initialValue: Boolean) {
    private var internal: Boolean = initialValue

    actual var value: Boolean
        get() = internal
        set(value) {
            internal = value
        }

    actual fun compareAndSwap(expected: Boolean, new: Boolean): Boolean {
        return if (internal == expected) {
            internal = new

            new
        } else {
            internal
        }
    }
}

actual class AtomicReference<T> actual constructor(initialValue: T) {
    private var internal: T = initialValue

    actual var value: T
        get() = internal
        set(value) {
            internal = value
        }

    actual fun compareAndSwap(expected: T, new: T): T {
        return if (internal == expected) {
            internal = new

            new
        } else {
            internal
        }
    }
}

actual class AtomicInt actual constructor(initialValue: Int) {
    private var internal: Int = initialValue

    actual var value: Int
        get() = internal
        set(value) {
            internal = value
        }

    actual fun compareAndSwap(expected: Int, new: Int): Int {
        return if (internal == expected) {
            internal = new

            new
        } else {
            internal
        }
    }
}
