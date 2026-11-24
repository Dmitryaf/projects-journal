import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import path from 'node:path';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import type { AnalysisPreview, AnalysisResult } from '../../shared/analysis.js';
import { defaultSettings, type Workspace } from '../../shared/contracts.js';
import type { PeriodRun, PeriodRunPage, PeriodSelection } from '../../shared/period-analysis.js';
import type { ProposalAcceptance } from '../../shared/proposal-acceptances.js';
import type { Review } from '../../shared/reviews.js';
import { fixture } from '../fixtures.js';
import { expectJson, json, startHttp } from './runtime.js';

test('Java saves a manually imported Codex report with declared provenance', async (t) => {
  const files = await fixture();
  const cleanups = [files.cleanup];
  t.after(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const repo = await files.repo('project');
  const data = path.join(files.root, 'database');
  const http = await startHttp(data);
  cleanups.push(http.close);
  const { request } = http;
  await expectJson(
    request(
      '/api/settings',
      json('PUT', { ...defaultSettings, roots: [repo], autoRefreshMinutes: 0 }),
    ),
  );
  const project = (await expectJson<Workspace>(request('/api/workspace'))).projects[0];
  const review = await expectJson<Review>(
    request(
      '/api/reviews',
      json('POST', { projectId: project.id, kind: 'week', anchorDate: '2026-10-01' }),
    ),
    201,
  );
  const requestKey = randomUUID();
  const source = `codex_context:${requestKey}`;
  const proposal = {
    kind: 'improvement',
    projectIds: [project.id],
    title: 'Review the example',
    description: '',
    reason: 'The example is present.',
    uncertainty: '',
    cheapCheck: 'Read the example.',
    sources: [source],
  };
  const input = {
    purpose: 'project_review',
    projectId: project.id,
    reviewId: review.id,
    requestKey,
    confirm: true,
    context: 'Synthetic statement of the owner.',
    report: {
      narrative: 'Synthetic imported report.',
      findings: [{ statement: 'One checked example.', sources: [source] }],
      uncertainties: [],
      proposals: [proposal],
    },
  };
  const route = '/api/period-analysis/import-codex';
  const saved = await expectJson<PeriodRun>(request(route, json('POST', input)), 201);
  assert.equal(saved.origin, 'codex_manual');
  assert.equal(saved.status, 'succeeded');
  assert.equal(saved.responseId, null);
  assert.equal(saved.approvedAt, null);
  assert.deepEqual(saved.report, input.report);
  assert.deepEqual(await expectJson(request(route, json('POST', input)), 201), saved);
  const secondInput = {
    ...input,
    requestKey: randomUUID(),
    context: 'Синтетическая запись ☀',
    report: { ...input.report, findings: [], proposals: [] },
  };
  const secondReport = await expectJson<PeriodRun>(request(route, json('POST', secondInput)), 201);
  assert.notEqual(secondReport.id, saved.id);
  assert.deepEqual(secondReport.report, secondInput.report);
  assert.deepEqual(await expectJson(request(route, json('POST', secondInput)), 201), secondReport);
  assert.equal(
    (await request(route, json('POST', { ...input, context: 'Different context' }))).status,
    409,
  );
  assert.deepEqual(await expectJson(request(`/api/period-analysis/${saved.id}`)), saved);
  assert.deepEqual(
    await expectJson(
      request(
        `/api/period-analysis/${saved.id}/run`,
        json('POST', {
          packetHash: saved.packetHash,
          consent: true,
        }),
      ),
    ),
    saved,
  );
  assert.equal((await request('/api/test/openai/calls')).status, 404);

  const accept = `/api/period-analysis/${saved.id}/proposals/0/accept`;
  const approval = {
    kind: 'task',
    projectId: project.id,
    title: proposal.title,
    description: '',
    reason: proposal.reason,
    uncertainty: '',
    cheapCheck: proposal.cheapCheck,
    consent: true,
  };
  assert.equal((await request(accept, json('POST', { ...approval, consent: false }))).status, 400);
  const [accepted, replay] = await Promise.all([
    expectJson<ProposalAcceptance>(request(accept, json('POST', approval)), 201),
    expectJson<ProposalAcceptance>(request(accept, json('POST', approval)), 201),
  ]);
  assert.deepEqual(replay, accepted);
  assert.equal(accepted.resultExists, true);
  assert.equal(accepted.original.origin, 'codex_manual');
  assert.equal(accepted.original.packetHash, saved.packetHash);
  assert.deepEqual(accepted.original.proposal, proposal);
  assert.equal(accepted.original.sources[0].id, source);
  assert.equal(
    (await request(accept, json('POST', { ...approval, title: 'Different title' }))).status,
    409,
  );
  assert.deepEqual(await expectJson(request(`/api/period-analysis/${saved.id}/acceptances`)), [
    accepted,
  ]);
  const workspace = await expectJson<Workspace>(request('/api/workspace'));
  assert.deepEqual(
    workspace.tasks.filter((task) => task.title === approval.title).map((task) => task.id),
    [accepted.resultId],
  );
  assert.equal(workspace.proposalLinks?.[accepted.resultId], accepted.id);
  await expectJson(request(`/api/period-analysis/${saved.id}`, json('DELETE', { confirm: true })));
  const retained = await expectJson<ProposalAcceptance>(
    request(`/api/proposal-acceptances/${accepted.id}`),
  );
  assert.equal(retained.runId, null);
  assert.equal(retained.resultId, accepted.resultId);
  assert.deepEqual(retained.original, accepted.original);
  await cleanups.pop()!();
  const restarted = await startHttp(data);
  cleanups.push(restarted.close);
  assert.deepEqual(
    await expectJson(restarted.request(`/api/proposal-acceptances/${accepted.id}`)),
    retained,
  );
  assert.deepEqual(
    await expectJson(restarted.request(`/api/period-analysis/${secondReport.id}`)),
    secondReport,
  );
});

test('Java dispatches only the approved period packet and persists outcomes from a stubbed Responses API', async (t) => {
  const files = await fixture();
  const cleanups = [files.cleanup];
  t.after(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const repo = await files.repo('project');
  const http = await startHttp(path.join(files.root, 'database'), { openAiStub: true });
  cleanups.push(http.close);
  const { request } = http;
  await expectJson(
    request(
      '/api/settings',
      json('PUT', { ...defaultSettings, roots: [repo], autoRefreshMinutes: 0 }),
    ),
  );
  const project = (await expectJson<Workspace>(request('/api/workspace'))).projects[0];
  let review = await expectJson<Review>(
    request(
      '/api/reviews',
      json('POST', {
        projectId: project.id,
        kind: 'week',
        anchorDate: '2026-10-01',
      }),
    ),
    201,
  );
  const selection: PeriodSelection = {
    projectIds: [project.id],
    includeGeneral: false,
    sourceKinds: [],
    includeMetadata: true,
    includeOpenTasks: false,
    includeUserText: true,
    includeRelatedReviews: false,
    includeRelatedAnalyses: false,
    codexDigestIds: [],
  };
  const preparePath = '/api/period-analysis/prepare';
  const selectionInput = { reviewId: review.id, selection, readConsent: true };
  const prepare = (repeatConsent = true) =>
    expectJson<PeriodRun>(
      request(
        preparePath,
        json('POST', {
          ...selectionInput,
          requestKey: randomUUID(),
          repeatConsent,
        }),
      ),
      201,
    );
  const patchReview = async (userText: string) => {
    review = await expectJson<Review>(
      request(`/api/reviews/${review.id}`, json('PATCH', { revision: review.revision, userText })),
    );
  };
  const callCount = async () =>
    (await expectJson<{ calls: number }>(request('/api/test/openai/calls'))).calls;
  const run = (prepared: PeriodRun) =>
    expectJson<PeriodRun>(
      request(
        `/api/period-analysis/${prepared.id}/run`,
        json('POST', {
          packetHash: prepared.packetHash,
          consent: true,
        }),
      ),
    );

  const initial = { ...selectionInput, requestKey: randomUUID() };
  await patchReview('api key = synthetic-placeholder');
  assert.equal((await request(preparePath, json('POST', initial))).status, 400);
  assert.equal(await callCount(), 0);
  await patchReview('Synthetic review text.');
  const pending = await expectJson<PeriodRun>(request(preparePath, json('POST', initial)), 201);
  assert.equal(pending.status, 'pending');
  assert.equal(
    pending.packetHash,
    createHash('sha256')
      .update(
        JSON.stringify({
          payload: pending.payload,
          model: 'fixture-model',
          policy: pending.payload.policyVersion,
        }),
      )
      .digest('hex'),
  );
  assert.deepEqual(await expectJson(request(preparePath, json('POST', initial)), 201), pending);
  const runPath = `/api/period-analysis/${pending.id}/run`;
  assert.equal(
    (await request(runPath, json('POST', { packetHash: pending.packetHash, consent: false })))
      .status,
    400,
  );
  assert.equal(
    (await request(runPath, json('POST', { packetHash: 'f'.repeat(64), consent: true }))).status,
    409,
  );
  assert.equal(await callCount(), 0);
  const completed = await run(pending);
  assert.equal(completed.status, 'succeeded');
  assert.equal(completed.report?.narrative, 'Synthetic period report');
  assert.equal(completed.responseId, 'fixture-response-1');
  assert.deepEqual(completed.usage, { inputTokens: 42, outputTokens: 7 });
  assert.deepEqual(await run(pending), completed);
  assert.deepEqual(await expectJson(request(`/api/period-analysis/${pending.id}`)), completed);
  assert.equal(await callCount(), 1);
  assert.equal(
    (await request(preparePath, json('POST', { ...initial, requestKey: randomUUID() }))).status,
    409,
  );

  const stale = await prepare();
  await patchReview('Update the synthetic review.');
  assert.equal((await run(stale)).status, 'cancelled');
  assert.equal(await callCount(), 1);

  for (const text of ['stub-refusal', 'stub-foreign-source', 'stub-disconnect']) {
    await patchReview(text);
    const prepared = await prepare();
    const before = await callCount();
    const failed = await run(prepared);
    assert.equal(failed.status, text === 'stub-disconnect' ? 'unknown' : 'failed');
    assert.equal(failed.report, null);
    assert.equal(failed.rawOutput, null);
    assert.equal(
      failed.responseId,
      text === 'stub-disconnect' ? null : `fixture-response-${before + 1}`,
    );
    assert.deepEqual(await run(prepared), failed);
    assert.equal(await callCount(), before + 1);
  }

  await patchReview('stub-hold');
  const cancellable = await prepare();
  const before = await callCount();
  const inFlight = run(cancellable);
  for (let attempt = 0; attempt < 30 && (await callCount()) === before; attempt++) await delay(50);
  assert.equal(await callCount(), before + 1);
  assert.equal((await run(cancellable)).status, 'running');
  const cancelling = await expectJson<PeriodRun>(
    request(`/api/period-analysis/${cancellable.id}/cancel`, json('POST', {})),
  );
  assert.equal(cancelling.cancelRequested, true);
  const cancelled = await inFlight;
  assert.equal(cancelled.status, 'unknown');
  assert.equal(cancelled.cancelRequested, true);
  assert.deepEqual(await run(cancellable), cancelled);
  assert.equal(await callCount(), before + 1);

  const page = await expectJson<PeriodRunPage>(
    request(`/api/reviews/${review.id}/analyses?limit=2`),
  );
  assert.equal(page.items[0].id, cancelled.id);
  assert.equal(page.items.length, 2);
  assert.ok(page.nextCursor);
  const next = await expectJson<PeriodRunPage>(
    request(
      `/api/reviews/${review.id}/analyses?limit=2&cursor=${encodeURIComponent(page.nextCursor)}`,
    ),
  );
  assert.ok(next.items.every((item) => item.generation < page.items[1].generation));

  const legacy = await expectJson<AnalysisPreview>(
    request('/api/analysis/prepare', json('POST', { projectIds: [project.id] })),
  );
  const legacyInput = { previewId: legacy.id, consent: true };
  const result = await expectJson<AnalysisResult>(
    request('/api/analysis/run', json('POST', legacyInput)),
  );
  assert.equal(result.report.summary, 'Synthetic legacy report');
  assert.deepEqual(
    await expectJson(request('/api/analysis/run', json('POST', legacyInput))),
    result,
  );
  assert.equal(await callCount(), before + 2);
});
