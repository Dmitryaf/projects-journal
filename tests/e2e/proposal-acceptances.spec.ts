import { test, expect } from '@playwright/test';
import type { PeriodRun } from '../../shared/period-analysis';
import type { Review } from '../../shared/reviews';
import { fixture } from '../fixtures';
import { expectJson, json, startHttp } from '../java/runtime';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { mkdir } from 'node:fs/promises';
import type { Workspace } from '../../shared/contracts';
import type { ProposalApproval, ProposalAcceptance } from '../../shared/proposal-acceptances';

test('persisted AI proposal → protected human draft → real atomic task/idea acceptance; lost response, reload and retained evidence', async ({
  page,
  playwright,
}) => {
  const f = await fixture();
  const runtime = await startHttp(path.join(f.root, 'database'), { openAiStub: true });
  const { base } = runtime;
  const request = await playwright.request.newContext({ baseURL: base });
  const headers = { 'X-Control-Center': '1' };
  try {
    let review = await expectJson<Review>(
      runtime.request(
        '/api/reviews',
        json('POST', { projectId: null, kind: 'week', anchorDate: '2012-02-01' }),
      ),
      201,
    );
    review = await expectJson<Review>(
      runtime.request(
        `/api/reviews/${review.id}`,
        json('PATCH', {
          revision: review.revision,
          userText: '<b>Наблюдение как текст</b>',
        }),
      ),
    );
    const reviewId = review.id;
    const reviewRevision = review.revision;
    const prepared = await expectJson<PeriodRun>(
      runtime.request(
        '/api/period-analysis/prepare',
        json('POST', {
          reviewId,
          requestKey: randomUUID(),
          selection: {
            projectIds: [],
            includeGeneral: true,
            sourceKinds: [],
            includeMetadata: false,
            includeOpenTasks: false,
            includeUserText: true,
            includeRelatedReviews: false,
          },
          readConsent: true,
        }),
      ),
      201,
    );
    const run = await expectJson<PeriodRun>(
      runtime.request(
        `/api/period-analysis/${prepared.id}/run`,
        json('POST', {
          packetHash: prepared.packetHash,
          consent: true,
        }),
      ),
    );
    expect(run.status).toBe('succeeded');
    const runId = run.id;
    let approval: ProposalApproval | undefined,
      lost = false;
    await page.route(`**/api/period-analysis/${runId}/proposals/0/accept`, async (route) => {
      approval = route.request().postDataJSON();
      const response = await route.fetch();
      expect(response.status()).toBe(201);
      lost = true;
      await route.fulfill({ status: 503, json: { error: 'Fixture lost response after commit' } });
    });
    await page.goto(`${base}/period-analysis?review=${reviewId}&run=${runId}`);
    await page.getByText(/^Предложения · /).click();
    await page.getByRole('link', { name: 'Проверить и принять предложение →' }).first().click();
    await expect(page.getByRole('heading', { name: 'Принять предложение' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Создать', exact: true }).selectOption('task');
    await page.getByLabel('Название', { exact: true }).fill('Моя редакция для задачи');
    await page.getByLabel('Основание', { exact: true }).fill('Я уточнил основание.');
    let failRead = true;
    await page.route(`**/api/period-analysis/${runId}`, (route) => {
      if (failRead) {
        failRead = false;
        return route.fulfill({ status: 503, json: { error: 'Fixture read failure' } });
      }
      return route.continue();
    });
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(page.getByText('Fixture read failure', { exact: false })).toBeVisible();
    await expect(page.getByLabel('Название', { exact: true })).toHaveValue(
      'Моя редакция для задачи',
    );
    await page
      .getByRole('navigation', { name: 'Основная навигация' })
      .getByRole('link', { name: 'История', exact: true })
      .click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: 'Продолжить редактирование' }).click();
    await expect(page.getByLabel('Название', { exact: true })).toHaveValue(
      'Моя редакция для задачи',
    );
    const create = page.getByRole('button', { name: 'Создать задачу', exact: true });
    await expect(create).toBeDisabled();
    await page.getByLabel('Я проверил редакцию и разрешаю создать выбранную запись').check();
    await create.click();
    await expect.poll(() => lost).toBe(true);
    await expect(page.getByRole('region', { name: 'Принятое предложение' })).toContainText(
      'Моя редакция для задачи',
    );
    await expect(page.getByRole('button', { name: 'Создать задачу', exact: true })).toHaveCount(0);
    const repeated = await request.post(`/api/period-analysis/${runId}/proposals/0/accept`, {
      headers,
      data: approval,
    });
    expect(repeated.status()).toBe(201);
    const taskAccepted = (await repeated.json()) as ProposalAcceptance;
    const current = (await (await request.get('/api/workspace')).json()) as Workspace;
    expect(current.tasks.filter((t) => t.title === 'Моя редакция для задачи')).toHaveLength(1);
    expect(
      (await (await request.get(`/api/period-analysis/${runId}`)).json()).report.proposals[0].title,
    ).toBe('Исходное AI предложение');
    await page.reload();
    await expect(page.getByRole('region', { name: 'Принятое предложение' })).toBeVisible();
    await page.getByRole('link', { name: 'Открыть задачу →' }).click();
    await page.getByRole('link', { name: 'Основания принятого предложения →' }).click();
    await expect(page.getByRole('region', { name: 'Принятое предложение' })).toContainText(
      'Моя редакция для задачи',
    );
    await page.goto(`${base}/period-proposal?run=${runId}&index=1`);
    await page.getByLabel('Название', { exact: true }).fill('Моя редакция для идеи');
    await page.getByLabel('Я проверил редакцию и разрешаю создать выбранную запись').check();
    await page.getByRole('button', { name: 'Создать идею', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Принятое предложение' })).toContainText(
      'Моя редакция для идеи',
    );
    expect(
      (
        await request.delete(`/api/period-analysis/${runId}`, { headers, data: { confirm: true } })
      ).ok(),
    ).toBe(true);
    await page.goto(`${base}/proposal-acceptances`);
    await expect(
      page.getByText('Анализ удалён; редакция и основания принятого предложения сохранены.'),
    ).toHaveCount(2);
    await page
      .getByRole('region', { name: 'Принятое предложение' })
      .filter({ has: page.getByRole('heading', { name: 'Моя редакция для задачи', exact: true }) })
      .getByText('Исходное предложение AI и сохранённые источники', { exact: true })
      .click();
    await expect(page.getByText('Исходное AI предложение', { exact: true })).toBeVisible();
    const taskCard = page
      .getByRole('region', { name: 'Принятое предложение' })
      .filter({ has: page.getByRole('heading', { name: 'Моя редакция для задачи', exact: true }) });
    await taskCard.locator('details details summary').click();
    await expect(taskCard.getByText('<b>Наблюдение как текст</b>', { exact: true })).toBeVisible();
    await expect(taskCard.locator('b')).toHaveCount(0);
    await mkdir('.local/proposals-review', { recursive: true });
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/proposals-review/accepted-${width}.png`,
        fullPage: true,
      });
    }
    expect(
      (await request.delete(`/api/tasks/${taskAccepted.resultId}`, { headers, data: {} })).ok(),
    ).toBe(true);
    await page.reload();
    await expect(
      page.getByText('Созданная запись удалена. Автоматического восстановления нет.'),
    ).toBeVisible();
    await request.delete(`/api/reviews/${reviewId}`, {
      headers,
      data: { revision: reviewRevision },
    });
  } finally {
    await request.dispose();
    await runtime.close();
    await f.cleanup();
  }
});
