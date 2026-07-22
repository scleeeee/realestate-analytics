import mysql from 'mysql2/promise';

export function createPool() {
  return mysql.createPool({
    host: 'localhost',
    port: 13306,
    user: 'realestate',
    password: 'realestate',
    database: 'realestate',
    connectionLimit: 5,
  });
}
