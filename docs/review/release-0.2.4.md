# Definition-backed store preparation for 0.2.4

`Store.prepare()` no longer re-registers definition-backed stores. `Database.open()` already registers every configured declaration, and registering again replaced the delegate's table descriptors while other stores could be reading them. Stores declared with local indexes still register during preparation. Persisted payload and key encodings are unchanged.

Coverage verifies that preparing a definition-backed store after `open()` leaves the delegate's registrations untouched.
