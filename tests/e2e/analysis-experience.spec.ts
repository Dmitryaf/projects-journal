import { test, expect } from '@playwright/test';
import path from 'node:path';
import { mkdir } from 'node:fs/promises';
import { fixture } from '../fixtures';
import { codexProcessFixture } from '../java/codex-process-fixture';
import { expectJson, json, startHttp } from '../java/runtime';
import { seedProjects } from './java-fixture';
import { DatabaseSync } from 'node:sqlite';
import type { Task } from '../../shared/contracts';
import type { PeriodRun } from '../../shared/period-analysis';
import type { Review } from '../../shared/reviews';
import type { CodexGrant } from '../../shared/codex-context';
import { coreSelection, emptySelection } from '../../src/features/period-analysis/SourceSelection';

test('readable request, visible waiting, retained follow-up and explicit period change', async ({
  page,
}) => {
  const f = await fixture();
  const equipment = await f.repo('equipment-catalogue');
  await f.repo('shift-planner');
  const executor = await codexProcessFixture(f.root, equipment);
  await executor.update({ hold: true, browserReport: true });
  const data = path.join(f.root, 'database');
  const runtime = await startHttp(data, { codex: executor.options });
  const { base, request } = runtime;
  try {
    const selected = await seedProjects(runtime, [f.root], { timezone: 'UTC' });
    const task = await expectJson<Task>(
      request(
        '/api/tasks',
        json('POST', {
          projectId: selected[0].id,
          title: 'Search by equipment number',
          completedAt: '2024-03-07T12:00:00Z',
          result: { summary: 'Equipment can be found by its inventory number.' },
        }),
      ),
      201,
    );
    const db = new DatabaseSync(path.join(data, 'control-center.sqlite'));
    try {
      db.prepare('UPDATE task_history SET recorded_at=? WHERE task_id=?').run(
        '2024-03-07T12:00:00Z',
        task.id,
      );
    } finally {
      db.close();
    }
    await mkdir('.local/work/analysis-ux-2026-11-09', { recursive: true });
    await page.goto(`${base}/analysis?projects=${selected.map((project) => project.id).join(',')}`);
    await page
      .locator('.analysis-scope summary')
      .filter({ hasText: /^Неделя/ })
      .click();
    await page.getByLabel('Дата внутри периода').fill('2024-03-12');
    await page.getByRole('button', { name: 'Просмотреть запрос', exact: true }).click();
    const preview = page.getByRole('region', { name: 'Просмотр запроса Codex' });
    await expect(preview.getByText('11.03.2024 — 17.03.2024', { exact: false })).toBeVisible();
    await expect(
      preview.getByRole('heading', { name: 'equipment-catalogue', exact: true, level: 4 }),
    ).toBeVisible();
    await expect(preview.locator('.request-chat-state').first()).toContainText(
      'Чаты не подключены',
    );
    await expect(
      preview.getByRole('heading', { name: 'shift-planner', exact: true, level: 4 }),
    ).toBeVisible();
    await expect(preview.getByText('Предыстория · 1', { exact: true })).toBeVisible();
    await preview
      .locator('.request-project')
      .filter({
        has: page.getByRole('heading', {
          name: selected[0].metadata.name,
          exact: true,
          level: 4,
        }),
      })
      .getByText('Прочитать данные проекта')
      .click();
    await expect(
      preview.getByText('Equipment can be found by its inventory number.', { exact: true }),
    ).toBeVisible();
    expect((await executor.executions()).length).toBe(0);
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/work/analysis-ux-2026-11-09/preview-${width}.png`,
        fullPage: true,
      });
    }
    await preview.getByRole('checkbox', { name: /^Я просмотрел контекст/ }).check();
    await preview.getByRole('button', { name: 'Запустить Codex' }).click();
    const progress = page.getByRole('region', { name: 'Ход анализа' });
    await expect(progress.getByRole('progressbar', { name: 'Выполняется анализ' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Codex работает', exact: true })).toHaveCount(1);
    await expect(progress.getByText(/Прошло /)).toBeVisible();
    await expect(progress.getByRole('button', { name: 'Остановить анализ' })).toBeEnabled();
    await expect(progress.locator('.analysis-elapsed')).toHaveText(/Прошло 0:0[1-9]/);
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/work/analysis-ux-2026-11-09/running-${width}.png`,
        fullPage: true,
      });
    }
    await page.screenshot({
      path: '.local/work/analysis-ux-2026-11-09/running.png',
      fullPage: true,
    });
    await expect.poll(async () => (await executor.executions()).length).toBe(1);
    await executor.release((await executor.executions())[0].pid);
    await expect(page.locator('article.analysis-reading')).toHaveCount(1);
    await expect(progress).toHaveCount(0);
    await page.getByRole('textbox', { name: 'Уточняющий вопрос' }).fill('Какой первый шаг?');
    await page.getByRole('button', { name: 'Подготовить уточнение' }).click();
    await preview.getByRole('checkbox', { name: /^Я просмотрел контекст/ }).check();
    await preview.getByRole('button', { name: 'Запустить Codex' }).click();
    await expect(progress).toBeVisible();
    await expect(page.locator('article.analysis-reading')).toHaveCount(1);
    await expect(page.locator('article.analysis-reading').first()).toBeVisible();
    await expect.poll(async () => (await executor.executions()).length).toBe(2);
    await executor.release((await executor.executions())[1].pid);
    await expect(page.locator('article.analysis-reading')).toHaveCount(2);
    await expect(page.locator('article.analysis-reading').first()).toBeVisible();
    await page.emulateMedia({ reducedMotion: 'reduce' });
    expect(
      await page
        .locator('.analysis-result')
        .last()
        .evaluate((el) => getComputedStyle(el).animationName),
    ).toBe('none');
    await page.getByRole('link', { name: 'Изменить период или проекты' }).click();
    await expect(page).toHaveURL(/\/analysis\?/);
    await expect(
      page.getByRole('textbox', { name: 'Что хотите обсудить?', exact: true }),
    ).toHaveValue('Какой первый шаг?');
    await page
      .locator('.analysis-scope summary')
      .filter({ hasText: /^Неделя/ })
      .click();
    await page.getByRole('combobox', { name: /^Период обзора/ }).selectOption('month');
    await page.getByRole('button', { name: 'Просмотреть запрос', exact: true }).click();
    await expect(page).toHaveURL(/\/codex-analysis\?.*run=/);
    const runId = new URL(page.url()).searchParams.get('run')!;
    const saved = await expectJson<PeriodRun>(request(`/api/period-analysis/${runId}`));
    expect(saved.payload.period.kind).toBe('month');
    expect(saved.selection.projectIds).toEqual(selected.map((project) => project.id).sort());
    expect((await executor.executions()).length).toBe(2);
    await expect(
      preview.getByRole('checkbox', { name: /^Я просмотрел контекст/ }),
    ).not.toBeChecked();
  } finally {
    await runtime.close();
    await f.cleanup();
  }
});

test('adding chat excerpts rebuilds the frozen preview and requires new send approval', async ({
  page,
}) => {
  const f = await fixture();
  const repo = await f.repo('demo-notes');
  const fake = await codexProcessFixture(f.root, repo);
  const runtime = await startHttp(path.join(f.root, 'database'), { codex: fake.options });
  const { base, request } = runtime;
  try {
    const [project] = await seedProjects(runtime, [repo], { timezone: 'UTC' });
    await expectJson(
      request(
        `/api/projects/${project.id}`,
        json('PUT', {
          metadata: { ...project.metadata, goal: 'Keep a useful record.\n\nReturn to it later.' },
          notes: '',
        }),
      ),
    );
    const review = await expectJson<Review>(
      request(
        '/api/reviews',
        json('POST', {
          projectId: project.id,
          kind: 'week',
          anchorDate: '2024-03-06',
        }),
      ),
      201,
    );
    const run = await expectJson<PeriodRun>(
      request(
        '/api/codex-analysis/prepare',
        json('POST', {
          reviewId: review.id,
          requestKey: crypto.randomUUID(),
          selection: coreSelection(emptySelection(project.id)),
          readConsent: true,
        }),
      ),
      201,
    );
    await page.goto(`${base}/codex-analysis?review=${review.id}&run=${run.id}`);
    const preview = page.getByRole('region', { name: 'Просмотр запроса Codex' });
    const consent = preview.getByRole('checkbox', { name: /^Я просмотрел контекст/ });
    await consent.check();
    await preview.getByText('Добавить выдержки чатов проектов', { exact: true }).click();
    await preview.getByRole('checkbox', { name: /^Разрешаю читать диалоги/ }).check();
    await preview.getByRole('button', { name: 'Подготовить контекст выбранных проектов' }).click();
    await expect(
      preview.getByRole('button', { name: 'Обновить запрос', exact: true }),
    ).toBeVisible();
    await expect(preview.getByRole('button', { name: 'Запустить Codex' })).toBeDisabled();
    await expect(consent).not.toBeChecked();
    await preview.getByRole('button', { name: 'Обновить запрос', exact: true }).click();
    await expect(page).not.toHaveURL(new RegExp(`run=${run.id}`));
    await expect(preview.locator('.request-chat-state')).toContainText('Чаты в запросе · 1');
    await expect(consent).not.toBeChecked();
    await preview.getByText('Прочитать данные проекта', { exact: true }).click();
    await expect(preview.getByText(`Вы: ${fake.state.text}`, { exact: false })).toBeVisible();
    await expect(preview).not.toContainText('[object Object]');
    expect((await expectJson<PeriodRun>(request(`/api/period-analysis/${run.id}`))).status).toBe(
      'cancelled',
    );
    expect(
      (await expectJson<CodexGrant>(request(`/api/projects/${project.id}/codex-context`))).enabled,
    ).toBe(true);
    expect((await fake.executions()).length).toBe(0);
  } finally {
    await runtime.close();
    await f.cleanup();
  }
});
