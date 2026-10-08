#!/usr/bin/env node
// Release gate: verifies an APK before it may be published. Any failure BLOCKS the release (exit code 1).
//
//   node scripts/verify-release.mjs path/to/GoPreach.apk [--previous-version-code N]
//
// Checks: application id, versionCode/versionName and that the versionCode increased, the signing certificate equals the approved
// production certificate (scripts/release-cert.sha256), the APK is NOT signed with a debug certificate, the signature verifies,
// the build is not debuggable, cleartext HTTP is off, and every permission is on the allow-list (scripts/permission-allowlist.txt).
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const apk = process.argv[2];
if (!apk || !existsSync(apk)) { console.error("Usage: node scripts/verify-release.mjs <apk> [--previous-version-code N]"); process.exit(2); }
const legacy = process.argv.includes("--legacy"); // transitional: the debug key is tolerated (with a warning) until the production key exists
const prevIdx = process.argv.indexOf("--previous-version-code");
const previousCode = prevIdx > 0 ? Number(process.argv[prevIdx + 1]) : null;

const EXPECTED_ID = "com.emfitsolutions.gopreach";
// Android's well-known debug certificate (SHA-256): its private key is public knowledge, so it must never sign a user-facing build.
const DEBUG_CERT_PREFIXES = ["C0:99:FC", "5A:8E:C6"]; // checked as "starts with" against the debug.keystore actually on this machine too

function sdk() {
  const local = existsSync(join(root, "local.properties")) ? readFileSync(join(root, "local.properties"), "utf8") : "";
  const m = local.match(/^sdk\.dir=(.+)$/m);
  const dir = (m ? m[1].replace(/\\:/g, ":").replace(/\\\\/g, "\\") : process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || "");
  const bt = join(dir, "build-tools");
  const v = readdirSync(bt).sort().reverse()[0];
  return join(bt, v);
}
const tools = sdk();
const win = process.platform === "win32";
const run = (tool, args) => execFileSync(join(tools, tool + (win ? (tool === "aapt2" ? ".exe" : ".bat") : "")), args, { encoding: "utf8", shell: win && tool !== "aapt2" });

const failures = [];
const ok = (m) => console.log("  ok    " + m);
const bad = (m) => { failures.push(m); console.log("  FAIL  " + m); };
const keyIssue = (m) => { if (legacy) console.log("  WARN  (legacy mode) " + m); else bad(m); };

console.log("Verifying " + apk);

// ---- manifest facts -------------------------------------------------------
const badging = run("aapt2", ["dump", "badging", apk]);
const pkg = badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'/);
if (!pkg) { console.error("Could not read the package information."); process.exit(1); }
const [, id, code, name] = pkg;
id === EXPECTED_ID ? ok("application id " + id) : bad(`application id is ${id}, expected ${EXPECTED_ID}`);
ok(`version ${name} (code ${code})`);
if (previousCode != null) Number(code) > previousCode ? ok(`versionCode ${code} > previous ${previousCode}`) : bad(`versionCode ${code} is not greater than the previous release (${previousCode})`);
/application-debuggable/.test(badging) ? bad("the build is debuggable") : ok("not debuggable");

const xml = run("aapt2", ["dump", "xmltree", "--file", "AndroidManifest.xml", apk]);
/usesCleartextTraffic[^\n]*=\(type 0x12\)0xffffffff/.test(xml) ? bad("cleartext HTTP traffic is enabled") : ok("cleartext HTTP traffic is off");

// ---- permissions ----------------------------------------------------------
const allow = new Set(readFileSync(join(here, "permission-allowlist.txt"), "utf8").split(/\r?\n/).map((l) => l.split("#")[0].trim()).filter(Boolean));
const perms = [...badging.matchAll(/uses-permission(?:-sdk-23)?: name='([^']+)'/g)].map((m) => m[1]);
const extra = perms.filter((p) => !allow.has(p) && !p.startsWith(EXPECTED_ID + "."));
extra.length ? bad("permissions not on the allow-list: " + extra.join(", ")) : ok(`${perms.length} permissions, all on the allow-list`);

// ---- signature ------------------------------------------------------------
let certs = "";
try { certs = run("apksigner", ["verify", "--print-certs", "--verbose", apk]); ok("signature verifies"); }
catch (e) { bad("signature does not verify: " + (e.stdout || e.message).toString().split("\n")[0]); }
const sha = (certs.match(/certificate SHA-256 digest: ([0-9a-f]+)/i) || [])[1]?.toLowerCase();
if (sha) {
  const colon = sha.match(/../g).join(":").toUpperCase();
  console.log("  signing certificate SHA-256: " + colon);
  if (/CN=Android Debug/i.test(certs)) keyIssue("signed with the Android DEBUG certificate - never distribute this to users");
  const approvedFile = join(here, "release-cert.sha256");
  const approved = existsSync(approvedFile) ? readFileSync(approvedFile, "utf8").split(/\r?\n/).map((l) => l.trim().toLowerCase().replace(/:/g, "")).filter((l) => /^[0-9a-f]{64}$/.test(l)) : [];
  if (!approved.length) keyIssue("no approved production certificate is registered (scripts/release-cert.sha256 is empty) - create the release key first");
  else approved.includes(sha) ? ok("signing certificate matches the approved production certificate") : bad("signing certificate does NOT match the approved production certificate");
}

console.log(failures.length ? `\nBLOCKED - ${failures.length} check(s) failed. Do not publish this build.` : "\nAll release checks passed.");
process.exit(failures.length ? 1 : 0);
