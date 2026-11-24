# Setup

## Get started

Install JDK 25, Node.js 22.17+ and Git. Point `JAVA_HOME` to the JDK, or put `java` on `PATH`. From the project checkout:

```sh
npm ci
npm run build
npm start
```

In PowerShell use `npm.cmd`. Open [http://127.0.0.1:4310](http://127.0.0.1:4310). Add the folders containing your projects in Settings and scan. Open a project, describe its purpose and add a task or observation.

The Maven Wrapper uses JDK 25 to build the backend. For a checkout-specific JDK, set `CONTROL_CENTER_JAVA_HOME` or put the JDK directory on one line in the ignored `.local/java-home.txt` file. The launchers check `JAVA_HOME`, `CONTROL_CENTER_JAVA_HOME`, the hint file and then `PATH`.

## Development and MCP

`npm run dev` launches Java and rebuilds the frontend when files change. Reload the browser to see the new build. Restart the command after changing Java source.

For stdio MCP, build first and configure the client to run `java --enable-native-access=ALL-UNNAMED -jar <absolute-path-to-jar> --mcp`. The build writes `backend/target/projects-journal-backend.jar`. Use the same `CONTROL_CENTER_DATA_DIR` as the HTTP application when overriding the default location. From the checkout, `npm --silent run mcp` launches the same server.

## Windows shortcut

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/install-shortcut.ps1
```

The **Projects Journal** shortcut starts the packaged app or reuses the running Java process for this checkout and database. Shortcuts created before the rename still work from the original checkout location. Closing the browser leaves the server running. Stop it before rebuilding the JAR on Windows, then reopen the shortcut.

## Analysis

With a compatible Codex CLI installed, open analysis, select projects and period, prepare context and sign in to the separate Projects Journal profile when prompted. Inspect the packet before confirming a question. Opening analysis alone does not dispatch it.

The OpenAI API is a separate optional provider. Its credentials remain in the HTTP server environment. [Provider, context and task access](guides/ANALYSIS.md).

## Data

Windows defaults to `%USERPROFILE%/.control-center/control-center.sqlite`. `CONTROL_CENTER_DATA_DIR` overrides the directory. If an older AppData database exists and the new location is empty, startup asks for recovery rather than creating an apparently empty workspace.

Create a verified backup in Settings. Before moving or replacing a database, stop all HTTP and MCP writers and follow [storage, backup and restore](guides/MAINTENANCE.md#storage-backup-and-restore). Repository files and private context need their own backup.
