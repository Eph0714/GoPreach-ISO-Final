#!/usr/bin/env node
// Controlled release: build the RELEASE variant, run the release gate, and only then publish to GitHub Releases.
//
//   node scripts/release.mjs            # production: requires the approved release key (see docs/DISTRIBUTION.md)
//   node scripts/release.mjs --legacy   # transitional: the current debug-signed key is accepted with a loud warning
//   node scripts/release.mjs --dry-run  # build + verify only, never publish
//
// Nobody renames or edits the APK between signing and publishing; the file that was verified is the file that is uploaded.
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const legacy = process.argv.includes("--legacy");
const dry = process.argv.includes("--dry-run");
const REPO = "Eph0714/GoPreach";

const gradle = readFileSync(join(root, "app/build.gradle.kts"), "utf8");
const versionCode = Number(gradle.match(/versionCode\s*=\s*(\d+)/)[1]);
const versionName = gradle.match(/versionName\s*=\s*"([^"]+)"/)[1];
const historyFile = join(here, "release-history.json");
const history = JSON.parse(readFileSync(historyFile, "utf8"));
const previous = Math.max(0, ...history.map((h) => h.versionCode));

const notes = process.argv.find((a) => a.startsWith("--notes="))?.slice(8) || `GoPreach v${versionName}`;
console.log(`Releasing GoPreach ${versionName} (code ${versionCode}); previous code ${previous}${legacy ? "  [LEGACY debug-key mode]" : ""}`);

const win = process.platform === "win32";
const build = spawnSync(join(root, win ? "gradlew.bat" : "gradlew"), [":app:testDebugUnitTest", ":shared:testDebugUnitTest", ":app:assembleRelease", "-q"], { cwd: root, stdio: "inherit", shell: win });
if (build.status !== 0) { console.error("Build or tests failed - nothing was published."); process.exit(1); }

const apk = join(root, "app/build/outputs/apk/release/GoPreach.apk");
const args = [join(here, "verify-release.mjs"), apk, "--previous-version-code", String(previous)];
if (legacy) args.push("--legacy");
const gate = spawnSync(process.execPath, args, { stdio: "inherit" });
if (gate.status !== 0) { console.error("\nRelease gate FAILED - nothing was published."); process.exit(1); }
if (dry) { console.log("Dry run: not publishing."); process.exit(0); }

execFileSync("gh", ["release", "create", `v${versionName}`, apk, "-R", REPO, "--title", `GoPreach v${versionName}`, "--notes", notes], { stdio: "inherit" });
history.push({ version: versionName, versionCode, date: new Date().toISOString().slice(0, 10), channel: legacy ? "direct (legacy key)" : "direct (production key)", status: "Current" });
history.slice(0, -1).forEach((h) => { if (h.status === "Current") h.status = "Previous"; });
writeFileSync(historyFile, JSON.stringify(history, null, 2) + "\n");
console.log("Published v" + versionName);
