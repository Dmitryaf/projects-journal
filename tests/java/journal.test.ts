import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { test } from 'node:test';

import type {
  Decision,
  Idea,
  Relation,
  Task,
  TaskHistoryPage,
  Workspace,
} from '../../shared/contracts.js';
import { defaultSettings } from '../../shared/contracts.js';
import { fixture } from '../fixtures.js';
import { connectMcp } from '../mcp-client.js';
import { expectJson, javaMcp, json, startHttp } from './runtime.js';

test('Java journal exposes equivalent records, tasks and history through HTTP and MCP', async (t) => {
  const files = await fixture();
  const cleanups = [files.cleanup];
  t.after(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const roots = path.join(files.root, 'roots');
  const selected = await files.repo('roots/selected');
  const other = await files.repo('roots/other');
  const foreign = await files.repo('foreign');
  const worktree = path.join(files.root, 'linked worktree');
  files.git(selected, 'worktree', 'add', '--detach', worktree);
  const workingDirectory = path.join(worktree, 'nested');
  await mkdir(workingDirectory);
  await writeFile(path.join(selected, 'PROJECT.yaml'), 'name: Selected\nnext: [Legacy step]\n');
  await writeFile(
    path.join(selected, 'DECISIONS.md'),
    '# Registered decision\nCanonical contents.',
  );

  const data = path.join(files.root, 'database');
  const http = await startHttp(data);
  cleanups.push(http.close);
  const { request } = http;
  await expectJson(
    request(
      '/api/settings',
      json('PUT', { ...defaultSettings, roots: [roots], autoRefreshMinutes: 0 }),
    ),
  );
  const workspace = () => expectJson<Workspace>(request('/api/workspace'));
  const projects = (await workspace()).projects;
  const selectedProject = projects.find((project) => project.metadata.name === 'Selected')!;
  const otherProject = projects.find((project) => project.path === other)!;
  assert.ok(selectedProject && otherProject);
  const projectId = selectedProject.id;
  const otherId = otherProject.id;

  const idea = await expectJson<Idea>(
    request('/api/ideas', json('POST', { title: ' Idea ', projectId })),
    201,
  );
  assert.equal(idea.title, 'Idea');
  assert.equal(idea.state, 'new');
  const edited = await expectJson<Idea>(
    request(`/api/ideas/${idea.id}`, json('PUT', { ...idea, state: 'consider' })),
  );
  assert.equal(edited.createdAt, idea.createdAt);
  assert.equal(edited.state, 'consider');
  const decision = await expectJson<Decision>(
    request(
      '/api/decisions',
      json('POST', {
        projectId,
        title: 'Decision',
        decision: 'Continue',
        date: '2026-11-01',
        status: 'pending',
      }),
    ),
    201,
  );
  const finalized = await expectJson<Decision>(
    request(`/api/decisions/${decision.id}`, json('PUT', { ...decision, status: 'active' })),
  );
  assert.equal(finalized.status, 'active');
  assert.equal(
    (
      await request(
        `/api/decisions/${decision.id}`,
        json('PUT', { ...decision, date: '2026-02-30' }),
      )
    ).status,
    400,
  );
  const relation = json('POST', { sourceId: projectId, targetId: otherId, type: 'related_to' });
  const [relationA, relationB] = await Promise.all([
    expectJson<Relation>(request('/api/relations', relation), 201),
    expectJson<Relation>(request('/api/relations', relation), 201),
  ]);
  assert.equal(relationA.id, relationB.id);
  assert.deepEqual((await workspace()).relations, [relationA]);

  const mcp = await connectMcp(data, foreign, javaMcp);
  cleanups.push(mcp.close);
  const callOk = async (name: string, arguments_: Record<string, unknown> = {}) => {
    const result = await mcp.call(name, arguments_);
    assert.equal(result.isError, false, `${name}: ${JSON.stringify(result.value)}`);
    return result.value;
  };
  const callStatus = async (name: string, arguments_: Record<string, unknown>, status: number) => {
    const result = await mcp.call(name, arguments_);
    assert.equal(result.isError, true);
    assert.equal(result.value.status, status, JSON.stringify(result.value));
  };
  assert.deepEqual(
    (await callOk('projects', { query: 'SELECTED', limit: 1 })).items.map(
      (project: { id: string }) => project.id,
    ),
    [projectId],
  );
  assert.equal((await callOk('projects', { offset: 1, limit: 1 })).total, 2);
  const context = await callOk('project_context', { projectId, workingDirectory });
  assert.equal(context.project.id, projectId);
  assert.deepEqual(context.legacyPlan.next, ['Legacy step']);
  assert.deepEqual(context.localDecisions, [finalized]);
  assert.equal(context.decisions[0].body, '');
  assert.match(
    (await callOk('project_context', { workingDirectory, decisionKey: context.decisions[0].key }))
      .decision.body,
    /Canonical contents/,
  );
  await callStatus('project_context', { workingDirectory: foreign }, 404);
  await callStatus('project_context', { projectId: otherId, workingDirectory }, 409);
  await callStatus('project_context', {}, 400);
  await callStatus(
    'project_context',
    { workingDirectory, decisionKey: path.join(foreign, 'README.md') },
    404,
  );

  const importPlan = () =>
    expectJson<Task[]>(request(`/api/projects/${projectId}/import-plan`, json('POST', {})));
  const [task] = await importPlan();
  assert.equal(task.title, 'Legacy step');
  assert.deepEqual(
    (await importPlan()).map((item) => item.id),
    [task.id],
  );
  assert.deepEqual(
    (await callOk('task_list', { projectId, query: 'STEP' })).items.map((item: Task) => item.id),
    [task.id],
  );
  assert.equal((await callOk('task_list', { projectId: null })).total, 0);
  await callStatus('task_list', { limit: 101 }, 400);
  assert.equal((await callOk('project_context', { workingDirectory })).tasks[0].code, task.code);
  await callStatus(
    'task_complete',
    {
      identifier: task.code,
      revision: task.revision,
      result: { summary: 'Partial', remaining: 'Still work' },
    },
    400,
  );
  const completed = await callOk('task_complete', {
    identifier: task.code,
    revision: task.revision,
    result: { summary: 'Done' },
  });
  assert.ok(completed.completedAt);
  assert.equal((await callOk('task_list', { projectId })).total, 0);
  assert.equal((await callOk('task_list', { projectId, completed: 'done' })).items[0].id, task.id);
  const returned = await callOk('task_update', {
    identifier: task.code,
    revision: completed.revision,
    change: { completedAt: null },
  });
  assert.equal(returned.result.summary, 'Done');
  const again = await expectJson<Task>(
    request(
      `/api/tasks/${task.id}/complete`,
      json('POST', { revision: returned.revision, result: { summary: 'Completed through HTTP' } }),
    ),
  );
  assert.equal(again.result?.summary, 'Completed through HTTP');
  assert.equal(
    (await request(`/api/tasks/${task.id}`, json('DELETE', { revision: task.revision }))).status,
    409,
  );
  await expectJson(request(`/api/tasks/${task.id}`, json('DELETE', { revision: again.revision })));
  await callStatus('task_read', { identifier: task.code }, 404);

  const history: TaskHistoryPage = await callOk('task_history', {
    projectId,
    identifier: task.code,
  });
  assert.deepEqual(
    history.items.map((item) => item.kind),
    ['completed', 'reopened', 'completed'],
  );
  assert.ok(
    history.items.every((item) => !item.taskExists && item.projects?.[0]?.id === projectId),
  );
  const page = await expectJson<TaskHistoryPage>(
    request(`/api/task-history?identifier=${task.code}&limit=1&offset=1`),
  );
  assert.equal(page.total, 3);
  assert.deepEqual(page.items, [history.items[1]]);
  assert.deepEqual(await expectJson(request('/api/task-history?projectId=__general__')), {
    total: 0,
    items: [],
  });
  await callStatus('task_history', { from: '2026-11-22', to: '2026-11-21' }, 400);

  await expectJson(request(`/api/ideas/${idea.id}`, json('DELETE', {})));
  await expectJson(request(`/api/decisions/${decision.id}`, json('DELETE', {})));
  await expectJson(request(`/api/relations/${relationA.id}`, json('DELETE', {})));
  const cleared = await workspace();
  assert.deepEqual([cleared.ideas, cleared.decisions, cleared.relations], [[], [], []]);
  assert.deepEqual((await callOk('project_context', { workingDirectory })).localDecisions, []);
});
