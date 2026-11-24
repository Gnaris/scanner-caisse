// Cœur de la synchronisation : réception des modifications d'un appareil
// (push) et envoi de tout ce qui a changé depuis une version (pull).
//
// Règles :
// - Chaque écriture reçoit un numéro de version global croissant. Les envois
//   sont traités un par un (verrou sur sync_state), donc un appareil qui a
//   reçu « jusqu'à la version N » n'a jamais de trou derrière lui.
// - Deux modifications de la même ligne : la plus récente côté appareil
//   (updatedAt) gagne. La perdante est renvoyée à l'appareil (overrides)
//   pour qu'il s'aligne.
// - Doublons (même nom de catégorie / d'appareil, même code-barres) créés sur
//   deux appareils : le second est rattaché au premier (alias). L'appareil
//   reçoit la correspondance (remaps) et renomme ses références.
import { ENTITIES, ENTITY_BY_NAME, rowToRecord, toColumn, validateRecord } from './entities.js';

export const MAX_PUSH_RECORDS = 5000;
export const PULL_PAGE = 1000;

export class BadRequest extends Error {}

export async function push(pool, { origin, changes }) {
  if (changes === null || typeof changes !== 'object' || Array.isArray(changes)) {
    throw new BadRequest('changes doit être un objet');
  }
  let total = 0;
  for (const [name, list] of Object.entries(changes)) {
    if (!ENTITY_BY_NAME[name]) throw new BadRequest(`table inconnue : ${name}`);
    if (!Array.isArray(list)) throw new BadRequest(`${name} doit être une liste`);
    total += list.length;
  }
  if (total > MAX_PUSH_RECORDS) throw new BadRequest(`trop de modifications (max ${MAX_PUSH_RECORDS})`);
  const originId = typeof origin === 'string' ? origin.slice(0, 64) : null;

  const remaps = {};
  const overrides = {};
  const rejected = [];
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const [[state]] = await conn.query('SELECT version FROM sync_state WHERE id = 1 FOR UPDATE');
    let version = Number(state.version);

    const aliasCache = new Map();
    const resolve = async (entityName, id) => {
      const k = entityName + '\u0000' + id;
      if (!aliasCache.has(k)) {
        const [rows] = await conn.query('SELECT to_id FROM aliases WHERE entity = ? AND from_id = ?', [entityName, id]);
        aliasCache.set(k, rows.length ? rows[0].to_id : id);
      }
      return aliasCache.get(k);
    };
    const addAlias = async (entityName, fromId, toId) => {
      await conn.query(
        'INSERT INTO aliases (entity, from_id, to_id) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE to_id = VALUES(to_id)',
        [entityName, fromId, toId],
      );
      aliasCache.set(entityName + '\u0000' + fromId, toId);
    };
    const addOverride = (entity, row) => {
      (overrides[entity.name] ??= []).push(rowToRecord(entity, row));
    };
    const addRemap = (entity, from, to) => {
      (remaps[entity.name] ??= {})[from] = to;
    };

    // Ordre des tables = ordre de dépendance (appareils → catégories → produits
    // → canaux → lignes), pour que les références soient réécrites avec les
    // fusions faites plus tôt dans le même envoi.
    for (const entity of ENTITIES) {
      const list = changes[entity.name];
      if (!list) continue;
      for (const rec of list) {
        const err = validateRecord(entity, rec);
        if (err) {
          rejected.push({ entity: entity.name, id: typeof rec?.id === 'string' ? rec.id : null, error: err });
          continue;
        }
        const deleted = rec.deleted === true;
        const data = { ...rec };

        let id = await resolve(entity.name, rec.id);
        if (id !== rec.id) addRemap(entity, rec.id, id);
        for (const [field, def] of Object.entries(entity.fields)) {
          if (def.type === 'ref' && typeof data[field] === 'string') {
            data[field] = await resolve(def.entity, data[field]);
          }
        }

        let [[existing]] = await conn.query(`SELECT * FROM ${entity.table} WHERE id = ? FOR UPDATE`, [id]);
        const key = entity.unique && !deleted ? entity.unique(data) : null;

        if (!existing && key !== null) {
          // Nouvelle ligne qui porte la même clé qu'une ligne existante → fusion
          const [[dup]] = await conn.query(`SELECT * FROM ${entity.table} WHERE uniq_key = ? FOR UPDATE`, [key]);
          if (dup) {
            await addAlias(entity.name, rec.id, dup.id);
            addRemap(entity, rec.id, dup.id);
            id = dup.id;
            existing = dup;
            // Fusion : la ligne existante garde son nom tel quel (« Bague »
            // ne devient pas « bague ») et ses valeurs ; le doublon ne fait
            // que compléter les champs encore vides.
            for (const field of Object.keys(entity.fields)) {
              const current = dup[toColumn(field)];
              if (current !== null && current !== undefined && current !== '') data[field] = current;
            }
          }
        }

        if (existing && Number(existing.updated_at) > rec.updatedAt) {
          // Modification plus ancienne que celle du serveur : ignorée
          addOverride(entity, existing);
          continue;
        }

        if (existing && key !== null) {
          // Renommage d'une ligne existante vers une clé déjà prise ailleurs : refusé
          const [[clash]] = await conn.query(`SELECT id FROM ${entity.table} WHERE uniq_key = ? AND id <> ?`, [key, id]);
          if (clash) {
            addOverride(entity, existing);
            continue;
          }
        }

        version += 1;
        const cols = ['id', 'version', 'updated_at', 'deleted', 'origin'];
        const vals = [id, version, rec.updatedAt, deleted ? 1 : 0, originId];
        if (entity.unique) {
          cols.push('uniq_key');
          vals.push(key);
        }
        for (const field of Object.keys(entity.fields)) {
          let v = data[field];
          // Suppression envoyée sans les champs : on garde les anciennes valeurs
          if ((v === undefined || v === null) && deleted && existing) v = existing[toColumn(field)];
          cols.push(toColumn(field));
          vals.push(v ?? null);
        }
        const updates = cols.filter((c) => c !== 'id').map((c) => `${c} = VALUES(${c})`);
        await conn.query(
          `INSERT INTO ${entity.table} (${cols.join(', ')}) VALUES (${cols.map(() => '?').join(', ')})
           ON DUPLICATE KEY UPDATE ${updates.join(', ')}`,
          vals,
        );
        if (id !== rec.id) {
          // L'appareil connaît cette ligne sous un autre id : on lui renvoie la ligne officielle
          const [[row]] = await conn.query(`SELECT * FROM ${entity.table} WHERE id = ?`, [id]);
          addOverride(entity, row);
        }
      }
    }

    await conn.query('UPDATE sync_state SET version = ? WHERE id = 1', [version]);
    await conn.commit();
    return { version, remaps, overrides, rejected };
  } catch (e) {
    await conn.rollback().catch(() => {});
    throw e;
  } finally {
    conn.release();
  }
}

export async function pull(pool, since) {
  if (!Number.isSafeInteger(since) || since < 0) throw new BadRequest('since invalide');
  const [[state]] = await pool.query('SELECT version FROM sync_state WHERE id = 1');
  const current = Number(state.version);
  // Chaque version n'appartient qu'à une seule ligne : une tranche de
  // PULL_PAGE versions contient au plus PULL_PAGE lignes.
  const upTo = Math.min(current, since + PULL_PAGE);
  const changes = {};
  if (upTo > since) {
    for (const entity of ENTITIES) {
      const [rows] = await pool.query(
        `SELECT * FROM ${entity.table} WHERE version > ? AND version <= ? ORDER BY version`,
        [since, upTo],
      );
      if (rows.length) changes[entity.name] = rows.map((r) => rowToRecord(entity, r));
    }
  }
  // since > current : la base serveur a été réinitialisée (restauration…).
  // L'appareil doit tout renvoyer puis tout relire depuis 0.
  if (since > current) return { version: current, hasMore: false, reset: true, changes };
  return { version: upTo, hasMore: upTo < current, reset: false, changes };
}
