import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useForm } from 'react-hook-form';
import type { Decision, DecisionInput } from '../../../shared/contracts';
import { api } from '../../shared/http';
import { today, date, decisionLabels } from '../../shared/labels';
import { useActions, useWorkspace } from '../../shared/workspace';
import { UnsavedChanges } from '../../shared/UnsavedChanges';
import { DecisionFiles } from '../projects/ProjectDetail';
export function Decisions() {
  const { data: w } = useWorkspace();
  const { act, busy } = useActions();
  const [params, setParams] = useSearchParams();
  const projectId = params.get('project') ?? undefined;
  const view =
    params.get('view') === 'archive'
      ? 'archive'
      : params.get('view') === 'current'
        ? 'current'
        : 'all';
  const decisions = w!.decisions.filter(
    (decision) =>
      (!projectId || decision.projectId === projectId) &&
      (view === 'all' ||
        (view === 'archive' ? decision.status === 'superseded' : decision.status !== 'superseded')),
  );
  const [id, setId] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const empty: DecisionInput = {
    title: '',
    context: '',
    decision: '',
    reason: '',
    projectId: projectId ?? null,
    date: today(),
    status: 'active',
  };
  const {
    register,
    handleSubmit,
    reset,
    formState: { isDirty },
  } = useForm<DecisionInput>({ defaultValues: empty });
  return (
    <>
      <UnsavedChanges dirty={isDirty} />
      <div className="page-heading">
        <div>
          <h1>Решения</h1>
          {projectId && (
            <Link to={`/projects/${projectId}`}>
              {w!.projects.find((project) => project.id === projectId)?.metadata.name ??
                'Открыть проект'}
            </Link>
          )}
        </div>
        {!formOpen && (
          <button
            className="primary"
            onClick={() => {
              reset(empty);
              setFormOpen(true);
            }}
          >
            Новое решение
          </button>
        )}
      </div>
      <div className="row">
        <label>
          Показать решения
          <select
            value={view}
            onChange={(event) => {
              const next = new URLSearchParams(params);
              next.set('view', event.currentTarget.value);
              setParams(next);
            }}
          >
            <option value="all">Все решения</option>
            <option value="current">Текущие</option>
            <option value="archive">Архив решений</option>
          </select>
        </label>
      </div>
      {formOpen && (
        <form
          className="panel form"
          onSubmit={handleSubmit(async (v) => {
            if (
              await act(
                () =>
                  api(id ? `/decisions/${id}` : '/decisions', id ? 'PUT' : 'POST', {
                    ...v,
                    projectId: v.projectId || null,
                  }),
                'Решение сохранено',
              )
            ) {
              reset(empty);
              setId(null);
              setFormOpen(false);
            }
          })}
        >
          <h2>{id ? 'Изменить решение' : 'Записать решение'}</h2>
          <label>
            Название решения
            <input autoFocus maxLength={240} required {...register('title')} />
          </label>
          {(
            [
              ['context', 'Контекст'],
              ['decision', 'Решение'],
              ['reason', 'Почему'],
            ] as const
          ).map(([k, l]) => (
            <label key={k}>
              {l}
              <textarea rows={3} required={k === 'decision'} {...register(k)} />
            </label>
          ))}
          <div className="form-grid">
            {id && (
              <label>
                Проект
                <select {...register('projectId')}>
                  <option value="">Без проекта</option>
                  {w!.projects.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.metadata.name}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <label>
              Статус
              <select {...register('status')}>
                {Object.entries(decisionLabels).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Дата
              <input type="date" required {...register('date')} />
            </label>
          </div>
          <div className="row">
            <button className="primary" disabled={busy}>
              {id ? 'Сохранить решение' : 'Добавить решение'}
            </button>
            <button
              type="button"
              onClick={() => {
                reset(empty);
                setId(null);
                setFormOpen(false);
              }}
            >
              Отмена
            </button>
          </div>
        </form>
      )}
      <div className="section-heading section-space">
        <h2>
          Записанные решения <span className="count">{decisions.length}</span>
        </h2>
      </div>
      <div className="record-list">
        {decisions.map((d: Decision) => (
          <article id={`decision-${d.id}`} className="panel prose" key={d.id}>
            <div className="row between">
              {d.projectId ? (
                <Link to={`/projects/${d.projectId}`}>
                  {w!.projects.find((p) => p.id === d.projectId)?.metadata.name}
                </Link>
              ) : (
                <span>Без проекта</span>
              )}
              <span className="badge">{decisionLabels[d.status]}</span>
            </div>
            <h3>{d.title}</h3>
            <p className="section-label">
              {d.projectId ? 'Локальное решение Control Center' : 'Общее решение экосистемы'}
            </p>
            <p className="muted preserve">{d.context}</p>
            <p className="preserve">{d.decision}</p>
            {d.reason && (
              <>
                <h4>Почему</h4>
                <p className="preserve">{d.reason}</p>
              </>
            )}
            <div className="row between">
              <small>{date(d.date)}</small>
              <button
                onClick={() => {
                  if (isDirty && !confirm('Отбросить несохранённые изменения?')) return;
                  setFormOpen(true);
                  reset({ ...d });
                  setId(d.id);
                  window.scrollTo({ top: 0 });
                }}
              >
                Изменить
              </button>
              <button
                disabled={busy}
                onClick={async () => {
                  if (
                    confirm(`Удалить «${d.title}»?`) &&
                    (await act(() => api(`/decisions/${d.id}`, 'DELETE'), 'Запись удалена'))
                  ) {
                    if (id === d.id) {
                      reset(empty);
                      setId(null);
                      setFormOpen(false);
                    }
                  }
                }}
              >
                Удалить
              </button>
            </div>
          </article>
        ))}
      </div>
      {!decisions.length && <p className="empty">В этой выборке записанных решений нет.</p>}
      <DecisionFiles projectId={projectId} view={view} />
    </>
  );
}
