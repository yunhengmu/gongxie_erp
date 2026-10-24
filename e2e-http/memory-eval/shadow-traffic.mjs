#!/usr/bin/env node
/**
 * 真实链路影子流量生成器（T15）。
 *
 * <p>把 `traffic-cases.jsonl` 里的标注场景通过**真实聊天接口**跑一遍：建会话 → 发消息 → 读 SSE，
 * 同时把 shadow 模式开关打开（抽样率 1.0 / 每轮抽取），让平台的轮末抽取把样本写进
 * `memory/.extract_shadow/YYYY-MM-DD.jsonl`。跑完输出 `labels.jsonl`（会话主键 + 标注），
 * 供 `shadow-gate.mjs` 比对。</p>
 *
 * <p>使用前请先快照 MEMORY.md（门禁要用「跑流量之前」的版本判定已知事实）；跑完请把
 * MEMORY.md 还原，避免测试偏好留在开发账号里。</p>
 *
 * 用法：
 *   node e2e-http/memory-eval/shadow-traffic.mjs --labels-out /tmp/labels.jsonl [--limit 10] [--only id1,id2]
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConfig } from '../lib/env.mjs';
import { HttpClient } from '../lib/http.mjs';
import { createSession, sendUserMessage, streamChat, sleep } from '../lib/flow.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = parseArgs(process.argv.slice(2));
const casesPath = path.resolve(here, args.cases || 'traffic-cases.jsonl');
const labelsOut = path.resolve(args['labels-out'] || path.join(here, 'labels.jsonl'));
const only = (args.only || '').split(',').map((item) => item.trim()).filter(Boolean);

const allCases = fs.readFileSync(casesPath, 'utf8')
  .split(/\r?\n/)
  .filter((line) => line.trim())
  .map((line) => JSON.parse(line));
const selected = (only.length ? allCases.filter((item) => only.includes(item.id)) : allCases)
  .slice(0, args.limit ? Number(args.limit) : allCases.length);
if (!selected.length) {
  console.error('没有选中任何用例：检查 --cases / --only / --limit');
  process.exit(2);
}

const cfg = loadConfig({ baseUrl: args['base-url'] });
const http = new HttpClient(cfg, { timeoutMs: 30000 });
await http.login();
const entry = await http.request('GET', '/ai/agent/entry');
const profile = await http.request('GET', '/system/user/profile');
const userId = profile?.user?.userId;
if (!entry?.id || !userId) {
  console.error('未取到入口智能体或当前用户，请确认已登录且种子数据存在');
  process.exit(2);
}

const original = await http.request('GET', '/ai/memory/config');
await http.request('PUT', '/ai/memory/config', {
  body: { ...original, enabled: true, pipelineMode: 'shadow', flushMode: 'always', extractShadowSampleRate: 1 }
});
console.log(`shadow 配置已生效：用例 ${selected.length} 个，入口 ${entry.agentName || entry.id}，用户 ${userId}`);

const labels = [];
const created = [];
const paused = [];
try {
  for (let index = 0; index < selected.length; index++) {
    const item = selected[index];
    const session = await createSession(http, entry.id, `SHADOW_${item.id}`);
    created.push(session.id);
    let stopped = 'stop-event';
    for (const message of item.messages || []) {
      const sent = await sendUserMessage(http, session.id, message.content);
      const result = await streamChat(http, { sseTimeoutMs: 180000 }, session.id, sent.id);
      stopped = result.stopReason;
      if (stopped !== 'stop-event') {
        break;
      }
      await sleep(1500);
    }
    if (stopped !== 'stop-event') {
      paused.push({ id: item.id, sessionId: String(session.id), stop: stopped });
    }
    labels.push({
      sessionId: String(session.id),
      id: item.id,
      slice: item.slice,
      mustRemember: item.mustRemember || [],
      mustNotRemember: item.mustNotRemember || [],
      expectNeedsContext: Boolean(item.expectNeedsContext),
      stopped
    });
    // 逐条落盘：中途中断也能拿到已完成会话的标签
    fs.writeFileSync(labelsOut, labels.map((row) => JSON.stringify(row)).join('\n') + '\n', 'utf8');
    console.log(`[${index + 1}/${selected.length}] ${item.id} → session=${session.id} stop=${stopped}`);
  }
  // 等轮末异步抽取全部落盘
  await sleep(20000);
} finally {
  await http.request('PUT', '/ai/memory/config', { body: original }).catch(() => undefined);
  for (const sessionId of created) {
    await http.request('DELETE', `/ai/chat/session/${sessionId}`).catch(() => undefined);
  }
}

console.log(`\nlabels=${labelsOut}`);
console.log(`sessions=${labels.length} paused=${paused.length}${paused.length ? ` ${JSON.stringify(paused)}` : ''}`);
console.log(`restoredMode=${(await http.request('GET', '/ai/memory/config')).pipelineMode}`);

/**
 * 解析命令行参数，支持 `--key=value` 与 `--key value`。
 */
function parseArgs(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token.startsWith('--')) {
      continue;
    }
    const eq = token.indexOf('=');
    if (eq > 0) {
      result[token.slice(2, eq)] = token.slice(eq + 1);
    } else {
      const key = token.slice(2);
      const next = argv[i + 1];
      if (next && !next.startsWith('--')) {
        result[key] = next;
        i++;
      } else {
        result[key] = true;
      }
    }
  }
  return result;
}
