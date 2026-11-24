import { test, expect } from '@playwright/test';
import { DatabaseSync } from 'node:sqlite';
import type { Project, Task } from '../../shared/contracts';
import { expectJson, json, startHttp } from '../java/runtime';
import { seedProjects } from './java-fixture';
import { mkdir } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { fixture } from '../fixtures';

test('project closure: explanation, open tasks, retry, restart, history and reopening on desktop/mobile', async ({
  page,
}) => {
  const f = await fixture();
  const repo = await f.repo('garden-experiment');
  const data = path.join(f.root, 'database');
  let runtime = await startHttp(data);
  const base = runtime.base;
  const port = Number(new URL(base).port);
  const lifecycleCount = () => {
    const connection = new DatabaseSync(path.join(data, 'control-center.sqlite'), {
      readOnly: true,
    });
    try {
      return connection.prepare('SELECT COUNT(*) AS n FROM project_lifecycle_events').get()?.n;
    } finally {
      connection.close();
    }
  };
  try {
    const [project] = await seedProjects(runtime, [repo]);
    await expectJson(
      runtime.request(
        `/api/projects/${project.id}`,
        json('PUT', {
          metadata: { ...project.metadata, status: 'active' },
          notes: '',
        }),
      ),
    );
    const task = await expectJson<Task>(
      runtime.request(
        '/api/tasks',
        json('POST', {
          title: 'Проверить всходы',
          projectId: project.id,
          result: {
            summary: 'Первый этап проверки сохранён',
            remaining: 'Проверить ещё один образец',
          },
        }),
      ),
      201,
    );
    await page.goto(`${base}/projects/${project.id}`);
    await page.getByRole('button', { name: 'Действия проекта', exact: true }).click();
    await page.getByRole('menuitem', { name: 'Закрыть проект', exact: true }).click();
    const form = page.getByRole('form', { name: 'Закрытие проекта', exact: true });
    await form.getByRole('button', { name: 'Отмена', exact: true }).click();
    expect(lifecycleCount()).toBe(0);
    await page.getByRole('button', { name: 'Действия проекта', exact: true }).click();
    await page.getByRole('menuitem', { name: 'Закрыть проект', exact: true }).click();
    await expect(form).toContainText('Проверить всходы');
    await expect(form).toContainText('будут отменены');
    await form.getByLabel('Больше не развиваю', { exact: true }).focus();
    await page.keyboard.press('Space');
    await expect(form.getByLabel('Больше не развиваю', { exact: true })).toBeChecked();
    const reason =
      'Проверка помогла выбрать другой способ. Продолжать этот эксперимент не планирую.';
    await form.getByLabel('Пояснение', { exact: true }).fill(reason);
    page.once('dialog', (dialog) => dialog.dismiss());
    await form.getByRole('button', { name: 'Отмена', exact: true }).click();
    await expect(form.getByLabel('Пояснение', { exact: true })).toHaveValue(reason);
    const navToggle = page.getByRole('button', { name: 'Меню', exact: true });
    if (await navToggle.isVisible()) await navToggle.click();
    await page
      .getByRole('navigation', { name: 'Основная навигация' })
      .getByRole('link', { name: 'Проекты', exact: true })
      .click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: 'Продолжить редактирование', exact: true }).click();
    await expect(form.getByLabel('Пояснение', { exact: true })).toHaveValue(reason);
    let fail = true;
    await page.route('**/api/projects/*/close', async (route) => {
      if (fail) {
        fail = false;
        await route.fulfill({ status: 503, json: { error: 'Временная ошибка сохранения' } });
      } else await route.continue();
    });
    await form.getByRole('button', { name: 'Подтвердить закрытие', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Временная ошибка сохранения');
    await expect(form.getByLabel('Пояснение', { exact: true })).toHaveValue(reason);
    await mkdir('.local/project-closure/screenshots', {
      recursive: true,
    });
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/project-closure/screenshots/form-${width}.png`,
        fullPage: true,
      });
    }
    await form.getByRole('button', { name: 'Подтвердить закрытие', exact: true }).click();
    const outcome = page.getByRole('region', { name: 'Итог проекта', exact: true });
    await expect(outcome).toContainText(reason);
    await expect(outcome).toContainText('Больше не развиваю');
    await expect(form).toHaveCount(0);
    expect(lifecycleCount()).toBe(1);
    await runtime.close();
    runtime = await startHttp(data, { port });
    await page.reload();
    await expect(outcome).toContainText(reason);
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/project-closure/screenshots/closed-${width}.png`,
        fullPage: true,
      });
    }
    await page.getByRole('button', { name: 'Действия проекта', exact: true }).click();
    await page.getByRole('menuitem', { name: 'Вернуть в работу', exact: true }).click();
    await page.getByRole('button', { name: 'Подтвердить возврат', exact: true }).focus();
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: 'Действия проекта', exact: true }).click();
    await expect(page.getByRole('menuitem', { name: 'Закрыть проект', exact: true })).toBeVisible();
    await page.getByRole('menuitem', { name: 'Закрыть проект', exact: true }).click();
    await form.getByLabel('Завершён', { exact: true }).check();
    await form
      .getByLabel('Пояснение', { exact: true })
      .fill('Цель достигнута после повторной проверки.');
    await form.getByRole('button', { name: 'Подтвердить закрытие', exact: true }).click();
    await expect(outcome).toContainText('Цель достигнута');
    await page.goto(`${base}/projects`);
    await page.getByRole('combobox', { name: 'Статус проекта' }).selectOption('closed');
    await expect(
      page.getByRole('heading', { name: project.metadata.name, exact: true }),
    ).toBeVisible();
    await page.goto(`${base}/timeline?project=${project.id}&kind=project`);
    await expect(page.getByText(reason, { exact: true })).toBeVisible();
    await expect(
      page.getByText('Цель достигнута после повторной проверки.', { exact: true }),
    ).toBeVisible();
    await page
      .locator('.history-record')
      .filter({ hasText: reason })
      .getByRole('link', { name: 'Открыть источник →', exact: true })
      .click();
    await expect(page.getByRole('article', { name: 'Сохранённое событие проекта' })).toContainText(
      reason,
    );
    await navToggle.click();
    await expect(
      page
        .getByRole('navigation', { name: 'Основная навигация' })
        .getByRole('link', { name: 'История', exact: true }),
    ).toHaveAttribute('aria-current', 'page');
    await page.keyboard.press('Escape');
    const saved = await expectJson<Task>(runtime.request(`/api/tasks/${task.id}`));
    expect(saved.completedAt).toBe(null);
    expect(saved.cancellation?.projectEventId).toBeTruthy();
    expect(saved.revision).toBe(task.revision + 1);
    await page.goto(`${base}/tasks?project=${project.id}`);
    await expect(page.getByRole('region', { name: 'Список задач' })).not.toContainText(task.title);
    await page
      .getByRole('combobox', { name: 'Состояние задач', exact: true })
      .selectOption('cancelled');
    const card = page.getByRole('article').filter({ hasText: task.title });
    await card.getByRole('button', { name: task.title, exact: true }).click();
    const panel = page.getByRole('complementary', { name: 'Подробности задачи' });
    await expect(panel).toContainText('Отменена при закрытии проекта');
    await expect(panel).toContainText(task.result!.summary);
    await expect(panel).toContainText(task.result!.remaining);
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: `.local/project-closure/screenshots/cancelled-tasks-${width}.png`,
        fullPage: true,
      });
    }
    await panel.getByRole('button', { name: 'Вернуть', exact: true }).click();
    await panel.getByRole('button', { name: '← К списку задач' }).click();
    await page.getByRole('combobox', { name: 'Состояние задач', exact: true }).selectOption('open');
    await expect(card).toBeVisible();
    await page.goto(`${base}/history?task=${task.code}`);
    await expect(page.getByText('Задача отменена', { exact: true })).toBeVisible();
    await expect(page.getByText('Возвращена в работу', { exact: true })).toBeVisible();
    // A newer lifecycle revision must require explicit reconciliation and preserve the draft.
    await page.goto(`${base}/projects/${project.id}`);
    await page.getByRole('button', { name: 'Действия проекта', exact: true }).click();
    await page.getByRole('menuitem', { name: 'Изменить сводку', exact: true }).click();
    await page.getByLabel('Зачем существует', { exact: true }).fill('Несохранённое пояснение');
    const reopened = await page.request.post(`${base}/api/projects/${project.id}/reopen`, {
      headers: { 'X-Control-Center': '1' },
      data: { revision: 3, requestKey: randomUUID() },
    });
    expect(reopened.ok()).toBe(true);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.getByRole('button', { name: 'Действия проекта', exact: true }).click();
    await expect(page.getByRole('menuitem', { name: 'Закрыть проект', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    const saveSummary = page.getByRole('button', { name: 'Сохранить сводку', exact: true });
    await expect(saveSummary).toBeDisabled();
    await expect(page.getByLabel('Зачем существует', { exact: true })).toHaveValue(
      'Несохранённое пояснение',
    );
    expect(
      (await expectJson<Project>(runtime.request(`/api/projects/${project.id}`))).metadata.status,
    ).toBe('active');
    await page.getByRole('button', { name: 'Обновить сводку, сохранив мой ввод' }).click();
    await expect(page.getByRole('combobox', { name: 'Статус', exact: true })).toHaveValue('active');
    await saveSummary.click();
    await expect(page.getByText('Сводка сохранена в дневнике', { exact: true })).toBeVisible();
    expect(
      (await expectJson<Project>(runtime.request(`/api/projects/${project.id}`))).metadata.status,
    ).toBe('active');
  } finally {
    await runtime.close();
    await f.cleanup();
  }
});
