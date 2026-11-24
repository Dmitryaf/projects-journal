import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, realpath } from 'node:fs/promises';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test, type TestContext } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import type { AnalysisSchedule } from '../../shared/analysis-schedules.js';
import type { CodexGrant } from '../../shared/codex-context.js';
import { defaultSettings, type Workspace } from '../../shared/contracts.js';
import type { PeriodRun, PeriodRunPage, PeriodSelection } from '../../shared/period-analysis.js';
import type { Review } from '../../shared/reviews.js';
import { fixture } from '../fixtures.js';
import { codexProcessFixture } from './codex-process-fixture.js';
import { expectJson, json, startHttp } from './runtime.js';

const route = '/api/analysis-schedules';
const tick = (request: Awaited<ReturnType<typeof startHttp>>['request']) =>
  expectJson(request('/api/test/schedules/tick', json('POST', {})));
const calls = async (request: Awaited<ReturnType<typeof startHttp>>['request']) =>
  (await expectJson<{ calls: number }>(request('/api/test/openai/calls'))).calls;

async function setup(t: TestContext) {
  const files = await fixture();
  const cleanups = [files.cleanup];
  t.after(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const repo = await realpath(await files.repo('schedule-project'));
  const data = path.join(files.root, 'schedule-data');
  const codex = await codexProcessFixture(files.root, repo);
  const start = async (
    directory = data,
    fixtureOptions: Parameters<typeof startHttp>[1] = { schedules: true, codex: codex.options },
  ) => {
    const result = await startHttp(directory, fixtureOptions);
    cleanups.push(result.close);
    return result;
  };
  const http = await start();
  const { request } = http;
  await expectJson(
    request(
      '/api/settings',
      json('PUT', { ...defaultSettings, roots: [repo], timezone: 'UTC', autoRefreshMinutes: 0 }),
    ),
  );
  const project = (await expectJson<Workspace>(request('/api/workspace'))).projects[0];
  const selection: PeriodSelection = {
    projectIds: [project.id],
    includeGeneral: false,
    includeMetadata: true,
    sourceKinds: [],
    includeOpenTasks: false,
    includeUserText: true,
    includeRelatedReviews: false,
    includeRelatedAnalyses: false,
    codexDigestIds: [],
  };
  const week = new Date();
  week.setUTCDate(week.getUTCDate() - ((week.getUTCDay() + 6) % 7) - 7);
  const now = new Date();
  const month = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  const review = (kind: 'week' | 'month') =>
    expectJson<Review>(
      request(
        '/api/reviews',
        json('POST', {
          projectId: project.id,
          kind,
          anchorDate: (kind === 'week' ? week : month).toISOString().slice(0, 10),
        }),
      ),
      201,
    );
  const input = (target: Review, provider: 'openai' | 'codex' = 'openai') => ({
    reviewId: target.id,
    provider,
    revision: null,
    selection,
    readConsent: true,
    sendConsent: true,
    expectedModel: provider === 'codex' ? 'codex-default' : 'fixture-model',
    ...(provider === 'codex' ? { expectedPolicy: 'codex-project-review-v5' } : {}),
  });
  const db = new DatabaseSync(path.join(data, 'control-center.sqlite'));
  db.exec('PRAGMA busy_timeout=5000;');
  cleanups.push(async () => db.close());
  const due = (configured: AnalysisSchedule, target: Review) =>
    db
      .prepare('UPDATE analysis_schedules SET next_period_start=? WHERE id=?')
      .run(target.periodStart, configured.id);
  const grantPath = '/api/projects/' + project.id + '/codex-context';
  return { ...http, start, codex, db, due, grantPath, project, review, selection, input, files };
}

test('Java schedules serialize provider sends, retain period receipts and enforce Codex grants', async (t) => {
  const { request, start, codex, db, due, grantPath, project, review, input } = await setup(t);
  const week = await review('week');
  assert.equal(await expectJson(request('/api/reviews/' + week.id + '/schedule')), null);
  const configure = (target: Review, provider: 'openai' | 'codex', data: object = {}) =>
    expectJson<AnalysisSchedule>(
      request(route, json('POST', { ...input(target, provider), ...data })),
    );
  const weekly = await configure(week, 'openai');
  assert.equal(weekly.enabled, true);
  const peer = await start();
  due(weekly, week);
  await Promise.all([tick(request), tick(peer.request)]);
  assert.equal((await calls(request)) + (await calls(peer.request)), 1);
  const weekPath = '/api/reviews/' + week.id;
  const saved = await expectJson<AnalysisSchedule>(request(weekPath + '/schedule'));
  assert.ok(saved.lastRunId);
  const analysis = '/api/period-analysis/' + saved.lastRunId;
  const report = await expectJson<PeriodRun>(request(analysis));
  assert.equal(report.trigger, 'scheduled');
  assert.equal(report.status, 'succeeded');
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM analysis_schedule_periods').get()?.n, 1);
  await expectJson(request(analysis, json('DELETE', { confirm: true })));
  due(weekly, week);
  await tick(request);
  assert.equal((await calls(request)) + (await calls(peer.request)), 1);
  assert.equal((await expectJson<PeriodRunPage>(request(weekPath + '/analyses'))).items.length, 0);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM analysis_schedule_periods').get()?.n, 1);
  await expectJson(
    request(route + '/' + weekly.id + '/disable', json('POST', { revision: weekly.revision })),
  );

  const month = await review('month');
  const monthly = await configure(month, 'codex');
  due(monthly, month);
  await tick(request);
  const monthPath = '/api/reviews/' + month.id;
  let codexRuns = await expectJson<PeriodRunPage>(request(monthPath + '/analyses'));
  for (let index = 0; index < 50 && codexRuns.items[0]?.status !== 'succeeded'; index++) {
    await delay(75);
    codexRuns = await expectJson<PeriodRunPage>(request(monthPath + '/analyses'));
  }
  assert.equal(codexRuns.items[0]?.status, 'succeeded', JSON.stringify(codexRuns));
  assert.equal(codexRuns.items[0].origin, 'codex_runtime');
  assert.equal(codexRuns.items[0].trigger, 'scheduled');
  await tick(request);
  assert.equal(
    (await expectJson<AnalysisSchedule>(request(monthPath + '/schedule'))).lastRunId,
    codexRuns.items[0].id,
  );
  assert.equal((await codex.events()).filter((event) => event.kind === 'exec').length, 1);

  const grant = await expectJson<CodexGrant>(
    request(grantPath, json('PUT', { revision: null, enabled: true, readConsent: true })),
  );
  const approved = {
    revision: monthly.revision,
    codexProjectIds: [project.id],
    codexReadConsent: true,
    codexSendConsent: true,
  };
  assert.equal(
    (
      await request(
        route,
        json('POST', { ...input(month, 'codex'), ...approved, codexSendConsent: false }),
      )
    ).status,
    400,
  );
  const withDialogs = await configure(month, 'codex', approved);
  assert.deepEqual(withDialogs.codexProjectIds, [project.id]);
  await expectJson(request(grantPath, json('PUT', { enabled: false, revision: grant.revision })));
  due(withDialogs, month);
  const eventCount = (await codex.events()).length;
  await tick(request);
  const denied = await expectJson<AnalysisSchedule>(request(monthPath + '/schedule'));
  assert.equal(denied.enabled, false);
  assert.match(denied.note ?? '', /Доступ.*изменился/);
  assert.equal((await codex.events()).length, eventCount);
});

test('Java pauses an uncertain scheduled send after restart and revokes restored approvals', async (t) => {
  const { request, close, start, db, due, grantPath, review, input, selection, files } =
    await setup(t);
  const week = await review('week');
  await expectJson(
    request(
      '/api/reviews/' + week.id,
      json('PATCH', { revision: week.revision, userText: 'stub-disconnect' }),
    ),
  );
  await expectJson(
    request(grantPath, json('PUT', { revision: null, enabled: true, readConsent: true })),
  );
  const configured = await expectJson<AnalysisSchedule>(request(route, json('POST', input(week))));
  const schedulePath = '/api/reviews/' + week.id + '/schedule';
  due(configured, week);
  await tick(request);
  const first = await expectJson<AnalysisSchedule>(request(schedulePath));
  assert.equal(first.enabled, false);
  assert.ok(first.lastRunId);
  const runPath = '/api/period-analysis/' + first.lastRunId;
  assert.equal((await expectJson<PeriodRun>(request(runPath))).status, 'unknown');
  assert.equal(await calls(request), 1);

  await close();
  // A stale lease represents a crash after dispatch and before recording the outcome.
  const expired = '2024-01-01T00:00:00.000Z';
  db.prepare(
    "UPDATE analysis_runs SET status='running',lease_until=?,owner_id='fixture-old-provider',finished_at=NULL WHERE id=?",
  ).run(expired, first.lastRunId);
  db.prepare(
    "UPDATE analysis_schedules SET enabled=1,lease_owner='fixture-old-scheduler',lease_until=? WHERE id=?",
  ).run(expired, configured.id);
  const restarted = await start();
  await tick(restarted.request);
  let paused = await expectJson<AnalysisSchedule>(restarted.request(schedulePath));
  for (let attempt = 0; attempt < 30 && paused.enabled; attempt++) {
    await delay(50);
    paused = await expectJson<AnalysisSchedule>(restarted.request(schedulePath));
  }
  assert.equal(paused.enabled, false);
  assert.equal(paused.lastRunId, first.lastRunId);
  assert.equal((await expectJson<PeriodRun>(restarted.request(runPath))).status, 'unknown');
  assert.equal(await calls(restarted.request), 0);
  const resumed = await expectJson<AnalysisSchedule>(
    restarted.request(
      route + '/' + paused.id + '/resume',
      json('POST', { revision: paused.revision, confirm: true }),
    ),
  );
  assert.equal(resumed.enabled, true);
  assert.notEqual(resumed.nextPeriodStart, week.periodStart);
  const pending = await expectJson<PeriodRun>(
    restarted.request(
      '/api/period-analysis/prepare',
      json('POST', {
        reviewId: week.id,
        requestKey: randomUUID(),
        selection,
        readConsent: true,
        repeatConsent: true,
      }),
    ),
    201,
  );
  const backup = await expectJson<{ path: string }>(
    restarted.request('/api/backup', json('POST', {})),
  );
  const snapshot = new DatabaseSync(backup.path);
  try {
    assert.equal(snapshot.prepare('PRAGMA quick_check').get()?.quick_check, 'ok');
    assert.equal(snapshot.prepare('SELECT enabled FROM analysis_schedules').get()?.enabled, 0);
    assert.equal(snapshot.prepare('SELECT enabled FROM codex_grants').get()?.enabled, 0);
    assert.equal(
      snapshot.prepare('SELECT status FROM analysis_runs WHERE id=?').get(pending.id)?.status,
      'cancelled',
    );
  } finally {
    snapshot.close();
  }
  assert.equal(db.prepare('SELECT enabled FROM analysis_schedules').get()?.enabled, 1);

  const raw = path.join(files.root, 'restored data');
  await mkdir(raw);
  db.prepare('VACUUM INTO ?').run(path.join(raw, 'control-center.sqlite'));
  const restored = await start(raw, { restoreRecovery: true });
  assert.equal((await expectJson<AnalysisSchedule>(restored.request(schedulePath))).enabled, false);
  assert.equal((await expectJson<CodexGrant>(restored.request(grantPath))).enabled, false);
  assert.equal(
    (await expectJson<PeriodRun>(restored.request('/api/period-analysis/' + pending.id))).status,
    'cancelled',
  );
});
