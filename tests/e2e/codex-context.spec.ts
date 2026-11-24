import { test, expect } from '@playwright/test';
import path from 'node:path';
import { mkdir } from 'node:fs/promises';
import { fixture } from '../fixtures';
import { codexProcessFixture } from '../java/codex-process-fixture';
import { expectJson, json, startHttp } from '../java/runtime';
import { seedProjects } from './java-fixture';
import type { CodexDigest } from '../../shared/codex-context';
import type { Review } from '../../shared/reviews';
import type { PeriodRun } from '../../shared/period-analysis';

test('project opt-in → selected dialog → protected summary/excerpts → real saved AI packet; unavailable CLI and stale source stay explicit', async ({
  page,
}) => {
  const f = await fixture();
  const repo = await f.repo('dialog-context-project');
  const fake = await codexProcessFixture(f.root, repo);
  await fake.update({
    available: false,
    name: 'Содержательный диалог',
    text: '<b>Намерение, не результат</b> ' + 'Длинная проверяемая формулировка. '.repeat(60),
  });
  const runtime = await startHttp(path.join(f.root, 'database'), { codex: fake.options });
  const { base, request } = runtime;
  const list = async (reviewId: string) =>
    (
      await expectJson<{ items: CodexDigest[] }>(
        request(`/api/codex-context/digests?reviewId=${reviewId}`),
      )
    ).items;
  const read = (id: string) => expectJson<CodexDigest>(request(`/api/codex-context/digests/${id}`));
  try {
    const [project] = await seedProjects(runtime, [repo]);
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
    await page.goto(`${base}/codex-context?review=${review.id}&project=${project.id}`);
    await expect(page.getByRole('button', { name: 'Показать диалоги проекта' })).toBeDisabled();
    expect(await fake.events()).toHaveLength(0);
    await page
      .getByLabel('Разрешаю чтение списка и выбранных диалогов только этого проекта', {
        exact: false,
      })
      .check();
    await page.getByRole('button', { name: 'Разрешить чтение проекта' }).click();
    expect(await fake.events()).toHaveLength(0);
    await page.getByRole('button', { name: 'Проверить CLI без чтения диалогов' }).click();
    await expect(
      page.getByText('Codex недоступен, ответ слишком велик или протокол не поддерживается.', {
        exact: false,
      }),
    ).toBeVisible();
    await fake.update({ available: true });
    let lose = true;
    await page.route(`**/api/projects/${project.id}/codex-context/digests`, async (route) => {
      const response = await route.fetch();
      expect(response.status()).toBe(201);
      if (lose) {
        lose = false;
        await route.fulfill({ status: 503, json: { error: 'Fixture lost digest response' } });
      } else {
        await route.fulfill({ response });
      }
    });
    await page.getByRole('button', { name: 'Проверить CLI без чтения диалогов' }).click();
    await expect(page.getByText('Версия CLI: 2.42.7.', { exact: false })).toBeVisible();
    await page.getByRole('button', { name: 'Показать диалоги проекта' }).click();
    await expect(page.getByRole('heading', { name: 'Содержательный диалог' })).toBeVisible();
    await page.getByRole('button', { name: 'Просмотреть текст за период' }).click();
    await page
      .getByLabel('Краткий итог — можно исправить', { exact: true })
      .fill('Сформулировано намерение; результат требует отдельной проверки.');
    await page
      .getByRole('navigation', { name: 'Основная навигация' })
      .getByRole('link', { name: 'История', exact: true })
      .click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: 'Продолжить редактирование' }).click();
    await expect(page.getByLabel('Краткий итог — можно исправить')).toHaveValue(
      'Сформулировано намерение; результат требует отдельной проверки.',
    );
    await expect(page.getByRole('button', { name: 'Сохранить итог диалога' })).toBeDisabled();
    await page.getByRole('checkbox', { name: /Пользователь ·/ }).check();
    await page.getByRole('checkbox', { name: /Ассистент ·/ }).uncheck();
    await page
      .getByLabel('Я просмотрел текст и разрешаю сохранить мой итог', { exact: false })
      .check();
    await mkdir('.local/codex-context-review', { recursive: true });
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/codex-context-review/capture-${width}.png`,
        fullPage: true,
      });
    }
    await expect(page.locator('b')).toHaveCount(0);
    await page.getByRole('button', { name: 'Сохранить итог диалога' }).click();
    await expect(page.getByText('Fixture lost digest response')).toBeVisible();
    await expect(page.getByLabel('Краткий итог — можно исправить')).toHaveValue(
      'Сформулировано намерение; результат требует отдельной проверки.',
    );
    const calls = (await fake.events()).length;
    await page.getByRole('button', { name: 'Сохранить итог диалога' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('Краткий итог сохранён.', { exact: false })).toBeVisible();
    expect((await fake.events()).length).toBe(calls);
    const saved = (await list(review.id))[0];
    expect(saved.excerpts).toHaveLength(1);
    expect(JSON.stringify(saved)).not.toContain('DO_NOT_PERSIST');
    await page.getByRole('link', { name: 'К ручному AI-анализу →' }).click();
    await page
      .getByRole('checkbox', { name: /dialog-context-project · Содержательный диалог/ })
      .check();
    await page.getByLabel('Разрешаю прочитать выбранные источники', { exact: false }).check();
    await page.getByRole('button', { name: 'Подготовить точный пакет' }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get('run')).toBeTruthy();
    const runId = new URL(page.url()).searchParams.get('run')!;
    const run = await expectJson<PeriodRun>(request(`/api/period-analysis/${runId}`));
    expect(run.payload.sources).toHaveLength(1);
    expect(run.payload.sources[0].kind).toBe('codex_digest');
    expect(run.payload.sources[0].text).toContain(saved.summary);
    expect(run.payload.sources[0].text).not.toContain('Предложение: сначала');
    await page.goto(`${base}/codex-context?review=${review.id}&project=${project.id}`);
    await fake.update({ text: 'Изменённый текст', updatedAt: fake.state.updatedAt + 1 });
    await page.getByRole('button', { name: 'Проверить изменения диалога' }).click();
    await expect(
      page.getByText('Диалог изменён или не удалось проверить источник.', { exact: false }),
    ).toBeVisible();
    await page.getByRole('button', { name: 'Отключить чтение', exact: true }).click();
    expect((await read(saved.id)).eligible).toBe(false);
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: 'Удалить итог диалога', exact: true }).click();
    await expect(page.getByText('Сохранённых итогов диалогов пока нет.')).toBeVisible();
    const retained = await expectJson<PeriodRun>(request(`/api/period-analysis/${runId}`));
    expect(retained.payload.sources[0].text).toContain(saved.summary);

    // The ordinary path no longer requires a hand-written summary.
    await fake.update({
      text: 'Цель: проверить одну гипотезу, потому что результата пока нет. Это намерение пользователя.',
      updatedAt: Date.parse('2024-03-07T00:00:00Z') / 1000,
    });
    await page.goto(`${base}/period-analysis?review=${review.id}`);
    await page.getByLabel('Разрешаю читать диалоги выбранных проектов', { exact: false }).check();
    await page
      .getByRole('button', { name: 'Подготовить контекст выбранных проектов', exact: true })
      .click();
    const choice = page.getByRole('checkbox', {
      name: /dialog-context-project · Содержательный диалог/,
    });
    await expect(choice).toBeChecked();
    const automatic = (await list(review.id))[0];
    expect(automatic.summary).toContain('Автоматическая выборка');
    expect(automatic.excerpts.length).toBeGreaterThan(0);
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 });
      await expect(
        page.getByRole('button', { name: 'Подготовить контекст выбранных проектов', exact: true }),
      ).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/codex-context-review/automatic-${width}.png`,
        fullPage: true,
      });
    }
    await choice.uncheck();
    await expect(choice).not.toBeChecked();
    await page.goto(`${base}/codex-context?review=${review.id}&project=${project.id}`);
    const digest = page.getByRole('region', { name: 'Сохранённый итог диалога' });
    await digest.getByText('Исправить краткий итог', { exact: true }).click();
    await digest
      .getByLabel('Краткий итог диалога', { exact: true })
      .fill('Поправка владельца: итог обсуждения ещё не проверен.');
    await digest.getByRole('button', { name: 'Сохранить исправление' }).click();
    await expect(digest.locator('p.preserve').first()).toHaveText(
      'Поправка владельца: итог обсуждения ещё не проверен.',
    );
    await page.goto(`${base}/period-analysis?review=${review.id}`);
    await choice.check();
    await fake.update({ text: 'Поздняя поправка: от прежнего решения отказались после проверки.' });
    // Same updatedAt: the content hash, not metadata alone, must invalidate the choice.
    await page.getByLabel('Разрешаю читать диалоги выбранных проектов', { exact: false }).check();
    await page
      .getByRole('button', { name: 'Подготовить контекст выбранных проектов', exact: true })
      .click();
    await expect(choice).not.toBeChecked();
    await expect(choice).toBeDisabled();
    await expect(
      page.getByText('диалог изменился — пересмотрите сохранённый итог', { exact: false }),
    ).toBeVisible();
    expect((await read(automatic.id)).summary).toBe(
      'Поправка владельца: итог обсуждения ещё не проверен.',
    );
    expect((await read(automatic.id)).eligible).toBe(false);
  } finally {
    await runtime.close();
    await f.cleanup();
  }
});
