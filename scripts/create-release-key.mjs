#!/usr/bin/env node
// One-time: create GoPreach's production signing key, wire it into the build and register its fingerprint for the release gate.
//
//   node scripts/create-release-key.mjs
//
// You type the password (it is never printed, logged or committed). Afterwards:
//   1. BACK UP the .jks file and the password somewhere safe and off this PC. Losing either means no app update can ever be signed again.
//   2. Release with:  node scripts/release.mjs   (without --legacy)
// NOTE: phones that have the app signed with the old debug key cannot update to a build signed with the new key; they must uninstall and
// reinstall once (their data comes back from the server after sign-in).
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync, appendFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createInterface } from "node:readline/promises";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const jks = join(process.env.USERPROFILE || process.env.HOME, "gopreach-release.jks");
const props = join(root, "keystore.properties");
if (existsSync(jks) || existsSync(props)) { console.error(`Stopped: ${existsSync(jks) ? jks : props} already exists. Nothing was changed.`); process.exit(1); }

const rl = createInterface({ input: process.stdin, output: process.stdout });
const pw = await rl.question("Choose a key password (min 8 characters; write it down): ");
const again = await rl.question("Type it again: ");
rl.close();
if (pw.length < 8 || pw !== again) { console.error("Stopped: the passwords differ or are shorter than 8 characters."); process.exit(1); }

const k = spawnSync("keytool", ["-genkeypair", "-v", "-keystore", jks, "-alias", "gopreach", "-keyalg", "RSA", "-keysize", "4096", "-validity", "10950",
  "-storepass", pw, "-keypass", pw, "-dname", "CN=GoPreach, O=GoPreach, C=PH"], { stdio: ["ignore", "ignore", "inherit"] });
if (k.status !== 0) { console.error("keytool failed (is the JDK on PATH?)."); process.exit(1); }

writeFileSync(props, `storeFile=${jks.split("\\").join("/")}\nstorePassword=${pw}\nkeyAlias=gopreach\nkeyPassword=${pw}\n`);
const out = spawnSync("keytool", ["-list", "-v", "-keystore", jks, "-alias", "gopreach", "-storepass", pw], { encoding: "utf8" }).stdout || "";
const sha = (out.match(/SHA256:\s*([0-9A-F:]+)/i) || [])[1];
if (!sha) { console.error("Key created, but its fingerprint could not be read. Run: keytool -list -v -keystore " + jks); process.exit(1); }
const certFile = join(root, "scripts", "release-cert.sha256");
const body = existsSync(certFile) ? readFileSync(certFile, "utf8") : "";
appendFileSync(certFile, (body.endsWith("\n") || !body ? "" : "\n") + sha + "\n");

console.log(`\nDone.\n  Key file:      ${jks}\n  Build config:  keystore.properties (gitignored)\n  Fingerprint:   ${sha}  (added to scripts/release-cert.sha256)\n\nBACK UP the key file and password now. Then run: node scripts/release.mjs`);
