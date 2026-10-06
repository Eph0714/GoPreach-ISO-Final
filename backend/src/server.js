import { config } from './config.js';
import { createStore } from './store.js';
import { createApp } from './app.js';

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

const app = createApp(lazyStore, { devAuth: process.env.AUTH_MODE === 'dev', health: () => ({ db: dbStatus }) });

// Hostinger's Node.js hosting passes the port in PORT and fronts the app with its own HTTPS proxy.
const server = app.listen(config.port, () => console.log(`GoPreach API listening on ${config.port} (${config.nodeEnv}, store=${config.store})`));

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(async () => { await store?.close(); process.exit(0); }));
}
