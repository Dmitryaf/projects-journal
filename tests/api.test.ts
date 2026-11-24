import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import path from 'node:path';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import type { Project, Task, Workspace } from '../shared/contracts.js';
import { fixture } from './fixtures.js';

test('Java API scans an empty workspace, exports summaries, and writes records and links', async (t) => {
  const files = await fixture();
  const root = await files.repo('product');
  const other = await files.repo('tool');
  await writeFile(path.join(root, 'work.txt'), 'Dirty work tree.');
  const reservation = createServer().listen(0, '127.0.0.1');
  await once(reservation, 'listening');
  const address = reservation.address();
  assert.ok(address && typeof address === 'object');
  const closed = once(reservation, 'close');
  reservation.close();
  await closed;
  const java = process.env.JAVA_HOME
    ? path.resolve(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : 'java';
  const server = spawn(java, ['-jar', 'backend/target/control-center-backend.jar'], {
    windowsHide: true,
    env: {
      ...process.env,
      PORT: String(address.port),
      CONTROL_CENTER_CLIENT_DIR: '',
      CONTROL_CENTER_DATA_DIR: path.join(files.root, 'data'),
    },
  });
  const finished = once(server, 'close');
  t.after(async () => {
    server.kill();
    await finished;
    await files.cleanup();
  });
  const request = (route: string, method = 'GET', body?: object) =>
    fetch(`http://127.0.0.1:${address.port}/api${route}`, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-Control-Center': '1' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(5000),
    });
  let logs = '';
  server.stdout.on('data', (chunk) => (logs += chunk));
  server.stderr.on('data', (chunk) => (logs += chunk));
  for (let i = 0; i < 100; i++) {
    const response = await request('/workspace').catch(() => null);
    if (response?.ok) break;
    assert.ok(i < 99 && server.exitCode === null, logs || 'Java API did not start.');
    await delay(100);
  }

  const workspace = async () => (await (await request('/workspace')).json()) as Workspace;
  const empty = await workspace();
  assert.deepEqual(empty.projects, []);
  assert.equal((await request('/settings', 'PUT', { ...empty.settings, roots: [root, other] })).status, 200);
  const scanned = await workspace();
  const project = scanned.projects.find((row) => row.metadata.name === 'product')!;
  const target = scanned.projects.find((row) => row.metadata.name === 'tool')!;
  assert.equal(project.snapshot.git.dirty, true);
  assert.equal(project.metadataSource, 'defaults');
  const route = `/projects/${project.id}`;
  assert.equal((await request(route, 'PUT', {
    metadata: { ...project.metadata, status: 'active', current_focus: 'Ship' }, notes: 'Local note',
  })).status, 200);
  assert.equal((await request(`${route}/export`, 'POST', { expectedHash: null })).status, 200);
  assert.match(await readFile(path.join(root, 'PROJECT.yaml'), 'utf8'), /current_focus: Ship/);
  assert.equal((await request(`${route}/export`, 'POST', { expectedHash: null })).status, 409);
  const yaml = (await (await request(`${route}/use-yaml`, 'POST', {})).json()) as Project;
  assert.equal(yaml.metadataSource, 'yaml');
  assert.equal(yaml.notes, 'Local note');
  assert.equal((await request('/tasks', 'POST', { title: '' })).status, 400);
  const taskResponse = await request('/tasks', 'POST', { title: 'Task', projectId: project.id });
  assert.equal(taskResponse.status, 201);
  const task = (await taskResponse.json()) as Task;
  assert.equal(task.state, 'next');
  const taskRoute = `/tasks/${task.id}`;
  assert.equal((await request(taskRoute, 'PUT', { ...task, state: 'later' })).status, 200);
  assert.equal((await request('/ideas', 'POST', { title: 'Idea' })).status, 201);
  assert.equal((await request('/decisions', 'POST', {
    title: 'Decision', decision: 'Use SQLite', projectId: project.id, date: '2026-10-05', status: 'pending',
  })).status, 201);
  const relation = { sourceId: project.id, targetId: target.id, type: 'uses' };
  await request('/relations', 'POST', relation);
  await request('/relations', 'POST', relation);
  const saved = await workspace();
  assert.equal(saved.tasks[0].state, 'later');
  assert.equal(saved.relations.length, 1);
  assert.ok(saved.projects.find((row) => row.id === project.id)!.signals.some((signal) => signal.code === 'decision'));
  assert.equal((await request(taskRoute, 'DELETE', {})).status, 200);
  assert.equal((await request(taskRoute, 'DELETE', {})).status, 404);
  const guarded = await fetch(`http://127.0.0.1:${address.port}/api/workspace`, {
    headers: { Origin: 'https://foreign.invalid' },
  });
  assert.equal(guarded.status, 403);
});
