# GoPreach — Setup

## Current status: live

This project is wired to a real Firebase project — **`gopreach-957a6`**
(console: https://console.firebase.google.com/project/gopreach-957a6/overview).

- ✅ Authentication (Email/Password) — enabled
- ✅ Firestore — database created, security rules deployed (`firestore.rules`)
- ✅ Android apps registered (`com.emfitsolutions.gopreach` and the `.debug`
  variant), `app/google-services.json` is the real config, not a placeholder
- ⏸️ **Storage is not set up** — Google now requires the Blaze (pay-as-you-go)
  plan to create a Storage bucket, which means linking a payment method. The
  app works fully without it; only the Super-Admin's Control Panel logo
  upload/replace feature needs it. To enable later: Firebase Console →
  Upgrade project → Blaze, then Storage → Get Started, then run
  `npx firebase-tools deploy --only storage --project gopreach-957a6`
  to push `storage.rules`.
- ✅ A working Super-Admin account exists (username `admin` — ask in-thread
  for the password rather than storing it here, since this file may end up
  somewhere more public than your machine)

## Bootstrapping another Super-Admin (or reference for how the first was made)

There's no in-app enrollment screen for Super-Admin — the spec has exactly
one administration root. It's created directly against Firebase:

1. Create a Firebase Auth user with email `{new-lowercase-id}@gopreach.internal`
   and a password. **The id must be lowercase-only** — Firebase Auth
   lowercases every email automatically, and [`AuthEmail.kt`](app/src/main/java/com/emfitsolutions/gopreach/data/repository/AuthEmail.kt)'s
   `personIdFromAuthEmail` has to recover the exact same id after sign-in, so
   any uppercase character breaks login permanently for that account. (See
   [`CredentialGenerator.newPersonId()`](app/src/main/java/com/emfitsolutions/gopreach/domain/CredentialGenerator.kt) —
   this is exactly why it generates lowercase-only ids.)
2. Create a `people/{that-id}` document matching [`Person`](app/src/main/java/com/emfitsolutions/gopreach/data/model/Person.kt)'s
   fields — set `username`, and `isTemporaryCredential: true` to force the
   normal first-login password-change flow (spec §4.5). **Do not include an
   `id` field in the document body** — `id` is `@DocumentId`-annotated, and
   Firestore throws on read (`toObject()`) if a document also stores a
   literal field with that name.
3. Create a `roleAssignments/{anyId}` document (same "no literal `id` field"
   rule applies): `personId` = that id, `roleType` = `"ADMIN:SUPER_ADMIN"`,
   `status` = `"ACTIVE"`.
4. **Also set `isSuperAdmin: true`** on that same `people/{that-id}` document
   (a plain boolean field, alongside `username` etc. from step 2). This is a
   denormalized copy of step 3 that only exists for `firestore.rules` — rules
   can't cheaply query "does this person have an active SUPER_ADMIN
   RoleAssignment" the way the app itself does, so `isSuperAdmin()` in the
   rules file checks this flag on the Person document directly instead. The
   in-app permission system (`PermissionChecker`) never reads this field —
   only the security rules do — but the User Access Management feature (who
   may create Circuit Overseer/custom users and edit their permissions) is
   enforced *server-side* using it, so a Super-Admin account created before
   this flag existed must have it added by hand, exactly like this step, or
   `userAccessGrants` writes will be rejected for that account. **There is no
   in-app way to create another Super-Admin or set this flag** — by design,
   matching how the very first Super-Admin is bootstrapped.
5. Log in from the app with the username you set and that password.

Every other account (Admins, Coordinator Elders, Regular Elders, Publishers)
is created from inside the app by an existing role, per spec §4 — those
flows already avoid both pitfalls above.

## Why login is by username but Firebase Auth uses email

See [`AuthEmail.kt`](app/src/main/java/com/emfitsolutions/gopreach/data/repository/AuthEmail.kt) —
each Person gets a synthetic `{personId}@gopreach.internal` Auth address;
[`Person.email`](app/src/main/java/com/emfitsolutions/gopreach/data/model/Person.kt)
stays a separate, optional, real contact field untouched by Auth.

## Redeploying security rules after an edit

```
npx firebase-tools deploy --only firestore:rules --project gopreach-957a6
```

## Building

```
./gradlew :app:assembleDebug
```

Requires the Android SDK (compileSdk 35) and JDK 17.

## Publishing a new version (auto-update)

GoPreach checks for updates against **GitHub Releases' own API** — see
[`UpdateManifestRepository`](app/src/main/java/com/emfitsolutions/gopreach/data/update/UpdateManifestRepository.kt)
for why that's the update server instead of a bespoke backend. This means
publishing a new version is just:

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`.
2. `./gradlew :app:assembleDebug` (or `:assembleRelease` once a real release
   signing config exists — see the note below).
3. `gh release create vX.Y.Z app/build/outputs/apk/debug/GoPreach-debug.apk --title "GoPreach vX.Y.Z" --notes "..."`
   — **a real new tag**, not re-uploading onto an existing one. The app
   compares its own version against whatever tag GitHub calls "latest," so
   reusing a tag means installed apps never see the update as available.

Nothing in the app needs to change for this — `UpdateManifestRepository`
always asks `releases/latest` for whatever's newest.

**Signing note**: the app is currently built and distributed as a
**debug-signed** APK (no dedicated release keystore exists yet in this
project). In-place updates work correctly between debug-signed builds made
from this same machine/keystore, since Android's Package Installer requires
an update to be signed with the same certificate as what's already
installed — that check is what actually protects the auto-update flow (see
[`UpdateInstaller`](app/src/main/java/com/emfitsolutions/gopreach/data/update/UpdateInstaller.kt)).
For a genuine production release, generate a proper release keystore, add a
`signingConfig` for the `release` build type, and build/sign every future
version with that same key — otherwise devices that installed a
debug-signed build can never auto-update to a release-signed one (Android
will refuse the install; a manual uninstall/reinstall would be needed).

## Release signing

A production build must be signed with GoPreach's own release key, never the debug key.

1. Create the key once and keep it (and its passwords) somewhere safe and backed up -- losing it means
   no future update can ever be installed over existing installs:
   `keytool -genkeypair -v -keystore gopreach-release.jks -alias gopreach -keyalg RSA -keysize 4096 -validity 10000`
2. Create `keystore.properties` in the project root (it is gitignored, never commit it):
   ```
   storeFile=C:/path/to/gopreach-release.jks
   storePassword=...
   keyAlias=gopreach
   keyPassword=...
   ```
3. `./gradlew assembleRelease` then signs with that key. Without `keystore.properties` the release build
   still falls back to the debug key and prints a WARNING -- do not distribute that build.
4. Always sign every future update with the same key.
