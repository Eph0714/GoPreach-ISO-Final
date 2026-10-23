import 'node:process';

/** Reads a minimal .env (KEY=VALUE lines) without a dependency; real environment variables win. */
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const envFile = join(here, '..', '.env');
if (existsSync(envFile)) {
  for (const line of readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
    if (m && !line.trim().startsWith('#') && process.env[m[1]] === undefined) {
      process.env[m[1]] = m[2].replace(/^"(.*)"$/, '$1');
    }
  }
}

export const config = {
  nodeEnv: process.env.NODE_ENV ?? 'development',
  port: Number(process.env.PORT ?? 3000),
  store: process.env.STORE ?? 'mysql',
  /** Signs the app's sign-in tokens. Required: without it the sign-in endpoints answer 503 and no request is accepted. */
  authSecret: process.env.AUTH_SECRET ?? '',
  /** Firebase's password-hash settings (console → Authentication → Users → ⋮ → Password hash parameters), only for imported accounts. */
  firebaseHash: {
    signerKey: process.env.FIREBASE_HASH_SIGNER_KEY ?? '',
    saltSeparator: process.env.FIREBASE_HASH_SALT_SEPARATOR ?? '',
    rounds: Number(process.env.FIREBASE_HASH_ROUNDS ?? 8),
    memCost: Number(process.env.FIREBASE_HASH_MEM_COST ?? 14),
  },
  /**
   * Passkeys (WebAuthn). The relying-party id is the domain the API is served from; ANDROID_CERT_SHA256 lists the SHA-256 fingerprint(s)
   * of the certificate(s) the Android app is signed with (debug and release differ), comma-separated. Without them passkeys answer 503.
   */
  passkey: {
    rpId: process.env.PASSKEY_RP_ID ?? 'gopreach.sinaglahinv.com',
    rpName: process.env.PASSKEY_RP_NAME ?? 'GoPreach',
    androidPackage: process.env.ANDROID_PACKAGE ?? 'com.emfitsolutions.gopreach',
    androidCertSha256: (process.env.ANDROID_CERT_SHA256 ?? '').split(',').map((x) => x.trim()).filter(Boolean),
    /** Origins of the GoPreach website that may use passkeys (comma-separated, e.g. https://app.gopreach.sinaglahinv.com). The site's host must end with PASSKEY_RP_ID. */
    webOrigins: (process.env.PASSKEY_WEB_ORIGINS ?? '').split(',').map((x) => x.trim().replace(/\/+$/, '')).filter(Boolean),
  },
  /**
   * The free, self-hosted AI that writes the plain-language part of the Previous Manual Record summary (see docs/AI_SETUP.md). Off unless AI_PROVIDER is
   * set: 'ollama' (Ollama's /api/chat) or 'openai-compatible' (any self-hosted server that speaks /v1/chat/completions, e.g. llama.cpp or vLLM).
   * The endpoint and model live only here, on the server; the apps never see them. Nothing paid is ever substituted when it is off or unreachable.
   */
  ai: {
    provider: (process.env.AI_PROVIDER ?? '').trim().toLowerCase(),
    url: (process.env.AI_URL ?? 'http://127.0.0.1:11434').replace(/\/+$/, ''),
    model: process.env.AI_MODEL ?? 'qwen2.5:7b-instruct',
    timeoutMs: Number(process.env.AI_TIMEOUT_MS ?? 90_000),
    contextTokens: Number(process.env.AI_CONTEXT_TOKENS ?? 8192),
    /** Only if the AI server sits behind a reverse proxy that asks for a bearer key. Never sent to the apps, never logged. */
    apiKey: process.env.AI_API_KEY ?? '',
  },
  db: {
    host: process.env.DB_HOST ?? 'localhost',
    port: Number(process.env.DB_PORT ?? 3306),
    database: process.env.DB_NAME ?? '',
    user: process.env.DB_USER ?? '',
    password: process.env.DB_PASSWORD ?? '',
  },
};
