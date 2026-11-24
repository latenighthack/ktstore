# Bulk blob reads on Apple platforms for 0.2.8

`SqliteDriver.getBytes` copied each blob with cinterop's `readBytes`, which in the consuming application's device build copied one byte at a time through boxed values. A Full House cold start spent about five seconds of the SQLite thread there while reading 37.5 MB of connector journal rows. It now reads the blob pointer, then its size, and copies it with one `memcpy` into a pinned array. `AppleBlobReadTest` round-trips blobs from empty to 1 MiB byte-exactly; on device the same journal pages dropped from 200–300 ms to 7–55 ms.

Persisted payloads, keys, schemas and delegate transaction ownership are unchanged. The library and migration Gradle plugin versions advance together to 0.2.8.
