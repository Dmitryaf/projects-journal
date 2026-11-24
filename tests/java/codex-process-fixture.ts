import { randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { protocolScript } from '../codex-protocol-fixture.js';

const protocol = protocolScript
  .replace("'codex-cli 2.42.7'", "config.legacyProtocol ? 'codex-cli 2.42.6' : 'codex-cli 2.42.7'")
  .replace(
    "['cwd','useStateDbOnly','cursor','limit']",
    "['cwd','cursor','limit', ...(config.legacyProtocol ? [] : ['useStateDbOnly'])]",
  );
const script = String.raw`
const fs = require('node:fs');
const readline = require('node:readline');
const configPath = process.argv[2];
const current = () => JSON.parse(fs.readFileSync(configPath, 'utf8'));
const config = current();
if (!config.available) process.exit(2);
const args = process.argv.slice(3);
const log = (event) => fs.appendFileSync(config.log, JSON.stringify({ pid: process.pid, ...event }) + '\n');
const emit = (value) => console.log(JSON.stringify(value));
${protocol}
if (args.includes('--help')) {
  console.log('--ignore-user-config --ephemeral --output-schema --json --sandbox');
} else if (args.includes('features')) {
  args.forEach((value, index) => {
    if (value === '--disable') console.log(args[index + 1] + ' stable false');
  });
} else if (args[0] === 'login') {
  if (args[1] === 'status') process.exit(config.authenticated ? 0 : 1);
  if (args[1] !== '--device-auth') process.exit(2);
  log({ kind: 'login', cwd: process.cwd(), home: process.env.CODEX_HOME, inherited: process.env.TEST_PRIVATE_TOKEN ?? null });
  console.log('https://auth.openai.com/codex/device');
  console.log('ABCD-EFGH');
  const timer = setInterval(() => { if (current().authenticated) process.exit(0); }, 25);
  setTimeout(() => clearInterval(timer), 12_000);
} else if (args[0] === 'exec') {
  let prompt = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => { prompt += chunk; });
  process.stdin.on('end', () => {
    if (!prompt) return;
    const packet = JSON.parse(prompt.slice(prompt.lastIndexOf('\n') + 1));
    const schema = JSON.parse(fs.readFileSync(args[args.indexOf('--output-schema') + 1], 'utf8'));
    log({ kind: 'exec', args, cwd: process.cwd(), home: process.env.CODEX_HOME, userProfile: process.env.USERPROFILE,
      unixHome: process.env.HOME, inherited: process.env.TEST_PRIVATE_TOKEN ?? null, schema, packet });
    console.error('FIXTURE_DIAGNOSTIC_DO_NOT_EXPOSE');
    emit({ type: 'thread.started', thread_id: 'fixture-codex-execution' });
    emit({ type: 'turn.started' });
    if (prompt.includes('fixture-hold')) {
      setTimeout(() => {}, 12_000);
      return;
    }
    if (prompt.includes('fixture-tool')) {
      emit({ type: 'item.started', item: { type: 'command_execution', command: 'fixture unused command' } });
      return;
    }
    const finish = () => {
      const narrative = !config.browserReport
        ? 'Синтетический анализ.'
        : packet.conversation.length
          ? 'Попроси одного человека записать наблюдение и вернуться к нему позже. Посмотри, сможет ли он найти запись без подсказки.'
          : 'Я бы сначала проверил, легко ли вернуться к прошлой записи. Записать наблюдение — только полдела: оно пригодится, когда человек в следующий раз откроет дневник.';
      const report = { narrative, findings: [], uncertainties: [], proposals: [] };
      emit({ type: 'item.completed', item: { type: 'agent_message', text: JSON.stringify(report) } });
      emit({ type: 'turn.completed', usage: { input_tokens: 23, output_tokens: 13 } });
    };
    if (!config.hold) return finish();
    const hold = setInterval(() => {
      if (!fs.existsSync(configPath + '.release-' + process.pid)) return;
      clearInterval(hold);
      finish();
    }, 25);
    setTimeout(() => clearInterval(hold), 30_000).unref();
  });
} else {
  if (args.join(' ') !== 'app-server --listen stdio://') process.exit(2);
  const thread = { id: config.threadId, cwd: config.cwd, name: config.name,
    updatedAt: config.updatedAt, createdAt: 1709726400, turns: [], path: 'FIXTURE_PATH_DO_NOT_EXPOSE',
    preview: 'FIXTURE_PREVIEW_DO_NOT_EXPOSE' };
  const lines = readline.createInterface({ input: process.stdin });
  lines.on('line', (line) => {
    const message = JSON.parse(line);
    log({ kind: 'rpc', method: message.method, params: message.params, error: message.error });
    let result;
    switch (message.method) {
      case 'initialize':
        emit({ id: 'fixture-approval', method: 'item/commandExecution/requestApproval', params: {} });
        result = { userAgent: 'fixture-app-server' }; break;
      case 'initialized': return;
      case 'thread/list':
        if (!message.params.useStateDbOnly) process.exit(3);
        result = { data: [thread], nextCursor: null }; break;
      case 'thread/read': result = { thread }; break;
      case 'thread/turns/list':
        result = { data: [{ id: config.turnId, status: 'completed', itemsView: 'notLoaded',
          startedAt: 1709726400, items: [] }], nextCursor: null };
        break;
      case 'thread/items/list':
        result = { data: [
          { turnId: config.turnId, item: { type: 'userMessage', id: 'user-1', content: [
            { type: 'text', text: config.text, text_elements: [] },
            { type: 'localImage', path: 'FIXTURE_ATTACHMENT_DO_NOT_EXPOSE' },
          ] } },
          { turnId: config.turnId, item: { type: 'commandExecution', id: 'command-1',
            command: 'FIXTURE_COMMAND_DO_NOT_EXPOSE' } },
          { turnId: config.turnId, item: { type: 'agentMessage', id: 'assistant-1',
            phase: 'final_answer', text: 'Синтетический ответ, требующий проверки.' } },
        ], nextCursor: null };
        break;
      default: return;
    }
    emit({ id: message.id, result });
  });
}
`;

interface FixtureEvent {
  kind: 'rpc' | 'exec' | 'login';
  pid: number;
  method?: string;
  params?: { useStateDbOnly?: boolean; cwd?: string[] };
  error?: { code: number };
  cwd?: string;
  home?: string;
  unixHome?: string;
  userProfile?: string;
  inherited?: string | null;
  args?: string[];
  schema?: { properties: Record<string, unknown> };
  packet?: {
    question: string;
    conversation: unknown[];
    context: { sources: { kind: string; title: string; text: string }[] };
  };
}

export async function codexProcessFixture(root: string, cwd: string) {
  const scriptPath = path.join(root, 'codex-fixture.cjs');
  const config = path.join(root, 'codex-fixture.json');
  const log = path.join(root, 'codex-fixture-events.ndjson');
  const state = {
    cwd,
    log,
    threadId: randomUUID(),
    turnId: randomUUID(),
    authenticated: true,
    available: true,
    legacyProtocol: false,
    name: 'Синтетический диалог',
    updatedAt: 1709899200,
    browserReport: false,
    hold: false,
    text: 'Проверю синтетический пример в дневнике.',
  };
  await writeFile(scriptPath, script);
  await writeFile(log, '');
  const update = async (next: Partial<typeof state> = {}) => {
    await writeFile(config, JSON.stringify(Object.assign(state, next)));
  };
  const events = async () =>
    (await readFile(log, 'utf8'))
      .split('\n')
      .filter(Boolean)
      .map((event) => JSON.parse(event) as FixtureEvent);
  const executions = async () => (await events()).filter((event) => event.kind === 'exec');
  await update();
  return {
    options: { script: scriptPath, config },
    state,
    update,
    events,
    executions,
    loginCount: async () => (await events()).filter((event) => event.kind === 'login').length,
    release: (pid: number) => writeFile(`${config}.release-${pid}`, ''),
  };
}
