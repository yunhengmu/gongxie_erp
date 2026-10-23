#!/usr/bin/env node
/**
 * 长期记忆真实流量门禁一键执行（T15 e2e 化）。
 *
 * <p>把原先手工的四步（快照环境 → 跑流量 → 导出影子样本 → 判门禁 → 还原环境）
 * 收敛成一条命令，便于改提示词 / 抽调取逻辑后做回归。判定逻辑复用 `shadow-gate.mjs`，
 * 流量生成复用 `shadow-traffic.mjs`，本脚本只负责编排与现场还原。</p>
 *
 * <p>用法：
 * <pre>
 *   node e2e/memory-eval/gate.mjs --gate
 *   node e2e/memory-eval/gate.mjs --only date-format,currency-cny      # 快速验证
 *   node e2e/memory-eval/gate.mjs --out gate-report.md                 # 额外输出 Markdown
 *   BIZBUDDY_DOCKER="wsl -e docker" node e2e/memory-eval/gate.mjs      # 指定 docker 入口
 * </pre></p>
 *
 * <p>依赖：已运行的后端容器（默认 bizbuddy-admin）与可用的 docker 命令。
 * 脚本会读取容器内记忆目录做快照，结束后**默认还原** MEMORY.md、当日流水与合并/抽取水位，
 * 并清掉本次产生的影子样本，避免测试偏好留在开发账号。</p>
 *
 * 退出码：0 通过 / 1 未通过门禁 / 2 环境或参数错误。
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { evaluate, printReport, renderMarkdown, THRESHOLDS } from './shadow-gate.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = parseArgs(process.argv.slice(2));

/** 解析后的 docker 入口（如 `['wsl', '-e', 'docker']`），在 main 中初始化 */
let dockerCmd = null;

main();

/** 编排入口：快照 → 跑流量 → 判门禁 → 还原。 */
function main() {
  const container = args.container || 'bizbuddy-admin';
  const agentCode = args['agent-code'] || 'entry_agent';
  const userId = String(args['user-id'] || '1761100000000000001');
  const workspaceRoot = args['workspace-root'] || '/ruoyi/server/data/agent-workspace';
  const memoryDir = `${workspaceRoot}/${agentCode}/${userId}`;
  const snapshots = new Map();
  const workDir = path.join(os.tmpdir(), `bizbuddy-memory-gate-${Date.now()}`);
  const labelsPath = path.join(workDir, 'labels.jsonl');
  fs.mkdirSync(workDir, { recursive: true });

  console.log(`门禁阈值：召回 ≥${(THRESHOLDS.recall * 100).toFixed(0)}%，`
    + `污染 ≤${(THRESHOLDS.pollution * 100).toFixed(0)}%`);

  let report = null;
  let failure = null;
  try {
    dockerCmd = resolveDocker();
    console.log(`docker=${dockerCmd.join(' ')} container=${container} memoryDir=${memoryDir}`);
    for (const file of [
      `${memoryDir}/MEMORY.md`,
      `${memoryDir}/memory/${todayInContainer()}.md`,
      `${memoryDir}/memory/.consolidation_state`,
      `${memoryDir}/memory/.extract_state.json`
    ]) {
      snapshotFile(container, file, snapshots);
    }

    runTraffic(labelsPath);
    const samples = readSamples(container, memoryDir);
    const labels = readJsonl(labelsPath);
    report = evaluate(samples, labels, snapshots.get(`${memoryDir}/MEMORY.md`) || '');
    printReport(report);

    if (args.out) {
      const outPath = path.resolve(args.out);
      fs.writeFileSync(outPath, renderMarkdown(report), 'utf8');
      console.log(`报告已写入：${outPath}`);
    }
  } catch (error) {
    failure = error;
    console.error(`\n门禁执行失败：${error.message}`);
  } finally {
    try {
      if (!args['no-restore']) {
        restoreSnapshots(container, snapshots);
      }
      removeShadowArtifacts(container, memoryDir);
    } catch (error) {
      console.error(`现场还原失败，请手工检查：${error.message}`);
      failure = failure || error;
    }
    if (args['keep-work-dir']) {
      console.log(`中间文件保留在：${workDir}`);
    } else {
      fs.rmSync(workDir, { recursive: true, force: true });
    }
  }

  if (failure) {
    process.exit(2);
  }
  process.exit(report && report.passed ? 0 : 1);
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
      continue;
    }
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith('--')) {
      result[key] = next;
      i++;
    } else {
      result[key] = true;
    }
  }
  return result;
}

/**
 * 解析 docker 入口：优先 `BIZBUDDY_DOCKER`，其次尝试本机 docker，最后回退到 WSL 内的 docker。
 *
 * @returns {string[]} 可执行文件 + 前置参数
 */
function resolveDocker() {
  const candidates = [];
  if (process.env.BIZBUDDY_DOCKER) {
    candidates.push(process.env.BIZBUDDY_DOCKER);
  }
  candidates.push('docker', 'wsl -e docker');
  for (const candidate of candidates) {
    const [command, ...prefix] = candidate.split(/\s+/).filter(Boolean);
    const probe = spawnSync(command, [...prefix, 'version', '--format', '{{.Server.Version}}'],
      { encoding: 'utf8' });
    if (probe.status === 0) {
      return [command, ...prefix];
    }
  }
  throw new Error('未找到可用的 docker，请用 BIZBUDDY_DOCKER 指定入口（如 "wsl -e docker"）');
}

/**
 * 执行 docker 子命令，失败时按需抛错。
 *
 * @param {string[]} dockerArgs docker 参数
 * @param {{input?: string, allowFailure?: boolean}} options 输入与容错开关
 * @returns {{ok: boolean, stdout: string, stderr: string}}
 */
function runDocker(dockerArgs, options = {}) {
  const [command, ...prefix] = dockerCmd;
  const result = spawnSync(command, [...prefix, ...dockerArgs],
    { encoding: 'utf8', input: options.input, maxBuffer: 64 * 1024 * 1024 });
  const ok = result.status === 0;
  if (!ok && !options.allowFailure) {
    throw new Error(`docker ${dockerArgs.slice(0, 3).join(' ')} 失败：`
      + `${(result.stderr || result.stdout || result.error?.message || '').trim()}`);
  }
  return { ok, stdout: result.stdout || '', stderr: result.stderr || '' };
}

/** 读取容器内文件正文；不存在时返回 null。 */
function readContainerFile(container, file) {
  const result = runDocker(['exec', container, 'cat', file], { allowFailure: true });
  return result.ok ? result.stdout : null;
}

/** 覆盖写入容器内文件（内容为空字符串时写空文件）。 */
function writeContainerFile(container, file, content) {
  runDocker(['exec', '-i', container, 'sh', '-c', `cat > '${file}'`], { input: content });
}

/** 记录文件快照（不存在记为 null）。 */
function snapshotFile(container, file, snapshots) {
  const content = readContainerFile(container, file);
  snapshots.set(file, content);
  console.log(`快照 ${file}：${content === null ? '不存在' : `${Buffer.byteLength(content, 'utf8')} 字节`}`);
}

/** 把快照写回容器；原本不存在的文件删除，避免留下测试产物。 */
function restoreSnapshots(container, snapshots) {
  for (const [file, content] of snapshots) {
    if (content === null) {
      runDocker(['exec', container, 'sh', '-c', `rm -f '${file}'`], { allowFailure: true });
      continue;
    }
    writeContainerFile(container, file, content);
  }
  console.log('已还原 MEMORY.md / 当日流水 / 合并与抽取水位');
}

/** 清理本次（以及历史遗留）的影子样本：它们只用于评测。 */
function removeShadowArtifacts(container, memoryDir) {
  runDocker(['exec', container, 'sh', '-c',
    `rm -rf '${memoryDir}/memory/.extract_shadow' '${memoryDir}/memory/.extract_shadow_state.json'`],
  { allowFailure: true });
}

/** 以子进程运行真实流量生成器，标签落盘到临时目录。 */
function runTraffic(labelsPath) {
  const trafficArgs = [path.join(here, 'shadow-traffic.mjs'), '--labels-out', labelsPath];
  for (const key of ['cases', 'limit', 'only', 'base-url']) {
    if (args[key]) {
      trafficArgs.push(`--${key}`, String(args[key]));
    }
  }
  console.log(`\n开始跑真实流量：${trafficArgs.slice(1).join(' ')}`);
  const result = spawnSync(process.execPath, trafficArgs, { stdio: 'inherit' });
  if (result.status !== 0) {
    throw new Error(`流量脚本退出码 ${result.status}，请检查后端与登录配置`);
  }
  if (!fs.existsSync(labelsPath)) {
    throw new Error('流量脚本未产出 labels.jsonl');
  }
}

/** 读取本次影子样本（按行解析，空文件视为无样本）。 */
function readSamples(container, memoryDir) {
  const result = runDocker(['exec', container, 'sh', '-c',
    `cat '${memoryDir}/memory/.extract_shadow/'*.jsonl 2>/dev/null || true`], { allowFailure: true });
  const rows = parseJsonl(result.stdout);
  if (!rows.length) {
    throw new Error(`未读到影子样本（${memoryDir}/memory/.extract_shadow）`);
  }
  console.log(`读到影子样本 ${rows.length} 条`);
  return rows;
}

/** 解析 JSONL 文本，忽略空行。 */
function parseJsonl(text) {
  return String(text || '').split(/\r?\n/).filter((line) => line.trim()).map((line) => JSON.parse(line));
}

/** 读取 JSONL 文件。 */
function readJsonl(file) {
  return parseJsonl(fs.readFileSync(file, 'utf8'));
}

/** 容器时区为 Asia/Shanghai，与宿主一致；按本地日期定位当日流水。 */
function todayInContainer() {
  const now = new Date();
  const pad = (value) => String(value).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
