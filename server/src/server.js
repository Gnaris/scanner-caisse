import { timingSafeEqual, createHash } from 'node:crypto';
import Fastify from 'fastify';
import { BadRequest, pull, push } from './sync.js';
import { registerAdmin, touchClient } from './admin.js';

function sameSecret(a, b) {
  // Comparaison à temps constant (hash pour égaliser les longueurs)
  const ha = createHash('sha256').update(a).digest();
  const hb = createHash('sha256').update(b).digest();
  return timingSafeEqual(ha, hb);
}

export function buildApp({ pool, apiKey, adminPassword, logger = true }) {
  if (!apiKey || apiKey.length < 32) throw new Error('API_KEY absente ou trop courte (32 caractères min)');
  const app = Fastify({ logger, bodyLimit: 10 * 1024 * 1024 });

  app.get('/v1/health', async () => ({ ok: true }));

  // API des PDA : clé Bearer obligatoire. La page d'admin (/ et /admin/*)
  // a sa propre protection par mot de passe (admin.js).
  app.addHook('onRequest', async (req, reply) => {
    if (!req.url.startsWith('/v1/') || req.url.startsWith('/v1/health')) return;
    const h = req.headers.authorization ?? '';
    const token = h.startsWith('Bearer ') ? h.slice(7) : '';
    if (!token || !sameSecret(token, apiKey)) {
      return reply.code(401).send({ ok: false, error: 'Clé de synchronisation invalide' });
    }
  });

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof BadRequest) return reply.code(400).send({ ok: false, error: err.message });
    if (err.statusCode && err.statusCode < 500) return reply.code(err.statusCode).send({ ok: false, error: err.message });
    req.log.error(err);
    return reply.code(500).send({ ok: false, error: 'Erreur serveur' });
  });

  // Vérifie la clé sans rien modifier
  app.get('/v1/ping', async (req) => {
    await touchClient(pool, req);
    return { ok: true };
  });

  app.post('/v1/sync/push', async (req) => {
    const body = req.body ?? {};
    const res = await push(pool, { origin: body.origin, changes: body.changes ?? {} });
    if (res.rejected.length) req.log.warn({ rejected: res.rejected }, 'enregistrements refusés');
    await touchClient(pool, req, { pushed: true, origin: body.origin });
    return { ok: true, ...res };
  });

  app.get('/v1/sync/pull', async (req) => {
    const since = Number(req.query.since ?? 0);
    const res = await pull(pool, since);
    await touchClient(pool, req, { pullVersion: res.version });
    return { ok: true, ...res };
  });

  registerAdmin(app, { pool, adminPassword });

  app.addHook('onClose', async () => pool.end());
  return app;
}
