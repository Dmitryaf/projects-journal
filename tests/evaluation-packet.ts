import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { fixture } from './fixtures.js';
import type { EvaluationCase } from './period-evaluation-fixture.js';
import { defaultSettings, metadataSchema, type Workspace } from '../shared/contracts.js';
import type { Review } from '../shared/reviews.js';
import type { PeriodRun } from '../shared/period-analysis.js';
import { expectJson, json, startHttp } from './java/runtime.js';

export async function evaluationPacket(example: EvaluationCase) {
  const f = await fixture();
  let runtime: Awaited<ReturnType<typeof startHttp>> | undefined;
  const cleanup = async () => {
    try {
      await runtime?.close();
    } finally {
      await f.cleanup();
    }
  };
  try {
    const roots = await Promise.all(
      [...example.projects, null].map((_, i) => f.repo(`project-${i}`)),
    );
    runtime = await startHttp(path.join(f.root, 'data'), { openAiStub: true });
    const { request } = runtime;
    await expectJson(
      request('/api/settings', json('PUT', { ...defaultSettings, roots, timezone: 'UTC', autoRefreshMinutes: 0 })),
    );
    const workspace = await expectJson<Workspace>(request('/api/workspace'));
    const ids: string[] = [];
    async function observe(projectId: string, occurredOn: string, body: string) {
      await expectJson(request('/api/observations', json('POST', { projectId, occurredOn, body })), 201);
    }
    for (const [i, input] of example.projects.entries()) {
      const project = workspace.projects.find((item) => item.path === roots[i])!;
      ids.push(project.id);
      await expectJson(
        request(`/api/projects/${project.id}`, json('PUT', {
          metadata: metadataSchema.parse({
            ...project.metadata,
            name: input.name,
            status: input.status,
            stage: input.stage,
          }),
          notes: 'UNSELECTED_PRIVATE_NOTE',
        })),
      );
      for (const body of input.notes) await observe(project.id, '2024-03-10', body);
      await observe(project.id, '2024-03-11', 'OUTSIDE_PERIOD_CANARY');
    }
    const excluded = workspace.projects.find((project) => project.path === roots.at(-1))!;
    await observe(excluded.id, '2024-03-10', 'UNSELECTED_PROJECT_CANARY');
    const review = await expectJson<Review>(
      request('/api/reviews', json('POST', {
        projectId: ids.length === 1 ? ids[0] : null,
        kind: 'week',
        anchorDate: '2024-03-10',
      })),
      201,
    );
    const run = await expectJson<PeriodRun>(request('/api/period-analysis/prepare', json('POST', {
      reviewId: review.id,
      requestKey: randomUUID(),
      readConsent: true,
      selection: {
        projectIds: ids,
        includeGeneral: false,
        sourceKinds: ['observation'],
        includeMetadata: true,
        includeOpenTasks: false,
        includeUserText: false,
        includeRelatedReviews: false,
      },
    })), 201);
    const { calls } = await expectJson<{ calls: number }>(request('/api/test/openai/calls'));
    const { maxInputBytes, maxOutputTokens } = await expectJson<{
      maxInputBytes: number;
      maxOutputTokens: number;
    }>(request('/api/period-analysis/status'));
    return { run, ids, calls: () => calls, limits: { maxInputBytes, maxOutputTokens }, cleanup };
  } catch (cause) {
    await cleanup();
    throw cause;
  }
}
