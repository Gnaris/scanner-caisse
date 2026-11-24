// Point d'entrée du service (PM2 / npm start)
import { createPool } from './db.js';
import { migrate } from './migrate.js';
import { buildApp } from './server.js';

const pool = createPool();
await migrate(pool);
const app = buildApp({ pool, apiKey: process.env.API_KEY, adminPassword: process.env.ADMIN_PASSWORD });
await app.listen({ port: Number(process.env.PORT ?? 3100), host: process.env.HOST ?? '127.0.0.1' });
