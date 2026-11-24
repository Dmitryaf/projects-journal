import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { access, realpath, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { test, type TestContext } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import type {
  CodexConversation,
  CodexExecutionStatus,
  CodexFileList,
} from '../../shared/codex-analysis.js';
import type {
  CodexDigest,
  CodexGrant,
  CodexPreview,
  CodexThread,
} from '../../shared/codex-context.js';
import { defaultSettings, type Workspace } from '../../shared/contracts.js';
import type { PeriodRun, PeriodSelection } from '../../shared/period-analysis.js';
import type { Review } from '../../shared/reviews.js';
import { fixture } from '../fixtures.js';
import { codexProcessFixture } from './codex-process-fixture.js';
import { expectJson, json, startHttp } from './runtime.js';

async function setup(t: TestContext) {
  const files = await fixture();
  const cleanups = [files.cleanup];
  t.after(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const repo = await realpath(await files.repo('Синтетический проект'));
  const codex = await codexProcessFixture(files.root, repo);
  const data = path.join(files.root, 'База журнала');
  const http = await startHttp(data, { codex: codex.options });
  cleanups.push(http.close);
  const { request } = http;
  await expectJson(
    request(
      '/api/settings',
      json('PUT', { ...defaultSettings, roots: [repo], timezone: 'UTC', autoRefreshMinutes: 0 }),
    ),
  );
  const project = (await expectJson<Workspace>(request('/api/workspace'))).projects[0];
  const review = await expectJson<Review>(
    request(
      '/api/reviews',
      json('POST', { projectId: project.id, kind: 'week', anchorDate: '2024-03-06' }),
    ),
    201,
  );
  return { request, codex, project, review, repo, data, files };
}

async function eventually<T>(read: () => Promise<T>, ready: (result: T) => boolean) {
  const deadline = Date.now() + 7000;
  let result = await read();
  while (!ready(result) && Date.now() < deadline) {
    await delay(50);
    result = await read();
  }
  assert.ok(ready(result), JSON.stringify(result));
  return result;
}

test('Java reads scoped Codex dialogs over stdio and saves only consented excerpts', async (t) => {
  const { request, codex, project, review, repo } = await setup(t);
  const base = '/api/projects/' + project.id + '/codex-context';
  assert.deepEqual(await expectJson(request(base)), {
    projectId: project.id,
    approved: false,
    enabled: false,
    revision: null,
  });
  assert.equal((await request(base + '/threads', json('POST', { revision: 1 }))).status, 410);
  assert.deepEqual(await codex.events(), []);
  assert.equal((await request(base, json('PUT', { revision: null, enabled: true }))).status, 400);
  const grant = await expectJson<CodexGrant>(
    request(base, json('PUT', { revision: null, enabled: true, readConsent: true })),
  );
  assert.equal(grant.enabled, true);
  assert.ok(grant.revision);
  const threadRequest = () =>
    request(base + '/threads', json('POST', { revision: grant.revision }));
  await codex.update({ legacyProtocol: true });
  assert.equal((await threadRequest()).status, 503);
  assert.deepEqual(await codex.events(), []);
  await codex.update({ legacyProtocol: false });
  assert.deepEqual(
    await expectJson(request(base + '/probe', json('POST', { revision: grant.revision }))),
    { version: '2.42.7' },
  );
  const list = await expectJson<{ items: CodexThread[]; nextCursor: string | null }>(
    threadRequest(),
  );
  assert.deepEqual(
    list.items.map((item) => ({ id: item.id, name: item.name })),
    [{ id: codex.state.threadId, name: 'Синтетический диалог' }],
  );
  assert.doesNotMatch(JSON.stringify(list), /FIXTURE|"cwd"/);
  const events = await codex.events();
  const threads = events.find((event) => event.method === 'thread/list');
  assert.equal(threads?.params?.useStateDbOnly, true);
  assert.deepEqual(threads?.params?.cwd, [repo]);
  assert.ok(events.some((event) => event.error?.code === -32601));

  const preview = await expectJson<CodexPreview>(
    request(
      base + '/preview',
      json('POST', {
        revision: grant.revision,
        reviewId: review.id,
        threadId: codex.state.threadId,
      }),
    ),
  );
  assert.equal(preview.excerpts.length, 2);
  assert.equal(preview.excerpts[0].text, codex.state.text);
  assert.doesNotMatch(JSON.stringify(preview), /FIXTURE/);
  const saved = await expectJson<CodexDigest>(
    request(
      base + '/digests',
      json('POST', {
        previewId: preview.id,
        revision: null,
        summary: 'Одна проверяемая выдержка.',
        excerptIds: [preview.excerpts[0].id],
        confirm: true,
      }),
    ),
    201,
  );
  assert.deepEqual(saved.excerpts, [preview.excerpts[0]]);
  assert.equal(saved.sourceHash, preview.sourceHash);
  assert.equal(saved.eligible, true);
  const digestPath = '/api/codex-context/digests/' + saved.id;
  const edited = await expectJson<CodexDigest>(
    request(
      digestPath,
      json('PATCH', { revision: saved.revision, summary: 'Обновлённая заметка.', confirm: true }),
    ),
  );
  assert.equal(edited.revision, saved.revision + 1);
  assert.deepEqual(await expectJson(request(digestPath)), edited);

  const auto = {
    reviewId: review.id,
    projects: [{ projectId: project.id, revision: grant.revision }],
    readConsent: true,
    saveConsent: true,
  };
  const unchanged = await expectJson<{ items: CodexDigest[] }>(
    request('/api/codex-context/prepare', json('POST', auto)),
  );
  assert.deepEqual(unchanged.items, [edited]);
  await codex.update({ text: 'Изменённый синтетический источник.' });
  const stale = await expectJson<CodexDigest>(
    request(
      digestPath + '/check',
      json('POST', {
        revision: edited.revision,
        grantRevision: grant.revision,
      }),
    ),
  );
  assert.equal(stale.stale, true);
  assert.equal(stale.eligible, false);
  assert.deepEqual(stale.excerpts, edited.excerpts);

  await expectJson(request(base, json('PUT', { revision: grant.revision, enabled: false })));
  const calls = (await codex.events()).length;
  assert.equal((await threadRequest()).status, 410);
  assert.equal((await codex.events()).length, calls);
  assert.deepEqual(await expectJson(request(digestPath)), stale);
});

type CodexRunView = { run: PeriodRun; progress: string | null };

test('Java runs isolated Codex analysis, checks file stamps, and cancels the owned CLI', async (t) => {
  const { request, codex, project, review, repo, data, files } = await setup(t);
  const base = '/api/codex-analysis';
  const status = () => expectJson<CodexExecutionStatus>(request(base + '/status'));
  await codex.update({ authenticated: false });
  assert.equal((await status()).authenticated, false);
  assert.deepEqual(await codex.events(), []);
  assert.equal((await request(base + '/login', json('POST', { confirm: false }))).status, 400);
  await expectJson(request(base + '/login', json('POST', { confirm: true })));
  const waiting = await eventually(status, (value) => value.login?.state === 'waiting');
  assert.equal(waiting.login?.code, 'ABCD-EFGH');
  assert.equal(waiting.login?.url, 'https://auth.openai.com/codex/device');
  await expectJson(request(base + '/login', json('POST', { confirm: true })));
  const [login] = await codex.events();
  assert.equal((await codex.events()).filter((event) => event.kind === 'login').length, 1);
  assert.equal(login.home, path.join(data, 'codex-analysis'));
  assert.equal(login.inherited, null);
  assert.equal(
    (
      await expectJson<CodexExecutionStatus>(
        request(base + '/login/cancel', json('POST', { confirm: true })),
      )
    ).login,
    null,
  );
  assert.throws(() => process.kill(login.pid, 0), { code: 'ESRCH' });
  await expectJson(request(base + '/login', json('POST', { confirm: true })));
  await eventually(status, (value) => value.login?.state === 'waiting');
  await codex.update({ authenticated: true });
  await eventually(status, (value) => value.authenticated === true);

  const instructions = await expectJson<{ question: string; policy: string }>(
    request(base + '/instructions'),
  );
  assert.ok(instructions.question);
  assert.equal(instructions.policy, 'codex-project-review-v5');
  await writeFile(path.join(repo, 'notes.md'), 'Только синтетические данные.\n');
  await writeFile(path.join(repo, 'private-secret.md'), 'synthetic excluded name\n');
  await writeFile(path.join(repo, 'untracked.md'), 'synthetic untracked content\n');
  files.git(repo, 'add', 'notes.md', 'private-secret.md');
  const listing = await expectJson<CodexFileList>(
    request(base + '/files', json('POST', { projectIds: [project.id], readConsent: true })),
  );
  assert.deepEqual(listing.projects[0].files, ['README.md', 'notes.md']);
  const selection: PeriodSelection = {
    projectIds: [project.id],
    includeGeneral: false,
    includeMetadata: true,
    sourceKinds: [],
    includeOpenTasks: false,
    includeUserText: false,
    includeRelatedReviews: false,
    includeRelatedAnalyses: false,
    codexDigestIds: [],
  };
  const input = {
    reviewId: review.id,
    requestKey: randomUUID(),
    selection,
    readConsent: true,
    files: [{ projectId: project.id, path: 'notes.md' }],
  };
  const prepareRequest = (changes: object = {}) =>
    request(base + '/prepare', json('POST', { ...input, ...changes }));
  const prepare = (changes: object = {}) => expectJson<PeriodRun>(prepareRequest(changes), 201);
  const startRequest = (run: PeriodRun, consent = true) =>
    request(base + '/' + run.id + '/start', json('POST', { packetHash: run.packetHash, consent }));
  const start = (run: PeriodRun) => expectJson<CodexRunView>(startRequest(run), 202);
  const read = (run: PeriodRun) => expectJson<CodexRunView>(request(base + '/' + run.id));
  const execs = async () => (await codex.events()).filter((event) => event.kind === 'exec');
  assert.equal(
    (await prepareRequest({ files: [{ projectId: project.id, path: 'untracked.md' }] })).status,
    403,
  );
  const pending = await prepare();
  assert.equal(pending.origin, 'codex_runtime');
  assert.equal(pending.model, 'codex-default');
  assert.equal(
    pending.packetHash,
    createHash('sha256')
      .update(
        JSON.stringify({
          payload: pending.payload,
          model: pending.model,
          policy: instructions.policy,
        }),
      )
      .digest('hex'),
  );
  assert.deepEqual(await prepare(), pending);
  assert.equal((await startRequest(pending, false)).status, 400);
  assert.equal(
    (
      await request(
        '/api/period-analysis/' + pending.id + '/run',
        json('POST', { packetHash: pending.packetHash, consent: true }),
      )
    ).status,
    403,
  );
  assert.deepEqual(await execs(), []);
  await writeFile(path.join(repo, 'notes.md'), 'Файл изменён после просмотра.\n');
  assert.equal((await startRequest(pending)).status, 410);
  assert.deepEqual(await execs(), []);

  const ready = await prepare({ requestKey: randomUUID() });
  await Promise.all([start(ready), start(ready)]);
  const completed = (
    await eventually(
      () => read(ready),
      (value) => value.run.status === 'succeeded',
    )
  ).run;
  assert.equal(completed.report?.narrative, 'Синтетический анализ.');
  assert.equal(completed.responseId, 'fixture-codex-execution');
  assert.deepEqual(completed.usage, { inputTokens: 23, outputTokens: 13 });
  assert.equal((await execs()).length, 1);
  const [executed] = await execs();
  assert.equal(executed.home, path.join(data, 'codex-analysis'));
  assert.equal(executed.userProfile, executed.home);
  assert.equal(executed.unixHome, executed.home);
  assert.equal(executed.inherited, null);
  assert.ok(executed.args?.includes('--ignore-user-config'));
  assert.ok(executed.args?.includes('--ephemeral'));
  assert.equal(executed.args?.[executed.args.indexOf('--sandbox') + 1], 'read-only');
  for (const tool of ['shell_tool', 'apps', 'hooks', 'computer_use']) {
    assert.equal(executed.args?.[executed.args.indexOf(tool) - 1], '--disable');
  }
  assert.ok(executed.schema?.properties.narrative);
  assert.deepEqual(executed.packet?.conversation, []);
  assert.equal(
    executed.packet?.context.sources.find((source) => source.title === 'notes.md')?.text,
    'Файл изменён после просмотра.\n',
  );
  await assert.rejects(access(executed.cwd!), { code: 'ENOENT' });

  const parent = completed.payload.origin;
  assert.equal(parent?.kind, 'codex_runtime');
  const followup = await prepare({
    requestKey: randomUUID(),
    parentRunId: completed.id,
    question: 'Уточни fixture-hold.',
  });
  await start(followup);
  const [, running] = await eventually(execs, (events) => events.length === 2);
  assert.equal(running.packet?.conversation.length, 1);
  await eventually(
    () => read(followup),
    (value) => value.progress === 'Codex анализирует выбранный контекст',
  );
  await expectJson(request('/api/period-analysis/' + followup.id + '/cancel', json('POST', {})));
  const stopped = (
    await eventually(
      () => read(followup),
      (value) => value.run.status === 'unknown',
    )
  ).run;
  assert.equal(stopped.cancelRequested, true);
  assert.equal(stopped.report, null);
  assert.doesNotMatch(JSON.stringify(stopped), /FIXTURE_DIAGNOSTIC/);
  assert.throws(() => process.kill(running.pid, 0), { code: 'ESRCH' });
  await assert.rejects(access(running.cwd!), { code: 'ENOENT' });
  assert.deepEqual((await start(followup)).run, stopped);
  assert.equal((await execs()).length, 2);
  if (parent?.kind !== 'codex_runtime') assert.fail('Missing conversation metadata');
  const conversation = await expectJson<CodexConversation>(
    request(base + '/conversation?sessionId=' + parent.sessionId),
  );
  assert.deepEqual(
    conversation.turns.map((run) => run.id),
    [completed.id, stopped.id],
  );

  const unsupported = await prepare({
    requestKey: randomUUID(),
    question: 'Проверка fixture-tool.',
  });
  await start(unsupported);
  const rejected = (
    await eventually(
      () => read(unsupported),
      (value) => value.run.status === 'failed',
    )
  ).run;
  assert.equal(rejected.report, null);
  assert.equal(rejected.rawOutput, null);
  assert.doesNotMatch(JSON.stringify(rejected), /fixture unused command|FIXTURE_DIAGNOSTIC/);
});
