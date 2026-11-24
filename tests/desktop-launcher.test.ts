import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { access, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

test(
  'Windows desktop launcher: Java cold start, concurrent clicks, foreign port and recovery',
  {
    skip: process.platform !== 'win32',
    timeout: 120_000,
  },
  async () => {
    const data = await mkdtemp(path.join(tmpdir(), 'cc-launch-'));
    const foreign = createServer((_req, res) => res.end('foreign service'));
    await new Promise<void>((resolve) => foreign.listen(0, '127.0.0.1', resolve));
    const address = foreign.address();
    assert.ok(address && typeof address !== 'string');
    const port = address.port;
    const base = `http://127.0.0.1:${port}`;
    let serverPid: number | undefined;
    const launch = (
      dataDirectory: string | null = data,
      profile = process.env.USERPROFILE,
      localData = process.env.LOCALAPPDATA,
    ) =>
      new Promise<{ code: number | null; output: string }>((resolve, reject) => {
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
            windowsHide: true,
            env: {
              ...process.env,
              PORT: String(port),
              CONTROL_CENTER_DATA_DIR: dataDirectory ?? '',
              CONTROL_CENTER_CLIENT_DIR: '',
              CONTROL_CENTER_CODEX_PATH: path.join(data, 'disabled-codex.exe'),
              USERPROFILE: profile,
              LOCALAPPDATA: localData,
              OPENAI_API_KEY: '',
              CONTROL_CENTER_OPENAI_MODEL: '',
            },
          },
        );
        let output = '';
        child.stdout.on('data', (chunk) => {
          output += String(chunk);
        });
        child.stderr.on('data', (chunk) => {
          output += String(chunk);
        });
        child.once('error', reject);
        child.once('exit', (code) => resolve({ code, output }));
      });
    try {
      const occupied = await launch();
      assert.equal(occupied.code, 1);
      assert.ok(occupied.output.includes(String(port)));
      assert.equal(await (await fetch(base)).text(), 'foreign service');
      await new Promise<void>((resolve) => foreign.close(() => resolve()));
      const results = await Promise.all([launch(), launch()]);
      const health = await (await fetch(`${base}/api/health`)).json();
      serverPid = health.pid;
      for (const result of results) assert.equal(result.code, 0, result.output);
      assert.equal(health.application, 'control-center');
      assert.equal(health.runtime, 'java');
      assert.match(health.instance, /^[a-f0-9]{64}$/);
      const html = await (await fetch(base)).text();
      assert.match(html, /<title>Projects Journal<\/title>/);
      const asset = html.match(/src="(\/assets\/[^"]+\.js)"/)?.[1];
      assert.ok(asset && (await fetch(base + asset)).ok);
      const repeated = await launch();
      assert.equal(repeated.code, 0, repeated.output);
      assert.equal((await (await fetch(`${base}/api/health`)).json()).pid, serverPid);
      const otherData = await launch(path.join(data, 'other'));
      assert.equal(otherData.code, 1, 'A different database must not reuse this server');
      assert.equal((await (await fetch(`${base}/api/health`)).json()).pid, serverPid);
      assert.equal(
        (await fetch(`${base}/api/health`, { headers: { Origin: 'https://example.com' } })).status,
        403,
      );
      const taskResponse = await fetch(`${base}/api/tasks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Control-Center': '1' },
        body: JSON.stringify({ title: 'Launcher restart check' }),
      });
      assert.equal(taskResponse.status, 201);
      const task = await taskResponse.json();
      process.kill(serverPid!);
      serverPid = undefined;
      await new Promise((resolve) => setTimeout(resolve, 500));
      const restarted = await launch();
      const afterRestart = await (await fetch(`${base}/api/health`)).json();
      serverPid = afterRestart.pid;
      assert.equal(restarted.code, 0, restarted.output);
      const workspace = await (await fetch(`${base}/api/workspace`)).json();
      assert.ok(workspace.tasks.some((saved: { id: string }) => saved.id === task.id));

      process.kill(serverPid!);
      serverPid = undefined;
      await new Promise((resolve) => setTimeout(resolve, 500));
      const profile = path.join(data, 'profile');
      const localData = path.join(data, 'desktop-local');
      const legacy = path.join(localData, 'ControlCenter');
      const stable = path.join(profile, '.control-center', 'control-center.sqlite');
      await mkdir(legacy, { recursive: true });
      await writeFile(path.join(legacy, 'control-center.sqlite'), 'legacy database sentinel');
      const needsMigration = await launch(null, profile, localData);
      assert.equal(needsMigration.code, 1);
      assert.match(needsMigration.output, /SETUP\.md/);
      await assert.rejects(
        access(stable),
        'Legacy data must not be hidden by a new empty database',
      );
      const seeded = await launch(path.dirname(stable), profile, localData);
      assert.equal(seeded.code, 0, seeded.output);
      serverPid = (await (await fetch(`${base}/api/health`)).json()).pid;
      const selectedTaskResponse = await fetch(`${base}/api/tasks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Control-Center': '1' },
        body: JSON.stringify({ title: 'Selected profile task' }),
      });
      assert.equal(selectedTaskResponse.status, 201);
      const selectedTask = await selectedTaskResponse.json();
      process.kill(serverPid!);
      serverPid = undefined;
      await new Promise((resolve) => setTimeout(resolve, 500));
      const defaultLaunch = await launch(null, profile, localData);
      assert.equal(defaultLaunch.code, 0, defaultLaunch.output);
      serverPid = (await (await fetch(`${base}/api/health`)).json()).pid;
      const selectedWorkspace = await (await fetch(`${base}/api/workspace`)).json();
      assert.equal(selectedWorkspace.storage.databasePath, stable);
      assert.equal(selectedWorkspace.tasks[0].id, selectedTask.id);
      const packagedLocal = path.join(data, 'packaged-local');
      const sameProfile = await launch(null, profile, packagedLocal);
      assert.equal(sameProfile.code, 0, sameProfile.output);
      assert.equal((await (await fetch(`${base}/api/health`)).json()).pid, serverPid);
      const profileTask = await fetch(`${base}/api/tasks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Control-Center': '1' },
        body: JSON.stringify({ title: 'Shared profile restart check' }),
      });
      assert.equal(profileTask.status, 201);
      const savedProfileTask = await profileTask.json();
      process.kill(serverPid!);
      serverPid = undefined;
      await new Promise((resolve) => setTimeout(resolve, 500));
      const defaultRestart = await launch(null, profile, packagedLocal);
      assert.equal(defaultRestart.code, 0, defaultRestart.output);
      serverPid = (await (await fetch(`${base}/api/health`)).json()).pid;
      const defaultWorkspace = await (await fetch(`${base}/api/workspace`)).json();
      assert.equal(defaultWorkspace.storage.databasePath, stable);
      assert.deepEqual(
        defaultWorkspace.tasks.map((saved: { id: string }) => saved.id).sort(),
        [selectedTask.id, savedProfileTask.id].sort(),
      );
    } finally {
      foreign.closeAllConnections();
      foreign.close();
      if (serverPid) process.kill(serverPid);
      await new Promise((resolve) => setTimeout(resolve, 500));
      await rm(data, { recursive: true, force: true, maxRetries: 5, retryDelay: 300 });
    }
  },
);
