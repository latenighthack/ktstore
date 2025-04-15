package com.latenighthack.ktstore

import java.io.File

actual class PersistentKeyValueStoreDelegate actual constructor(private val storeName: String): KeyValueStoreDelegate {
    private val filePath = "$storeName.yaml"

    private val values = mutableMapOf<String, String>()
    private var isLoaded = false

    private suspend fun ensureContents() {
        if (!isLoaded) {
            isLoaded = true
            values.putAll(loadContents())
        }
    }

    actual override suspend fun getItem(key: String): String? {
        ensureContents()

        return values[key]
    }

    actual override suspend fun saveItem(key: String, value: String) {
        ensureContents()

        values[key] = value

        writeContents()
    }

    actual override suspend fun deleteItem(key: String) {
        ensureContents()

        values.remove(key)

        writeContents()
    }

    actual override suspend fun deleteAll() {
        values.clear()

        writeContents()
    }

    private suspend fun writeContents() {
        val yamlContent = buildString {
            values.forEach { (key, value) ->
                val escapedKey = if (key.contains(":") || key.contains(" ") || key.contains("\"")) {
                    val mappedKey = key.replace("\"", "\\\"")

                    "\"$mappedKey\""
                } else {
                    key
                }

                append("$escapedKey: $value\n")
            }
        }
        File(filePath).writeText(yamlContent)
    }

    private suspend fun loadContents(): MutableMap<String, String> {
        return mutableMapOf(
            *File(filePath).let { file ->
                if (!file.exists()) {
                    return@let emptyArray<Pair<String, String>>()
                }

                file
                    .readLines()
                    .mapNotNull { line ->
                        val trimmedLine = line.trim()

                        if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                            val colonIndex = trimmedLine.indexOf(":")

                            if (colonIndex != -1) {
                                val rawKey = trimmedLine.substring(0, colonIndex).trim()
                                val rawValue = trimmedLine.substring(colonIndex + 1).trim()

                                // Unescape the key if necessary
                                val key = if (rawKey.startsWith("\"") && rawKey.endsWith("\"")) {
                                    rawKey.substring(1, rawKey.length - 1).replace("\\\"", "\"")
                                } else {
                                    rawKey
                                }

                                Pair(key, rawValue)
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    .toTypedArray()
            }
        )
    }
}
