# Security

## What the app keeps and where
- **Collection, inventory, scan history and learning, purchases:** the app's private storage. Other apps can't read it.
- **API tokens** (PriceCharting, backup-server sign-in): encrypted with a key held in the Android Keystore (`data/SecretStore.kt`). The key never leaves the phone. The encrypted values are left out of Android's backups, because they couldn't be decrypted on another phone anyway.
- **Debug log** (`files/logs/`): what the scanner read and did, plus crash traces. It holds no tokens or personal details. It's only shared when the user taps *Send debug log…*, and it's left out of backups.

## Network
- HTTPS only (`res/xml/network_security_config.xml`). Only the system's certificate authorities are trusted, not ones a user or another app installs.
- Beta (debug) builds may also use plain HTTP to `localhost`, `127.0.0.1` and `10.0.2.2`, for testing a backup server on the developer's computer. Release builds can't.
- Error messages never show raw exception text, because a request's address can contain an API token.

## App surface
- Only the launcher screen is exported. The file provider behind *Send debug log…* isn't exported, and it grants one-time read access to that single file.
- Release builds are not debuggable and are shrunk and obfuscated with R8.

## Signing releases
Android only installs an update if it's signed with the same key as the installed copy, so the signing key must be kept private and never lost.

- Releases so far were signed with the build computer's **debug key**. That's fine for a small group, but not for a public release: anyone who gets that key file can sign updates.
- To use a private release key, create one once. Store it outside the repository and back it up; it's needed for every future update.
  ```
  keytool -genkeypair -v -keystore card-companion-release.jks -alias card-companion -keyalg RSA -keysize 4096 -validity 10000
  ```
  Then create `keystore.properties` next to `settings.gradle.kts`. It's already ignored by git.
  ```
  storeFile=../card-companion-release.jks
  storePassword=…
  keyAlias=card-companion
  keyPassword=…
  ```
- Copies signed with the debug key can't be updated to the release key in place. Moving over means backing up (see the backup server), uninstalling, installing the new copy and restoring.

## Reporting a problem
Contact the maintainer privately rather than opening a public issue.
