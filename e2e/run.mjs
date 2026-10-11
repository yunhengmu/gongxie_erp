#!/usr/bin/env node
/**
 * BizBuddy 端到端集成测试脚本（外部驱动）。
 *
 * 对**已运行**的容器栈发真实 HTTP 请求做断言，覆盖 L1~L9；不参与 `mvn test`、不引 Testcontainers。
 * 详细设计见 `.trae/documents/端到端集成测试脚本方案.md`。</p>
 *
 * <p>用法：
 * <pre>
 *   node e2e/run.mjs                       # 跑全部（未配密钥的层自动 SKIP）
 *   node e2e/run.mjs --only L1,L3 --verbose
 *   node e2e/run.mjs --allow-writes        # L5 走「接受」路径（会真建公告，收尾自动清理）
 * </pre></p>
 */

import fs from 'node:fs';
import path from 'node:path';

import { loadConfig } from './lib/env.mjs';
import { HttpClient, createThrottle } from './lib/http.mjs';
import { PreflightError, preflight } from './lib/preflight.mjs';
import { maskSecret } from './lib/crypto.mjs';

import * as l1 from './cases/l1.mjs';
import * as l2 from './cases/l2.mjs';
import * as l3 from './cases/l3.mjs';
import * as l4 from './cases/l4.mjs';
import * as l5 from './cases/l5.mjs';
import * as l6 from './cases/l6.mjs';
import * as l7 from './cases/l7.mjs';
import * as l8 from './cases/l8.mjs';
import * as l9 from './cases/l9.mjs';

const CASES = [l1, l2, l3, l4, l5, l6, l7, l8, l9];
const EXIT = { OK: 0, FAIL: 1, PREFLIGHT: 2, USAGE: 3 };

/** 参数错误 */
class UsageError extends Error {}

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 参数列表
 * @returns {object} 解析结果
 */
const parseArgs = (argv) => {
  const valueFlags = {
    '--only': 'only',
    '--skip': 'skip',
    '--timeout': 'timeout',
    '--sse-timeout': 'sseTimeout',
    '--idle-timeout': 'idleTimeout',
    '--base-url': 'baseUrl',
    '--report': 'report'
  };
  const boolFlags = {
    '--allow-writes': 'allowWrites',
    '--verbose': 'verbose',
    '--keep': 'keep',
    '--help': 'help'
  };

  const args = { allowWrites: false, verbose: false, keep: false, help: false };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (boolFlags[token]) {
      args[boolFlags[token]] = true;
      continue;
    }
    if (valueFlags[token]) {
      const value = argv[++i];
      if (value === undefined) {
        throw new UsageError(`参数 ${token} 缺少取值`);
      }
      args[valueFlags[token]] = value;
      continue;
    }
    throw new UsageError(`未知参数：${token}`);
  }

  const layers = (text, flag) => {
    if (!text) {
      return null;
    }
    const parsed = text
      .split(',')
      .map((item) => item.trim().toUpperCase())
      .filter(Boolean);
    const known = CASES.map((item) => item.meta.layer);
    const unknown = parsed.filter((item) => !known.includes(item));
    if (unknown.length) {
      throw new UsageError(`${flag} 中出现未知层级：${unknown.join(',')}（可选：${known.join(',')}）`);
    }
    return parsed;
  };

  args.onlyLayers = layers(args.only, '--only');
  args.skipLayers = layers(args.skip, '--skip') ?? [];
  args.timeoutMs = args.timeout ? Number(args.timeout) * 1000 : 15000;
  args.sseTimeoutMs = args.sseTimeout ? Number(args.sseTimeout) * 1000 : 90000;
  args.idleTimeoutMs = args.idleTimeout ? Number(args.idleTimeout) * 1000 : 20000;
  if ([args.timeoutMs, args.sseTimeoutMs, args.idleTimeoutMs].some((item) => !Number.isFinite(item) || item <= 0)) {
    throw new UsageError('超时参数必须是正数（秒）');
  }
  return args;
};

const usage = () => `BizBuddy 端到端集成测试（e2e）

用法：node e2e/run.mjs [选项]

选项：
  --only L1,L3        只跑指定层级（默认全部；可选 ${CASES.map((item) => item.meta.layer).join(' / ')}）
  --skip L5           跳过指定层级
  --allow-writes      L5 走「接受」路径（会真建公告，收尾自动清理）；默认「拒绝」无副作用
  --verbose           打印请求/响应细节
  --keep              收尾不清理会话/敏感词（调试用；安全配置恢复不受此影响）
  --timeout <秒>      单次 HTTP 超时（默认 15）
  --sse-timeout <秒>  SSE 整轮超时（默认 90）
  --idle-timeout <秒> SSE 空闲超时（默认 20）
  --base-url <url>    后端地址（默认 http://localhost:8080）
  --report <path>     指定报告输出路径

说明：L7 会临时开启内容审核并新增一条临时敏感词，收尾**无条件恢复原配置并删除该词**；
     L1~L6 需模型密钥，L7~L9 不需要（走拒答 / 失败路径，不触发模型调用）。

环境变量：见 e2e/.env.example（机密仅 BIZBUDDY_LLM_API_KEY）

退出码：0 无失败 / 1 有 FAIL / 2 前置检查失败 / 3 参数错误`;

const main = async () => {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(`✗ ${error.message}\n`);
    console.error(usage());
    process.exit(EXIT.USAGE);
  }
  if (args.help) {
    console.log(usage());
    process.exit(EXIT.OK);
  }

  const cfg = loadConfig(args);
  const log = (message) => console.log(message);
  const detail = args.verbose ? log : () => {};
  const http = new HttpClient(cfg, { timeoutMs: args.timeoutMs, verbose: args.verbose, log: detail });

  const registry = { stamp: String(Date.now()), sessions: [], noticeTitles: [], sensitiveWordIds: [], securityRestore: null };
  const throttle = createThrottle(3500);

  log(`BizBuddy e2e｜${cfg.baseUrl}｜密钥 ${maskSecret(cfg.llmApiKey)}｜${args.allowWrites ? 'L5 允许写' : 'L5 默认拒绝'}\n`);

  let resolved;
  try {
    resolved = await preflight(http, cfg, { log });
  } catch (error) {
    console.error(`\n✗ 前置检查失败：${error instanceof PreflightError ? error.message : error.message}`);
    await cleanup(http, registry, args, log);
    process.exit(EXIT.PREFLIGHT);
  }

  const ctx = { cfg, http, args, resolved, registry, log, detail, throttle };
  const selected = CASES.filter((item) => {
    const layer = item.meta.layer;
    if (args.onlyLayers && !args.onlyLayers.includes(layer)) {
      return false;
    }
    return !args.skipLayers.includes(layer);
  });

  log('');
  const results = [];
  for (const testCase of selected) {
    const started = Date.now();
    let outcome;
    try {
      outcome = await testCase.run(ctx);
    } catch (error) {
      outcome = { status: 'FAIL', detail: `执行异常：${error.message}`, evidence: { stack: args.verbose ? error.stack : undefined } };
    }
    const elapsed = Date.now() - started;
    results.push({ layer: testCase.meta.layer, name: testCase.meta.name, elapsedMs: elapsed, ...outcome });
    console.log(`[${testCase.meta.layer}] ${badge(outcome.status)} ${testCase.meta.name}｜${outcome.detail}（${elapsed}ms）`);
    if (outcome.status === 'FAIL' && args.verbose && outcome.evidence) {
      console.log(`      evidence: ${JSON.stringify(outcome.evidence)}`);
    }
  }

  await cleanup(http, registry, args, log);

  const passed = results.filter((item) => item.status === 'PASS').length;
  const skipped = results.filter((item) => item.status === 'SKIP').length;
  const failed = results.filter((item) => item.status === 'FAIL').length;
  console.log(`\n汇总：PASS ${passed}｜SKIP ${skipped}｜FAIL ${failed}`);

  if (failed > 0 || args.report) {
    const reportPath = args.report || path.join(cfg.e2eDir, `.e2e-report-${registry.stamp}.json`);
    fs.writeFileSync(
      reportPath,
      JSON.stringify(
        {
          baseUrl: cfg.baseUrl,
          startedAt: registry.stamp,
          llmApiKey: maskSecret(cfg.llmApiKey),
          allowWrites: args.allowWrites,
          summary: { passed, skipped, failed },
          results
        },
        null,
        2
      ),
      'utf8'
    );
    console.log(`报告已写入：${reportPath}`);
  }

  process.exit(failed > 0 ? EXIT.FAIL : EXIT.OK);
};

/**
 * 状态徽标。
 *
 * @param {string} status 状态
 * @returns {string} 徽标
 */
const badge = (status) => ({ PASS: 'PASS', FAIL: 'FAIL', SKIP: 'SKIP' })[status] ?? status;

/**
 * 收尾清理：**无条件执行**（即使前面失败也清），保证多跑不产生脏数据。
 *
 * @param {HttpClient} http HTTP 客户端
 * @param {object} registry 资源登记表
 * @param {object} args 参数
 * @param {Function} log 日志
 * @returns {Promise<void>}
 */
const cleanup = async (http, registry, args, log) => {
  // 安全配置恢复**先于且不受 --keep 影响**：它是「撤销临时改动」而非数据清理，
  // 若不恢复会把 L7 临时开启的内容审核（action=block）遗留在运行期配置里。
  if (registry.securityRestore) {
    try {
      await http.request('PUT', '/ai/runtime/security', { body: registry.securityRestore });
      log('  ✓ 已恢复安全合规配置（撤销 L7 临时改动）');
    } catch (error) {
      log(`  ! 恢复安全合规配置失败：${error.message}`);
    }
    registry.securityRestore = null;
  }

  if (args.keep) {
    log(`\n· 收尾：跳过清理（--keep）｜会话 ${registry.sessions.length} 个、公告标题 ${registry.noticeTitles.length} 个、敏感词 ${registry.sensitiveWordIds.length} 个`);
    return;
  }
  log('\n· 收尾清理');

  // 临时敏感词（L7）：逻辑删除即可，词库缓存随删除刷新
  if (registry.sensitiveWordIds.length) {
    try {
      await http.request('DELETE', `/ai/safety/word/${registry.sensitiveWordIds.join(',')}`);
      log(`  ✓ 删除临时敏感词 ${registry.sensitiveWordIds.length} 条`);
    } catch (error) {
      log(`  ! 删除临时敏感词失败：${error.message}`);
    }
  }

  // 注意：「关闭会话」(`DELETE /ai/chat/session/{id}`) 只把 status 置为已关闭、**不删行**；
  // 真正的清理由会话管理的删除接口负责（逻辑删除，并一并清理该会话的 Redis 记忆）。
  if (registry.sessions.length) {
    try {
      await http.request('DELETE', `/ai/session/${registry.sessions.join(',')}`);
      log(`  ✓ 删除会话 ${registry.sessions.length} 个（含 Redis 记忆）`);
    } catch (error) {
      log(`  ! 删除会话失败：${error.message}`);
    }
  }

  for (const title of registry.noticeTitles) {
    try {
      const page = await http.request('GET', '/system/notice/list', { query: { noticeTitle: title, pageNum: 1, pageSize: 50 } });
      const ids = (page?.rows ?? []).map((item) => item.noticeId ?? item.id).filter(Boolean);
      if (!ids.length) {
        log(`  · 无残留公告（${title}）`);
        continue;
      }
      await http.request('DELETE', `/system/notice/${ids.join(',')}`);
      log(`  ✓ 清理公告 ${ids.length} 条（${title}）`);
    } catch (error) {
      log(`  ! 清理公告 ${title} 失败：${error.message}`);
    }
  }
};

main().catch((error) => {
  console.error(`✗ 未预期异常：${error?.stack || error}`);
  process.exit(EXIT.FAIL);
});
