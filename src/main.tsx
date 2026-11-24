import { createRoot } from 'react-dom/client';
import type { ComponentType } from 'react';
import { createBrowserRouter, RouterProvider, Navigate } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ActionProvider } from './shared/workspace';
import App from './App';
import Overview from './features/overview/Overview';
import './style.css';
import './styles/components.css';

const loadPage = (load: () => Promise<{ default: ComponentType }>) => async () => ({
  Component: (await load()).default,
});

const router = createBrowserRouter([
  {
    element: <App />,
    hydrateFallbackElement: (
      <main>
        <p role="status">Загружаем рабочее пространство…</p>
      </main>
    ),
    children: [
      { path: '/', element: <Overview /> },
      { path: '/projects', lazy: loadPage(() => import('./features/projects/Projects')) },
      { path: '/projects/:id', lazy: loadPage(() => import('./features/projects/ProjectDetail')) },
      {
        path: '/project-lifecycle/:id',
        lazy: loadPage(() => import('./features/projects/ProjectLifecycleHistory')),
      },
      { path: '/tasks', lazy: loadPage(() => import('./features/tasks/Tasks')) },
      {
        path: '/history',
        lazy: async () => ({ Component: (await import('./features/tasks/Tasks')).TaskHistory }),
      },
      { path: '/timeline', lazy: loadPage(() => import('./features/history/History')) },
      {
        path: '/observations',
        lazy: loadPage(() => import('./features/observations/Observations')),
      },
      { path: '/reviews', lazy: loadPage(() => import('./features/reviews/Reviews')) },
      {
        path: '/period-analysis',
        lazy: loadPage(() => import('./features/period-analysis/PeriodAnalysis')),
      },
      {
        path: '/analysis-schedules',
        lazy: loadPage(() => import('./features/period-analysis/Schedules')),
      },
      {
        path: '/period-proposal',
        lazy: async () => ({
          Component: (await import('./features/period-analysis/Proposals')).Proposal,
        }),
      },
      {
        path: '/codex-context',
        lazy: loadPage(() => import('./features/codex-context/CodexContext')),
      },
      {
        path: '/proposal-acceptances',
        lazy: loadPage(() => import('./features/period-analysis/Proposals')),
      },
      { path: '/checks', lazy: loadPage(() => import('./features/checks/Checks')) },
      {
        path: '/checks/:id',
        lazy: async () => ({ Component: (await import('./features/checks/Checks')).CheckDetail }),
      },
      { path: '/ideas/:id?', lazy: loadPage(() => import('./features/records/Ideas')) },
      {
        path: '/decisions',
        lazy: async () => ({ Component: (await import('./features/records/Records')).Decisions }),
      },
      { path: '/analysis', lazy: loadPage(() => import('./features/analysis/Analysis')) },
      {
        path: '/codex-analysis',
        lazy: loadPage(() => import('./features/codex-analysis/CodexAnalysis')),
      },
      { path: '/settings', lazy: loadPage(() => import('./features/settings/Settings')) },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]);
const client = new QueryClient({
  defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
});
createRoot(document.getElementById('app')!).render(
  <QueryClientProvider client={client}>
    <ActionProvider>
      <RouterProvider router={router} />
    </ActionProvider>
  </QueryClientProvider>,
);
