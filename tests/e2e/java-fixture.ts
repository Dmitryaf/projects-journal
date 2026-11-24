import { defaultSettings, type Workspace } from '../../shared/contracts';
import { expectJson, json, startHttp } from '../java/runtime';

export async function seedProjects(
  runtime: Awaited<ReturnType<typeof startHttp>>,
  roots: string[],
  settings: Partial<Workspace['settings']> = {},
) {
  await expectJson(
    runtime.request(
      '/api/settings',
      json('PUT', {
        ...defaultSettings,
        roots,
        autoRefreshMinutes: 0,
        ...settings,
      }),
    ),
  );
  return (await expectJson<Workspace>(runtime.request('/api/workspace'))).projects;
}
