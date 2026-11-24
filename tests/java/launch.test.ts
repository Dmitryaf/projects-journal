import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { access } from 'node:fs/promises';
import { createServer } from 'node:net';
import path from 'node:path';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import type { Workspace } from '../../shared/contracts.js';
import { fixture } from '../fixtures.js';
import { directory, expectJson, json, startHttp } from './runtime.js';

function scriptAsset(html: string) {
  const asset = html.match(/src="(\/assets\/[^"]+\.js)"/)?.[1];
  assert.ok(asset, 'Vite module asset is referenced by the packaged page');
  return asset;
}

test('Java serves the packaged React application, deep links and assets on the API port', async (t) => {
  const temporary = await fixture();
  const server = await startHttp(path.join(temporary.root, 'Изолированная база'));
  t.after(async () => {
    await server.close();
    await temporary.cleanup();
  });
  const { request } = server;
  const page = await request('/');
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-type') ?? '', /^text\/html\b/);
  const html = await page.text();
  assert.match(html, /<title>Projects Journal<\/title>/);
  const asset = await request(scriptAsset(html));
  assert.equal(asset.status, 200);
  assert.match(asset.headers.get('content-type') ?? '', /javascript/);
  assert.equal(
    await (await request('/projects/eaafaf07-3a41-4ea2-bfca-556602987aea')).text(),
    html,
  );
  assert.match((await request('/favicon.svg')).headers.get('content-type') ?? '', /image\/svg/);
  assert.equal((await request('/assets/missing-module.js')).status, 404);
  const api = await request('/api/missing');
  assert.equal(api.status, 404);
  assert.match(api.headers.get('content-type') ?? '', /json/);
  assert.equal(
    (await request('/', { headers: { Origin: 'https://example.invalid' } })).status,
    403,
  );
});

test(
  'Windows launcher starts and reuses the Java JAR for the selected data directory',
  {
    skip: process.platform !== 'win32',
    timeout: 65_000,
  },
  async (t) => {
    const temporary = await fixture();
    const reservation = createServer();
    await new Promise<void>((resolve) => reservation.listen(0, '127.0.0.1', resolve));
    const address = reservation.address();
    assert.ok(address && typeof address === 'object');
    const { port } = address;
    await new Promise<void>((resolve, reject) =>
      reservation.close((error) => (error ? reject(error) : resolve())),
    );
    const data = path.join(temporary.root, 'Данные с пробелом');
    const base = 'http://127.0.0.1:' + port;
    const request = (route: string, options?: RequestInit) =>
      fetch(base + route, { signal: AbortSignal.timeout(5000), ...options });
    const launch = (dataDirectory: string) =>
      new Promise<void>((resolve, reject) => {
        const child = spawn(
          'powershell.exe',
          [
            '-NoProfile',
            '-NonInteractive',
            '-ExecutionPolicy',
            'Bypass',
            '-File',
            'scripts/launch.ps1',
            '-NoBrowser',
            '-NoDialog',
          ],
          {
            cwd: directory,
            windowsHide: true,
            timeout: 60000,
            env: {
              ...process.env,
              PORT: String(port),
              CONTROL_CENTER_DATA_DIR: dataDirectory,
              CONTROL_CENTER_CLIENT_DIR: '',
              CONTROL_CENTER_CODEX_PATH: path.join(temporary.root, 'disabled-codex.exe'),
              OPENAI_API_KEY: '',
              CONTROL_CENTER_OPENAI_MODEL: '',
            },
          },
        );
        let output = '';
        child.stdout.on('data', (chunk) => (output += String(chunk)));
        child.stderr.on('data', (chunk) => (output += String(chunk)));
        child.once('error', reject);
        child.once('exit', (code) => {
          child.stdout.destroy();
          child.stderr.destroy();
          if (code === 0) resolve();
          else reject(Object.assign(new Error(output), { code }));
        });
      });
    let serverPid = 0;
    t.after(async () => {
      if (serverPid) {
        try {
          process.kill(serverPid);
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error;
        }
        for (let attempt = 0; attempt < 20; attempt++) {
          try {
            process.kill(serverPid, 0);
          } catch (error) {
            if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error;
            break;
          }
          await delay(50);
        }
      }
      await temporary.cleanup();
    });
    await launch(data);
    const health = await expectJson<{ runtime: string; pid: number; instance: string }>(
      request('/api/health'),
    );
    serverPid = health.pid;
    assert.equal(health.runtime, 'java');
    assert.match(health.instance, /^[0-9a-f]{64}$/);
    scriptAsset(await (await request('/')).text());
    const task = await expectJson<{ id: string }>(
      request('/api/tasks', json('POST', { title: 'Проверка ярлыка Java' })),
      201,
    );
    await launch(data);
    assert.deepEqual(await expectJson(request('/api/health')), health);
    const other = path.join(temporary.root, 'Другая база');
    await assert.rejects(launch(other), { code: 1 });
    await assert.rejects(access(path.join(other, 'control-center.sqlite')), { code: 'ENOENT' });
    const workspace = await expectJson<Workspace>(request('/api/workspace'));
    assert.equal(workspace.storage?.databasePath, path.join(data, 'control-center.sqlite'));
    assert.ok(workspace.tasks.some((saved) => saved.id === task.id));
  },
);
