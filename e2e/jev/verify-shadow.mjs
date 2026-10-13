#!/usr/bin/env node
/**
 * 结构化决策「影子档」运行时验证（docs/21 落地进展 T5 的实机验证）。
 *
 * <p>一次跑完五步：读配置 → 选决策模型 → 打开影子档 → 探测连通性 → 真实对话一轮 → 回读编排运行 → 恢复关闭。
 * 脚本只做「开启 + 触发 + 回读接口」，**判定审计列（judge_*）用 SQL 复核**，脚本末尾会打印复核命令。</p>
 *
 * <p><b>决策模型来源</b>：结构化决策配置不再直填地址 / 模型 / 密钥，改为引用「模型配置」里
 * {@code modelType=decision} 的启用记录。本脚本默认取第一条可用决策模型，也可用
 * {@code --model-config-id} 显式指定。</p>
 *
 * <p>用法：
 * <pre>
 *   node e2e/jev/verify-shadow.mjs                   # 用 e2e/.env.local 的业务空间地址，自动选决策模型
 *   node e2e/jev/verify-shadow.mjs --keep-enabled    # 跑完不恢复「关闭」（留着采集影子数据）
 *   node e2e/jev/verify-shadow.mjs --model-config-id 9
 *   node e2e/jev/verify-shadow.mjs --base-url http://localhost:8081
 *   node e2e/jev/verify-shadow.mjs --message "帮我看看有哪些公告"
 * </pre></p>
 *
 * <p>退出码：{@code 0} 全流程通过 / {@code 1} 有断言失败 / {@code 2} 配置缺失 / {@code 3} 参数错误。</p>
 */

import { loadConfig } from '../lib/env.mjs';
import { HttpClient } from '../lib/http.mjs';
import { readSse } from '../lib/sse.mjs';

const EXIT = { OK: 0, FAIL: 1, USAGE: 2 };

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 参数
 * @returns {object} 选项
 */
const parseArgs = (argv) => {
  const args = { baseUrl: '', modelConfigId: '', message: '', keepEnabled: false, mode: '', threshold: null };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token === '--keep-enabled') {
      args.keepEnabled = true;
      continue;
    }
    if (token === '--mode') {
      args.mode = argv[++i] ?? '';
      continue;
    }
    if (token === '--threshold') {
      args.threshold = Number(argv[++i]);
      continue;
    }
    if (token === '--base-url') {
      args.baseUrl = argv[++i] ?? '';
      continue;
    }
    if (token === '--model-config-id') {
      args.modelConfigId = argv[++i] ?? '';
      continue;
    }
    if (token === '--message') {
      args.message = argv[++i] ?? '';
      continue;
    }
    throw new Error(`未知参数：${token}`);
  }
  return args;
};

/**
 * 从「模型配置」里选一条启用中的决策模型（{@code modelType=decision}）。
 *
 * @param {HttpClient} http HTTP 客户端
 * @param {string} explicitId 显式指定的模型主键（空表示自动取第一条）
 * @returns {Promise<object>} 决策模型选项（含 id / configName / modelCode）
 */
const resolveDecisionModel = async (http, explicitId) => {
  const options = await http.request('GET', '/ai/model/options', { query: { modelType: 'decision' } });
  const list = options ?? [];
  if (explicitId !== '' && explicitId != null) {
    const hit = list.find((item) => String(item.id) === String(explicitId));
    if (!hit) {
      throw new Error(`指定的决策模型 #${explicitId} 不在可用选项内（需为启用中的 decision 类型模型）`);
    }
    return hit;
  }
  if (!list.length) {
    throw new Error('「模型配置」里没有启用中的决策模型，请先新增一条 decision 类型模型');
  }
  return list[0];
};

/**
 * 组装保存用的配置体（只含后端 Bo 的字段）：连接信息不再直填，只带所选决策模型主键。
 *
 * @param {number|string} modelConfigId 所选决策模型主键
 * @param {boolean} enabled 是否开启
 * @param {string} mode 判定档（shadow / gate）
 * @param {number} threshold 置信度阈值
 * @returns {object} 配置体
 */
const jevPayload = (modelConfigId, enabled, mode = 'shadow', threshold = 0.75) => ({
  enabled,
  modelConfigId,
  timeoutSeconds: 1,
  mode,
  confidenceThreshold: threshold,
  stateMaxChars: 4000,
  persistProbabilities: true,
  circuitBreakerEnabled: true,
  circuitBreakerThreshold: 3,
  circuitBreakerPauseSeconds: 300
});

/**
 * 轮询编排运行直到终态（判定与状态在收尾时才落库，读太早会看到 running）。
 *
 * <p>走 {@code /ai/route/run/list}（按会话过滤）而非会话内的 routeRuns：前者随列表 VO 返回判定列，
 * 后者不含判定字段（口径差异见 docs/21 T7）。</p>
 *
 * @param {object} http HTTP 客户端
 * @param {number|string} sessionId 会话主键
 * @param {number} attempts 最大轮询次数
 * @returns {Promise<object|null>} 最后一条编排运行
 */
const waitForRouteRun = async (http, sessionId, attempts = 20) => {
  for (let i = 0; i < attempts; i++) {
    const page = await http.request('GET', '/ai/route/run/list', {
      query: { sessionId, pageNum: 1, pageSize: 10 }
    });
    const rows = page?.rows ?? [];
    // 列表按 createTime desc 排序，首行即本会话最近一次编排
    const latest = rows.length ? rows[0] : null;
    // 非 running 即视为已收尾（success / degraded / aborted / pending）
    if (latest && latest.status && latest.status !== 'running') {
      return latest;
    }
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  return null;
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
  await http.login();

  // 选决策模型（「模型配置」里的 decision 类型启用记录）
  let decisionModel;
  try {
    decisionModel = await resolveDecisionModel(http, args.modelConfigId);
  } catch (error) {
    console.error(`✗ ${error.message}`);
    process.exit(EXIT.USAGE);
  }
  const modelConfigId = decisionModel.id;

  console.log(`结构化决策影子档运行时验证｜${cfg.baseUrl}`);
  console.log(`决策模型｜#${modelConfigId}｜${decisionModel.configName}｜${decisionModel.modelCode}`);

  let failed = false;
  const check = (ok, label, detail) => {
    console.log(`  ${ok ? '✓' : '✗'} ${label}｜${detail}`);
    if (!ok) {
      failed = true;
    }
  };

  // 1. 读取当前配置（验证连接信息不经本接口下发）
  const before = await http.request('GET', '/ai/runtime/jev');
  check(
    before.baseUrl === undefined && before.model === undefined && before.apiKey === undefined,
    'GET /ai/runtime/jev 不下载连接信息',
    `enabled=${before.enabled}｜mode=${before.mode}｜modelConfigId=${before.modelConfigId ?? '(未选)'}`
  );

  // 2. 打开配置（选中决策模型，走运行期配置存 Redis）；模式与阈值可命令行覆盖
  const mode = args.mode || 'shadow';
  const threshold = args.threshold ?? 0.75;
  const modeLabel = mode === 'gate' ? '接管（置信度闸门）' : '影子（只留痕）';
  await http.request('PUT', '/ai/runtime/jev', { body: jevPayload(modelConfigId, true, mode, threshold) });
  const applied = await http.request('GET', '/ai/runtime/jev');
  check(applied.enabled === true && applied.mode === mode, `已切换为「${modeLabel}」档`, `enabled=${applied.enabled}｜mode=${applied.mode}｜阈值=${applied.confidenceThreshold}｜modelConfigId=${applied.modelConfigId}`);
  check(String(applied.modelConfigId) === String(modelConfigId), '所选决策模型已生效', `modelConfigId=${applied.modelConfigId}`);

  // 3. 连通性探测（走健康自检的探测通道，真实调一次判定）
  const probe = await http.request('POST', '/ai/health/probe', { body: { target: 'jev' } });
  check(probe?.status === 'PASS', '连通性探测', `${probe?.status}｜${probe?.detail}`);

  // 4. 真实对话一轮（刻意不指定专家 → 走自然路由，判定才会触发）
  const entry = await http.request('GET', '/ai/agent/entry');
  const title = `SHADOW_VERIFY_${Date.now()}`;
  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: entry.id, title }
  });
  const question = args.message || '帮我看看现在有哪些公告，另外简单说说你是谁';
  const userMessage = await http.request('POST', `/ai/chat/session/${session.id}/message`, {
    body: { content: question, attachmentIds: [] }
  });
  const { events, stopReason } = await readSse(http, '/ai/chat/stream', {
    query: { sessionId: session.id, messageId: userMessage.id },
    timeoutMs: 90000,
    idleMs: 30000
  });
  check(stopReason === 'stop-event', '对话一轮完成', `会话 ${session.id}｜${events.length} 个 SSE 事件｜${stopReason}`);

  // 5. 回读编排运行（轮询到终态；判定列由 T7 起随列表 VO 返回）
  const run = await waitForRouteRun(http, session.id);
  check(Boolean(run), '编排运行已落库', run ? `action=${run.action}｜status=${run.status}` : '未取到编排运行');
  if (run) {
    const expectMode = mode === 'gate' ? 'G' : 'S';
    check(run.judgeMode === expectMode, '判定模式已落库', `judge_mode=${run.judgeMode}（期望 ${expectMode}）`);
    check(run.judgeChoice != null, '判定结果已落库', `judge_choice=${run.judgeChoice}｜confidence=${run.judgeConfidence}｜latency=${run.judgeLatencyMs}ms｜tokens=${run.judgeTokens}｜error=${run.judgeError || '（无）'}`);
    if (mode === 'gate') {
      // 接管档：采信记 J、回落记 F，两者都是合法结果，此处只校验来源口径与编排动作
      check(run.decisionSource === 'J' || run.decisionSource === 'F', '接管档判定来源口径正确', `decision_source=${run.decisionSource}｜action=${run.action}｜task_context=${String(run.taskContext ?? '').slice(0, 40)}`);
    }
  }

  // 6. 恢复「关闭」（默认；--keep-enabled 时保留影子档继续采集）
  if (!args.keepEnabled) {
    await http.request('PUT', '/ai/runtime/jev', { body: jevPayload(modelConfigId, false) });
    const restored = await http.request('GET', '/ai/runtime/jev');
    check(restored.enabled === false, '已恢复「关闭」档', `enabled=${restored.enabled}｜modelConfigId=${restored.modelConfigId}`);
  } else {
    console.log('  · 按 --keep-enabled 保留影子档（继续采集对照语料）');
  }

  console.log('\n—— SQL 复核（判定审计列）——');
  console.log(`docker exec bizbuddy-postgres psql -U postgres -d biz_buddy -x -c "select id, action, status, decision_source, judge_mode, judge_choice, judge_confidence, judge_latency_ms, judge_tokens, judge_error from ai_route_run where session_id = ${session.id}"`);
  console.log(`\n汇总：${failed ? '存在失败项 ✗' : '全部通过 ✓'}｜会话主键 ${session.id}`);
  process.exit(failed ? EXIT.FAIL : EXIT.OK);
};

main().catch((error) => {
  console.error(`✗ 未预期异常：${error?.stack || error}`);
  process.exit(EXIT.FAIL);
});