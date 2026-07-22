import mysql from 'mysql2/promise';

export function createPool() {
  return mysql.createPool({
    host: process.env.DB_HOST ?? 'localhost',
    port: Number(process.env.DB_PORT ?? 13306),
    user: process.env.DB_USER ?? 'realestate',
    password: process.env.DB_PASSWORD ?? 'realestate',
    database: process.env.DB_NAME ?? 'realestate',
    connectionLimit: 5,
  });
}
