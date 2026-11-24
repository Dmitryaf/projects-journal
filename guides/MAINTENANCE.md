# Backup and maintenance

## Running and architecture

One Spring Boot process binds to loopback, default `127.0.0.1:4310`. The JAR serves the built React application. Development runs Java with static files rebuilt by Vite on source changes. The optional stdio MCP server uses the same SQLite. `/api/health` identifies the checkout/database instance and Java process.

The Maven module in `backend/` owns HTTP, MCP, SQLite and analysis. `JournalApplication` selects HTTP or stdio MCP. Controllers and MCP tools share the domain services. `shared/` holds frontend schemas and DTOs independent of React and Node. `src/features/` owns screens and `src/shared/` owns shared UI, queries and drafts. Forms protect unsaved changes, and mutations require explicit retries. `scripts/check-boundaries.ts` checks TypeScript import boundaries.

The Windows launcher uses `Projects Journal.vbs` and `scripts/launch.ps1`, verifies instance identity and serializes concurrent starts. It does not stop unrelated processes. Closing the browser leaves the HTTP process running; restart it after backend updates. Logs are in `.local/launcher/`.

## Storage, backup and restore

SQLite stores project identities/snapshots, settings, tasks and immutable result history, observations, reviews, analysis and permissions. The current schema is 16.

| Platform | Default data directory                                             |
| -------- | ------------------------------------------------------------------ |
| Windows  | `%USERPROFILE%/.control-center`                                    |
| macOS    | `~/Library/Application Support/ControlCenter`                      |
| Linux    | `$XDG_DATA_HOME/control-center` or `~/.local/share/control-center` |

`CONTROL_CENTER_DATA_DIR` overrides the directory. Older Windows AppData copies are detected when the new database is missing; startup refuses to hide them behind an empty workspace. Inspect the actual path and choose a verified copy before migration. Retain differing originals.

Create a backup in Settings. It captures committed WAL data and verifies the copy. In the copy, schedule/Codex grants are disabled, pending approvals cancelled and running attempts marked unknown. Successful reports and accepted evidence remain intact. Repository files, private context, dialog archives and separate CLI credentials are outside this backup.

To restore:

1. Record the active database path and stop every HTTP/MCP writer.
2. Preserve the existing database and its matching WAL/SHM together in a recovery directory.
3. Copy the verified backup as `control-center.sqlite` into the chosen data directory; do not attach old WAL/SHM sidecars.
4. For a raw copy outside the verified backup flow, run `npm start -- --restore-recovery` or `npm run dev -- --restore-recovery` before other processes.
5. Inspect records/settings, recover external files separately and renew desired grants explicitly.

Schema upgrades from version 1 onward make a verified pre-upgrade backup and migrate transactionally. Stop old writers before upgrading. A downgrade requires a compatible backup. Restoring a backup replaces the database.

The first Control Center release used `.data/` in its checkout. To upgrade that workspace, set `CONTROL_CENTER_DATA_DIR` to the absolute path of the old data directory before starting the current app.

## Project and task lifecycle

Task completion stores its result/history atomically. Current tasks have no generic `state`; open/completed/cancelled are derived from completion and cancellation fields. Task numbers, identities and historical snapshots persist through reopening.

Closing a project records completed/stopped, an optional explanation and event date. In the same transaction, it cancels open tasks that have no other open project. Shared tasks with another open project remain active, and completed work stays intact. Returning a project and reopening a task are separate actions. Old completed projects retain unknown historical dates rather than invented closure events.

Removing a project deletes its catalogue metadata/notes/relations, detaches records, removes its exact explicit scan root and excludes rediscovery. Shared parent roots and external files remain. Rediscovery creates a new identity. Restore removed local state from an earlier backup.

## Project files and checks

`PROJECT.yaml` is an optional initial summary. Saved metadata take precedence; a changed source hash needs explicit reconciliation. Export is explicit and preserves unknown fields. Relink a moved repository to preserve its identity.

Decisions are read from connected projects' `DECISIONS.md` and `decisions/*.md`. Bodies stay at the source; the metadata index is disposable. Unavailable sources do not return cached bodies. Private context paths and publication allowlists are local settings, excluded from YAML. A Git-index publication check does not audit remote visibility, all history or secrets and never publishes.

Checks distinguish question, external actions, evidence and conclusion. Git activity does not reset the external-action clock; pause suppresses reminders. A period without activity is not itself a failed check.

## Developer checks

```sh
npm run check
npm run test:e2e
npm run test:production
```

`check` runs lint and type checks, React tests, a packaged build and Java integration tests. E2E uses Edge and isolated data. Production smoke tests persistence, backup and restart. Run `npm run eval:period -- prepare <directory>` to freeze synthetic analysis packets, then `npm run eval:period -- review <directory>` to review answers. Both commands use the built Java module.

Run `npm run build` before `npm start`, `npm run test:e2e` or `npm run test:production`. For MCP configuration, see [Setup](../SETUP.md#development-and-mcp). API mutations require matching Host and Origin, JSON and `x-control-center: 1`.
