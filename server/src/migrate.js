// Crée les tables si elles n'existent pas (idempotent). Lancé à chaque
// déploiement : `npm run migrate`.
import { ENTITIES, toColumn } from './entities.js';
import { createPool } from './db.js';

function columnSql(def) {
  switch (def.type) {
    case 'string':
      return def.max > 255 ? 'TEXT NULL' : `VARCHAR(${def.max}) NULL`;
    case 'int':
      return 'BIGINT NULL';
    case 'ref':
      return 'VARCHAR(64) NULL';
  }
  throw new Error('type inconnu ' + def.type);
}

export function schemaStatements() {
  const stmts = [
    `CREATE TABLE IF NOT EXISTS sync_state (
       id TINYINT PRIMARY KEY,
       version BIGINT UNSIGNED NOT NULL
     ) ENGINE=InnoDB`,
    `INSERT IGNORE INTO sync_state (id, version) VALUES (1, 0)`,
    // Fusion de doublons : un id envoyé par un appareil qui a été rattaché à
    // une ligne existante (même nom de catégorie, même code-barres…).
    `CREATE TABLE IF NOT EXISTS aliases (
       entity VARCHAR(32) NOT NULL,
       from_id VARCHAR(64) NOT NULL,
       to_id VARCHAR(64) NOT NULL,
       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
       PRIMARY KEY (entity, from_id)
     ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin`,
  ];
  for (const e of ENTITIES) {
    const cols = Object.entries(e.fields).map(([f, def]) => `${toColumn(f)} ${columnSql(def)}`);
    const refIdx = Object.entries(e.fields)
      .filter(([, def]) => def.type === 'ref')
      .map(([f]) => `KEY idx_${toColumn(f)} (${toColumn(f)})`);
    stmts.push(`CREATE TABLE IF NOT EXISTS ${e.table} (
       id VARCHAR(64) NOT NULL PRIMARY KEY,
       version BIGINT UNSIGNED NOT NULL,
       updated_at BIGINT NOT NULL,
       deleted TINYINT(1) NOT NULL DEFAULT 0,
       origin VARCHAR(64) NULL,
       server_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
       ${e.unique ? 'uniq_key VARCHAR(255) NULL,' : ''}
       ${cols.join(',\n       ')},
       KEY idx_version (version)
       ${e.unique ? ', UNIQUE KEY uq_key (uniq_key)' : ''}
       ${refIdx.length ? ', ' + refIdx.join(', ') : ''}
     ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin`);
  }
  return stmts;
}

export async function migrate(pool) {
  for (const sql of schemaStatements()) await pool.query(sql);
}

if (process.argv[1]?.endsWith('migrate.js')) {
  const pool = createPool();
  try {
    await migrate(pool);
    console.log('Schéma à jour.');
  } finally {
    await pool.end();
  }
}
