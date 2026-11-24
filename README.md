# Projects Journal

A local project journal for keeping track of what happened, why you made decisions, and what comes next. The interface is in Russian.

## Current workflows

- Discover local repositories and keep a project summary, goal, stage and focus.
- Create numbered tasks, link one task to several projects, record results and inspect its history.
- Save ideas and observations; read project decisions from their original files.
- Track a question, external actions, evidence and a conclusion in a check.
- Close or return a project. Closing cancels open tasks only when they have no other open project. Completed results and history stay available in the archive.
- Review one or several projects, discuss the prepared context with Codex or use the OpenAI API, and explicitly accept useful proposals.
- Enable separate opt-in weekly/monthly review schedules while the HTTP server is running.

Project pages show their purpose, tasks, history and latest meaningful review. Removing a project from this catalogue does not delete its repository.

![Project overview](assets/screenshots/overview.png)
![Tasks](assets/screenshots/tasks.png)
![Project analysis](assets/screenshots/analysis.png)

## Start

Use JDK 25, Node.js 22.17+ and Git. From the checkout:

```sh
npm ci
npm run build
npm start
```

Open [Projects Journal](http://127.0.0.1:4310), add project folders in Settings and scan them. [Setup](SETUP.md) covers the Windows shortcut and first connection. [Analysis](guides/ANALYSIS.md) covers context and consent; [maintenance](guides/MAINTENANCE.md) covers storage, recovery, architecture and checks.

The application uses React, TypeScript, Java, Spring Boot and SQLite. It runs on loopback and stores data locally.
