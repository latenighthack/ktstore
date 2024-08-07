package com.latenighthack.ktstore

import android.content.Context

var getAppContext: (() -> Context)? = null

actual fun createStoreDelegate(db: String): StoreDelegate {
    return SqliteStoreDelegate(getAppContext!!.invoke(), db)
}