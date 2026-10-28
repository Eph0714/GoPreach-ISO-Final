import { config } from './config.js';
import { createStore } from './store.js';
import { createApp } from './app.js';
import { smtpMailer } from './loginApproval.js';

/**
 * The server starts listening FIRST and connects to the database in the background, so a wrong database setting shows up
 * on /v1/health (as a short error code — never the password) instead of the whole app crashing with a bare 503.
 */
let store = null;
let dbStatus = 'connecting';

const lazyStore = new Proxy({}, {
  get(_t, prop) {
    return (...args) => {
      if (!store) throw Object.assign(new Error('Database not connected'), { code: 'DB_NOT_CONNECTED' });
      return store[prop](...args);
    };
  },
});

async function connect() {
  try {
    store = await createStore();
    dbStatus = 'ok';
    console.log('Database connected.');
  } catch (e) {
    store = null;
    dbStatus = `error: ${e.code ?? e.name ?? 'unknown'}`;
    console.error('Database connection failed:', e.code ?? e.message);
    setTimeout(connect, 15_000); // keep trying (e.g. the database was just created)
  }
}
connect();

// Mail for the sign-in e-mail code: only when SMTP is configured (and nodemailer installed); otherwise that method is simply not offered.
const mailer = smtpMailer();

const app = createApp(lazyStore, { devAuth: process.env.AUTH_MODE === 'dev', health: () => ({ db: dbStatus }), authOptions: { mailer } });

// Hostinger's Node.js hosting passes the port in PORT and fronts the app with its own HTTPS proxy.
const server = app.listen(config.port, () => console.log(`GoPreach API listening on ${config.port} (${config.nodeEnv}, store=${config.store})`));

// Hourly housekeeping: login requests and e-mail codes live minutes; they are dropped an hour after they expire.
const purgeTimer = setInterval(() => { store?.purgeChallenges?.().catch((e) => console.error('Challenge cleanup failed:', e.code ?? e.message)); }, 3_600_000);
purgeTimer.unref();

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(async () => { await store?.close(); process.exit(0); }));
}
