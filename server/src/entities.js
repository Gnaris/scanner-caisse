// Description des tables synchronisées. Source unique de vérité : le schéma
// MySQL (migrate.js), la validation des envois et la conversion
// ligne MySQL <-> JSON de l'API sont tous dérivés de ce fichier.
//
// Colonnes communes à toutes les tables (ajoutées automatiquement) :
//   id          identifiant (UUID côté app, clé texte pour settings)
//   version     numéro croissant attribué par le serveur à chaque écriture
//   updated_at  horodatage (ms) de la dernière modif CÔTÉ APPAREIL, sert à
//               départager deux modifications de la même ligne
//   deleted     1 = supprimée (on garde la ligne pour propager la suppression)
//   origin      appareil physique qui a écrit la dernière version
//   uniq_key    (si `unique`) clé normalisée pour la fusion des doublons,
//               NULL quand la ligne est supprimée

/**
 * Types de champs :
 *   { type: 'string', max, required }  texte (vide autorisé sauf si `nonEmpty`)
 *   { type: 'int', min, max, required }
 *   { type: 'ref', entity, required }  identifiant d'une autre table
 */
export const ENTITIES = [
  {
    name: 'devices',
    table: 'devices',
    fields: {
      name: { type: 'string', max: 100, required: true, nonEmpty: true },
    },
    // Deux appareils créés hors-ligne avec le même nom = le même appareil
    unique: (r) => normalizeName(r.name),
  },
  {
    name: 'categories',
    table: 'categories',
    fields: {
      name: { type: 'string', max: 100, required: true, nonEmpty: true },
      nameEn: { type: 'string', max: 100 },
      sageArticleCode: { type: 'string', max: 50 },
      barcodePrefix: { type: 'string', max: 50 },
    },
    // Même règle que l'index NOCASE de Room : « Bague » == « bague »
    unique: (r) => normalizeName(r.name),
  },
  {
    name: 'products',
    table: 'products',
    fields: {
      barcode: { type: 'string', max: 100, required: true, nonEmpty: true },
      categoryId: { type: 'ref', entity: 'categories', required: true },
      priceCents: { type: 'int', min: 0, max: 100_000_000, required: true },
      nameEn: { type: 'string', max: 100 },
    },
    unique: (r) => r.barcode.trim(),
  },
  {
    name: 'channels',
    table: 'channels',
    fields: {
      deviceId: { type: 'ref', entity: 'devices', required: true },
      name: { type: 'string', max: 100, required: true, nonEmpty: true },
      discountPercent: { type: 'int', min: 0, max: 100, required: true },
      createdAt: { type: 'int', min: 0, max: Number.MAX_SAFE_INTEGER, required: true },
    },
  },
  {
    // Une ligne = un ajout au panier (un scan, un ajout manuel…). L'app
    // regroupe à l'affichage par (catégorie, prix). Comme chaque ajout a son
    // propre id, deux appareils qui scannent dans le même canal hors-ligne
    // ne s'écrasent jamais : les ajouts s'additionnent.
    name: 'cart_lines',
    table: 'cart_lines',
    fields: {
      channelId: { type: 'ref', entity: 'channels', required: true },
      productId: { type: 'ref', entity: 'products' },
      barcode: { type: 'string', max: 100, required: true },
      categoryName: { type: 'string', max: 100, required: true, nonEmpty: true },
      categoryNameEn: { type: 'string', max: 100 },
      nameEn: { type: 'string', max: 100 },
      sageArticleCode: { type: 'string', max: 50 },
      unitPriceCents: { type: 'int', min: 0, max: 100_000_000, required: true },
      quantity: { type: 'int', min: 1, max: 1_000_000, required: true },
      createdAt: { type: 'int', min: 0, max: Number.MAX_SAFE_INTEGER, required: true },
    },
  },
  {
    // Paramètres boutique partagés (nom, adresse, QR…). id = clé du paramètre.
    // Les paramètres propres à un appareil (IP imprimante…) ne passent jamais ici.
    name: 'settings',
    table: 'settings',
    fields: {
      value: { type: 'string', max: 4000, required: true },
    },
  },
];

export const ENTITY_BY_NAME = Object.fromEntries(ENTITIES.map((e) => [e.name, e]));

export function normalizeName(s) {
  return s.trim().replace(/\s+/g, ' ').toLowerCase();
}

export function toColumn(field) {
  return field.replace(/[A-Z]/g, (c) => '_' + c.toLowerCase());
}

const ID_RE = /^[A-Za-z0-9_.:-]{1,64}$/;

export function isValidId(id) {
  return typeof id === 'string' && ID_RE.test(id);
}

/**
 * Valide un enregistrement envoyé par un appareil. Renvoie un message
 * d'erreur ou null. Les champs d'une ligne supprimée sont facultatifs.
 */
export function validateRecord(entity, r) {
  if (r === null || typeof r !== 'object' || Array.isArray(r)) return 'enregistrement invalide';
  if (!isValidId(r.id)) return 'id invalide';
  if (!Number.isSafeInteger(r.updatedAt) || r.updatedAt < 0) return 'updatedAt invalide';
  if (r.deleted !== undefined && typeof r.deleted !== 'boolean') return 'deleted invalide';
  const deleted = r.deleted === true;
  for (const [field, def] of Object.entries(entity.fields)) {
    const v = r[field];
    if (v === undefined || v === null) {
      if (def.required && !deleted) return `${field} manquant`;
      continue;
    }
    switch (def.type) {
      case 'string':
        if (typeof v !== 'string') return `${field} doit être un texte`;
        if (v.length > def.max) return `${field} trop long`;
        if (def.nonEmpty && !deleted && v.trim() === '') return `${field} vide`;
        break;
      case 'int':
        if (!Number.isSafeInteger(v) || v < def.min || v > def.max) return `${field} hors limites`;
        break;
      case 'ref':
        if (!isValidId(v)) return `${field} invalide`;
        break;
    }
  }
  return null;
}

/** Ligne MySQL -> objet JSON de l'API. */
export function rowToRecord(entity, row) {
  const rec = {
    id: row.id,
    version: Number(row.version),
    updatedAt: Number(row.updated_at),
    deleted: row.deleted === 1 || row.deleted === true,
  };
  for (const [field, def] of Object.entries(entity.fields)) {
    const v = row[toColumn(field)];
    rec[field] = v === null || v === undefined ? null : def.type === 'int' ? Number(v) : v;
  }
  return rec;
}
