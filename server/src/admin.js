// Page d'administration en LECTURE SEULE : https://caisse.beliandjolie.com/
// Protégée par mot de passe (HTTP Basic, ADMIN_PASSWORD dans .env), avec
// blocage temporaire après plusieurs mauvais mots de passe.
import { readFile } from 'node:fs/promises';
import { timingSafeEqual, createHash } from 'node:crypto';
import { isValidId } from './entities.js';

function sameSecret(a, b) {
  const ha = createHash('sha256').update(a).digest();
  const hb = createHash('sha256').update(b).digest();
  return timingSafeEqual(ha, hb);
}

/** IP réelle du visiteur (Nginx pose X-Real-IP ; on ne s'y fie que derrière lui). */
export function clientIp(req) {
  const direct = req.socket.remoteAddress ?? '';
  const fromProxy = direct === '127.0.0.1' || direct === '::1' || direct === '::ffff:127.0.0.1';
  const real = req.headers['x-real-ip'];
  return (fromProxy && typeof real === 'string' && real) ? real.slice(0, 64) : direct.slice(0, 64);
}

/**
 * Note qu'un PDA a contacté le serveur (en-têtes X-Install-Id, X-Device-Id,
 * X-App-Version envoyés par l'app depuis la 1.1.1). Ne bloque jamais la
 * synchro : les erreurs sont seulement journalisées.
 */
export async function touchClient(pool, req, { pullVersion = null, pushed = false, origin = null } = {}) {
  const installId = req.headers['x-install-id'] ?? origin;
  if (!isValidId(installId)) return;
  const deviceId = isValidId(req.headers['x-device-id']) ? req.headers['x-device-id'] : null;
  const appVersion = typeof req.headers['x-app-version'] === 'string'
    ? req.headers['x-app-version'].slice(0, 32) : null;
  try {
    await pool.query(
      `INSERT INTO clients (install_id, device_id, app_version, ip, last_seen, last_pull_at, last_pull_version, last_push_at)
       VALUES (?, ?, ?, ?, NOW(), IF(? IS NULL, NULL, NOW()), ?, IF(?, NOW(), NULL))
       ON DUPLICATE KEY UPDATE
         device_id = COALESCE(VALUES(device_id), device_id),
         app_version = COALESCE(VALUES(app_version), app_version),
         ip = VALUES(ip),
         last_seen = NOW(),
         last_pull_at = IF(? IS NULL, last_pull_at, NOW()),
         last_pull_version = COALESCE(VALUES(last_pull_version), last_pull_version),
         last_push_at = IF(?, NOW(), last_push_at)`,
      [installId, deviceId, appVersion, clientIp(req), pullVersion, pullVersion, pushed, pullVersion, pushed],
    );
  } catch (e) {
    req.log.warn(e, 'touchClient');
  }
}

const MAX_FAILS = 8;
const LOCK_MS = 15 * 60 * 1000;

export function registerAdmin(app, { pool, adminPassword }) {
  const fails = new Map(); // ip -> { count, until }

  const guard = async (req, reply) => {
    const ip = clientIp(req);
    const f = fails.get(ip);
    if (f && f.until > Date.now()) {
      return reply.code(429).type('text/plain; charset=utf-8')
        .send('Trop de tentatives. Réessayez dans 15 minutes.');
    }
    const h = req.headers.authorization ?? '';
    let ok = false;
    if (adminPassword && h.startsWith('Basic ')) {
      const decoded = Buffer.from(h.slice(6), 'base64').toString('utf8');
      const pass = decoded.slice(decoded.indexOf(':') + 1);
      ok = sameSecret(pass, adminPassword);
    }
    if (!ok) {
      if (h.startsWith('Basic ')) {
        // Compteur remis à zéro seulement à la fin d'un blocage
        const lockExpired = f && f.until && f.until <= Date.now();
        const count = (lockExpired ? 0 : f?.count ?? 0) + 1;
        fails.set(ip, { count, until: count >= MAX_FAILS ? Date.now() + LOCK_MS : 0 });
      }
      return reply.code(401)
        .header('WWW-Authenticate', 'Basic realm="Caisse Beli & Jolie", charset="UTF-8"')
        .type('text/plain; charset=utf-8')
        .send('Mot de passe requis.');
    }
    fails.delete(ip);
  };

  const page = readFile(new URL('./admin.html', import.meta.url), 'utf8');

  app.get('/', { onRequest: guard }, async (req, reply) => {
    reply.header('Cache-Control', 'no-store')
      .header('X-Frame-Options', 'DENY')
      .header('Referrer-Policy', 'no-referrer')
      .header('X-Robots-Tag', 'noindex, nofollow')
      .type('text/html; charset=utf-8');
    return page;
  });

  app.get('/admin/data.json', { onRequest: guard }, async (req, reply) => {
    reply.header('Cache-Control', 'no-store');
    return buildData(pool);
  });
}

async function buildData(pool) {
  const q = async (sql, params = []) => (await pool.query(sql, params))[0];
  const [[state]] = await pool.query('SELECT version FROM sync_state WHERE id = 1');
  const serverVersion = Number(state.version);

  const devices = await q('SELECT id, name, server_updated_at, origin FROM devices WHERE deleted = 0 ORDER BY name');
  const channels = await q(
    'SELECT id, device_id, name, discount_percent, created_at, server_updated_at FROM channels WHERE deleted = 0 ORDER BY created_at',
  );
  const lines = await q(
    `SELECT l.channel_id, l.category_name, l.unit_price_cents, l.quantity, l.barcode, l.origin
       FROM cart_lines l JOIN channels c ON c.id = l.channel_id AND c.deleted = 0
      WHERE l.deleted = 0 ORDER BY l.created_at`,
  );
  const categories = await q(
    `SELECT c.id, c.name, c.name_en, c.sage_article_code, c.barcode_prefix, c.server_updated_at, c.origin,
            (SELECT COUNT(*) FROM products p WHERE p.category_id = c.id AND p.deleted = 0) AS product_count
       FROM categories c WHERE c.deleted = 0 ORDER BY c.name`,
  );
  const products = await q(
    `SELECT p.barcode, p.price_cents, p.name_en, p.server_updated_at, p.origin, c.name AS category_name
       FROM products p LEFT JOIN categories c ON c.id = p.category_id
      WHERE p.deleted = 0 ORDER BY c.name, p.price_cents, p.barcode`,
  );
  const settings = await q('SELECT id, value, server_updated_at FROM settings WHERE deleted = 0 ORDER BY id');
  const clients = await q('SELECT * FROM clients ORDER BY last_seen DESC');
  const deletedCounts = {};
  for (const t of ['devices', 'categories', 'products', 'channels', 'cart_lines']) {
    deletedCounts[t] = Number((await q(`SELECT COUNT(*) AS n FROM ${t} WHERE deleted = 1`))[0].n);
  }

  const deviceName = Object.fromEntries(devices.map((d) => [d.id, d.name]));
  // Nom lisible d'un PDA physique : « PDA 1a2b » + dernière caisse ouverte
  const clientLabel = Object.fromEntries(clients.map((c) => [c.install_id, `PDA ${c.install_id.slice(0, 4).toUpperCase()}`]));
  const who = (origin) => (origin ? clientLabel[origin] ?? `PDA ${origin.slice(0, 4).toUpperCase()}` : null);

  const linesByChannel = new Map();
  for (const l of lines) {
    if (!linesByChannel.has(l.channel_id)) linesByChannel.set(l.channel_id, []);
    linesByChannel.get(l.channel_id).push(l);
  }
  const groupLines = (rows) => {
    const groups = new Map();
    for (const r of rows) {
      const k = r.category_name + '\u0000' + r.unit_price_cents;
      const g = groups.get(k) ?? { categoryName: r.category_name, unitPriceCents: Number(r.unit_price_cents), quantity: 0 };
      g.quantity += Number(r.quantity);
      groups.set(k, g);
    }
    return [...groups.values()];
  };

  return {
    generatedAt: new Date().toISOString(),
    serverVersion,
    clients: clients.map((c) => ({
      label: clientLabel[c.install_id],
      deviceName: c.device_id ? deviceName[c.device_id] ?? null : null,
      appVersion: c.app_version,
      ip: c.ip,
      firstSeen: c.first_seen,
      lastSeen: c.last_seen,
      lastPullAt: c.last_pull_at,
      lastPullVersion: c.last_pull_version === null ? null : Number(c.last_pull_version),
      lastPushAt: c.last_push_at,
    })),
    devices: devices.map((d) => {
      const chans = channels.filter((c) => c.device_id === d.id).map((c) => {
        const grouped = groupLines(linesByChannel.get(c.id) ?? []);
        const totalCents = grouped.reduce((s, g) => s + g.unitPriceCents * g.quantity, 0);
        return {
          name: c.name,
          discountPercent: Number(c.discount_percent),
          createdAt: Number(c.created_at),
          receivedAt: c.server_updated_at,
          articles: grouped.reduce((s, g) => s + g.quantity, 0),
          totalCents,
          lines: grouped,
        };
      });
      return { name: d.name, createdBy: who(d.origin), channels: chans };
    }),
    categories: categories.map((c) => ({
      name: c.name,
      nameEn: c.name_en,
      sageArticleCode: c.sage_article_code,
      barcodePrefix: c.barcode_prefix,
      productCount: Number(c.product_count),
      receivedAt: c.server_updated_at,
      by: who(c.origin),
    })),
    products: products.map((p) => ({
      barcode: p.barcode,
      categoryName: p.category_name,
      priceCents: Number(p.price_cents),
      nameEn: p.name_en,
      receivedAt: p.server_updated_at,
      by: who(p.origin),
    })),
    settings: settings.map((s) => ({ key: s.id, value: s.value, receivedAt: s.server_updated_at })),
    deletedCounts,
  };
}
