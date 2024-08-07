@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.latenighthack.ktstore

expect class AtomicInt(initialValue: Int) {
    var value: Int

    fun compareAndSwap(expected: Int, new: Int): Int
}

expect class AtomicBoolean(initialValue: Boolean) {
    var value: Boolean

    fun compareAndSwap(expected: Boolean, new: Boolean): Boolean
}

expect class AtomicReference<T>(initialValue: T) {
    var value: T

    fun compareAndSwap(expected: T, new: T): T
}
