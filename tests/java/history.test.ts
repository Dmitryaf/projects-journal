import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { test } from 'node:test';
import type { Check, CheckEntry } from '../../shared/checks.js';
import { defaultSettings, type Idea, type Task, type Workspace } from '../../shared/contracts.js';
import type { HistoryPage } from '../../shared/history.js';
import type { Observation } from '../../shared/observations.js';
import type { RelatedReviews, Review } from '../../shared/reviews.js';
import { fixture } from '../fixtures.js';
import { expectJson, json, startHttp } from './runtime.js';
import { openDatabase } from './sqlite-fixture.js';

test('Java serves unified history on a new journal', async (t) => {
  const files = await fixture();
  const cleanups = [files.cleanup];
  t.after(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const http = await startHttp(files.root);
  cleanups.push(http.close);
  assert.deepEqual(await expectJson(http.request('/api/history')), {
    items: [],
    nextCursor: null,
    files: { undated: 0, unavailable: 0, notLoadedProjects: 0 },
  });
});

test('Java history keeps available decisions when a repository disappears', async (t) => {
  const files = await fixture();
  const cleanups = [files.cleanup];
  t.after(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const available = await files.repo('available-repository');
  const unavailable = await files.repo('unavailable-repository');
  await writeFile(
    path.join(available, 'DECISIONS.md'),
    `---
schema_version: 1
id: D-0001
title: Keep showing available decisions
date: 2026-10-05
status: accepted
area: data
implementation: implemented
visibility: repository
---
Synthetic evidence.
`,
  );
  const data = path.join(files.root, 'db');
  const { request, close } = await startHttp(data);
  cleanups.push(close);
  await expectJson(
    request(
      '/api/settings',
      json('PUT', { ...defaultSettings, roots: [available, unavailable], autoRefreshMinutes: 0 }),
    ),
  );
  await rename(unavailable, path.join(files.root, 'moved-repository'));
  await expectJson(request('/api/scan', json('POST', {})));
  const workspace = await expectJson<Workspace>(request('/api/workspace'));
  const project = workspace.projects.find(
    (item) => item.metadata.name === 'unavailable-repository',
  );
  assert.ok(project);
  assert.equal(project.available, false);
  const decisionError = workspace.fileDecisions.find((item) => item.projectId === project.id);
  assert.ok(decisionError?.error);
  assert.equal(decisionError.hasContent, undefined);

  for (const route of [
    '/api/history/latest-projects?timezone=Asia%2FYekaterinburg',
    '/api/history?timezone=Asia%2FYekaterinburg&groupTasks=latest&limit=5',
  ]) {
    const page = await expectJson<HistoryPage>(request(route));
    assert.equal(page.files.unavailable, 1);
    assert.deepEqual(
      page.items.filter((item) => item.kind === 'file_decision').map((item) => item.sourceId),
      ['D-0001'],
    );
  }
});

test('Reviews apply DST boundaries to history and history pages stay in order', async (t) => {
  const files = await fixture();
  const cleanups = [files.cleanup];
  t.after(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const repo = await files.repo('project');
  await writeFile(
    path.join(repo, 'DECISIONS.md'),
    `---
schema_version: 1
id: D-0001
title: Retain source
date: 2026-03-04
status: accepted
area: local_data
implementation: not_implemented
supersedes: []
superseded_by: null
visibility: repository
---
# Decision
Do not copy the source body to history.
`,
  );
  const data = path.join(files.root, 'db');
  const http = await startHttp(data);
  cleanups.push(http.close);
  const { request } = http;
  const settings = {
    ...defaultSettings,
    roots: [repo],
    timezone: 'America/New_York',
    autoRefreshMinutes: 0,
  };
  await expectJson(request('/api/settings', json('PUT', settings)));
  const workspace = () => expectJson<Workspace>(request('/api/workspace'));
  const projectId = (await workspace()).projects[0].id;
  assert.equal(await expectJson(request(`/api/projects/${projectId}/latest-review`)), null);

  const reviewInput = { projectId, kind: 'week', anchorDate: '2026-03-08' };
  const review = await expectJson<Review>(request('/api/reviews', json('POST', reviewInput)), 201);
  assert.equal(review.periodStart, '2026-03-02');
  assert.equal(review.periodEnd, '2026-03-08');
  assert.equal(review.startAt, '2026-03-02T05:00:00.000Z');
  assert.equal(review.nextStartAt, '2026-03-09T04:00:00.000Z');
  const updatedReview = await expectJson<Review>(
    request(`/api/reviews/${review.id}`, json('PATCH', { revision: 1, userText: ' Saved text ' })),
  );
  assert.equal(updatedReview.userText, ' Saved text ');
  assert.equal(updatedReview.revision, 2);
  assert.equal(
    (await request(`/api/reviews/${review.id}`, json('PATCH', { revision: 1, userText: 'stale' })))
      .status,
    409,
  );
  await expectJson(request('/api/settings', json('PUT', { ...settings, timezone: 'UTC' })));
  const replay = await expectJson<Review>(request('/api/reviews', json('POST', reviewInput)), 201);
  assert.deepEqual(replay, updatedReview);
  const month = await expectJson<Review>(
    request('/api/reviews', json('POST', { kind: 'month', anchorDate: '2026-03-03' })),
    201,
  );
  const related = await expectJson<RelatedReviews>(
    request(`/api/reviews/${month.id}/related?limit=1`),
  );
  assert.equal(related.items[0].id, review.id);
  assert.equal(related.items[0].overlapsBoundary, false);
  assert.equal(
    (await expectJson<{ id: string }>(request(`/api/projects/${projectId}/latest-review`))).id,
    review.id,
  );

  const observationInput = {
    projectId,
    occurredOn: '2026-03-05',
    body: ' Initial observation ',
    requestKey: randomUUID(),
  };
  const note = await expectJson<Observation>(
    request('/api/observations', json('POST', observationInput)),
    201,
  );
  assert.equal(note.body, 'Initial observation');
  assert.equal(
    (await request('/api/observations', json('POST', { ...observationInput, body: 'Different' })))
      .status,
    409,
  );
  const changedNote = await expectJson<Observation>(
    request(
      `/api/observations/${note.id}`,
      json('PATCH', { revision: note.revision, body: 'Edited observation' }),
    ),
  );
  assert.equal(changedNote.scopeProjectId, projectId);
  assert.deepEqual(
    await expectJson(request('/api/observations', json('POST', observationInput)), 201),
    changedNote,
  );
  assert.equal(
    (await request(`/api/observations/${note.id}`, json('DELETE', { revision: note.revision })))
      .status,
    409,
  );

  const idea = await expectJson<Idea>(
    request('/api/ideas', json('POST', { title: 'Idea to test', projectId })),
    201,
  );
  const checkFields = {
    projectId,
    ideaId: idea.id,
    title: 'Test a hypothesis',
    question: 'Will it help?',
    assumption: 'Yes',
    expectedExternalResult: 'Feedback',
    startedAt: '2026-03-03',
    reviewAt: '2026-03-07',
    continueIf: 'Useful',
    stopIf: 'Unhelpful',
    nextExternalAction: 'Ask an early user',
  };
  const check = await expectJson<Check>(
    request('/api/checks', json('POST', { check: checkFields })),
    201,
  );
  assert.equal((await workspace()).ideas[0].state, 'testing');
  assert.equal(
    (await request('/api/checks', json('POST', { check: { ...checkFields, title: 'Parallel' } })))
      .status,
    409,
  );
  const entry = await expectJson<CheckEntry>(
    request(
      `/api/checks/${check.id}/entries`,
      json('POST', {
        kind: 'action',
        occurredAt: '2026-03-06',
        text: 'Asked a user',
        type: 'fact',
        numericValue: 4,
      }),
    ),
    201,
  );
  assert.equal(entry.numericValue, null);
  const evidence = await expectJson<CheckEntry>(
    request(
      `/api/checks/${check.id}/entries`,
      json('POST', {
        kind: 'evidence',
        occurredAt: '2026-03-07',
        text: 'Feedback arrived',
        type: 'fact',
        numericValue: 3,
      }),
    ),
    201,
  );
  const conclusion = {
    actualResult: 'Feedback arrived',
    whatWasConfirmedOrRejected: 'Useful',
    outcome: 'continue',
    basis: 'User interview',
  };
  assert.equal(
    (
      await request(
        `/api/checks/${check.id}/complete`,
        json('POST', { ...conclusion, completedAt: '2026-03-06' }),
      )
    ).status,
    400,
  );
  const completed = await expectJson<Check>(
    request(
      `/api/checks/${check.id}/complete`,
      json('POST', { ...conclusion, completedAt: '2026-03-08' }),
    ),
  );
  assert.equal(completed.status, 'completed');
  const view = (await workspace()).checks[0];
  assert.equal(view.lastExternalActionAt, '2026-03-06');
  assert.equal(view.lastEvidenceAt, '2026-03-07');
  assert.equal(view.daysWithoutMovement, 2);
  assert.deepEqual(view.signals, []);

  const createTask = (title: string, summary: string) =>
    expectJson<Task>(
      request('/api/tasks', json('POST', { title, projectId, result: { summary } })),
      201,
    );
  const before = await createTask('Outside before', 'Outside');
  const inside = await createTask('Inside', 'Included at start');
  const changedTask = await expectJson<Task>(
    request(
      `/api/tasks/${inside.id}`,
      json('PUT', { ...inside, result: { summary: 'Latest inside' } }),
    ),
  );
  const after = await createTask('Outside after', 'Outside');
  openDatabase(path.join(data, 'control-center.sqlite'), (db) => {
    const setDate = db.prepare(
      'UPDATE task_history SET recorded_at=? WHERE task_id=? AND revision=?',
    );
    const events = [
      [before, '2026-03-02T04:59:59.999Z'],
      [inside, review.startAt],
      [changedTask, '2026-03-02T05:00:00.001Z'],
      [after, review.nextStartAt],
    ] as const;
    for (const [task, recordedAt] of events)
      assert.equal(setDate.run(recordedAt, task.id, task.revision).changes, 1);
  });

  const week = await expectJson<HistoryPage>(request(`/api/reviews/${review.id}/history`));
  assert.deepEqual(
    week.items.filter((item) => item.kind === 'task').map((item) => item.excerpt),
    ['Latest inside', 'Included at start'],
  );
  const file = week.items.find((item) => item.kind === 'file_decision')!;
  assert.equal(file.excerpt, '');
  assert.equal(file.recordedAt, null);
  assert.equal(file.dateQuality, 'declared');
  const filters = {
    projectId,
    kind: 'task',
    timezone: 'America/New_York',
    from: '2026-03-02',
    to: '2026-03-08',
    groupTasks: 'latest',
    limit: '1',
  };
  const grouped = await expectJson<HistoryPage>(
    request(`/api/history?${new URLSearchParams(filters)}`),
  );
  assert.deepEqual(
    grouped.items.map((item) => item.excerpt),
    ['Latest inside'],
  );
  assert.equal(grouped.nextCursor, null);
  const query = { timezone: 'America/New_York', limit: '2' };
  const first = await expectJson<HistoryPage>(
    request(`/api/history?${new URLSearchParams(query)}`),
  );
  assert.ok(first.nextCursor);
  const second = await expectJson<HistoryPage>(
    request(`/api/history?${new URLSearchParams({ ...query, cursor: first.nextCursor })}`),
  );
  assert.equal(first.items.length, 2);
  assert.equal(second.items.length, 2);
  const unpaged = await expectJson<HistoryPage>(
    request(`/api/history?${new URLSearchParams({ ...query, limit: '100' })}`),
  );
  assert.deepEqual([...first.items, ...second.items], unpaged.items.slice(0, 4));
  const latest = await expectJson<HistoryPage>(
    request('/api/history/latest-projects?timezone=America%2FNew_York'),
  );
  assert.deepEqual(latest.items, [unpaged.items.find((item) => item.scopeProjectId === projectId)]);

  await expectJson(request(`/api/checks/${check.id}/entries/${evidence.id}`, json('DELETE', {})));
  await expectJson(
    request(`/api/observations/${note.id}`, json('DELETE', { revision: changedNote.revision })),
  );
  assert.equal((await request(`/api/observations/${note.id}`)).status, 404);
  assert.equal(
    (await request(`/api/reviews/${review.id}`, json('DELETE', { revision: 1 }))).status,
    409,
  );
  await expectJson(request(`/api/reviews/${review.id}`, json('DELETE', { revision: 2 })));
  assert.equal((await request(`/api/reviews/${review.id}`)).status, 404);
});

test('An existing review keeps its saved boundaries in a historical sub-minute time zone', async (t) => {
  const files = await fixture();
  const cleanups = [files.cleanup];
  t.after(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const database = path.join(files.root, 'control-center.sqlite');
  const input = { kind: 'month', anchorDate: '1900-01-01' };
  const setup = await startHttp(files.root);
  try {
    await expectJson(
      setup.request(
        '/api/settings',
        json('PUT', { ...defaultSettings, autoRefreshMinutes: 0, timezone: 'Europe/Paris' }),
      ),
    );
  } finally {
    await setup.close();
  }
  const saved: Review = {
    id: randomUUID(),
    projectId: null,
    scopeKey: 'general',
    projectName: null,
    kind: 'month',
    periodStart: '1899-12-30',
    periodEnd: '1900-01-29',
    timezone: 'Europe/Paris',
    startAt: '1899-12-30T23:50:18.000Z',
    nextStartAt: '1900-01-30T23:49:57.000Z',
    userText: '',
    revision: 1,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
  };
  openDatabase(database, (db) =>
    db
      .prepare(
        `INSERT INTO reviews(id,project_id,scope_key,project_name,period_kind,period_start,period_end,
          timezone,start_at,next_start_at,user_text,revision,created_at,updated_at)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      )
      .run(
        saved.id,
        saved.projectId,
        saved.scopeKey,
        saved.projectName,
        saved.kind,
        saved.periodStart,
        saved.periodEnd,
        saved.timezone,
        saved.startAt,
        saved.nextStartAt,
        saved.userText,
        saved.revision,
        saved.createdAt,
        saved.updatedAt,
      ),
  );
  const http = await startHttp(files.root);
  cleanups.push(http.close);
  assert.deepEqual(await expectJson(http.request(`/api/reviews/${saved.id}`)), saved);
  const reopened = await expectJson<Review>(http.request('/api/reviews', json('POST', input)), 201);
  assert.deepEqual(reopened, saved);
  const next = await expectJson<Review>(
    http.request('/api/reviews', json('POST', { ...input, anchorDate: '1900-02-01' })),
    201,
  );
  assert.notEqual(next.id, saved.id);
  assert.equal(next.periodStart, '1900-02-01');
});
