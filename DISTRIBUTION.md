# GoPreach – Trusted Distribution, Signing & Updates

Goal: make GoPreach a consistently signed, legitimately distributed Android app so Android and Google Play Protect have no reason to
treat it as suspicious. Nothing here hides, disables or works around Play Protect.

## 1. Audit (October 2026)

| Area | Finding | Status |
|---|---|---|
| Application ID | `com.emfitsolutions.gopreach`, never changed, same for every build | OK - keep it forever |
| Signing | Every shipped APK (including v1.133.0 and v1.134.0) is signed with Android's **public debug key** (SHA-256 `D2:5B:E9:DB:...:E7:78`). Its private key is public knowledge, so Play Protect cannot tell a GoPreach update from a forged one. **This is the main cause of the warnings.** | **Open - needs the production key (section 3)** |
| Build shipped | The GitHub releases so far were the *debug* variant (debuggable, not minified) | Fixed in the process: only `assembleRelease` goes through `scripts/release.mjs` |
| Cleartext HTTP | `usesCleartextTraffic="false"`; checked on every release | OK |
| Debuggable | Release variant is `isDebuggable=false`; checked on every release | OK |
| Backup | `allowBackup="false"` | OK |
| Secrets | `keystore.properties`, `*.jks`, `*.keystore`, `local.properties`, `ACCOUNTS.txt` are git-ignored; map/API keys come from `local.properties` | OK |
| Self-update | Downloads an APK from GitHub Releases and hands it to the Package Installer; needs `REQUEST_INSTALL_PACKAGES` | Allowed for direct distribution only; **must be removed from the Google Play build** (section 5) |
| Permissions | 19 in the built APK, all on `scripts/permission-allowlist.txt` with the feature that needs each | OK; gate blocks any new one |

## 2. Release gate (already in place)

`node scripts/verify-release.mjs <apk> --previous-version-code N` blocks a release unless: application id is correct, versionCode
increased, signature verifies, signing certificate equals the approved production certificate (`scripts/release-cert.sha256`) and is not a
debug certificate, the build is not debuggable, cleartext is off, and every permission is on the allow-list.

`node scripts/release.mjs` = unit tests -> `assembleRelease` -> gate -> `gh release create`. It never publishes a failed build.
`--legacy` accepts the current debug key with a warning (transitional only), `--dry-run` stops before publishing.
`scripts/release-history.json` is the release history (version, code, date, channel, status).

## 3. Creating the production signing key (do this once, keep it forever)

```
keytool -genkeypair -v -keystore gopreach-release.jks -alias gopreach -keyalg RSA -keysize 4096 -validity 10000
```
1. Store `gopreach-release.jks` in a password manager / encrypted backup in **two** places. Losing it means users can never update again.
2. Create `keystore.properties` (git-ignored) next to `build.gradle.kts`: `storeFile=...`, `storePassword=...`, `keyAlias=gopreach`, `keyPassword=...`.
3. `./gradlew assembleRelease`, then `keytool -printcert -jarfile app/build/outputs/apk/release/GoPreach.apk` and put its SHA-256 in
   `scripts/release-cert.sha256`. From then on the gate rejects any other certificate.

### Migration - decided: prepare now, switch later
Changing the signing key makes Android refuse to update an app signed with the old key, so every phone must **sync, uninstall and
reinstall once** (unsynced offline data is lost). Until you choose the day:
- keep releasing with `node scripts/release.mjs --legacy`;
- before the switch, tell users to sync, then switch, and put "uninstall and reinstall once" in the release notes.
Android 9+ also supports signing-key rotation with a lineage (`apksigner rotate`), which keeps data, but Google Play does not accept it.

## 4. Google Play (primary channel)

1. Create a Play Console developer account (one-time fee, identity verification) and the app `com.emfitsolutions.gopreach`.
2. Enable **Play App Signing**; build an **AAB** (`./gradlew :app:bundleRelease`) signed with the *upload* key (section 3).
3. Release path: Internal testing -> Closed testing -> Production. Never publish a lower versionCode.
4. Listing: accurate name/description/screenshots, privacy policy URL, Data safety form, support email, category, permission disclosure
   (location is used for the territory map and the optional Share Location feature; camera for the optional place photo).
5. Because the Play listing is signed by Google's key, phones that installed the old debug-signed APK also need one reinstall.

### Play build differences (to implement when the Play account exists)
- Remove `REQUEST_INSTALL_PACKAGES` and the self-update downloader from the Play variant (a `play` product flavor).
- Replace it with the Play in-app update API (`com.google.android.play:app-update-ktx`): *Optional* = "A new version of GoPreach is
  available" with Update Now / Later; *Required* = "GoPreach requires an update to continue" with a single Update GoPreach button.
- The only update link allowed is the official Play listing.
- Optionally add the Play Integrity API as an extra backend signal (never instead of authentication).

## 5. Direct distribution (until Play is live)
Same application id and production key, built by `scripts/release.mjs`, published over HTTPS on GitHub Releases, version code strictly
increasing, no manual re-signing or renaming, no replacement of an existing tag.

## 6. If Play Protect still warns
Do not tell users to turn it off. Identify the exact APK, confirm its certificate matches `release-cert.sha256`, review third-party SDKs and
native libraries, then use Google's appeal for incorrectly flagged apps.

## 7. Dependencies (high level)
| Dependency | Purpose | Network | Notes |
|---|---|---|---|
| Firebase Auth / Firestore / Storage | accounts, sync, files | Google (HTTPS) | required |
| Google Play services Location | position on the map | none direct | location permission |
| Ktor (OkHttp) | backend / update calls | HTTPS | cleartext disabled |
| Room, WorkManager, Koin, Compose, AndroidX | local database, background sync, UI | none | |
| AndroidX Security-Crypto, Biometric | secure storage, fingerprint / face | none | |
Review on every release; remove anything unused.

## 8. Acceptance checklist
- [ ] Production keystore created and its SHA-256 registered
- [ ] Clean install, v1 -> v2 update accepted as the same app, data and offline records intact
- [ ] Release build (R8 minified) tested on a device - it has only been built, not run
- [ ] Play Console listing, Internal testing track, Data safety form
- [ ] Play build without `REQUEST_INSTALL_PACKAGES`, in-app update flow
- [x] HTTPS only, not debuggable, permission allow-list, release gate, release history
