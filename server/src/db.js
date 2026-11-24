import mysql from 'mysql2/promise';

export function createPool(overrides = {}) {
  return mysql.createPool({
    host: process.env.DB_HOST ?? '127.0.0.1',
    port: Number(process.env.DB_PORT ?? 3306),
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
    charset: 'utf8mb4',
    connectionLimit: 5,
    // BIGINT renvoyés en Number (versions et timestamps < 2^53)
    supportBigNumbers: true,
    bigNumberStrings: false,
    ...overrides,
  });
}
