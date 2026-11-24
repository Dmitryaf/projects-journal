import { test, expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fixture } from '../fixtures';
import { codexProcessFixture } from '../java/codex-process-fixture';
import { expectJson, startHttp } from '../java/runtime';
import { seedProjects } from './java-fixture';
import type { Workspace } from '../../shared/contracts';

test('embedded Codex: real HTTP preview, lost send reply, follow-up, restart, stop and responsive UI', async ({
  page,
}) => {
  const f = await fixture();
  const repo = await f.repo('garden-journal');
  await writeFile(
    path.join(repo, 'README.md'),
    '# Садовый дневник\nПомогает сохранять наблюдения о растениях и выбирать следующий уход.',
  );
  const data = path.join(f.root, 'database');
  const executor = await codexProcessFixture(f.root, repo);
  await executor.update({ authenticated: false });
  let runtime = await startHttp(data, { codex: executor.options });
  const base = runtime.base;
  const port = Number(new URL(base).port);
  try {
    const [project] = await seedProjects(runtime, [repo]);
    await page.goto(`${base}/analysis?project=${project.id}`);
    const firstQuestion = 'Что проверить в садовом дневнике до добавления новых возможностей?';
    const question = page.getByLabel('Что хотите обсудить?');
    await question.fill(firstQuestion);
    await page.getByRole('navigation').getByRole('link', { name: 'Проекты', exact: true }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: 'Продолжить редактирование' }).click();
    await expect(question).toHaveValue(firstQuestion);
    await page
      .locator('.analysis-scope summary')
      .filter({ hasText: /^Неделя/ })
      .click();
    await page.getByLabel('Дата внутри периода').fill('2024-03-10');
    await page.getByText('Другие возможности анализа', { exact: true }).click();
    await page.getByRole('button', { name: 'Выбрать источники, файлы и модель Codex' }).click();
    await expect(page.getByLabel('Вопрос Codex', { exact: true })).toHaveValue(firstQuestion);
    await expect(
      page.getByRole('heading', { name: 'Обсуждение с Codex', exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Подключить Codex' })).toBeVisible();
    expect(await executor.loginCount()).toBe(0);
    await page.getByRole('button', { name: 'Войти в Codex', exact: true }).click();
    await expect(page.getByText('ABCD-EFGH', { exact: true })).toBeVisible();
    await mkdir('.local/work/embedded-codex-ui', { recursive: true });
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/work/embedded-codex-ui/login-${width}.png`,
        fullPage: true,
      });
    }
    expect(await executor.loginCount()).toBe(1);
    expect((await executor.executions()).length).toBe(0);
    await page.getByRole('button', { name: 'Отменить вход', exact: true }).click();
    await expect(page.getByText('ABCD-EFGH', { exact: true })).toHaveCount(0);
    await executor.update({ authenticated: true });
    await page.getByRole('button', { name: 'Проверить вход', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Вход в Codex' })).toBeHidden();
    await page
      .getByLabel('Вопрос Codex', { exact: true })
      .fill('Что проверить в садовом дневнике до добавления новых возможностей?');
    await page.getByText('Добавить файлы проектов — по необходимости', { exact: true }).click();
    await page.getByRole('button', { name: 'Показать файлы выбранных проектов' }).click();
    await page.getByLabel('README.md', { exact: true }).check();
    await mkdir('.local/work/embedded-codex-ui', { recursive: true });
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/work/embedded-codex-ui/compose-${width}.png`,
        fullPage: true,
      });
    }
    await page.getByRole('button', { name: 'Подготовить контекст', exact: true }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('region', { name: 'Просмотр запроса Codex' })).toBeVisible();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    expect((await executor.executions()).length).toBe(0);
    await expect(page.getByRole('button', { name: 'Запустить Codex', exact: true })).toBeDisabled();
    let lost = true;
    await page.route('**/api/codex-analysis/*/start', async (route) => {
      const response = await route.fetch();
      if (lost) {
        lost = false;
        await route.fulfill({ status: 503, json: { error: 'Ответ отправки потерян' } });
      } else await route.fulfill({ response });
    });
    await page.getByRole('checkbox', { name: /Я просмотрел контекст/ }).check();
    await page.getByRole('button', { name: 'Запустить Codex', exact: true }).click();
    await expect(
      page.getByRole('heading', {
        name: 'Что проверить в садовом дневнике до добавления новых возможностей?',
        exact: true,
      }),
    ).toBeVisible();
    await page.goto(`${base}/analysis`);
    const saved = page.getByRole('region', { name: 'Сохранённые анализы проектов' });
    await expect(page.locator('.analysis-reading')).toHaveCount(0);
    await saved.getByRole('link', { name: firstQuestion, exact: true }).click();
    await expect(page.getByLabel('Уточняющий вопрос', { exact: true })).toBeVisible();
    expect((await executor.executions()).length).toBe(1);
    const firstPrompt = (await executor.executions())[0].packet!;
    expect(
      firstPrompt.context.sources.some(
        (source: { kind: string }) => source.kind === 'repository_file',
      ),
    ).toBe(true);
    await page
      .getByLabel('Уточняющий вопрос', { exact: true })
      .fill('Как провести эту проверку за один подход?');
    await page.getByRole('button', { name: 'Подготовить уточнение', exact: true }).click();
    await page.getByRole('checkbox', { name: /Я просмотрел контекст/ }).check();
    await page.getByRole('button', { name: 'Запустить Codex', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Как провести эту проверку за один подход?', exact: true }),
    ).toBeVisible();
    await expect(page.locator('.analysis-reading')).toHaveCount(2);
    expect((await executor.executions()).length).toBe(2);
    await expect(page.getByLabel('Уточняющий вопрос', { exact: true })).toHaveValue('');
    expect((await executor.executions())[1].packet?.conversation).toHaveLength(1);
    await runtime.close();
    runtime = await startHttp(data, { port, codex: executor.options });
    await page.reload();
    await expect(
      page.getByRole('heading', { name: 'Как провести эту проверку за один подход?', exact: true }),
    ).toBeVisible();
    await expect(page.locator('.analysis-reading')).toHaveCount(2);
    await expect(page.getByLabel('Уточняющий вопрос', { exact: true })).toHaveValue('');
    expect((await executor.executions()).length).toBe(2);
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.getByLabel('Уточняющий вопрос', { exact: true }).scrollIntoViewIfNeeded();
      await page.screenshot({ path: `.local/work/embedded-codex-ui/discussion-${width}.png` });
    }
    await page.goto(`${base}/analysis?project=${project.id}`);
    await expect(saved.getByRole('link', { name: firstQuestion, exact: true })).toHaveCount(1);
    await expect(
      saved.getByText('Как провести эту проверку за один подход?', { exact: true }),
    ).toHaveCount(0);
    await executor.update({ hold: true });
    const finalQuestion = 'Новый синтетический вопрос для проверки остановки.';
    await question.fill(finalQuestion);
    let firstPreparedId: string | null = null;
    await page.route('**/api/codex-analysis/prepare', async (route) => {
      const response = await route.fetch();
      const prepared = await response.json();
      if (!firstPreparedId) {
        firstPreparedId = prepared.id;
        await route.fulfill({
          status: 503,
          json: { error: 'Не удалось получить подтверждение подготовки' },
        });
      } else {
        expect(prepared.id).toBe(firstPreparedId);
        await route.fulfill({ response });
      }
    });
    await page.getByRole('button', { name: 'Просмотреть запрос', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText(
      'Не удалось получить подтверждение подготовки',
    );
    await expect(question).toHaveValue(finalQuestion);
    expect((await executor.executions()).length).toBe(2);
    await page.getByRole('button', { name: 'Просмотреть запрос', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Просмотр запроса Codex' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Запустить Codex' })).toBeDisabled();
    expect((await executor.executions()).length).toBe(2);
    await page.getByRole('checkbox', { name: /Я просмотрел контекст/ }).check();
    await page.getByRole('button', { name: 'Запустить Codex', exact: true }).click();
    await expect.poll(async () => (await executor.executions()).length).toBe(3);
    await page.getByRole('button', { name: 'Остановить анализ', exact: true }).click();
    await expect(page.getByText('Результат неизвестен', { exact: true })).toBeVisible();
    expect((await expectJson<Workspace>(runtime.request('/api/workspace'))).tasks).toHaveLength(0);
  } finally {
    await runtime.close();
    await f.cleanup();
  }
});
