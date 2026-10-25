#!/usr/bin/env node
/**
 * 百炼决策模型（TypeSafe System One 协议）连通性验证脚本。
 *
 * <p>用途：在给 BizBuddy 接入「结构化决策」做专家路由之前，先用真实请求确认三件事：
 * <ol>
 *   <li>endpoint / API Key / 模型名是否可用（含地域与业务空间子域名是否正确）；</li>
 *   <li>请求体与返回体结构是否与 AgentScope {@code JevClient} 的 DTO 对齐
 *       （字段名、choice/noul 两种答案形态、probabilities 是否归一）；</li>
 *   <li>顺带打印 {@code JevClient} 会拼出的真实 URL（照抄其 systemOneUrl 逻辑），
 *       便于直接填到 {@code agentscope.jev.base-url}。</li>
 * </ol>
 * 注意：本脚本只验证**协议与结构**；「JevClient 能否解析该返回」需要 Java 侧冒烟
 * （引入 agentscope-extensions-jev 后按同一 base-url 跑一次），属下一步。</p>
 *
 * <p>用法：
 * <pre>
 *   node e2e/http/jev/check-systemone.mjs                        # 用环境变量 / e2e/http/.env.local
 *   node e2e/http/jev/check-systemone.mjs --probe-all             # 依次探测所有候选 endpoint
 *   node e2e/http/jev/check-systemone.mjs --base-url https://xxx.cn-beijing.maas.aliyuncs.com/compatible-mode
 * </pre></p>
 *
 * <p>配置项（环境变量优先，其次 {@code e2e/http/.env.local}，复用 e2e/http/lib/env.mjs 的解析）：
 * <ul>
 *   <li>{@code BIZBUDDY_JEV_API_KEY}：百炼 API Key；缺省回退 {@code BIZBUDDY_LLM_API_KEY}（同一把百炼密钥）</li>
 *   <li>{@code BIZBUDDY_JEV_WORKSPACE_ID}：业务空间 ID（百炼决策模型的 endpoint 主机前缀）</li>
 *   <li>{@code BIZBUDDY_JEV_BASE_URL}：含 {@code /compatible-mode} 的基础地址（不含 {@code /v1/systemone}）</li>
 *   <li>{@code BIZBUDDY_JEV_MODEL}：默认 {@code decision-model-preview}</li>
 * </ul></p>
 *
 * <p>退出码：{@code 0} 校验通过 / {@code 1} 结构校验失败 / {@code 2} 配置缺失 /
 * {@code 3} 全部候选 endpoint 均不可达。</p>
 */

import path from 'node:path';
import url from 'node:url';

import { parseEnvFile } from '../lib/env.mjs';

const EXIT = { OK: 0, INVALID: 1, USAGE: 2, UNREACHABLE: 3 };

/** System One 协议路径（与 AgentScope JevClient.SYSTEM_ONE_ENDPOINT 一致） */
const SYSTEM_ONE_ENDPOINT = '/v1/systemone';

/** 请求超时（毫秒） */
const TIMEOUT_MS = 15000;

/** 概率和容差（JevClient 逐项容差为 1e-6，整体判定放宽到 1e-4） */
const PROBABILITY_SUM_TOLERANCE = 1e-4;

/**
 * 与项目演示种子一致的候选专家清单，用于构造「专家路由」同构的 choice 问题。
 * 刻意带上 other 兜底项（官方建议），并保留 self（小Z 自答）。
 */
const DEMO_CRITERIA = {
  self: '用户问题不需要专业领域知识，入口智能体可直接回答',
  notice_admin_expert: '公告的创建、查询与维护等公告管理事务',
  bizbuddy_intro_expert: 'BizBuddy 产品介绍、能力与架构相关问题',
  other: '无法判断归属，或候选专家都不合适'
};

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 参数列表
 * @returns {object} { baseUrl, probeAll }
 */
const parseArgs = (argv) => {
  const args = { baseUrl: '', probeAll: false };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token === '--probe-all') {
      args.probeAll = true;
      continue;
    }
    if (token === '--base-url') {
      args.baseUrl = argv[++i] ?? '';
      if (!args.baseUrl) {
        throw new Error('--base-url 缺少取值');
      }
      continue;
    }
    throw new Error(`未知参数：${token}`);
  }
  return args;
};

/**
 * 载入配置：环境变量 > e2e/http/.env.local > 默认值。
 *
 * @param {object} args 命令行参数
 * @returns {object} 配置
 */
const loadConfig = (args) => {
  const here = path.dirname(url.fileURLToPath(import.meta.url));
  const localEnv = parseEnvFile(path.resolve(here, '..', '.env.local'));
  const pick = (key, fallback = '') => (process.env[key] ?? localEnv[key] ?? fallback).trim();

  return {
    apiKey: pick('BIZBUDDY_JEV_API_KEY') || pick('BIZBUDDY_LLM_API_KEY'),
    workspaceId: pick('BIZBUDDY_JEV_WORKSPACE_ID'),
    baseUrl: args.baseUrl || pick('BIZBUDDY_JEV_BASE_URL'),
    model: pick('BIZBUDDY_JEV_MODEL') || 'decision-model-preview'
  };
};

/**
 * 照抄 AgentScope JevClient#systemOneUrl 的拼接规则，保证打印的 URL 就是客户端会请求的地址。
 *
 * @param {string} baseUrl 基础地址
 * @returns {string} System One 完整地址
 */
const systemOneUrl = (baseUrl) => {
  const normalized = baseUrl.replace(/\/+$/, '');
  if (normalized.endsWith(SYSTEM_ONE_ENDPOINT)) {
    return normalized;
  }
  if (normalized.endsWith('/v1')) {
    return `${normalized}/systemone`;
  }
  return `${normalized}${SYSTEM_ONE_ENDPOINT}`;
};

/**
 * 组装待探测的候选 endpoint：显式指定优先，否则按地域枚举（含业务空间子域名）。
 *
 * @param {object} cfg 配置
 * @returns {string[]} 候选基础地址
 */
const buildCandidates = (cfg) => {
  if (cfg.baseUrl) {
    return [cfg.baseUrl];
  }
  const hosts = [];
  if (cfg.workspaceId) {
    hosts.push(`https://${cfg.workspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode`);
    hosts.push(`https://${cfg.workspaceId}.ap-southeast-1.maas.aliyuncs.com/compatible-mode`);
  }
  hosts.push('https://dashscope.aliyuncs.com/compatible-mode');
  hosts.push('https://dashscope-intl.aliyuncs.com/compatible-mode');
  return hosts;
};

/**
 * 构造请求体：一次携带「专家路由 choice」与「是否需要人工介入 noul」两个问题，
 * 同时覆盖两种答案形态（choice 带 confidence、noul 不带）。
 *
 * @param {string} model 模型名
 * @returns {object} 请求体
 */
const buildRequestBody = (model) => ({
  model,
  state: {
    user_message: '帮我把上个月发过的那条公告标题改一下，顺便看看现在都有哪些公告？',
    entry_agent: '小Z',
    candidates: Object.keys(DEMO_CRITERIA)
  },
  questions: {
    expert: {
      type: 'choice',
      instructions: '该问题应由哪个智能体处理？',
      criteria: DEMO_CRITERIA
    },
    need_human: {
      type: 'noul',
      instructions: '这是否涉及需要人工确认的写操作？'
    }
  }
});

/**
 * 发起一次 System One 请求。
 *
 * @param {string} targetUrl 完整地址
 * @param {object} cfg 配置
 * @returns {Promise<object>} { status, body, error, elapsedMs }
 */
const callSystemOne = async (targetUrl, cfg) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  const startedAt = Date.now();
  try {
    const response = await fetch(targetUrl, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${cfg.apiKey}`,
        Accept: 'application/json',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(buildRequestBody(cfg.model)),
      signal: controller.signal
    });
    const text = await response.text();
    let body = null;
    try {
      body = JSON.parse(text);
    } catch {
      body = { _raw: text.slice(0, 300) };
    }
    return { status: response.status, body, elapsedMs: Date.now() - startedAt };
  } catch (error) {
    return { status: 0, body: null, error: error.message, elapsedMs: Date.now() - startedAt };
  } finally {
    clearTimeout(timer);
  }
};

/**
 * 判定概率表是否归一。
 *
 * @param {object} probabilities 概率表
 * @returns {boolean} 是否归一
 */
const isNormalized = (probabilities) => {
  if (!probabilities || typeof probabilities !== 'object') {
    return false;
  }
  const values = Object.values(probabilities);
  if (!values.length || values.some((item) => typeof item !== 'number')) {
    return false;
  }
  const sum = values.reduce((acc, item) => acc + item, 0);
  return Math.abs(sum - 1) <= PROBABILITY_SUM_TOLERANCE;
};

/**
 * 严格校验返回结构（对齐 JevClient.validateResult 的检查点）。
 *
 * @param {object} body 响应体
 * @param {string} model 期望模型名
 * @returns {{ok: boolean, notes: string[], facts: object}} 校验结果
 */
const validateAnswers = (body, model) => {
  const notes = [];
  const facts = {};
  if (!body || typeof body !== 'object') {
    return { ok: false, notes: ['响应体不是 JSON 对象'], facts };
  }
  if (body.model && body.model !== model) {
    notes.push(`模型名回显不一致：期望 ${model}，实际 ${body.model}`);
  }

  const answers = body.answers;
  if (!answers || typeof answers !== 'object') {
    return { ok: false, notes: [...notes, '缺少 answers 对象'], facts };
  }

  const choice = answers.expert;
  if (!choice || choice.type !== 'choice') {
    notes.push(`answers.expert.type 应为 choice，实际 ${choice?.type ?? '(缺失)'}`);
  } else {
    facts.choice = choice.choice;
    facts.confidence = choice.confidence;
    facts.probabilities = choice.probabilities;
    if (!Object.keys(DEMO_CRITERIA).includes(choice.choice)) {
      notes.push(`answers.expert.choice=${choice.choice} 不在 criteria 选项内`);
    }
    if (typeof choice.confidence !== 'number' || choice.confidence < 0 || choice.confidence > 1) {
      notes.push(`choice 置信度非法：${choice.confidence}`);
    }
    if (!isNormalized(choice.probabilities)) {
      notes.push('choice 概率表缺失或未归一');
    }
  }

  const noul = answers.need_human;
  if (!noul || noul.type !== 'noul') {
    notes.push(`answers.need_human.type 应为 noul，实际 ${noul?.type ?? '(缺失)'}`);
  } else if (typeof noul.noul !== 'number' || noul.noul < 0 || noul.noul > 1) {
    notes.push(`noul 概率非法：${noul.noul}`);
  } else {
    facts.needHuman = noul.noul;
    // noul 官方口径不返回 confidence，仅记录是否出现，供 JevClient DTO 兼容性判断参考
    facts.noulHasConfidence = Object.prototype.hasOwnProperty.call(noul, 'confidence');
  }

  facts.inputTokens = body.usage?.input_tokens;
  facts.latencyMs = body.latency_ms;
  facts.requestId = body.request_id;
  if (typeof facts.inputTokens !== 'number') {
    notes.push('usage.input_tokens 缺失（费用口径需要）');
  }
  return { ok: notes.length === 0, notes, facts };
};

/**
 * 探测单个候选地址。
 *
 * @param {string} baseUrl 候选基础地址
 * @param {object} cfg 配置
 * @returns {Promise<{reachable: boolean, valid: boolean}>} 探测结论
 */
const probe = async (baseUrl, cfg) => {
  const targetUrl = systemOneUrl(baseUrl);
  console.log(`\n· 探测 ${targetUrl}`);
  const result = await callSystemOne(targetUrl, cfg);

  if (result.status !== 200) {
    const hint = result.status === 0 ? result.error : `HTTP ${result.status}`;
    console.log(`  ✗ 不可用（${hint}，${result.elapsedMs}ms）`);
    if (result.body) {
      console.log(`    响应：${JSON.stringify(result.body).slice(0, 300)}`);
    }
    return { reachable: false, valid: false };
  }

  const { ok, notes, facts } = validateAnswers(result.body, cfg.model);
  console.log(`  ✓ 可达（HTTP 200，${result.elapsedMs}ms）`);
  console.log(`    choice=${facts.choice}｜confidence=${facts.confidence}｜概率=${JSON.stringify(facts.probabilities)}`);
  console.log(`    noul P(yes)=${facts.needHuman}｜noul 是否带 confidence=${facts.noulHasConfidence}`);
  console.log(`    input_tokens=${facts.inputTokens}｜服务端 latency_ms=${facts.latencyMs}｜request_id=${facts.requestId}`);
  if (ok) {
    console.log('  ✓ 结构校验通过（字段与 JevClient DTO 对齐，可作 agentscope.jev.base-url）');
    console.log(`    agentscope.jev.base-url: ${baseUrl}`);
    console.log(`    agentscope.jev.model:    ${cfg.model}`);
  } else {
    console.log('  ✗ 结构校验未通过：');
    notes.forEach((note) => console.log(`    - ${note}`));
  }
  return { reachable: true, valid: ok };
};

const main = async () => {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(`✗ ${error.message}`);
    process.exit(EXIT.USAGE);
  }

  const cfg = loadConfig(args);
  if (!cfg.apiKey) {
    console.error('✗ 缺少 API Key：请设置 BIZBUDDY_JEV_API_KEY（或 BIZBUDDY_LLM_API_KEY）到环境变量或 e2e/http/.env.local');
    process.exit(EXIT.USAGE);
  }

  const candidates = buildCandidates(cfg);
  console.log(`决策模型连通性检查｜模型 ${cfg.model}｜密钥 ${cfg.apiKey.slice(0, 5)}***（长度 ${cfg.apiKey.length}）`);
  if (!cfg.workspaceId && !cfg.baseUrl) {
    console.log('提示：未提供 BIZBUDDY_JEV_WORKSPACE_ID，业务空间专属 endpoint 不会被探测（百炼决策模型通常要求该子域名）');
  }

  let reachable = false;
  let valid = false;
  for (const candidate of candidates) {
    const outcome = await probe(candidate, cfg);
    reachable = reachable || outcome.reachable;
    valid = valid || outcome.valid;
    if (outcome.valid && !args.probeAll) {
      break;
    }
  }

  console.log(
    valid
      ? '\n汇总：协议连通且结构校验通过 ✓'
      : reachable
        ? '\n汇总：endpoint 可达但结构校验未通过 ✗'
        : '\n汇总：全部候选 endpoint 均不可达 ✗（请确认业务空间 ID、地域与网络出网策略）'
  );
  process.exit(valid ? EXIT.OK : reachable ? EXIT.INVALID : EXIT.UNREACHABLE);
};

main().catch((error) => {
  console.error(`✗ 未预期异常：${error?.stack || error}`);
  process.exit(EXIT.INVALID);
});