import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { realpathSync } from 'node:fs';
import { mkdir, readFile, readdir, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import {
  defaultSettings,
  type Project,
  type Task,
  type Workspace,
} from '../../shared/contracts.js';
import { connectMcp } from '../mcp-client.js';
import { fixture } from '../fixtures.js';
import { directory, expectJson, javaMcp, json, startHttp } from './runtime.js';
import { openDatabase } from './sqlite-fixture.js';

test('HTTP and MCP share persisted tasks across concurrent writes, backup and restart', async (t) => {
  const files = await fixture();
  t.after(files.cleanup);
  const data = path.join(files.root, 'Копия данных');
  const database = path.join(data, 'control-center.sqlite');
  const replayInput = { title: 'Сохранённая задача', projectId: null, requestKey: 'task-replay' };
  const setup = await startHttp(data);
  let original: Task;
  try {
    original = await expectJson<Task>(setup.request('/api/tasks', json('POST', replayInput)), 201);
  } finally {
    await setup.close();
  }
  const future = '2099-01-01T00:00:00.000Z';
  const oldTask = { ...original, createdAt: future, updatedAt: future };
  openDatabase(database, (db) => {
    db.prepare('UPDATE records SET created_at=?,updated_at=? WHERE id=?').run(
      future,
      future,
      oldTask.id,
    );
    db.exec(`
    INSERT INTO projects(id,path,snapshot) VALUES('fixture-project','fixture-path','{}');
    INSERT INTO codex_grants VALUES('fixture-project',4,1,'access','[]','now','policy');
    INSERT INTO analysis_schedules(id,scope_key,period_kind,timezone,model,policy_version,selection_json,
      access_json,revision,enabled,enabled_at,next_period_start,lease_owner,lease_until,updated_at)
      VALUES('weekly','general','week','UTC','model','policy','{}','[]',7,1,'now','2026-11-23','owner','later','now');
    INSERT INTO analysis_runs(id,review_key,scope_key,generation,status,model,policy_version,selection_json,
      payload_json,guards_json,packet_hash,request_key,request_hash,created_at,expires_at,owner_id,lease_until)
      VALUES('pending','general','general',1,'pending','model','policy','{}','{}','[]','hash','pending','hash','now','later',NULL,NULL),
            ('running','general','general',2,'running','model','policy','{}','{}','[]','hash','running','hash','now','later','owner','later');
    `);
  });
  const cleanups: (() => Promise<void>)[] = [];
  try {
    const http = await startHttp(data);
    cleanups.push(http.close);
    const paths = `${directory}\n${database}`;
    assert.equal(http.health.status, 200, await http.health.clone().text());
    const health = await http.health.json();
    assert.equal(health.application, 'control-center');
    assert.equal(
      health.instance,
      createHash('sha256')
        .update(process.platform === 'win32' ? paths.toLowerCase() : paths)
        .digest('hex'),
    );
    assert.ok(Number.isInteger(health.pid));
    assert.equal((await http.request('/api/tasks', { method: 'POST' })).status, 403);
    assert.equal(
      (await http.request('/api/health', { headers: { Origin: 'https://other.invalid' } })).status,
      403,
    );

    const create = await http.request(
      '/api/tasks',
      json('POST', { title: 'Задача из Java', projectId: null }),
    );
    assert.equal(create.status, 201, await create.clone().text());
    const task = await create.json();
    assert.equal(task.number, oldTask.number + 1);
    assert.match(task.createdAt, /\.\d{3}Z$/);
    const first = await connectMcp(data, directory, javaMcp);
    cleanups.push(first.close);
    const advertised = (await first.client.listTools()).tools.map((tool) => tool.name).sort();
    assert.deepEqual(advertised, [
      'project_context',
      'projects',
      'task_complete',
      'task_create',
      'task_history',
      'task_list',
      'task_read',
      'task_update',
    ]);
    const second = await connectMcp(data, directory, javaMcp);
    cleanups.push(second.close);
    assert.deepEqual(await first.call('task_create', replayInput), {
      isError: false,
      value: oldTask,
    });
    const mismatchedReplay = await first.call('task_create', {
      ...replayInput,
      title: 'Другие поля',
    });
    assert.equal(mismatchedReplay.isError, true);
    assert.equal(mismatchedReplay.value.status, 409);
    const incompleteCreate = await first.call('task_create', { title: 'Нет ключа' });
    assert.equal(incompleteCreate.isError, true);
    assert.equal(incompleteCreate.value.status, 400);
    const skewed = await first.call('task_update', {
      identifier: oldTask.code,
      revision: oldTask.revision,
      change: { result: { summary: 'Recorded by Java' } },
    });
    assert.equal(skewed.isError, false);
    assert.equal(skewed.value.updatedAt, oldTask.updatedAt);
    assert.deepEqual(await first.call('task_read', { identifier: task.code }), {
      isError: false,
      value: task,
    });

    const edit = (description: string) => ({
      identifier: task.code,
      revision: task.revision,
      change: { description },
    });
    const edits = await Promise.all([
      first.call('task_update', edit('Первая запись')),
      second.call('task_update', edit('Вторая запись')),
    ]);
    const saved = edits.find((result) => !result.isError);
    const conflict = edits.find((result) => result.isError);
    assert.ok(saved);
    assert.equal(conflict?.value.status, 409);
    assert.equal(saved.value.revision, task.revision + 1);
    assert.deepEqual(await (await http.request(`/api/tasks/${task.id}`)).json(), saved.value);
    assert.equal((await http.request(`/api/tasks/${task.id}`, json('PUT', task))).status, 409);
    const update = await http.request(
      `/api/tasks/${task.id}`,
      json('PUT', { ...saved.value, result: { summary: 'Записан результат' } }),
    );
    assert.equal(update.status, 200, await update.clone().text());
    const updated = await update.json();
    assert.equal(updated.revision, saved.value.revision + 1);

    const backupResponse = await http.request('/api/backup', json('POST', {}));
    assert.equal(backupResponse.status, 200, await backupResponse.clone().text());
    openDatabase((await backupResponse.json()).path, (db) => {
      assert.equal(db.prepare('PRAGMA quick_check').get()?.quick_check, 'ok');
      const recorded = db
        .prepare(
          'SELECT r.data,r.updated_at,t.revision FROM records r JOIN task_identity t ON t.record_id=r.id WHERE r.id=?',
        )
        .get(task.id)!;
      assert.equal(recorded.revision, updated.revision);
      assert.equal(recorded.updated_at, updated.updatedAt);
      assert.deepEqual(JSON.parse(String(recorded.data)).result, updated.result);
      assert.equal(
        db.prepare('SELECT kind FROM task_history WHERE task_id=? ORDER BY id DESC').get(task.id)
          ?.kind,
        'result',
      );
      assert.deepEqual(
        Object.values(db.prepare('SELECT enabled,revision FROM codex_grants').get()!),
        [0, 5],
      );
      assert.deepEqual(
        Object.values(
          db
            .prepare('SELECT enabled,revision,lease_owner,lease_until FROM analysis_schedules')
            .get()!,
        ),
        [0, 8, null, null],
      );
      assert.deepEqual(
        db
          .prepare('SELECT status,owner_id,lease_until FROM analysis_runs ORDER BY id')
          .all()
          .map(Object.values),
        [
          ['cancelled', null, null],
          ['unknown', null, null],
        ],
      );
    });

    while (cleanups.length) await cleanups.pop()!();
    const restarted = await startHttp(data);
    cleanups.push(restarted.close);
    assert.deepEqual(await (await restarted.request(`/api/tasks/${task.code}`)).json(), updated);
    await cleanups.pop()!();
    const raw = new DatabaseSync(database);
    try {
      assert.equal(raw.prepare('SELECT enabled FROM codex_grants').get()?.enabled, 1);
      assert.equal(raw.prepare('SELECT enabled FROM analysis_schedules').get()?.enabled, 1);
      assert.deepEqual(
        raw
          .prepare('SELECT status FROM analysis_runs ORDER BY id')
          .all()
          .map((row) => row.status),
        ['pending', 'running'],
      );
      assert.equal(raw.prepare('PRAGMA user_version').get()?.user_version, 16);
    } finally {
      raw.close();
    }
    const client = await connectMcp(data, directory);
    cleanups.push(client.close);
    const editAfterRestart = await client.call('task_update', {
      identifier: updated.code,
      revision: updated.revision,
      change: { description: 'Запись после перезапуска' },
    });
    assert.equal(editAfterRestart.isError, false);
    assert.equal(editAfterRestart.value.id, updated.id);
    assert.equal(editAfterRestart.value.code, updated.code);
    assert.equal(editAfterRestart.value.revision, updated.revision + 1);
    const switchedBack = await startHttp(data);
    cleanups.push(switchedBack.close);
    assert.deepEqual(
      await expectJson(switchedBack.request(`/api/tasks/${task.code}`)),
      editAfterRestart.value,
    );
  } finally {
    while (cleanups.length) await cleanups.pop()!();
  }
});

test('A fresh workspace allocates the first task number and stores a valid SQLite schema', async (t) => {
  const files = await fixture();
  t.after(files.cleanup);
  const data = path.join(files.root, 'new-data');
  const http = await startHttp(data);
  try {
    assert.equal(http.health.status, 200);
    const created = await http.request('/api/tasks', json('POST', { title: 'Первый номер' }));
    assert.equal(created.status, 201, await created.clone().text());
    assert.equal((await created.json()).code, 'CC-1');
  } finally {
    await http.close();
  }
  const file = path.join(data, 'control-center.sqlite');
  openDatabase(file, (db) => {
    assert.equal(db.prepare('PRAGMA user_version').get()?.user_version, 16);
    assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
    assert.equal(db.prepare('PRAGMA quick_check').get()?.quick_check, 'ok');
    const task = db
      .prepare(
        'SELECT r.kind,r.data,t.number,t.revision FROM records r JOIN task_identity t ON t.record_id=r.id',
      )
      .get()!;
    assert.equal(task.kind, 'tasks');
    assert.equal(task.number, 1);
    assert.equal(task.revision, 1);
    assert.equal(JSON.parse(String(task.data)).title, 'Первый номер');
  });
});

test('Java upgrades a v1 workspace and preserves the original MVP records in a verified backup', async (t) => {
  const files = await fixture();
  t.after(files.cleanup);
  const file = path.join(files.root, 'control-center.sqlite');
  openDatabase(file, (db) => {
    db.exec(`
      CREATE TABLE projects(id TEXT PRIMARY KEY,path TEXT NOT NULL UNIQUE,snapshot TEXT NOT NULL,
        metadata TEXT,notes TEXT NOT NULL DEFAULT '',available INTEGER NOT NULL DEFAULT 1);
      CREATE TABLE records(id TEXT PRIMARY KEY,kind TEXT NOT NULL CHECK(kind IN ('tasks','ideas','decisions')),
        project_id TEXT REFERENCES projects(id),data TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL);
      INSERT INTO projects(id,path,snapshot,notes) VALUES('mvp-project','mvp-path','{}','Kept note');
      INSERT INTO records VALUES('mvp-task','tasks','mvp-project',
        '{"title":"MVP task","state":"next"}','2026-10-01T12:00:00Z','2026-10-01T12:00:00Z');
      INSERT INTO records VALUES('mvp-idea','ideas','mvp-project',
        '{"title":"MVP idea"}','2026-10-01T12:00:00Z','2026-10-01T12:00:00Z');
      PRAGMA user_version=1;
    `);
  });

  const http = await startHttp(files.root);
  try {
    const task = await expectJson<{ id: string; projectId: string }>(
      http.request('/api/tasks/CC-1'),
      200,
    );
    assert.equal(task.id, 'mvp-task');
    assert.equal(task.projectId, 'mvp-project');
  } finally {
    await http.close();
  }
  openDatabase(file, (db) => {
    assert.equal(db.prepare('PRAGMA user_version').get()?.user_version, 16);
    assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
    assert.equal(db.prepare('SELECT notes FROM projects').get()?.notes, 'Kept note');
    assert.equal(db.prepare('SELECT yaml_base_hash FROM projects').get()?.yaml_base_hash, null);
    assert.equal(
      db.prepare("SELECT project_id FROM records WHERE id='mvp-idea'").get()?.project_id,
      'mvp-project',
    );
  });
  const backups = path.join(files.root, 'backups');
  const backupName = (await readdir(backups)).find((name) => name.startsWith('before-tasks-v4'));
  assert.ok(backupName);
  openDatabase(path.join(backups, backupName), (db) => {
    assert.equal(db.prepare('PRAGMA user_version').get()?.user_version, 1);
    assert.equal(db.prepare('PRAGMA quick_check').get()?.quick_check, 'ok');
    assert.equal(
      db
        .prepare("SELECT json_extract(data,'$.state') AS state FROM records WHERE id='mvp-task'")
        .get()?.state,
      'next',
    );
  });
});

test('Java upgrades a v2 database and preserves a verified v2 backup', async (t) => {
  const files = await fixture();
  t.after(files.cleanup);
  const file = path.join(files.root, 'control-center.sqlite');
  openDatabase(file, (db) => {
    db.exec(`
      CREATE TABLE projects(id TEXT PRIMARY KEY,path TEXT NOT NULL UNIQUE,snapshot TEXT NOT NULL,
        metadata TEXT,notes TEXT NOT NULL DEFAULT '',available INTEGER NOT NULL DEFAULT 1,
        yaml_base_hash TEXT,decision_sources TEXT NOT NULL DEFAULT '[]');
      CREATE TABLE records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,project_id TEXT REFERENCES projects(id),
        data TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL);
      CREATE TABLE settings(id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL);
      INSERT INTO projects(id,path,snapshot,notes,decision_sources)
        VALUES('legacy-project','legacy-path','{}','Owner note','["kept-in-backup"]');
      INSERT INTO records VALUES('legacy-task','tasks',NULL,
        '{"title":"Legacy task","state":"done","result":{"summary":"Saved"}}',
        '2026-10-01T12:00:00.000Z','2026-10-01T12:30:00.000Z');
      PRAGMA user_version=2;
    `);
    db.prepare('INSERT INTO settings VALUES(1,?)').run(
      JSON.stringify({ roots: [], exclusions: [], inactivityDays: 14, reviewDays: 30 }),
    );
  });
  const http = await startHttp(files.root);
  try {
    assert.equal(http.health.status, 200);
    const response = await http.request('/api/tasks/CC-1');
    assert.equal(response.status, 200, await response.clone().text());
    const task = await response.json();
    assert.equal(task.id, 'legacy-task');
    assert.equal(task.result.summary, 'Saved');
  } finally {
    await http.close();
  }
  openDatabase(file, (db) => {
    assert.equal(db.prepare('PRAGMA user_version').get()?.user_version, 16);
    assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
    const project = db.prepare('SELECT * FROM projects').get()!;
    assert.equal(project.notes, 'Owner note');
    assert.equal(project.visibility, 'unknown');
    assert.equal(Object.hasOwn(project, 'decision_sources'), false);
    assert.equal(
      db.prepare("SELECT json_extract(data,'$.timezone') AS tz FROM settings").get()?.tz,
      'Asia/Yekaterinburg',
    );
    assert.equal(
      db.prepare("SELECT json_type(data,'$.state') AS state FROM records").get()?.state,
      null,
    );
    assert.equal(db.prepare('SELECT kind FROM task_history').get()?.kind, 'baseline');
  });
  const backups = path.join(files.root, 'backups');
  const backupName = (await readdir(backups)).find((name) => name.startsWith('before-tasks-v4'));
  assert.ok(backupName);
  openDatabase(path.join(backups, backupName), (db) => {
    assert.equal(db.prepare('PRAGMA user_version').get()?.user_version, 2);
    assert.equal(db.prepare('PRAGMA quick_check').get()?.quick_check, 'ok');
    assert.equal(
      db.prepare("SELECT json_extract(data,'$.state') AS state FROM records").get()?.state,
      'done',
    );
  });
});

test('Java preserves immutable v13 history and its sequence through migration', async (t) => {
  const files = await fixture();
  t.after(files.cleanup);
  const file = path.join(files.root, 'control-center.sqlite');
  const before = await startHttp(files.root);
  try {
    await expectJson(
      before.request(
        '/api/tasks',
        json('POST', { title: 'Before migration', result: { summary: 'Saved' } }),
      ),
      201,
    );
  } finally {
    await before.close();
  }
  const history = openDatabase(file, (db) => {
    db.exec(`ALTER TABLE task_history RENAME TO fixture_history;
      CREATE TABLE task_history (id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,revision INTEGER NOT NULL,
        kind TEXT NOT NULL CHECK(kind IN ('baseline','result','completed','reopened')),recorded_at TEXT NOT NULL,
        project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,project_name TEXT,snapshot TEXT NOT NULL,UNIQUE(task_id,revision));
      INSERT INTO task_history SELECT * FROM fixture_history; DROP TABLE fixture_history;
      CREATE INDEX task_history_project_time ON task_history(project_id,recorded_at);
      UPDATE sqlite_sequence SET seq=70 WHERE name='task_history'; PRAGMA user_version=13;`);
    return db.prepare('SELECT * FROM task_history').all();
  });
  const http = await startHttp(files.root);
  try {
    assert.equal(http.health.status, 200);
    openDatabase(file, (db) => {
      assert.equal(db.prepare('PRAGMA user_version').get()?.user_version, 16);
      assert.deepEqual(db.prepare('SELECT * FROM task_history').all(), history);
      assert.equal(
        db.prepare("SELECT seq FROM sqlite_sequence WHERE name='task_history'").get()?.seq,
        70,
      );
    });
    const next = await http.request(
      '/api/tasks',
      json('POST', { title: 'Next result', result: { summary: 'Next' } }),
    );
    assert.equal(next.status, 201, await next.clone().text());
    const task = await next.json();
    openDatabase(file, (db) =>
      assert.equal(db.prepare('SELECT id FROM task_history WHERE task_id=?').get(task.id)?.id, 71),
    );
  } finally {
    await http.close();
  }
});

test('Java rolls back failed schema changes and refuses future versions', async (t) => {
  const files = await fixture();
  t.after(files.cleanup);
  await mkdir(path.join(files.root, 'future'));
  const future = path.join(files.root, 'future', 'control-center.sqlite');
  openDatabase(future, (db) => db.exec('PRAGMA user_version=17;'));
  await assert.rejects(startHttp(path.dirname(future)), /Версия базы не поддерживается/);
  openDatabase(future, (db) =>
    assert.equal(db.prepare('PRAGMA user_version').get()?.user_version, 17),
  );

  const oldFile = path.join(files.root, 'control-center.sqlite');
  const setup = await startHttp(files.root);
  await setup.close();
  openDatabase(oldFile, (db) => {
    db.exec(`DROP TABLE observations; PRAGMA user_version=11;
      INSERT INTO records VALUES('invalid','tasks',NULL,'broken legacy JSON','before','before');`);
  });
  await assert.rejects(startHttp(files.root), /malformed JSON/i);
  openDatabase(oldFile, (db) => {
    assert.equal(db.prepare('PRAGMA user_version').get()?.user_version, 11);
    assert.equal(
      db.prepare("SELECT name FROM sqlite_schema WHERE name='observations'").get(),
      undefined,
    );
    assert.equal(db.prepare('SELECT data FROM records').get()?.data, 'broken legacy JSON');
  });
});

test('Project discovery preserves Git snapshots, YAML and persisted settings', async (t) => {
  const files = await fixture();
  let stop = () => Promise.resolve();
  t.after(async () => {
    await stop();
    await files.cleanup();
  });
  const root = path.join(files.root, 'Рабочие проекты');
  const project = await files.repo(path.join('Рабочие проекты', 'Сценарий'));
  const yamlFile = path.join(project, 'PROJECT.yaml');
  const yaml =
    '# Keep this comment\nname: Original\nstatus: active\ncurrent_focus: Legacy focus\nnext: [Legacy plan]\ncustom: on\n';
  await writeFile(yamlFile, yaml);
  const repository = await startHttp(path.join(files.root, 'sqlite'));
  stop = repository.close;
  const { request } = repository;
  const settings = { ...defaultSettings, roots: [root, root], autoRefreshMinutes: 0 };
  assert.deepEqual(await expectJson(request('/api/settings', json('PUT', settings))), {
    ...settings,
    roots: [root],
  });
  const legacy = { ...settings } as Partial<typeof settings>;
  delete legacy.autoRefreshMinutes;
  const legacySaved = await expectJson<typeof settings>(
    request('/api/settings', json('PUT', legacy)),
  );
  assert.equal(legacySaved.autoRefreshMinutes, 0);
  assert.equal(
    (await request('/api/settings', json('PUT', { ...settings, roots: ['relative'] }))).status,
    400,
  );

  const workspace = await expectJson<Workspace>(request('/api/workspace'));
  assert.equal(workspace.projects.length, 1);
  const discovered = workspace.projects[0];
  assert.equal(discovered.path, realpathSync(project));
  assert.equal(discovered.snapshot.git.branch, 'main');
  assert.equal(discovered.snapshot.git.commits[0].subject, 'Initial fixture');
  assert.ok(workspace.scan.scannedAt);
  assert.deepEqual(workspace.scan.errors, []);
  assert.deepEqual(workspace.refresh, { running: false, lastAttemptAt: null, errors: [] });
  const route = `/api/projects/${discovered.id}`;
  const detail = await expectJson<Project>(request(route));
  assert.equal(detail.metadata.name, 'Original');
  assert.equal(detail.metadataSource, 'yaml');
  const edit = json('PUT', {
    metadata: { name: 'Local', status: 'active' },
    notes: 'Локальная заметка',
    expectedHash: detail.snapshot.yaml.hash,
    expectedMetadataVersion: detail.metadataVersion,
    expectedLifecycleRevision: detail.lifecycle?.revision,
  });
  const local = await expectJson<Project>(request(route, edit));
  assert.equal(local.metadataSource, 'local');
  assert.equal(local.metadata.current_focus, 'Legacy focus');
  assert.deepEqual(local.metadata.next, ['Legacy plan']);
  assert.equal((await request(route, edit)).status, 409);

  await writeFile(yamlFile, yaml.replace('Original', 'External'));
  const changed = await expectJson<Project>(request(route));
  assert.equal(changed.yamlConflict, true);
  assert.equal(changed.metadata.name, 'Local');
  assert.equal(
    (await request(`${route}/export`, json('POST', { expectedHash: detail.snapshot.yaml.hash })))
      .status,
    409,
  );
  const kept = await expectJson<Project>(
    request(`${route}/keep-local`, json('POST', { expectedHash: changed.snapshot.yaml.hash })),
  );
  assert.equal(kept.yamlConflict, false);
  const exported = await expectJson<Project>(
    request(`${route}/export`, json('POST', { expectedHash: kept.snapshot.yaml.hash })),
  );
  assert.equal(exported.yamlConflict, false);
  const written = await readFile(yamlFile, 'utf8');
  assert.match(written, /# Keep this comment/);
  assert.match(written, /custom: on/);
  assert.match(written, /name: Local/);
  assert.doesNotMatch(written, /Локальная заметка/);
  await writeFile(yamlFile, yaml.replace('Original', 'Adopted'));
  const newYaml = await expectJson<Project>(request(route));
  const adopted = await expectJson<Project>(
    request(`${route}/use-yaml`, json('POST', { expectedHash: newYaml.snapshot.yaml.hash })),
  );
  assert.equal(adopted.metadata.name, 'Adopted');
  assert.equal(adopted.metadataSource, 'yaml');
  assert.equal(adopted.notes, 'Локальная заметка');
  openDatabase(path.join(files.root, 'sqlite', 'control-center.sqlite'), (db) => {
    const settings = db
      .prepare("SELECT json_extract(data,'$.autoRefreshMinutes') AS interval FROM settings")
      .get();
    assert.equal(settings?.interval, 0);
    const row = db.prepare('SELECT path,metadata FROM projects WHERE id=?').get(discovered.id)!;
    assert.equal(row.path, realpathSync(project));
    assert.equal(row.metadata, null);
  });
  assert.notEqual(adopted.metadataVersion, changed.metadataVersion);
  assert.equal(
    (await expectJson<Project>(request(route))).metadataVersion,
    adopted.metadataVersion,
  );
});

test('Java keeps private context scoped and preserves tasks when projects are moved or forgotten', async (t) => {
  const files = await fixture();
  let stop = () => Promise.resolve();
  t.after(async () => {
    await stop();
    await files.cleanup();
  });
  const roots = [path.join(files.root, 'projects')];
  const original = await files.repo('projects/original');
  const taken = await files.repo('projects/taken');
  const replacement = await files.repo('projects/replacement');
  await writeFile(path.join(original, 'DECISIONS.md'), '# Repository decision\nPublic body');
  files.git(original, 'add', 'DECISIONS.md');
  const privateRoot = path.join(files.root, 'private');
  await mkdir(privateRoot);
  await writeFile(
    path.join(privateRoot, 'DECISIONS.md'),
    '# Private decision\nCanonical private body',
  );
  const repository = await startHttp(path.join(files.root, 'sqlite'));
  stop = repository.close;
  const { request } = repository;
  await expectJson(
    request('/api/settings', json('PUT', { ...defaultSettings, roots, autoRefreshMinutes: 0 })),
  );
  const workspace = () => expectJson<Workspace>(request('/api/workspace'));
  const found = (await workspace()).projects;
  const [old, occupied, free] = [original, taken, replacement].map((directory) =>
    found.find((project) => project.path === realpathSync(directory))!,
  );
  assert.ok(old && occupied && free);
  const route = `/api/projects/${old.id}`;
  const context = await expectJson<Project>(
    request(
      `${route}/context`,
      json('PUT', { visibility: 'public', privateContextPath: privateRoot }),
    ),
  );
  assert.equal(context.context.privateStatus, 'connected');
  assert.equal(context.context.hadPrivateRecords, true);
  assert.ok(context.context.audit.findings.some((finding) => finding.path === 'DECISIONS.md'));
  assert.equal(
    (
      await request(
        `${route}/publication-allowlist`,
        json('PUT', { path: '../unknown', allowed: true }),
      )
    ).status,
    409,
  );
  const allowed = await expectJson<Project>(
    request(`${route}/publication-allowlist`, json('PUT', { path: 'DECISIONS.md', allowed: true })),
  );
  assert.deepEqual(allowed.context.allowlist, ['DECISIONS.md']);
  assert.deepEqual(allowed.context.audit.findings, []);
  const decision = (await workspace()).fileDecisions.find(
    (file) => file.source === 'private_context',
  )!;
  assert.equal(decision.body, '');
  const content = await expectJson<{ body: string }>(
    request(`${route}/decision-content`, json('POST', { key: decision.key })),
  );
  assert.match(content.body, /Canonical private body/);
  assert.equal(
    (
      await request(
        `${route}/context`,
        json('PUT', { visibility: 'public', privateContextPath: original }),
      )
    ).status,
    400,
  );
  const disconnected = await expectJson<Project>(
    request(`${route}/context`, json('PUT', { visibility: 'public', privateContextPath: null })),
  );
  assert.equal(disconnected.context.privateStatus, 'disconnected');
  assert.equal(
    (await request(`${route}/decision-content`, json('POST', { key: decision.key }))).status,
    404,
  );

  const projectIds = [old.id, occupied.id].sort();
  const task = await expectJson<Task>(
    request('/api/tasks', json('POST', { title: 'Shared task', projectIds })),
    201,
  );
  await rename(original, path.join(files.root, 'moved'));
  const unavailable = await expectJson<Project>(request(route));
  assert.equal(unavailable.available, false);
  assert.equal(
    (await request(`${route}/rebind`, json('POST', { targetId: occupied.id }))).status,
    409,
  );
  const rebound = await expectJson<Project>(
    request(`${route}/rebind`, json('POST', { targetId: free.id })),
  );
  assert.equal(rebound.id, old.id);
  assert.equal(rebound.path, realpathSync(replacement));
  assert.deepEqual((await workspace()).tasks[0].projectIds, projectIds);

  const close = (project: Project) =>
    json('POST', {
      revision: project.lifecycle?.revision,
      outcome: 'completed',
      requestKey: randomUUID(),
    });
  const firstClosed = await expectJson<Project>(request(`${route}/close`, close(rebound)));
  assert.equal((await workspace()).tasks[0].cancellation, null);
  const secondClose = close(occupied);
  const closed = await expectJson<Project>(
    request(`/api/projects/${occupied.id}/close`, secondClose),
  );
  await expectJson<Project>(request(`/api/projects/${occupied.id}/close`, secondClose));
  const cancelled = await expectJson<Task>(request(`/api/tasks/${task.code}`));
  assert.equal(cancelled.cancellation?.projectEventId, closed.lifecycle?.currentClosure?.id);
  const reopened = await expectJson<Project>(
    request(
      `/api/projects/${occupied.id}/reopen`,
      json('POST', { revision: closed.lifecycle?.revision, requestKey: randomUUID() }),
    ),
  );
  assert.equal(reopened.metadata.status, 'active');
  assert.deepEqual(
    (await expectJson<Task>(request(`/api/tasks/${task.code}`))).cancellation,
    cancelled.cancellation,
  );
  await expectJson(request(route, json('DELETE', {})));
  const event = await expectJson<{ projectId: string | null; scopeProjectId: string }>(
    request(`/api/project-lifecycle/${firstClosed.lifecycle?.currentClosure?.id}`),
  );
  assert.equal(event.projectId, null);
  assert.equal(event.scopeProjectId, old.id);
  await expectJson(request('/api/scan', json('POST', {})));
  const after = await workspace();
  assert.deepEqual(after.settings.roots, roots);
  assert.deepEqual(after.settings.excludedProjectPaths, [realpathSync(replacement)]);
  assert.deepEqual(
    after.projects.map((project) => project.id),
    [occupied.id],
  );
  assert.equal(after.tasks[0].code, task.code);
  assert.deepEqual(after.tasks[0].projectIds, [occupied.id]);
  assert.match(await readFile(path.join(replacement, 'README.md'), 'utf8'), /Fixture/);
});
