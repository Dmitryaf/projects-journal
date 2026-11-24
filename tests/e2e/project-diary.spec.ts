import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { mkdir } from 'node:fs/promises';
import type { Project, Workspace } from '../../shared/contracts';
import { dateInZone } from '../../shared/periods';
import { fixture } from '../fixtures';

test('project diary preserves a concurrent note and can be read again from the archive', async ({
  page,
  context,
  request,
}) => {
  const f = await fixture();
  const headers = { 'X-Control-Center': '1' };
  const workspace = async (): Promise<Workspace> => (await request.get('/api/workspace')).json();
  const initial = await workspace();
  let project: Project | undefined;
  try {
    const repo = await f.repo('diary-garden');
    const settings = await request.put('/api/settings', {
      headers,
      data: { ...initial.settings, roots: [repo], autoRefreshMinutes: 0 },
    });
    expect(settings.ok()).toBeTruthy();
    project = (await workspace()).projects.find((item) => item.path === repo)!;
    const projectUrl = `/projects/${project.id}`;
    const editor = async (tab: typeof page) => {
      await tab.goto(projectUrl);
      await tab.getByRole('button', { name: 'Действия проекта', exact: true }).click();
      await tab.getByRole('menuitem', { name: 'Изменить сводку', exact: true }).click();
    };
    const other = await context.newPage();
    await editor(page);
    await editor(other);
    await other.getByLabel('Зачем существует').fill('Проверить, помогает ли сравнение образцов.');
    await page.getByText('Дополнительные поля', { exact: true }).click();
    await page.getByLabel('Заметки', { exact: true }).fill('Записали условия первого опыта.');
    await page.getByRole('button', { name: 'Сохранить сводку', exact: true }).click();
    await expect(page.getByText('Сводка сохранена в дневнике', { exact: true })).toBeVisible();
    await other.evaluate(() => window.dispatchEvent(new Event('focus')));
    await other.getByRole('button', { name: 'Обновить сводку, сохранив мой ввод' }).click();
    await expect(other.getByLabel('Зачем существует')).toHaveValue(
      'Проверить, помогает ли сравнение образцов.',
    );
    await other.getByRole('button', { name: 'Сохранить сводку', exact: true }).click();
    await expect(other.getByText('Сводка сохранена в дневнике', { exact: true })).toBeVisible();
    const saved = (await (await request.get('/api' + projectUrl)).json()) as Project;
    expect(saved.notes).toBe('Записали условия первого опыта.');
    await other.close();

    const today = dateInZone(new Date(), initial.settings.timezone);
    const record = (url: string, data: unknown) => request.post('/api' + url, { headers, data });
    expect(
      (
        await record('/decisions', {
          projectId: project.id,
          title: 'Использовать вторую методику',
          status: 'active',
          date: today,
          decision: 'Проверяем результат на равных выборках.',
          reason: 'Так сравнимы результаты.',
        })
      ).ok(),
    ).toBeTruthy();
    expect(
      (
        await record('/decisions', {
          projectId: project.id,
          title: 'Прежняя методика',
          status: 'superseded',
          date: today,
          decision: 'Пока считать только крупные образцы.',
        })
      ).ok(),
    ).toBeTruthy();
    expect(
      (
        await record('/tasks', {
          projectId: project.id,
          title: 'Сопоставить контрольные образцы',
          completedAt: new Date().toISOString(),
          result: { summary: 'Различие оказалось небольшим.' },
        })
      ).ok(),
    ).toBeTruthy();
    expect(
      (
        await record('/tasks', {
          projectId: project.id,
          title: 'Повторить опыт на отдельном участке',
        })
      ).ok(),
    ).toBeTruthy();
    expect(
      (
        await record(projectUrl + '/close', {
          revision: saved.lifecycle?.revision ?? 0,
          requestKey: randomUUID(),
          outcome: 'stopped',
          reason: 'Сравнение завершено. Повтор сейчас не нужен.',
        })
      ).ok(),
    ).toBeTruthy();

    await page.goto('/projects');
    await page.getByRole('link', { name: 'Архив проектов', exact: true }).click();
    await expect(page).toHaveTitle('Архив проектов · Projects Journal');
    await page.getByRole('link', { name: 'diary-garden', exact: true }).click();
    await expect(page).toHaveTitle('diary-garden · Projects Journal');
    await expect(page.getByRole('region', { name: 'Итог проекта' })).toContainText(
      'Сравнение завершено',
    );
    await expect(page.getByRole('heading', { name: 'Что происходило' })).toBeVisible();
    await expect(page.locator('#project-history')).toContainText('Различие оказалось небольшим.');
    await expect(page.locator('#project-decisions')).toContainText('Так сравнимы результаты.');
    await expect(page.getByRole('link', { name: 'Сохранённые выдержки Codex →' })).toHaveAttribute(
      'href',
      `/codex-context?project=${project.id}`,
    );
    const ordered = await page
      .locator('#project-history')
      .evaluate((history) =>
        Boolean(
          history.compareDocumentPosition(document.getElementById('project-tasks')!) &
          Node.DOCUMENT_POSITION_FOLLOWING,
        ),
      );
    expect(ordered).toBe(true);
    await mkdir('.local/project-diary', { recursive: true });
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({ path: `.local/project-diary/closed-${width}.png`, fullPage: true });
    }
    await page.getByRole('link', { name: 'Отменённые · 1' }).click();
    await expect(
      page.getByRole('button', { name: 'Повторить опыт на отдельном участке' }),
    ).toBeVisible();
    await page.goto(projectUrl);
    await page.getByRole('link', { name: 'Все решения проекта →' }).click();
    await page.getByRole('combobox', { name: 'Показать решения' }).selectOption('archive');
    await expect(page.getByRole('heading', { name: 'Прежняя методика' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Использовать вторую методику' })).toHaveCount(
      0,
    );
  } finally {
    if (project) await request.delete(`/api/projects/${project.id}`, { headers, data: {} });
    await request.put('/api/settings', { headers, data: initial.settings });
    await f.cleanup();
  }
});
