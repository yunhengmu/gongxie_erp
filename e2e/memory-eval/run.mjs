#!/usr/bin/env node
/**
 * 长期记忆抽取评测（T10 / T15）。
 *
 * <p>用平台真实的 PLATFORM_EXTRACT_PROMPT 对标注用例跑抽取，计算必记召回与污染率。
 * 本脚本只评提示词与输入构造，不启动 Java 服务；真实流量影子样本由平台写入
 * memory/.extract_shadow/YYYY-MM-DD.jsonl，另行人工复核。</p>
 *
 * 用法：
 *   node e2e/memory-eval/run.mjs --dry-run
 *   BIZBUDDY_LLM_API_KEY=... node e2e/memory-eval/run.mjs \
 *     --base-url https://dashscope.aliyuncs.com/compatible-mode/v1 --model qwen-plus --gate
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');
const args = parseArgs(process.argv.slice(2));

const casesPath = path.resolve(here, args.cases || 'cases.jsonl');
const promptPath = path.resolve(here, args.prompt || '../../code/back-end/ruoyi-modules/biz-buddy/src/main/java/org/dromara/ai/core/memory/AiMemoryPrompts.java');
const baseUrl = (args['base-url'] || process.env.BIZBUDDY_LLM_BASE_URL || '').replace(/\/$/, '');
const apiKey = args['api-key'] || process.env.BIZBUDDY_LLM_API_KEY || '';
const model = args.model || process.env.BIZBUDDY_LLM_MODEL || '';
const dryRun = Boolean(args['dry-run']);
const gate = Boolean(args.gate);

const prompt = loadPrompt(promptPath);
const cases = fs.readFileSync(casesPath, 'utf8')
  .split(/\r?\n/)
  .filter((line) => line.trim())
  .map((line, index) => ({ ...JSON.parse(line), _line: index + 1 }));

if (!dryRun && (!baseUrl || !apiKey || !model)) {
  console.error('缺少参数：--base-url / --api-key / --model（或对应环境变量），可先用 --dry-run');
  process.exit(2);
}

const results = [];
for (const item of cases) {
  const userContent = buildUserContent(item);
  if (dryRun) {
    console.log(`\n--- ${item.id} (${item.slice}) ---\n${userContent}`);
    results.push({ item, output: '', skipped: true });
    continue;
  }
  const output = await callModel(userContent);
  results.push({ item, output });
}

const report = summarize(results);
printReport(report);

if (args.out) {
  fs.writeFileSync(path.resolve(args.out), renderMarkdown(report), 'utf8');
}

if (gate && !dryRun) {
  const failed = report.overall.recall < 0.9 || report.overall.pollution > 0.02
    || report.overall.needsContextMiss > 0;
  process.exit(failed ? 1 : 0);
}

/** 解析命令行参数，支持 --key=value 与 --key value。 */
function parseArgs(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token.startsWith('--')) continue;
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

/** 从 Java 常量读取真实的平台抽取提示词，避免评测脚本与线上提示词漂移。 */
function loadPrompt(file) {
  const source = fs.readFileSync(file, 'utf8');
  const match = source.match(/PLATFORM_EXTRACT_PROMPT\s*=\s*"""([\s\S]*?)""";/);
  if (!match) {
    throw new Error(`未在 ${file} 中找到 PLATFORM_EXTRACT_PROMPT`);
  }
  return dedent(match[1]);
}

/** 去掉 Java 文本块的公共缩进。 */
function dedent(text) {
  const lines = text.replace(/^\r?\n/, '').replace(/\r?\n\s*$/, '').split(/\r?\n/);
  const indents = lines.filter((line) => line.trim()).map((line) => line.match(/^\s*/)[0].length);
  const min = indents.length ? Math.min(...indents) : 0;
  return lines.map((line) => line.slice(min)).join('\n').trim();
}

/** 构造与 Java 抽取服务一致的输入：只读上下文 + 待抽取消息。 */
function buildUserContent(item) {
  const messages = item.messages || [];
  const from = Number.isInteger(item.pendingFrom) ? item.pendingFrom : 0;
  const context = messages.slice(0, from);
  const pending = messages.slice(from);
  const parts = [];
  if (context.length) {
    parts.push('【只读上下文（仅用于理解指代与纠正关系，禁止从中抽取新事实）】');
    context.forEach((message) => parts.push(`[${label(message.role)}] ${message.content}`));
    parts.push('');
  }
  parts.push('【待抽取消息（只从这里提取新事实）】');
  pending.forEach((message) => parts.push(`[${label(message.role)}] ${message.content}`));
  parts.push('');
  parts.push('请按系统规则输出条目；无法自包含的事实输出 needs_context，不要猜测。');
  return parts.join('\n');
}

function label(role) {
  return role === 'user' ? '用户' : '助手';
}

async function callModel(userContent) {
  const response = await fetch(`${baseUrl}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${apiKey}`
    },
    body: JSON.stringify({
      model,
      temperature: 0,
      messages: [
        { role: 'system', content: prompt },
        { role: 'user', content: userContent }
      ]
    })
  });
  if (!response.ok) {
    throw new Error(`模型调用失败：HTTP ${response.status} ${await response.text()}`);
  }
  const payload = await response.json();
  return payload?.choices?.[0]?.message?.content || '';
}

/** 汇总召回、污染、needs_context 命中情况。 */
function summarize(rows) {
  const slices = new Map();
  const overall = {
    cases: 0,
    mustTotal: 0,
    mustHit: 0,
    pollutionTotal: 0,
    pollutionHit: 0,
    needsContextTotal: 0,
    needsContextMiss: 0
  };
  const details = [];
  for (const row of rows) {
    if (row.skipped) continue;
    const item = row.item;
    const normalized = normalize(row.output);
    const mustRemember = item.mustRemember || [];
    const mustNot = item.mustNotRemember || [];
    const mustHit = mustRemember.filter((fact) => normalized.includes(normalize(fact))).length;
    const pollution = mustNot.filter((fact) => normalized.includes(normalize(fact))).length;
    const needsContextMiss = item.expectNeedsContext
      && !/needs_context|needs-context/i.test(row.output) && normalized.length > 0 ? 1 : 0;
    const entry = {
      id: item.id,
      slice: item.slice || 'default',
      mustTotal: mustRemember.length,
      mustHit,
      pollutionTotal: mustNot.length,
      pollution,
      needsContextMiss,
      output: row.output
    };
    details.push(entry);
    const slice = slices.get(entry.slice) || { cases: 0, mustTotal: 0, mustHit: 0, pollutionTotal: 0, pollutionHit: 0, needsContextTotal: 0, needsContextMiss: 0 };
    slice.cases++;
    slice.mustTotal += entry.mustTotal;
    slice.mustHit += entry.mustHit;
    slice.pollutionTotal += entry.pollutionTotal;
    slice.pollutionHit += entry.pollution;
    slice.needsContextTotal += item.expectNeedsContext ? 1 : 0;
    slice.needsContextMiss += entry.needsContextMiss;
    slices.set(entry.slice, slice);

    overall.cases++;
    overall.mustTotal += entry.mustTotal;
    overall.mustHit += entry.mustHit;
    overall.pollutionTotal += entry.pollutionTotal;
    overall.pollutionHit += entry.pollution;
    overall.needsContextTotal += item.expectNeedsContext ? 1 : 0;
    overall.needsContextMiss += entry.needsContextMiss;
  }
  return {
    overall: withRates(overall),
    slices: [...slices.entries()].map(([name, value]) => ({ name, ...withRates(value) })),
    details
  };
}

function withRates(value) {
  return {
    ...value,
    recall: value.mustTotal ? value.mustHit / value.mustTotal : 1,
    pollution: value.pollutionTotal ? value.pollutionHit / value.pollutionTotal : 0
  };
}

function normalize(text) {
  return String(text || '').toLowerCase().replace(/\s+/g, '');
}

function percent(value) {
  return `${(value * 100).toFixed(1)}%`;
}

function printReport(report) {
  console.log('\n=== 长期记忆抽取评测 ===');
  console.log(`总体：用例 ${report.overall.cases}，必记召回 ${report.overall.mustHit}/${report.overall.mustTotal} (${percent(report.overall.recall)})，`
    + `污染 ${report.overall.pollutionHit}/${report.overall.pollutionTotal} (${percent(report.overall.pollution)})，`
    + `needs_context 缺失 ${report.overall.needsContextMiss}/${report.overall.needsContextTotal}`);
  for (const slice of report.slices) {
    console.log(`- ${slice.name}: 召回 ${percent(slice.recall)}，污染 ${percent(slice.pollution)}，用例 ${slice.cases}`);
  }
  const failures = report.details.filter((item) =>
    item.mustHit < item.mustTotal || item.pollution > 0 || item.needsContextMiss > 0);
  if (failures.length) {
    console.log('\n未通过用例：');
    failures.forEach((item) => {
      console.log(`- ${item.id}: 必记 ${item.mustHit}/${item.mustTotal}，污染 ${item.pollution}/${item.pollutionTotal}`
        + `，needs_context 缺失 ${item.needsContextMiss}`);
      console.log(`  output: ${item.output.replace(/\n/g, ' | ').slice(0, 300)}`);
    });
  }
}

function renderMarkdown(report) {
  const lines = ['# 长期记忆抽取评测报告', '', `- 用例数：${report.overall.cases}`,
    `- 必记召回：${percent(report.overall.recall)}`,
    `- 污染率：${percent(report.overall.pollution)}`,
    `- needs_context 缺失：${report.overall.needsContextMiss}/${report.overall.needsContextTotal}`, '',
    '| 分桶 | 用例 | 必记召回 | 污染率 |', '|---|---|---|---|'];
  report.slices.forEach((slice) => {
    lines.push(`| ${slice.name} | ${slice.cases} | ${percent(slice.recall)} | ${percent(slice.pollution)} |`);
  });
  lines.push('', '## 明细', '', '| 用例 | 分桶 | 必记 | 污染 | needs_context |', '|---|---|---|---|---|');
  report.details.forEach((item) => {
    lines.push(`| ${item.id} | ${item.slice} | ${item.mustHit}/${item.mustTotal} | ${item.pollution}/${item.pollutionTotal} | ${item.needsContextMiss} |`);
  });
  return lines.join('\n');
}
