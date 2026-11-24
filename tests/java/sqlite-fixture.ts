import { DatabaseSync } from 'node:sqlite';

export function openDatabase<T>(file: string, inspect: (db: DatabaseSync) => T): T {
  const db = new DatabaseSync(file);
  try {
    return inspect(db);
  } finally {
    db.close();
  }
}
