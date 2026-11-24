// Modifications faites depuis la page web d'admin (produits, catégories,
// paramètres, canaux). Chaque écriture passe par push() comme si elle venait
// d'un PDA (origine « admin-web ») : mêmes contrôles, nouvelle version, et
// les PDA la récupèrent à leur prochaine synchro (30 s max).
import { randomUUID } from 'node:crypto';
import { ENTITY_BY_NAME, normalizeName, rowToRecord, toColumn } from './entities.js';
import { BadRequest, push } from './sync.js';

const ORIGIN = 'admin-web';

class Conflict extends Error {}
class NotFound extends Error {}

export function registerAdminWrites(app, { pool, guard }) {
  // Anti-CSRF : un site tiers ne peut pas poser cet en-tête sans préflight
  // CORS (que ce serveur n'accepte jamais).
  const csrf = async (req, reply) => {
    if (req.headers['x-requested-with'] !== 'fetch') {
      return reply.code(403).send({ ok: false, error: 'Requête refusée' });
    }
  };
  const opts = { onRequest: [guard, csrf] };

  const handle = (fn) => async (req, reply) => {
    try {
      return { ok: true, ...(await fn(req)) };
    } catch (e) {
      if (e instanceof BadRequest) return reply.code(400).send({ ok: false, error: e.message });
      if (e instanceof Conflict) return reply.code(409).send({ ok: false, error: e.message });
      if (e instanceof NotFound) return reply.code(404).send({ ok: false, error: e.message });
      throw e;
    }
  };

  const load = async (entityName, id) => {
    const e = ENTITY_BY_NAME[entityName];
    const [[row]] = await pool.query(`SELECT * FROM ${e.table} WHERE id = ? AND deleted = 0`, [id]);
    if (!row) throw new NotFound('Élément introuvable (supprimé entre-temps ?)');
    return rowToRecord(e, row);
  };

  const write = async (entityName, records) => {
    const res = await push(pool, { origin: ORIGIN, changes: { [entityName]: records } });
    if (res.rejected.length) throw new BadRequest(res.rejected[0].error);
    return res;
  };

  const assertUnique = async (entityName, key, id, message) => {
    const e = ENTITY_BY_NAME[entityName];
    const [[clash]] = await pool.query(`SELECT id FROM ${e.table} WHERE uniq_key = ? AND id <> ?`, [key, id ?? '']);
    if (clash) throw new Conflict(message);
  };

  const str = (v) => (typeof v === 'string' ? v.trim() : v);
  const optStr = (v) => (typeof v === 'string' && v.trim() !== '' ? v.trim() : null);

  // ── Catégories ──────────────────────────────────────────────────────
  app.post('/admin/api/categories', opts, handle(async (req) => {
    const b = req.body ?? {};
    const current = b.id ? await load('categories', b.id) : null;
    const name = str(b.name ?? current?.name);
    if (!name) throw new BadRequest('Le nom est obligatoire');
    const id = current?.id ?? randomUUID();
    await assertUnique('categories', normalizeName(name), id, `La catégorie « ${name} » existe déjà.`);
    await write('categories', [{
      ...(current ?? {}),
      id, name,
      nameEn: b.nameEn !== undefined ? optStr(b.nameEn) : current?.nameEn ?? null,
      sageArticleCode: b.sageArticleCode !== undefined ? optStr(b.sageArticleCode) : current?.sageArticleCode ?? null,
      barcodePrefix: b.barcodePrefix !== undefined ? optStr(b.barcodePrefix) : current?.barcodePrefix ?? null,
      updatedAt: Date.now(),
      deleted: false,
    }]);
    return { id };
  }));

  app.delete('/admin/api/categories/:id', opts, handle(async (req) => {
    const current = await load('categories', req.params.id);
    const [[{ n }]] = await pool.query(
      'SELECT COUNT(*) AS n FROM products WHERE category_id = ? AND deleted = 0', [current.id],
    );
    if (Number(n) > 0) throw new Conflict(`Impossible : ${n} produit(s) utilisent encore cette catégorie.`);
    await write('categories', [{ ...current, updatedAt: Date.now(), deleted: true }]);
    return {};
  }));

  // ── Produits ────────────────────────────────────────────────────────
  app.post('/admin/api/products', opts, handle(async (req) => {
    const b = req.body ?? {};
    const current = b.id ? await load('products', b.id) : null;
    const barcode = str(b.barcode ?? current?.barcode);
    if (!barcode) throw new BadRequest('Le code-barres est obligatoire');
    const categoryId = b.categoryId ?? current?.categoryId;
    await load('categories', categoryId); // la catégorie doit exister
    const priceCents = b.priceCents ?? current?.priceCents;
    const id = current?.id ?? randomUUID();
    await assertUnique('products', barcode, id, `Le code-barres « ${barcode} » est déjà utilisé.`);
    await write('products', [{
      ...(current ?? {}),
      id, barcode, categoryId, priceCents,
      nameEn: b.nameEn !== undefined ? optStr(b.nameEn) : current?.nameEn ?? null,
      updatedAt: Date.now(),
      deleted: false,
    }]);
    return { id };
  }));

  app.delete('/admin/api/products/:id', opts, handle(async (req) => {
    const current = await load('products', req.params.id);
    await write('products', [{ ...current, updatedAt: Date.now(), deleted: true }]);
    return {};
  }));

  // ── Paramètres boutique ─────────────────────────────────────────────
  app.post('/admin/api/settings', opts, handle(async (req) => {
    const b = req.body ?? {};
    if (typeof b.key !== 'string' || !b.key) throw new BadRequest('Paramètre inconnu');
    if (typeof b.value !== 'string') throw new BadRequest('Valeur invalide');
    await write('settings', [{ id: b.key, value: b.value.trim(), updatedAt: Date.now(), deleted: false }]);
    return {};
  }));

  // ── Canaux ──────────────────────────────────────────────────────────
  app.post('/admin/api/channels', opts, handle(async (req) => {
    const b = req.body ?? {};
    const current = b.id ? await load('channels', b.id) : null;
    const deviceId = current?.deviceId ?? b.deviceId;
    await load('devices', deviceId); // la caisse doit exister
    const name = str(b.name ?? current?.name);
    if (!name) throw new BadRequest('Le nom est obligatoire');
    const now = Date.now();
    const id = current?.id ?? randomUUID();
    await write('channels', [{
      ...(current ?? {}),
      id, deviceId, name,
      discountPercent: b.discountPercent ?? current?.discountPercent ?? 0,
      createdAt: current?.createdAt ?? now,
      updatedAt: now,
      deleted: false,
    }]);
    return { id };
  }));

  app.delete('/admin/api/channels/:id', opts, handle(async (req) => {
    const current = await load('channels', req.params.id);
    const now = Date.now();
    const lines = await activeLines(current.id);
    await write('cart_lines', lines.map((l) => ({ ...l, updatedAt: now, deleted: true })));
    await write('channels', [{ ...current, updatedAt: now, deleted: true }]);
    return {};
  }));

  /**
   * Quantité d'une ligne affichée d'un canal (= tous les ajouts de même
   * catégorie + même prix). 0 = retirer la ligne. Comme sur le PDA, les
   * ajouts du groupe sont remplacés par un seul.
   */
  app.post('/admin/api/channels/:id/lines', opts, handle(async (req) => {
    const channel = await load('channels', req.params.id);
    const b = req.body ?? {};
    const quantity = Number(b.quantity);
    if (!Number.isSafeInteger(quantity) || quantity < 0 || quantity > 1_000_000) throw new BadRequest('Quantité invalide');
    const group = (await activeLines(channel.id))
      .filter((l) => l.categoryName === b.categoryName && l.unitPriceCents === b.unitPriceCents);
    if (!group.length) throw new NotFound('Ligne introuvable (modifiée entre-temps ?)');
    const now = Date.now();
    const records = group.map((l) => ({ ...l, updatedAt: now, deleted: true }));
    if (quantity > 0) {
      const first = [...group].sort((a, c) => a.createdAt - c.createdAt)[0];
      const last = (f) => [...group].reverse().find((l) => l[f] != null)?.[f] ?? null;
      records.push({
        id: randomUUID(),
        channelId: channel.id,
        productId: group.find((l) => l.productId)?.productId ?? null,
        barcode: first.barcode,
        categoryName: first.categoryName,
        categoryNameEn: last('categoryNameEn'),
        nameEn: last('nameEn'),
        sageArticleCode: last('sageArticleCode'),
        unitPriceCents: first.unitPriceCents,
        quantity,
        createdAt: first.createdAt,
        updatedAt: now,
        deleted: false,
      });
    }
    await write('cart_lines', records);
    return {};
  }));

  const activeLines = async (channelId) => {
    const e = ENTITY_BY_NAME.cart_lines;
    const [rows] = await pool.query(
      `SELECT * FROM ${e.table} WHERE ${toColumn('channelId')} = ? AND deleted = 0`, [channelId],
    );
    return rows.map((r) => rowToRecord(e, r)).map(({ version, ...rest }) => rest);
  };
}
