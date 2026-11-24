import { spawn } from 'node:child_process';
import { access, mkdir, mkdtemp, realpath, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { findJavaHome, javaExecutable } from './java-home.js';

const root = fileURLToPath(new URL('../', import.meta.url));
const backend = path.join(root, 'backend');
const target = path.join(backend, 'target');
const jarName = 'projects-journal-backend.jar';
const jar = path.join(target, jarName);
const devClient = path.join(root, 'dist', 'client-dev');
const windows = process.platform === 'win32';
const shutdown = new AbortController();
const stop = (signal) => shutdown.abort(signal);

function terminate(child) {
  if (child.exitCode !== null || child.signalCode !== null || !child.pid) return;
  if (windows) {
    // Batch wrappers can own another process. Stop the tree rooted at our child PID only.
    const killer = spawn('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], {
      stdio: 'ignore',
      windowsHide: true,
    });
    killer.once('error', () => child.kill());
    killer.once('exit', (code) => {
      if (code !== 0 && child.exitCode === null && child.signalCode === null) child.kill();
    });
  } else {
    child.kill(shutdown.signal.reason);
  }
}

function execute(command, args, { cwd, env, name }) {
  shutdown.signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env, stdio: 'inherit', windowsHide: true });
    const abort = () => terminate(child);
    const unregister = () => shutdown.signal.removeEventListener('abort', abort);
    shutdown.signal.addEventListener('abort', abort, { once: true });
    child.once('error', (error) => {
      unregister();
      reject(new Error(`${name}: ${error.message}`));
    });
    child.once('exit', (code, signal) => {
      unregister();
      if (code === 0) resolve();
      else reject(new Error(`${name} завершился с кодом ${code ?? signal}.`));
    });
  });
}

async function removeGenerated(directory) {
  const expected = path.join(await realpath(root), path.relative(root, directory));
  try {
    await requireBuildPath(directory, expected);
  } catch (error) {
    if (error.code === 'ENOENT') return;
    throw error;
  }
  await rm(expected, { recursive: true, force: true, maxRetries: 3 });
}

async function requireBuildPath(directory, expected) {
  const actual = await realpath(directory);
  const normalize = (value) => (windows ? value.toLowerCase() : value);
  if (normalize(actual) !== normalize(expected)) {
    throw new Error(`Каталог сборки Java находится вне ожидаемого пути ${expected}.`);
  }
}

async function buildBackend(directory) {
  shutdown.signal.throwIfAborted();
  // Maven copies resources incrementally, so remove only its generated static output.
  await removeGenerated(path.join(target, 'classes', 'static'));
  const javaHome = findJavaHome(root);
  const options = {
    cwd: backend,
    env: {
      ...process.env,
      ...(javaHome && { JAVA_HOME: javaHome }),
      CONTROL_CENTER_JAVA_JAR_DIR: directory,
    },
    name: 'Maven Wrapper',
  };
  return windows
    ? execute(process.env.ComSpec || 'cmd.exe', ['/d', '/c', 'mvnw.cmd', 'package'], options)
    : execute('sh', ['./mvnw', 'package'], options);
}

async function withBackend(useJar) {
  const expectedBackend = path.join(await realpath(root), 'backend');
  await requireBuildPath(backend, expectedBackend);
  await mkdir(target, { recursive: true });
  await requireBuildPath(target, path.join(expectedBackend, 'target'));
  const directory = await mkdtemp(path.join(target, 'java-package-'));
  try {
    await buildBackend(directory);
    shutdown.signal.throwIfAborted();
    const artifact = path.join(directory, jarName);
    await access(artifact);
    await useJar(artifact);
  } finally {
    await removeGenerated(directory);
  }
}

async function publishJar(artifact) {
  try {
    await rename(artifact, jar);
  } catch (error) {
    if (!windows || !['EACCES', 'EBUSY', 'EPERM'].includes(error.code)) throw error;
    throw new Error(
      'Не удалось заменить JAR. Остановите Projects Journal и повторите npm run build.',
      { cause: error },
    );
  }
}

async function buildClient(options = {}) {
  shutdown.signal.throwIfAborted();
  const { build } = await import('vite');
  return build({ root, build: options });
}

async function startJava(args, { env = process.env, artifact = jar } = {}) {
  try {
    await access(artifact);
  } catch {
    throw new Error('Java-сборка отсутствует. Выполните npm run build.');
  }
  const executable = javaExecutable(findJavaHome(root));
  return execute(executable, ['--enable-native-access=ALL-UNNAMED', '-jar', artifact, ...args], {
    cwd: root,
    env,
    name: 'Projects Journal',
  });
}

function waitForClient(watcher) {
  shutdown.signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    const unregister = () => {
      watcher.off('event', onEvent);
      shutdown.signal.removeEventListener('abort', abort);
    };
    const abort = () => {
      unregister();
      reject(shutdown.signal.reason);
    };
    const onEvent = (event) => {
      if (event.code === 'END') {
        unregister();
        resolve();
      } else if (event.code === 'ERROR') {
        unregister();
        reject(event.error);
      }
    };
    watcher.on('event', onEvent);
    shutdown.signal.addEventListener('abort', abort, { once: true });
  });
}

async function develop(args) {
  await removeGenerated(devClient);
  // Keep the currently served bundle available while Rollup writes the replacement.
  const watcher = await buildClient({
    outDir: devClient,
    emptyOutDir: false,
    watch: { chokidar: { ignored: ['**/dist/client-dev/**'] } },
  });
  try {
    await waitForClient(watcher);
    const env = { ...process.env, CONTROL_CENTER_CLIENT_DIR: devClient };
    await withBackend((artifact) => startJava(args, { env, artifact }));
  } finally {
    await watcher.close();
  }
}

const [command, ...args] = process.argv.slice(2);
process.once('SIGINT', stop);
process.once('SIGTERM', stop);
try {
  if (command !== 'start' && command !== 'dev' && args.length > 0) {
    throw new Error('Передавайте параметры сборки Maven через MAVEN_ARGS.');
  }
  switch (command) {
    case 'build':
      await buildClient();
      await withBackend(publishJar);
      break;
    case 'dev':
      await develop(args);
      break;
    case 'start':
      await startJava(args);
      break;
    case 'mcp':
      await startJava(['--mcp']);
      break;
    default:
      throw new Error('Используйте npm run build, npm run dev, npm start или npm run mcp.');
  }
} catch (error) {
  if (!shutdown.signal.aborted) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
} finally {
  process.removeListener('SIGINT', stop);
  process.removeListener('SIGTERM', stop);
  if (shutdown.signal.aborted) {
    process.exitCode = shutdown.signal.reason === 'SIGINT' ? 130 : 143;
  }
}
