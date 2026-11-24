import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { javaMcp } from './java/runtime.js';

interface RpcReply {
  id: number;
  result?: unknown;
  error?: { message: string };
}

interface ToolList {
  tools: { name: string }[];
}

interface ToolResult {
  isError?: boolean;
  content: { type: string; text?: string }[];
}

export async function connectMcp(
  dataDirectory: string,
  cwd: string,
  executable = javaMcp,
) {
  const child = spawn(executable.command, executable.args, {
    cwd,
    env: { ...process.env, CONTROL_CENTER_DATA_DIR: dataDirectory },
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
  });
  const replies = new Map<number, (reply: RpcReply | Error) => void>();
  let stderr = '';
  let nextId = 0;
  let disconnected: Error | undefined;

  function disconnect(error: Error) {
    disconnected = error;
    for (const settle of replies.values()) settle(error);
  }

  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk: string) => {
    stderr = (stderr + chunk).slice(-4096);
  });
  child.on('error', disconnect);
  child.stdin.on('error', disconnect);
  const terminated = new Promise<void>((resolve) => {
    child.once('close', (status) => {
      disconnect(new Error(`MCP process closed (${status ?? 'signal'}). ${stderr}`));
      resolve();
    });
  });

  const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });
  lines.on('line', (line) => {
    try {
      const reply = JSON.parse(line) as RpcReply;
      replies.get(reply.id)?.(reply);
    } catch {
      disconnect(new Error('Expected a JSON-RPC response on MCP stdout.'));
    }
  });

  function request<T>(method: string, params: object = {}): Promise<T> {
    if (disconnected) return Promise.reject(disconnected);
    const id = ++nextId;
    return new Promise<T>((resolve, reject) => {
      const timeout = setTimeout(() => {
        replies.delete(id);
        reject(new Error(`Timed out waiting for MCP method ${method}. ${stderr}`));
      }, 15000);
      replies.set(id, (reply) => {
        clearTimeout(timeout);
        replies.delete(id);
        if (reply instanceof Error) reject(reply);
        else if (reply.error) reject(new Error(reply.error.message));
        else resolve(reply.result as T);
      });
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }

  async function close() {
    child.stdin.end();
    const timeout = setTimeout(() => child.kill(), 3000);
    try {
      await terminated;
    } finally {
      clearTimeout(timeout);
      lines.close();
    }
  }

  try {
    await request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'projects-journal-tests', version: '1.0.0' },
    });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
  } catch (error) {
    await close();
    throw error;
  }

  async function call(name: string, args: Record<string, unknown> = {}) {
    const response = await request<ToolResult>('tools/call', { name, arguments: args });
    const text = response.content.find((item) => item.type === 'text')?.text;
    if (text === undefined) throw new Error(`MCP tool ${name} returned no text result.`);
    let value;
    try {
      value = JSON.parse(text);
    } catch {
      value = { error: text };
    }
    return { isError: Boolean(response.isError), value };
  }

  return { client: { listTools: () => request<ToolList>('tools/list') }, call, close };
}
