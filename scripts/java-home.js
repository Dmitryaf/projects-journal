import { readFileSync } from 'node:fs';
import path from 'node:path';

export function findJavaHome(root) {
  const explicit = process.env.JAVA_HOME || process.env.CONTROL_CENTER_JAVA_HOME;
  if (explicit) return path.resolve(root, explicit);
  try {
    const hint = readFileSync(path.join(root, '.local', 'java-home.txt'), 'utf8').trim();
    return hint ? path.resolve(root, hint) : undefined;
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    return undefined;
  }
}

export function javaExecutable(javaHome) {
  return javaHome
    ? path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : 'java';
}
