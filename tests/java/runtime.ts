import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { realpathSync } from 'node:fs';
import { createServer } from 'node:net';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { findJavaHome, javaExecutable } from '../../scripts/java-home.js';

export const directory = realpathSync(process.cwd());
const jar = path.join(directory, 'backend', 'target', 'projects-journal-backend.jar');
const executable = javaExecutable(findJavaHome(directory));

export const javaMcp = { command: executable, args: ['-jar', jar, '--mcp'] };

interface RuntimeOptions {
  port?: number;
  openAiStub?: boolean;
  codex?: { script: string; config: string };
  schedules?: boolean;
  restoreRecovery?: boolean;
}

export async function startHttp(data: string, options: RuntimeOptions = {}) {
  const reservation = createServer().listen(options.port ?? 0, '127.0.0.1');
  await once(reservation, 'listening');
  const address = reservation.address();
  assert.ok(address && typeof address === 'object');
  reservation.close();
  await once(reservation, 'close');
  const base = `http://127.0.0.1:${address.port}`;
  const args =
    options.openAiStub || options.codex || options.schedules
      ? [
          `-Dloader.path=${path.join(directory, 'backend', 'target', 'test-classes')}`,
          '-Dloader.main=io.github.dmitryaf.projectsjournal.AnalysisStubApplication',
          ...(options.codex ? ['-Djournal.test.codex=true'] : []),
          ...(options.schedules ? ['-Djournal.test.schedules=true'] : []),
          ...(options.restoreRecovery ? ['-Djournal.restore-recovery=true'] : []),
          '-cp',
          jar,
          'org.springframework.boot.loader.launch.PropertiesLauncher',
        ]
      : ['-jar', jar, ...(options.restoreRecovery ? ['--restore-recovery'] : [])];
  const processHandle = spawn(executable, args, {
    env: {
      ...process.env,
      PORT: String(address.port),
      CONTROL_CENTER_DATA_DIR: data,
      CONTROL_CENTER_CLIENT_DIR: '',
      CONTROL_CENTER_CODEX_PATH: path.join(data, 'codex-disabled-in-java-tests'),
      OPENAI_API_KEY: '',
      CONTROL_CENTER_OPENAI_MODEL: '',
      ...(options.codex
        ? {
            JOURNAL_TEST_CODEX_NODE: process.execPath,
            JOURNAL_TEST_CODEX_SCRIPT: options.codex.script,
            JOURNAL_TEST_CODEX_CONFIG: options.codex.config,
          }
        : {}),
    },
    windowsHide: true,
  });
  let logs = '';
  processHandle.stdout.on('data', (chunk) => (logs += chunk));
  processHandle.stderr.on('data', (chunk) => (logs += chunk));
  const stopped = new Promise<void>((resolve) => {
    processHandle.once('error', (error) => (logs += error.message));
    processHandle.once('close', () => resolve());
  });
  const close = async () => {
    processHandle.kill();
    await stopped;
  };
  const request = (route: string, options?: RequestInit) =>
    fetch(base + route, { signal: AbortSignal.timeout(5000), ...options });
  try {
    const readyBy = performance.now() + 30_000;
    while (performance.now() < readyBy && processHandle.exitCode === null) {
      const response = await request('/api/health', { signal: AbortSignal.timeout(1000) }).catch(
        () => null,
      );
      if (response?.ok) {
        const health = await response
          .clone()
          .json()
          .catch(() => null);
        if (health?.application === 'control-center' && health.pid === processHandle.pid) {
          return { base, request, close, health: response };
        }
      }
      await delay(100);
    }
    assert.fail(`Java HTTP did not become ready. ${logs}`);
  } catch (error) {
    await close();
    throw error;
  }
}

export function json(method: string, value: object) {
  return {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Control-Center': '1' },
    body: JSON.stringify(value),
  };
}

export async function expectJson<T>(response: Promise<Response>, status = 200): Promise<T> {
  const received = await response;
  assert.equal(received.status, status, await received.clone().text());
  return received.json();
}
