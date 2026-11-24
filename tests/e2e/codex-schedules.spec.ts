import { test, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fixture } from '../fixtures';
import { codexProcessFixture } from '../java/codex-process-fixture';
import { expectJson, json, startHttp } from '../java/runtime';
import { seedProjects } from './java-fixture';
import { DatabaseSync } from 'node:sqlite';
import type { AnalysisSchedule } from '../../shared/analysis-schedules';
import type { PeriodRun, PeriodRunPage } from '../../shared/period-analysis';
import { dateInZone, periodBounds } from '../../shared/periods';

test('default review and explicit Codex schedule work without API keys on desktop/mobile and retain a follow-up', async ({
  page,
}) => {
  const f = await fixture();
  const repo = await f.repo('fictional-neighborhood');
  const data = path.join(f.root, 'database');
  const executor = await codexProcessFixture(f.root, repo);
  await executor.update({ browserReport: true });
  const runtime = await startHttp(data, { codex: executor.options, schedules: true });
  const { base, request } = runtime;
  const schedules = async () =>
    (await expectJson<{ items: AnalysisSchedule[] }>(request('/api/analysis-schedules'))).items;
  const tick = () => expectJson(request('/api/test/schedules/tick', json('POST', {})));
  try {
    const [project] = await seedProjects(runtime, [repo], { timezone: 'UTC' });
    const prior = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000);
    const priorDay = dateInZone(prior, 'UTC');
    const previousWeek = periodBounds('week', priorDay, 'UTC');
    await expectJson(
      request(
        '/api/observations',
        json('POST', {
          projectId: project.id,
          occurredOn: priorDay,
          body: 'Сохранили список договорённостей перед встречей.',
        }),
      ),
      201,
    );
    await mkdir('.local/work/codex-periodic-ui', { recursive: true });
    await page.goto(`${base}/analysis?project=${project.id}`);
    const question = page.getByRole('textbox', { name: 'Что хотите обсудить?', exact: true });
    await expect(question).toHaveValue('');
    await question.fill('Как развивается идея?');
    await page
      .locator('.analysis-scope summary')
      .filter({ hasText: /^Неделя/ })
      .click();
    await page.getByLabel('Дата внутри периода').fill('2024-03-06');
    await expect(question).toHaveValue('Как развивается идея?');
    await question.fill('');
    await page.getByText('Промпт анализа по умолчанию', { exact: true }).click();
    await expect(
      page.getByText('Помоги осмыслить развитие выбранных проектов за этот период.', {
        exact: false,
      }),
    ).toBeVisible();
    await page.getByRole('button', { name: 'Просмотреть запрос', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Просмотр запроса Codex' })).toBeVisible();
    expect((await executor.executions()).length).toBe(0);
    await expect(
      page.getByRole('heading', { name: 'Разбор проектов за период', exact: true }),
    ).toBeVisible();
    await page.goto(`${base}/analysis?project=${project.id}`);
    await page.getByRole('button', { name: 'Настроить разбор по расписанию' }).click();
    const form = page.locator('#schedule');
    await expect(form).toHaveAttribute('open', '');
    await form.getByRole('combobox', { name: 'Исполнитель анализа' }).selectOption('codex');
    const enable = form.getByRole('button', { name: 'Включить с текущего периода' });
    await expect(enable).toBeDisabled();
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await form.scrollIntoViewIfNeeded();
      await page.screenshot({
        path: `.local/work/codex-periodic-ui/schedule-${width}.png`,
        fullPage: true,
      });
    }
    await form
      .getByLabel('Разрешаю читать и сохранять выбранные источники для каждого нового периода')
      .check();
    await expect(enable).toBeDisabled();
    await form
      .getByLabel('Разрешаю автоматическую отправку выбранных сведений в Codex', { exact: false })
      .check();
    await enable.focus();
    await page.keyboard.press('Enter');
    await expect(form.getByRole('button', { name: 'Отключить', exact: true })).toBeEnabled();
    expect((await executor.executions()).length).toBe(0);
    const grant = (await schedules())[0];
    expect(grant.provider).toBe('codex');
    expect(grant.selection.projectIds).toEqual([project.id]);
    await tick();
    expect((await executor.executions()).length).toBe(0);
    // Make the previous period due in the isolated fixture without waiting a week.
    const db = new DatabaseSync(path.join(data, 'control-center.sqlite'));
    try {
      db.prepare('UPDATE analysis_schedules SET next_period_start=? WHERE id=?').run(
        previousWeek.periodStart,
        grant.id,
      );
    } finally {
      db.close();
    }
    await tick();
    await expect
      .poll(
        async () =>
          (await expectJson<PeriodRunPage>(request('/api/period-analysis/recent'))).items.filter(
            (item) => item.status === 'succeeded',
          ).length,
      )
      .toBe(1);
    await tick();
    const runId = (await schedules())[0].lastRunId!;
    const run = await expectJson<PeriodRun>(request(`/api/period-analysis/${runId}`));
    expect(run.trigger).toBe('scheduled');
    await page.goto(`${base}/codex-analysis?review=${run.reviewKey}&run=${run.id}`);
    await expect(
      page
        .locator('article.analysis-reading')
        .getByText('Я бы сначала проверил, легко ли вернуться к прошлой записи.', {
          exact: false,
        }),
    ).toBeVisible();
    await page.reload();
    const followup = page.getByRole('textbox', { name: 'Уточняющий вопрос' });
    await followup.fill('Как проверить это одним действием?');
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      await followup.focus();
      expect(await followup.evaluate((element) => getComputedStyle(element).outlineStyle)).toBe(
        'none',
      );
      expect(
        await page
          .locator('.analysis-follow-form')
          .evaluate((element) => getComputedStyle(element).outlineStyle),
      ).toBe('solid');
      await page.screenshot({
        path: `.local/work/codex-periodic-ui/report-${width}.png`,
        fullPage: true,
      });
    }
    await page.getByRole('button', { name: 'Подготовить уточнение' }).click();
    expect((await executor.executions()).length).toBe(1);
    const preview = page.getByRole('region', { name: 'Просмотр запроса Codex' });
    await preview.getByRole('checkbox').check();
    await preview.getByRole('button', { name: 'Запустить Codex' }).click();
    await expect.poll(async () => (await executor.executions()).length).toBe(2);
    await expect(
      page
        .locator('article.analysis-reading')
        .getByText('Попроси одного человека записать наблюдение', { exact: false }),
    ).toBeVisible();
    expect(await executor.loginCount()).toBe(0);
    await page.goto(
      `${base}/analysis-schedules?review=${run.reviewKey}&projects=${project.id}&provider=codex`,
    );
    const settings = page.locator('#schedule');
    await expect(settings.getByRole('button', { name: 'Отключить', exact: true })).toBeEnabled();
    await settings.getByRole('textbox', { name: 'Модель Codex' }).fill('synthetic-model-choice');
    await page.getByRole('link', { name: 'К выбору проектов и периода →' }).click();
    await page
      .getByRole('alertdialog')
      .getByRole('button', { name: 'Продолжить редактирование' })
      .click();
    await expect(page).toHaveURL(/analysis-schedules/);
    await expect(settings.getByRole('textbox', { name: 'Модель Codex' })).toHaveValue(
      'synthetic-model-choice',
    );
    const save = settings.getByRole('button', { name: 'Включить с текущего периода' });
    await expect(save).toBeDisabled();
    await settings
      .getByLabel('Разрешаю читать и сохранять выбранные источники для каждого нового периода')
      .check();
    await settings
      .getByLabel('Разрешаю автоматическую отправку выбранных сведений в Codex', { exact: false })
      .check();
    await save.click();
    await expect.poll(async () => (await schedules())[0].revision).toBe(2);
    expect((await schedules())[0].model).toBe('synthetic-model-choice');
    const stale = await page.request.post(`${base}/api/analysis-schedules/${grant.id}/disable`, {
      headers: { 'X-Control-Center': '1' },
      data: { revision: 1 },
    });
    expect(stale.status()).toBe(409);
    expect((await schedules())[0].enabled).toBe(true);
    await settings.getByRole('button', { name: 'Отключить', exact: true }).click();
    await expect.poll(async () => (await schedules())[0].enabled).toBe(false);
    expect((await executor.executions()).length).toBe(2);
  } finally {
    await runtime.close();
    await f.cleanup();
  }
});
