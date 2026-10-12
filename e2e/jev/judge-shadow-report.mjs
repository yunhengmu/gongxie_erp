#!/usr/bin/env node
/**
 * 结构化决策「影子档」对照报告（docs/21 落地进展 T7）。
 *
 * <p>对影子期落在 {@code ai_route_run} 上的判定数据做三类观测，供 T9 选阈值与切闸门决策：
 * <ol>
 *   <li><b>一致率</b>：决策模型（judge_*）与工具决策（action / decision_json）在「是否路由」「目标专家」两个口径下的一致情况；</li>
 *   <li><b>置信度分桶曲线</b>：按 judge_confidence 分桶看一致率的单调性（阈值校准依据）；</li>
 *   <li><b>成本与延迟观测</b>：judge_latency_ms / judge_tokens 与入口轮 duration_ms / entry_tokens 的可核对观测值
 *       （<b>只报观测，不推算「节省了多少」</b>——真实节省需切 gate 后用同一指标对照）。</li>
 * </ol></p>
 *
 * <p>本脚本<b>纯只读</b>：只调 {@code GET /ai/route/run/list}，不写任何数据、不改配置。</p>
 *
 * <p>用法：
 * <pre>
 *   node e2e/jev/judge-shadow-report.mjs                      # 默认近 7 天
 *   node e2e/jev/judge-shadow-report.mjs --days 30 --limit 500
 *   node e2e/jev/judge-shadow-report.mjs --json e2e/.judge-shadow-report.json
 *   node e2e/jev/judge-shadow-report.mjs --base-url http://localhost:8081
 * </pre></p>
 *
 * <p>退出码：{@code 0} 正常（数据不足不是错误）/ {@code 2} 配置缺失或登录失败 / {@code 3} 参数错误。</p>
 */

import fs from 'node:fs';
import path from 'node:path';

import { loadConfig } from '../lib/env.mjs';
import { HttpClient } from '../lib/http.mjs';

const EXIT = { OK: 0, CONFIG: 2, USAGE: 3 };

/** 判定选项里代表「自答」的两个取值（其余取值视作指向某专家） */
const SELF_CHOICES = new Set(['self', 'other']);

/**
 * 单页拉取上限：接口（PageQuery）未设 maxLimit（默认 Integer.MAX_VALUE＝查全部），
 * 本脚本以 --limit 封顶，并额外设一个稳妥的单页大小避免一次拉全表；不足则继续翻页。
 */
const PAGE_SIZE_MAX = 200;

/** 阈值扫描候选（T9 选阈值用） */
const THRESHOLDS = [0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9];

/** 置信度分桶上界（左闭右开；null 单独一桶） */
const CONFIDENCE_BUCKETS = [
  { label: '[0.0,0.5)', min: 0.0, max: 0.5 },
  { label: '[0.5,0.6)', min: 0.5, max: 0.6 },
  { label: '[0.6,0.7)', min: 0.6, max: 0.7 },
  { label: '[0.7,0.8)', min: 0.7, max: 0.8 },
  { label: '[0.8,0.9)', min: 0.8, max: 0.9 },
  { label: '[0.9,1.0]', min: 0.9, max: 1.0000001 }
];

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 参数列表
 * @returns {{ days: number, limit: number, json: string, baseUrl: string }} 选项
 * @throws {Error} 参数非法
 */
const parseArgs = (argv) => {
  const args = { days: 7, limit: 500, json: '', baseUrl: '' };
  const valueFlags = { '--days': 'days', '--limit': 'limit', '--json': 'json', '--base-url': 'baseUrl' };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    const key = valueFlags[token];
    if (!key) {
      throw new Error(`未知参数：${token}`);
    }
    const value = argv[++i];
    if (value === undefined || value === '') {
      throw new Error(`参数 ${token} 缺少取值`);
    }
    args[key] = key === 'days' || key === 'limit' ? Number(value) : value;
  }
  if (!Number.isFinite(args.days) || args.days <= 0) {
    throw new Error('--days 必须是正数');
  }
  if (!Number.isFinite(args.limit) || args.limit <= 0) {
    throw new Error('--limit 必须是正整数');
  }
  return args;
};

/**
 * 分页拉取编排运行列表（按 createTime desc 排序），只取近 --days 的窗口。
 *
 * @param {HttpClient} http HTTP 客户端
 * @param {{ days: number, limit: number }} args 选项
 * @returns {Promise<{ rows: object[], fetched: number, total: number, cutoff: number, truncated: boolean }>} 拉取结果
 */
const fetchRows = async (http, args) => {
  const cutoff = Date.now() - args.days * 24 * 60 * 60 * 1000;
  const pageSize = Math.min(args.limit, PAGE_SIZE_MAX);
  const rows = [];
  let fetched = 0;
  let total = 0;
  let pageNum = 1;
  let reachedCutoff = false;
  for (;;) {
    const page = await http.request('GET', '/ai/route/run/list', { query: { pageNum, pageSize } });
    const current = page?.rows ?? [];
    total = page?.total ?? current.length;
    for (const row of current) {
      fetched++;
      const time = parseTime(row.createTime);
      if (time !== null && time < cutoff) {
        // 列表按 createTime desc 排序：一旦遇到早于窗口的行，后续必然更早，可以停
        reachedCutoff = true;
        continue;
      }
      if (time !== null && time >= cutoff) {
        rows.push(row);
      }
      // createTime 无法解析的行（理论上不会有）既不纳入窗口也不作为停页依据，忽略并计数
    }
    if (fetched >= args.limit || current.length === 0 || pageNum * pageSize >= total || reachedCutoff) {
      break;
    }
    pageNum++;
  }
  const truncated = rows.length > args.limit;
  return { rows: rows.slice(0, args.limit), fetched, total, cutoff, truncated };
};

/**
 * 宽松解析时间串，兼容 {@code 2026-10-12T15:09:05}（ISO）与 {@code 2026-10-12 15:09:05}（空格分隔）。
 *
 * @param {string} value 时间串
 * @returns {number|null} 毫秒时间戳；无法解析时为 null
 */
const parseTime = (value) => {
  if (!value) {
    return null;
  }
  const normalized = String(value).trim().replace(' ', 'T').replace(/\.\d+$/, '');
  const time = Date.parse(normalized);
  return Number.isNaN(time) ? null : time;
};

/**
 * 判断一行是否带判定数据（judge_choice 非空，或来源与模式都非空）。
 *
 * @param {object} row 列表行
 * @returns {boolean} 是否带判定数据
 */
const hasJudge = (row) => Boolean(row.judgeChoice) || (Boolean(row.decisionSource) && Boolean(row.judgeMode));

/**
 * 解析工具决策原文中的 expert_agent_key（口径 B 用）。
 *
 * @param {object} row 列表行
 * @returns {{ ok: boolean, expertKey: string|null }} 解析结果；ok=false 表示解析失败（JSON 非法 / route 缺字段）
 */
const parseLlmExpertKey = (row) => {
  const raw = row.decisionJson;
  if (!raw) {
    // action=self 时 decision_json 一般仍有 action 字段；缺失时按「无专家意图」处理，仅 route 才判失败
    return { ok: row.action !== 'route', expertKey: null };
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, expertKey: null };
  }
  if (!parsed || typeof parsed !== 'object') {
    return { ok: false, expertKey: null };
  }
  const key = parsed.expert_agent_key;
  if (row.action === 'route' && !key) {
    return { ok: false, expertKey: null };
  }
  return { ok: true, expertKey: key ? String(key) : null };
};

/**
 * 口径 A「是否路由」：LLM 侧 action=route 视为「路由」，判定侧 choice 非 self/other 视为「路由」。
 *
 * @param {object} row 列表行
 * @returns {boolean} 两侧是否同属「路由/自答」一侧
 */
const agreeOnRouting = (row) => {
  const llmRoute = row.action === 'route';
  const judgeRoute = !SELF_CHOICES.has(row.judgeChoice);
  return llmRoute === judgeRoute;
};

/**
 * 口径 B「目标专家」：都路由时比较 expert_agent_key 是否同一 agent_code；一方路由一方自答 → 不一致；都自答 → 一致。
 *
 * @param {object} row 列表行
 * @param {{ ok: boolean, expertKey: string|null }} llm LLM 侧解析结果
 * @returns {boolean|null} 是否一致；null 表示无法判定（本轮不应出现）
 */
const agreeOnExpert = (row, llm) => {
  const llmRoute = row.action === 'route';
  const judgeRoute = !SELF_CHOICES.has(row.judgeChoice);
  if (!llmRoute && !judgeRoute) {
    // 双方都自答：无专家可比，视作一致（不构成冲突）
    return true;
  }
  if (llmRoute !== judgeRoute) {
    return false;
  }
  // 都路由：比较目标专家
  return llm.expertKey !== null && llm.expertKey === row.judgeChoice;
};

/**
 * 均值（空集返回 null，避免 NaN）。
 */
const mean = (values) => (values.length ? values.reduce((acc, item) => acc + item, 0) / values.length : null);

/**
 * 分位值（向上取整法，空集返回 null）。
 *
 * @param {number[]} sorted 已升序排序的数组
 * @param {number} p 百分位（0~100）
 */
const percentile = (sorted, p) => {
  if (!sorted.length) {
    return null;
  }
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[index];
};

/** 百分比文本；分母为 0 时显示 0.0%（样本不足不应出现 NaN）。 */
const pct = (numerator, denominator) => `${denominator > 0 ? ((numerator / denominator) * 100).toFixed(1) : '0.0'}%`;

/** 已是百分比数值的文本（保留 1 位小数，样本不足时显示 0.0%）。 */
const rateText = (rate) => `${(rate ?? 0).toFixed(1)}%`;

/** 数值保留 1 位小数；null → '—' */
const num1 = (value) => (value === null || value === undefined ? '—' : value.toFixed(1));

/** 宽松取数：兼容后端把 Long 序列化成字符串的情况；非法/空值返回 null。 */
const toNumber = (value) => {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
};

/** 长字段截断（不一致清单用，防止整段 JSON 刷屏） */
const truncate = (value, max = 40) => {
  if (value === null || value === undefined) {
    return '';
  }
  const text = String(value);
  return text.length > max ? `${text.slice(0, max)}…` : text;
};

/**
 * 对「计入一致率的样本」逐行评估两个口径，并聚合总一致率、分桶曲线、阈值扫描。
 *
 * @param {object[]} consistencyRows action∈{self,route} 且带判定数据的行
 * @param {number} judgedRowsLength 全部带判定数据的行数（阈值扫描的采信覆盖率分母，按 docs/21 T9 口径）
 * @returns {object} 一致性统计结果
 */
const evaluateConsistency = (consistencyRows, judgedRowsLength) => {
  const bucketAcc = CONFIDENCE_BUCKETS.map((bucket) => ({ ...bucket, total: 0, aHit: 0, bTotal: 0, bHit: 0 }));
  const nullBucket = { label: 'null', total: 0, aHit: 0, bTotal: 0, bHit: 0 };
  const aTotal = consistencyRows.length;
  let aHit = 0;
  let bTotal = 0;
  let bHit = 0;
  let parseFailCount = 0;
  const inconsistent = [];

  for (const row of consistencyRows) {
    const llm = parseLlmExpertKey(row);
    const a = agreeOnRouting(row);
    if (a) {
      aHit++;
    }
    let b = null;
    if (!llm.ok) {
      parseFailCount++;
    } else {
      bTotal++;
      b = agreeOnExpert(row, llm);
      if (b) {
        bHit++;
      }
    }
    // 分桶：置信度缺失（null / 非数字）单独归入 null 桶，避免与低置信混淆
    const confidence = toNumber(row.judgeConfidence);
    const bucket = confidence === null
      ? nullBucket
      : bucketAcc.find((item) => confidence >= item.min && confidence < item.max) ?? nullBucket;
    bucket.total++;
    if (a) {
      bucket.aHit++;
    }
    if (llm.ok) {
      bucket.bTotal++;
      if (b) {
        bucket.bHit++;
      }
    }
    if (!a || (llm.ok && b === false)) {
      inconsistent.push({
        routeRunId: row.id,
        action: row.action,
        llmExpertKey: truncate(llm.expertKey, 30),
        judgeChoice: truncate(row.judgeChoice, 30),
        judgeConfidence: confidence,
        judgeError: truncate(row.judgeError, 40)
      });
    }
  }

  const rate = (hit, total) => (total > 0 ? Number(((hit / total) * 100).toFixed(1)) : 0);
  return {
    total: aTotal,
    a: { hit: aHit, total: aTotal, rate: rate(aHit, aTotal) },
    b: { hit: bHit, total: bTotal, rate: rate(bHit, bTotal) },
    parseFailCount,
    buckets: [...bucketAcc, nullBucket].map((bucket) => ({
      label: bucket.label,
      total: bucket.total,
      aRate: rate(bucket.aHit, bucket.total),
      bRate: rate(bucket.bHit, bucket.bTotal)
    })),
    // 阈值扫描：子集＝置信度≥阈值；覆盖率分母用 judgedRows（docs/21 T9 口径）
    thresholds: THRESHOLDS.map((threshold) => {
      const subset = consistencyRows.filter((row) => {
        const confidence = toNumber(row.judgeConfidence);
        return confidence !== null && confidence >= threshold;
      });
      let subAHit = 0;
      let subBTotal = 0;
      let subBHit = 0;
      for (const row of subset) {
        if (agreeOnRouting(row)) {
          subAHit++;
        }
        const llm = parseLlmExpertKey(row);
        if (llm.ok) {
          subBTotal++;
          if (agreeOnExpert(row, llm)) {
            subBHit++;
          }
        }
      }
      return {
        threshold,
        count: subset.length,
        aRate: rate(subAHit, subset.length),
        bRate: rate(subBHit, subBTotal),
        coverage: rate(subset.length, judgedRowsLength)
      };
    }),
    inconsistent
  };
};

/**
 * 汇总成本与延迟观测（只报可核对的观测值，不做任何「节省」推算）。
 *
 * @param {object[]} judgedRows 带判定数据的行
 * @returns {object} 观测汇总
 */
const summarizeCost = (judgedRows) => {
  const numbers = (pick) => judgedRows.map(pick).map(toNumber).filter((item) => item !== null);
  const judgeLatency = numbers((row) => row.judgeLatencyMs);
  const judgeTokens = numbers((row) => row.judgeTokens);
  const entryDuration = numbers((row) => row.durationMs);
  const entryTokens = numbers((row) => row.entryTokens);
  // 判定耗时 / 入口轮耗时 占比：仅取两者都有效的行
  const ratios = judgedRows
    .map((row) => ({ latency: toNumber(row.judgeLatencyMs), duration: toNumber(row.durationMs) }))
    .filter((item) => item.latency !== null && item.duration !== null && item.duration > 0)
    .map((item) => item.latency / item.duration);
  const sorted = (values) => [...values].sort((left, right) => left - right);
  const buckets = (values) => ({
    count: values.length,
    mean: mean(values),
    p50: percentile(sorted(values), 50),
    p95: percentile(sorted(values), 95)
  });
  return {
    judgeLatencyMs: buckets(judgeLatency),
    judgeTokens: buckets(judgeTokens),
    entryDurationMs: { count: entryDuration.length, mean: mean(entryDuration) },
    entryTokens: { count: entryTokens.length, mean: mean(entryTokens) },
    latencyRatio: { count: ratios.length, mean: mean(ratios) }
  };
};

/**
 * 打印控制台报告（分节、可读、中文）。
 *
 * @param {object} report 报告对象
 */
const printReport = (report) => {
  const line = '─'.repeat(64);
  console.log(`结构化决策影子对照报告｜窗口：近 ${report.args.days} 天｜样本上限 ${report.args.limit}`);
  console.log(`后端：${report.baseUrl}｜接口拉取 ${report.fetch.fetched} 行（total=${report.fetch.total}）｜窗口内 ${report.window.rows} 行`
    + (report.fetch.truncated ? '｜⚠ 已按 --limit 截断，更早样本可能遗漏' : ''));
  console.log(line);
  console.log('【样本分层】');
  console.log(`  pinned（跳过判定，不计一致率）      ：${report.layers.pinnedCount}`);
  console.log(`  有判定数据（judgedRows）           ：${report.layers.judgedRows}`);
  console.log(`    · 其中 action=self/route 计入一致率：${report.layers.consistencyRows}`);
  console.log(`    · 其中 action=pinned 却带判定（异常）：${report.layers.pinnedJudgedRows}${report.layers.pinnedJudgedRows > 0 ? ' ← 请单独核查' : ''}`);
  console.log(`    · 其中 action 非 self/route（忽略） ：${report.layers.judgedUnknownAction}`);
  console.log(`  self/route 但无判定数据（noJudgeRows）：${report.layers.noJudgeRows}`);
  console.log(`  工具决策原文解析失败（parseFailCount）：${report.consistency.parseFailCount}（不计入口径 B 分母）`);
  console.log(line);
  console.log('【一致率】');
  console.log(`  口径 A「是否路由」：${report.consistency.a.hit}/${report.consistency.a.total} = ${pct(report.consistency.a.hit, report.consistency.a.total)}`);
  console.log(`  口径 B「目标专家」：${report.consistency.b.hit}/${report.consistency.b.total} = ${pct(report.consistency.b.hit, report.consistency.b.total)}`);
  console.log(line);
  console.log('【置信度分桶曲线】');
  console.log(`  ${'置信度区间'.padEnd(12)}${'样本'.padEnd(8)}${'口径A一致率'.padEnd(14)}口径B一致率`);
  for (const bucket of report.consistency.buckets) {
    console.log(`  ${bucket.label.padEnd(12)}${String(bucket.total).padEnd(8)}${rateText(bucket.aRate).padEnd(14)}${rateText(bucket.bRate)}`);
  }
  console.log(line);
  console.log('【阈值扫描（T9 选阈值）】');
  console.log(`  ${'阈值'.padEnd(8)}${'子集样本'.padEnd(10)}${'口径A一致率'.padEnd(14)}${'口径B一致率'.padEnd(14)}采信覆盖率`);
  for (const item of report.consistency.thresholds) {
    // 阈值保留两位小数：0.75 / 0.85 若按一位小数显示会与 0.8 / 0.9 混同，导致同行多标签
    console.log(`  ≥${item.threshold.toFixed(2).padEnd(6)}${String(item.count).padEnd(10)}${rateText(item.aRate).padEnd(14)}${rateText(item.bRate).padEnd(14)}${rateText(item.coverage)}`);
  }
  console.log(line);
  console.log('【成本与延迟观测】（仅观测值；采信后能短路「入口轮整段路由推理」，真实节省需切 gate 后同指标对照，本报告不推算）');
  console.log(`  判定耗时 ms ：均值 ${num1(report.cost.judgeLatencyMs.mean)}｜P50 ${num1(report.cost.judgeLatencyMs.p50)}｜P95 ${num1(report.cost.judgeLatencyMs.p95)}｜累计 ${report.cost.judgeLatencyMs.count} 次`);
  console.log(`  判定 Token  ：均值 ${num1(report.cost.judgeTokens.mean)}｜P50 ${num1(report.cost.judgeTokens.p50)}｜P95 ${num1(report.cost.judgeTokens.p95)}｜累计 ${report.cost.judgeTokens.count} 次`);
  console.log(`  入口轮耗时 ms：均值 ${num1(report.cost.entryDurationMs.mean)}（${report.cost.entryDurationMs.count} 行）`);
  console.log(`  入口轮 Token ：均值 ${num1(report.cost.entryTokens.mean)}（${report.cost.entryTokens.count} 行）`);
  console.log(`  判定耗时/入口轮耗时 占比：均值 ${report.cost.latencyRatio.mean === null ? '—' : `${(report.cost.latencyRatio.mean * 100).toFixed(1)}%`}（${report.cost.latencyRatio.count} 行）`);
  if (report.consistency.inconsistent.length) {
    console.log(line);
    console.log(`【不一致清单（最多 10 条 / 共 ${report.consistency.inconsistent.length} 条）】`);
    for (const item of report.consistency.inconsistent.slice(0, 10)) {
      console.log(`  #${item.routeRunId}｜action=${item.action}｜LLM专家=${item.llmExpertKey || '—'}｜判定=${item.judgeChoice || '—'}`
        + `｜置信度=${num1(item.judgeConfidence)}｜err=${item.judgeError || '—'}`);
    }
  }
  console.log(line);
};

/**
 * 数据不足时的明确指引（退出码仍为 0）。
 */
const printInsufficientHint = () => {
  console.log('⚠ 当前窗口内没有任何「有判定数据」的编排（judgedRows=0），无法计算一致率。');
  console.log('  请先打开影子档并跑若干轮对话：');
  console.log('    · 页面：「安全合规配置 → 结构化决策路由」切到「影子」档，或');
  console.log('    · 命令行：node e2e/jev/verify-shadow.mjs --keep-enabled（跑完保留影子档继续采集）');
  console.log('  采集后重跑本脚本即可。');
};

const main = async () => {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(`✗ ${error.message}`);
    process.exit(EXIT.USAGE);
  }

  const cfg = loadConfig({ baseUrl: args.baseUrl });
  const http = new HttpClient(cfg, { timeoutMs: 20000, verbose: false, log: () => {} });

  let fetched;
  try {
    await http.login();
    fetched = await fetchRows(http, args);
  } catch (error) {
    console.error(`✗ 配置缺失或登录/请求失败：${error.message}`);
    process.exit(EXIT.CONFIG);
  }

  const rows = fetched.rows;
  const pinnedRows = rows.filter((row) => row.action === 'pinned');
  const judgedRows = rows.filter(hasJudge);
  const consistencyRows = judgedRows.filter((row) => row.action === 'self' || row.action === 'route');
  const pinnedJudgedRows = judgedRows.filter((row) => row.action === 'pinned');
  const noJudgeRows = rows.filter((row) => (row.action === 'self' || row.action === 'route') && !hasJudge(row));

  const consistency = evaluateConsistency(consistencyRows, judgedRows.length);
  const cost = summarizeCost(judgedRows);

  const report = {
    baseUrl: cfg.baseUrl,
    args,
    fetch: { fetched: fetched.fetched, total: fetched.total, truncated: fetched.truncated },
    window: { rows: rows.length, cutoff: new Date(fetched.cutoff).toISOString() },
    layers: {
      pinnedCount: pinnedRows.length,
      judgedRows: judgedRows.length,
      consistencyRows: consistencyRows.length,
      pinnedJudgedRows: pinnedJudgedRows.length,
      judgedUnknownAction: judgedRows.length - consistencyRows.length - pinnedJudgedRows.length,
      noJudgeRows: noJudgeRows.length
    },
    consistency,
    cost,
    note: '采信后能短路的是「入口轮的整段路由推理」；其真实节省需切 gate 后用同一指标对照，本报告不推算。'
  };

  printReport(report);
  if (judgedRows.length === 0) {
    printInsufficientHint();
  }

  if (args.json) {
    fs.writeFileSync(path.resolve(cfg.repoRoot, args.json), JSON.stringify(report, null, 2), 'utf8');
    console.log(`报告已写入：${path.resolve(cfg.repoRoot, args.json)}`);
  }
  process.exit(EXIT.OK);
};

main().catch((error) => {
  console.error(`✗ 未预期异常：${error?.stack || error}`);
  process.exit(EXIT.CONFIG);
});