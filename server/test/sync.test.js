// Tests d'intégration sur une vraie base MySQL (dont le nom DOIT finir par
// _test : les tables sont vidées à chaque test).
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { createPool } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { ENTITIES } from '../src/entities.js';
import { pull, push, PULL_PAGE } from '../src/sync.js';
import { buildApp } from '../src/server.js';

const DB = process.env.TEST_DB_NAME;
if (!DB || !DB.endsWith('_test')) throw new Error('TEST_DB_NAME doit finir par _test');
const pool = createPool({ database: DB });

before(async () => migrate(pool));
beforeEach(async () => {
  for (const e of ENTITIES) await pool.query(`TRUNCATE TABLE ${e.table}`);
  await pool.query('TRUNCATE TABLE aliases');
  await pool.query('UPDATE sync_state SET version = 0 WHERE id = 1');
});
after(async () => pool.end());

const cat = (id, name, updatedAt = 1000, extra = {}) => ({ id, name, nameEn: null, sageArticleCode: null, barcodePrefix: null, updatedAt, ...extra });
const prod = (id, barcode, categoryId, updatedAt = 1000) => ({ id, barcode, categoryId, priceCents: 1250, nameEn: null, updatedAt });

test('push puis pull renvoie les lignes avec leur version', async () => {
  const r = await push(pool, { origin: 'pda1', changes: { categories: [cat('c1', 'Bague')], products: [prod('p1', '123', 'c1')] } });
  assert.equal(r.version, 2);
  assert.deepEqual(r.rejected, []);
  const p = await pull(pool, 0);
  assert.equal(p.version, 2);
  assert.equal(p.hasMore, false);
  assert.equal(p.changes.categories[0].name, 'Bague');
  assert.equal(p.changes.products[0].categoryId, 'c1');
  assert.equal(p.changes.products[0].priceCents, 1250);
  assert.equal(p.changes.products[0].deleted, false);
  const again = await pull(pool, 2);
  assert.deepEqual(again.changes, {});
});

test('la modification la plus récente gagne, la plus ancienne est renvoyée', async () => {
  await push(pool, { origin: 'pda1', changes: { categories: [cat('c1', 'Bague', 2000, { nameEn: 'Ring' })] } });
  const r = await push(pool, { origin: 'pda2', changes: { categories: [cat('c1', 'Bague', 1500, { nameEn: 'Old' })] } });
  assert.equal(r.overrides.categories[0].nameEn, 'Ring');
  const p = await pull(pool, 0);
  assert.equal(p.changes.categories[0].nameEn, 'Ring');
});

test('catégorie en double (casse différente) fusionnée, produits rattachés', async () => {
  await push(pool, { origin: 'pda1', changes: { categories: [cat('c1', 'Bague')] } });
  const r = await push(pool, {
    origin: 'pda2',
    changes: { categories: [cat('c2', '  bague ', 1100, { sageArticleCode: 'ART1' })], products: [prod('p2', '999', 'c2')] },
  });
  assert.deepEqual(r.remaps, { categories: { c2: 'c1' } });
  assert.equal(r.overrides.categories[0].id, 'c1');
  assert.equal(r.overrides.categories[0].sageArticleCode, 'ART1');
  // La ligne existante garde son nom d'origine
  assert.equal(r.overrides.categories[0].name, 'Bague');
  const p = await pull(pool, 0);
  assert.equal(p.changes.categories.length, 1);
  assert.equal(p.changes.products[0].categoryId, 'c1');
  // Un envoi tardif qui référence encore l'ancien id est réécrit
  const late = await push(pool, { origin: 'pda2', changes: { products: [prod('p3', '888', 'c2')] } });
  assert.equal(late.rejected.length, 0);
  const [[row]] = await pool.query('SELECT category_id FROM products WHERE id = ?', ['p3']);
  assert.equal(row.category_id, 'c1');
  // Un nouvel envoi de c2 est redirigé vers c1
  const again = await push(pool, { origin: 'pda2', changes: { categories: [cat('c2', 'bague', 1200)] } });
  assert.deepEqual(again.remaps, { categories: { c2: 'c1' } });
});

test('produit en double par code-barres fusionné', async () => {
  await push(pool, { origin: 'pda1', changes: { categories: [cat('c1', 'Bague')], products: [prod('p1', '123', 'c1')] } });
  const r = await push(pool, { origin: 'pda2', changes: { products: [prod('pX', '123', 'c1', 1100)] } });
  assert.deepEqual(r.remaps, { products: { pX: 'p1' } });
});

test('les ajouts au panier de deux appareils s\'additionnent', async () => {
  const line = (id, origin) => ({
    id, channelId: 'ch1', productId: null, barcode: '', categoryName: 'Bague', categoryNameEn: null,
    nameEn: null, sageArticleCode: null, unitPriceCents: 500, quantity: 1, createdAt: 1, updatedAt: 1000,
  });
  await push(pool, {
    origin: 'pda1',
    changes: {
      devices: [{ id: 'd1', name: 'Appareil 1', updatedAt: 1 }],
      channels: [{ id: 'ch1', deviceId: 'd1', name: 'Canal 1', discountPercent: 0, createdAt: 1, updatedAt: 1 }],
      cart_lines: [line('l1')],
    },
  });
  await push(pool, { origin: 'pda2', changes: { cart_lines: [line('l2')] } });
  const p = await pull(pool, 0);
  assert.equal(p.changes.cart_lines.length, 2);
});

test('suppression sans les champs : valeurs gardées, nom libéré', async () => {
  await push(pool, { origin: 'pda1', changes: { categories: [cat('c1', 'Bague')] } });
  await push(pool, { origin: 'pda1', changes: { categories: [{ id: 'c1', deleted: true, updatedAt: 2000 }] } });
  const [[row]] = await pool.query('SELECT name, deleted, uniq_key FROM categories WHERE id = ?', ['c1']);
  assert.equal(row.name, 'Bague');
  assert.equal(row.deleted, 1);
  assert.equal(row.uniq_key, null);
  const r = await push(pool, { origin: 'pda1', changes: { categories: [cat('c9', 'Bague', 3000)] } });
  assert.deepEqual(r.remaps, {});
});

test('renommage vers un nom déjà pris : refusé', async () => {
  await push(pool, { origin: 'pda1', changes: { categories: [cat('c1', 'Bague'), cat('c2', 'Collier')] } });
  const r = await push(pool, { origin: 'pda2', changes: { categories: [cat('c2', 'BAGUE', 5000)] } });
  assert.equal(r.overrides.categories[0].name, 'Collier');
});

test('un enregistrement invalide est refusé sans bloquer les autres', async () => {
  const r = await push(pool, { origin: 'pda1', changes: { categories: [cat('c1', ''), cat('c2', 'Collier')] } });
  assert.equal(r.rejected.length, 1);
  assert.equal(r.rejected[0].id, 'c1');
  assert.equal((await pull(pool, 0)).changes.categories.length, 1);
});

test('pull par pages', async () => {
  const many = Array.from({ length: PULL_PAGE + 5 }, (_, i) => ({ id: 's' + i, value: 'v', updatedAt: 1 }));
  await push(pool, { origin: 'pda1', changes: { settings: many } });
  const p1 = await pull(pool, 0);
  assert.equal(p1.hasMore, true);
  assert.equal(p1.changes.settings.length, PULL_PAGE);
  const p2 = await pull(pool, p1.version);
  assert.equal(p2.hasMore, false);
  assert.equal(p2.changes.settings.length, 5);
});

test('base réinitialisée : l\'appareil est prévenu', async () => {
  const p = await pull(pool, 50);
  assert.equal(p.reset, true);
  assert.equal(p.version, 0);
});

test('HTTP : clé obligatoire', async () => {
  // Pool à part : l'app le ferme elle-même à app.close()
  const app = buildApp({ pool: createPool({ database: DB }), apiKey: 'k'.repeat(40), logger: false });
  assert.equal((await app.inject({ method: 'GET', url: '/v1/health' })).statusCode, 200);
  assert.equal((await app.inject({ method: 'GET', url: '/v1/ping' })).statusCode, 401);
  assert.equal((await app.inject({ method: 'GET', url: '/v1/ping', headers: { authorization: 'Bearer ' + 'x'.repeat(40) } })).statusCode, 401);
  const auth = { authorization: 'Bearer ' + 'k'.repeat(40) };
  const res = await app.inject({ method: 'POST', url: '/v1/sync/push', headers: auth, payload: { origin: 'pda1', changes: { categories: [cat('c1', 'Bague')] } } });
  assert.equal(res.statusCode, 200);
  assert.equal(res.json().version, 1);
  const bad = await app.inject({ method: 'POST', url: '/v1/sync/push', headers: auth, payload: { changes: { nope: [] } } });
  assert.equal(bad.statusCode, 400);
  const pl = await app.inject({ method: 'GET', url: '/v1/sync/pull?since=0', headers: auth });
  assert.equal(pl.json().changes.categories[0].id, 'c1');
  await app.close();
});
