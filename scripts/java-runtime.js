import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const windows = process.platform === 'win32';
const command = process.argv[2];
const java = process.env.JAVA_HOME
  ? path.resolve(process.env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java')
  : 'java';
const packageCommand = windows ? (process.env.ComSpec || 'cmd.exe') : 'sh';
const packageArguments = windows ? ['/d', '/c', 'mvnw.cmd', 'package'] : ['./mvnw', 'package'];
const jar = path.join(root, 'backend', 'target', 'control-center-backend.jar');

function run(executable, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { cwd, stdio: 'inherit', windowsHide: true });
    const stop = () => child.kill();
    function close() {
      process.removeListener('SIGINT', stop);
      process.removeListener('SIGTERM', stop);
    }
    process.once('SIGINT', stop);
    process.once('SIGTERM', stop);
    child.once('error', (failure) => {
      close();
      reject(failure);
    });
    child.once('exit', (code, signal) => {
      close();
      if (code === 0) resolve();
      else reject(new Error(`${executable} stopped with ${code ?? signal}.`));
    });
  });
}

try {
  if (command === 'package') {
    await run(packageCommand, packageArguments, path.join(root, 'backend'));
  } else if (command === 'start') {
    await run(java, ['--enable-native-access=ALL-UNNAMED', '-jar', jar], root);
  } else {
    throw new Error('Expected package or start.');
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
