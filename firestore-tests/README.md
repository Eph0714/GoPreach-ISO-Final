# Firestore rules tests

Runs `../firestore.rules` (the real file, not a copy) against the Firestore
emulator using `@firebase/rules-unit-testing`, simulating real users
(Congregation Admin, Regular Elder, Secretary, Publisher, Super Admin, ...)
and asserting who can and can't read/write what.

This exists because `firebase deploy --only firestore:rules --dry-run` only
checks that the rules file *compiles* — it says nothing about whether the
logic actually does what you think. A real bug (a privilege-escalation
check that was silently bypassed by an older, unrelated `||` branch) was
only caught by actually running requests through the emulator, not by the
dry-run.

## Setup (one-time)

Needs **JDK 21+** for the emulator (`firebase-tools` 15+ no longer supports
older JDKs) — check with `java -version` first; if it's older, install one
(e.g. `winget install --id Microsoft.OpenJDK.21`) and point `JAVA_HOME` at
it for the commands below.

```sh
cd firestore-tests
npm install
```

## Running

```sh
cd firestore-tests
JAVA_HOME="/path/to/jdk-21" PATH="$JAVA_HOME/bin:$PATH" npm test
```

(On Windows Git Bash, e.g. `JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"`.)

Prints PASS/FAIL per scenario and a summary; exits non-zero if anything
failed.

## Adding a scenario

Add another `await (async () => { ... })().catch(...)` block to
`rules.test.mjs`, following the existing pattern — seed whatever documents
the scenario needs in the `withSecurityRulesDisabled` block up top, then
`assertSucceeds`/`assertFails` the actual read/write as a specific
authenticated user. Re-run every time `../firestore.rules` changes, before
deploying — a passing dry-run is not enough on its own.
