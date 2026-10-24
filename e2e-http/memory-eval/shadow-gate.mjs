#!/usr/bin/env node
/**
 * 真实流量影子门禁（T15）。
 *
 * <p>与 `run.mjs` 的分工：`run.mjs` 用标注用例离线评测提示词；本脚本评测**平台在 shadow 模式下
 * 用真实会话产生的样本**（`memory/.extract_shadow/YYYY-MM-DD.jsonl`），按会话聚合后与人工标签比对，
 * 计算必记召回、污染率、PII 出境与 needs_context 缺失，并按门禁阈值给出退出码。</p>
 *
 * 用法：
 *   node e2e-http/memory-eval/shadow-gate.mjs \
 *     --samples shadow-samples.jsonl --labels shadow-labels.jsonl \
 *     [--memory MEMORY.md] [--out shadow-gate.md] [--gate]
 *
 * `--memory` 可选：传入当前 MEMORY.md。已有记忆里已经存在的事实不计为召回缺失
 * （平台会按「已有记忆摘要」去重，不重复抽取；系统最终仍然“知道”这条事实）。
 *
 * 标签格式（每行一个会话）：
 *   {"sessionId":"123","mustRemember":["表格"],"mustNotRemember":["MySQL"],"expectNeedsContext":false}
 *
 * 退出码：0 通过 / 1 未通过门禁 / 2 参数或输入错误。
 */

import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

/** 门禁阈值：必记召回 ≥90%、污染 ≤2%、PII 出境 0、needs_context 缺失 0 */
export const THRESHOLDS = { recall: 0.9, pollution: 0.02 };

/** PII 命中模式（抽取正文中出现即视为出境） */
const PII_PATTERNS = [
  { name: '手机号', regex: /1[3-9]\d{9}/g },
  { name: '邮箱', regex: /[\w.+-]+@[\w-]+\.[\w.]+/g },
  { name: '身份证', regex: /\d{17}[\dXx]/g },
  { name: '银行卡', regex: /\d{16,19}/g }
];

/**
 * 命令行入口：被 gate.mjs 复用时不执行（由 import.meta.url 判定）。
 */
function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.samples || !args.labels) {
    console.error('缺少参数：--samples <jsonl> --labels <jsonl>');
    process.exit(2);
  }

  const samples = readJsonl(path.resolve(args.samples));
  const labels = readJsonl(path.resolve(args.labels));
  const memoryText = args.memory && fs.existsSync(path.resolve(args.memory))
    ? fs.readFileSync(path.resolve(args.memory), 'utf8') : '';
  const report = evaluate(samples, labels, memoryText);
  printReport(report);

  if (args.out) {
    fs.writeFileSync(path.resolve(args.out), renderMarkdown(report), 'utf8');
  }

  if (args.gate) {
    process.exit(report.passed ? 0 : 1);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}

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

/** 读取 JSONL（忽略空行）。 */
export function readJsonl(file) {
  if (!fs.existsSync(file)) {
    console.error(`文件不存在：${file}`);
    process.exit(2);
  }
  return fs.readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line));
}

/**
 * 按会话聚合影子样本：同一会话可能因多轮抽取产生多条记录。
 *
 * @param {object[]} rows 影子样本
 * @returns {Map<string, object>} sessionId → 聚合结果
 */
function aggregateSessions(rows) {
  const sessions = new Map();
  for (const row of rows) {
    const key = String(row.sessionId ?? '');
    if (!key) {
      continue;
    }
    const entry = sessions.get(key) || {
      records: 0, extracted: [], kept: 0, needsContext: 0, lowConfidence: 0, messages: 0
    };
    entry.records++;
    entry.kept += Number(row.kept) || 0;
    entry.needsContext += Number(row.needsContext) || 0;
    entry.lowConfidence += Number(row.lowConfidence) || 0;
    entry.messages += Number(row.messages) || 0;
    if (row.extracted) {
      entry.extracted.push(String(row.extracted));
    }
    sessions.set(key, entry);
  }
  return sessions;
}

/**
 * 计算门禁指标。
 *
 * @param {object[]} rows   影子样本
 * @param {object[]} labels 会话标签
 * @param {string}   memoryText 当前 MEMORY.md 正文（可为空）
 * @returns {object} 指标与明细
 */
export function evaluate(rows, labels, memoryText = '') {
  const sessions = aggregateSessions(rows);
  const known = normalize(memoryText);
  const overall = {
    mustTotal: 0, mustHit: 0, mustHitInMemory: 0, pollutionTotal: 0, pollutionHit: 0,
    needsContextTotal: 0, needsContextMiss: 0, missingSessions: 0
  };
  const details = [];
  for (const label of labels) {
    const key = String(label.sessionId ?? '');
    const session = sessions.get(key);
    const text = normalize(session ? session.extracted.join('\n') : '');
    const missing = !session;
    if (missing) {
      overall.missingSessions++;
    }
    const mustRemember = label.mustRemember || [];
    const mustNot = label.mustNotRemember || [];
    const hitInExtract = mustRemember.filter((fact) => text.includes(normalize(fact)));
    // 已有 MEMORY.md 中已存在的事实：平台按「已知记忆」去重不再抽取，但仍算系统已知
    const hitInMemory = mustRemember
      .filter((fact) => !hitInExtract.includes(fact) && known.includes(normalize(fact)));
    const mustHit = hitInExtract.length + hitInMemory.length;
    const pollution = mustNot.filter((fact) => text.includes(normalize(fact))).length;
    const needsContextMiss = label.expectNeedsContext
      && (!session || (session.needsContext === 0 && text.length > 0)) ? 1 : 0;
    overall.mustTotal += mustRemember.length;
    overall.mustHit += mustHit;
    overall.mustHitInMemory += hitInMemory.length;
    overall.pollutionTotal += mustNot.length;
    overall.pollutionHit += pollution;
    overall.needsContextTotal += label.expectNeedsContext ? 1 : 0;
    overall.needsContextMiss += needsContextMiss;
    details.push({
      sessionId: key, missing, text: session ? session.extracted.join('\n') : '',
      mustTotal: mustRemember.length, mustHit,
      hitInMemory: hitInMemory.length,
      pollutionTotal: mustNot.length, pollution,
      needsContextMiss, records: session?.records ?? 0
    });
  }

  const pii = collectPii(sessions);
  const recall = overall.mustTotal ? overall.mustHit / overall.mustTotal : 1;
  const pollutionRate = overall.pollutionTotal ? overall.pollutionHit / overall.pollutionTotal : 0;
  const passed = recall >= THRESHOLDS.recall && pollutionRate <= THRESHOLDS.pollution
    && pii.length === 0 && overall.needsContextMiss === 0;
  return {
    sampleRecords: rows.length,
    sampleSessions: sessions.size,
    labelSessions: labels.length,
    overall: { ...overall, recall, pollutionRate },
    pii,
    details,
    passed
  };
}

/**
 * 扫描全部抽取正文中的 PII。
 *
 * @param {Map<string, object>} sessions 会话聚合结果
 * @returns {object[]} 命中明细
 */
function collectPii(sessions) {
  const hits = [];
  for (const [sessionId, session] of sessions) {
    const text = session.extracted.join('\n');
    for (const pattern of PII_PATTERNS) {
      const matched = text.match(pattern.regex);
      if (matched) {
        hits.push({ sessionId, type: pattern.name, count: matched.length });
      }
    }
  }
  return hits;
}

function normalize(text) {
  return String(text || '').toLowerCase().replace(/\s+/g, '');
}

function percent(value) {
  return `${(value * 100).toFixed(1)}%`;
}

export function printReport(report) {
  console.log('\n=== 长期记忆真实流量影子门禁 ===');
  console.log(`样本：${report.sampleRecords} 条记录 / ${report.sampleSessions} 个会话；`
    + `标签：${report.labelSessions} 个会话（样本缺失 ${report.overall.missingSessions}）`);
  console.log(`必记召回：${report.overall.mustHit}/${report.overall.mustTotal} (${percent(report.overall.recall)})`
    + `，门槛 ≥${percent(THRESHOLDS.recall)}`
    + `${report.overall.mustHitInMemory ? `（其中 ${report.overall.mustHitInMemory} 条已在 MEMORY.md 中）` : ''}`);
  console.log(`污染率：${report.overall.pollutionHit}/${report.overall.pollutionTotal} (${percent(report.overall.pollutionRate)})`
    + `，门槛 ≤${percent(THRESHOLDS.pollution)}`);
  console.log(`PII 出境：${report.pii.length === 0 ? '0' : report.pii.map((item) => `${item.type}×${item.count}`).join('，')}`);
  console.log(`needs_context 缺失：${report.overall.needsContextMiss}/${report.overall.needsContextTotal}`);
  const failures = report.details.filter((item) =>
    item.missing || item.mustHit < item.mustTotal || item.pollution > 0 || item.needsContextMiss > 0);
  if (failures.length) {
    console.log('\n未通过会话：');
    for (const item of failures) {
      console.log(`- ${item.sessionId}（记录 ${item.records}）：必记 ${item.mustHit}/${item.mustTotal}`
        + `，污染 ${item.pollution}/${item.pollutionTotal}，needs_context 缺失 ${item.needsContextMiss}`
        + `${item.missing ? '，无影子样本' : ''}`);
      if (item.text) {
        console.log(`  extracted: ${item.text.replace(/\n/g, ' | ').slice(0, 300)}`);
      }
    }
  }
  console.log(`\n结论：${report.passed ? 'PASS（可用于平台模式切量）' : 'FAIL（保持 framework / shadow）'}`);
}

export function renderMarkdown(report) {
  const lines = ['# 长期记忆真实流量影子门禁报告', '',
    `- 样本：${report.sampleRecords} 条 / ${report.sampleSessions} 个会话`,
    `- 标签会话：${report.labelSessions}（样本缺失 ${report.overall.missingSessions}）`,
    `- 必记召回：${percent(report.overall.recall)}`,
    `- 污染率：${percent(report.overall.pollutionRate)}`,
    `- PII 出境：${report.pii.length}`,
    `- needs_context 缺失：${report.overall.needsContextMiss}/${report.overall.needsContextTotal}`,
    `- 结论：${report.passed ? 'PASS' : 'FAIL'}`, '',
    '| 会话 | 记录 | 必记 | 污染 | needs_context 缺失 |', '|---|---|---|---|---|'];
  report.details.forEach((item) => {
    lines.push(`| ${item.sessionId} | ${item.records} | ${item.mustHit}/${item.mustTotal}`
      + ` | ${item.pollution}/${item.pollutionTotal} | ${item.needsContextMiss} |`);
  });
  return lines.join('\n');
}
