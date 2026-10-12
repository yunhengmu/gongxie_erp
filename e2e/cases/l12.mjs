/**
 * L12 结构化决策闸门（桩服务四分支）：用**本地桩服务**覆盖「网关档」的四条分支 + 关闭档零写入。
 *
 * <p><b>为何需要桩</b>：判定结果（高 / 低置信、越界、异常）要确定性可控，真实决策模型做不到。
 * 桩（{@code e2e/jev/SystemOneStub.java}）按请求 {@code state} 里的 ASCII 标记返回对应响应，
 * 后端把它当百炼决策模型解析，即可稳定复现四条分支。</p>
 *
 * <p><b>桩必须跑进 compose 网络</b>：容器解析不到 {@code host.docker.internal}（docker 网关是 WSL 主机），
 * 故桩不能跑在 Windows 上。操作者需**先在 WSL 内**启动桩（用例本身不 shell out，保持纯 HTTP）：</p>
 * <pre>
 *   wsl -d Ubuntu-22.04 -- docker run -d --rm --name jev-stub --network bizbuddy_default -p 18080:8080 \
 *     -v /mnt/d/test/mybuddy/biz-buddy/e2e/jev:/app bellsoft/liberica-openjdk-rocky:21.0.12-cds java /app/SystemOneStub.java 8080
 * </pre>
 *
 * <p>前置不满足（桩不可达 / 运行期配置接口不可读 / 平台无对话密钥）→ {@code SKIP}，不判 {@code FAIL}。
 * <b>收尾无条件恢复</b>：开始前先 {@code GET /ai/runtime/jev} **快照原配置**，{@code finally} 里把
 * <b>除密钥之外</b>的所有字段（{@code baseUrl} / {@code model} / {@code timeoutSeconds} / {@code mode} /
 * {@code confidenceThreshold} / {@code failOpen} / {@code stateMaxChars} / {@code persistProbabilities}）
 * 连同 {@code enabled=false} 一起 PUT 回去，避免把地址与模型清空；密钥传空串＝不修改。
 * 会话登记到 {@code registry.sessions} 以便统一清理。</p>
 *
 * <p><b>已知取舍 / 密钥处置</b>：本层<b>不写密钥</b>——测试配置与收尾的 {@code apiKey} 一律传空串
 * （＝不修改已保存密钥），且桩服务不校验 {@code Authorization}，故原有密钥始终不被覆盖，
 * 无需事后手工恢复。收尾只把**非密钥字段**按快照回填并置 {@code enabled=false}。</p>
 */

import { readSse, lastOfType } from '../lib/sse.mjs';

export const meta = { layer: 'L12', name: '结构化决策闸门（桩服务四分支）', needsKey: true };

/** 桩健康检查地址（Windows 侧经已发布端口访问） */
const STUB_HEALTH_URL = 'http://localhost:18080/health';

/** 桩地址（容器内服务名，供后端外呼） */
const STUB_BASE_URL = 'http://jev-stub:8080/compatible-mode';

/** 桩启动命令（写进 SKIP 提示；操作者需先在 WSL 内执行） */
const STUB_START_COMMAND = [
  'wsl -d Ubuntu-22.04 -- docker run -d --rm --name jev-stub --network bizbuddy_default -p 18080:8080 \\',
  '  -v /mnt/d/test/mybuddy/biz-buddy/e2e/jev:/app bellsoft/liberica-openjdk-rocky:21.0.12-cds java /app/SystemOneStub.java 8080'
].join('\n');

/** 五个子场景：前四条依赖桩（gate 档四分支），最后一条关闭档零写入（不依赖桩） */
const SCENARIOS = [
  { key: 'HIGH', label: '采信', enabled: true, mode: 'gate', threshold: 0.5, message: '[STUB:HIGH] 帮我看看现在有哪些公告', expect: 'accept' },
  { key: 'LOW', label: '低置信回落', enabled: true, mode: 'gate', threshold: 0.95, message: '[STUB:LOW] 帮我看看现在有哪些公告', expect: 'low' },
  { key: 'ERR', label: '判定异常回落', enabled: true, mode: 'gate', threshold: 0.5, message: '[STUB:ERR] 帮我看看现在有哪些公告', expect: 'error' },
  { key: 'OUT', label: '选项越界回落', enabled: true, mode: 'gate', threshold: 0.5, message: '[STUB:OUT] 帮我看看现在有哪些公告', expect: 'outOfSet' },
  { key: 'OFF', label: '关闭档零写入', enabled: false, mode: 'gate', threshold: 0.5, message: '帮我看看现在有哪些公告', expect: 'disabled' }
];

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, resolved, registry, log } = ctx;

  // 前置 1：桩可达（Windows 侧经已发布端口探活）
  if (!(await stubReachable())) {
    return {
      status: 'SKIP',
      detail: `桩服务不可达（${STUB_HEALTH_URL}）——请先在 WSL 内启动桩：\n${STUB_START_COMMAND}`
    };
  }
  // 前置 2：运行期配置接口可读（未登录 / jar 陈旧 → 404）
  const probe = await http.requestRaw('GET', '/ai/runtime/jev');
  if (probe.status !== 200 || probe.payload?.code !== 200) {
    return {
      status: 'SKIP',
      detail: `运行期配置接口不可读（GET /ai/runtime/jev → HTTP ${probe.status}）——jar 陈旧或登录态失效`,
      evidence: { status: probe.status }
    };
  }
  // 前置 2b：已配置决策模型密钥（未配置则不会外呼判定；本层不写密钥，故需环境已有密钥）
  if (probe.payload?.data?.apiKeyConfigured !== true) {
    return {
      status: 'SKIP',
      detail: '未配置决策模型密钥，无法触发判定；请在「安全合规配置 → 结构化决策路由」填入密钥，'
        + '或先跑一次 node e2e/jev/verify-shadow.mjs --keep-enabled'
    };
  }
  // 前置 3：平台对话密钥已配置（回落分支要跑入口轮，缺密钥会拿不到 done）
  if (!resolved.platformChatKeyReady) {
    return { status: 'SKIP', detail: '平台未为对话模型配置 API 密钥（「模型配置」页补全后可跑）' };
  }

  // 原配置快照：须在改测试配置之前读取，供 finally 恢复（声明在 try 之外以便 finally 可见）
  let snapshot = null;
  try {
    // 快照原配置（必须在改测试配置之前）：收尾据此回填 baseUrl / model 等，避免被清空
    snapshot = await http.request('GET', '/ai/runtime/jev');
    const observations = [];
    const failures = [];
    for (const spec of SCENARIOS) {
      const observed = await runScenario(ctx, spec);
      const check = evaluate(spec, observed);
      observations.push({ spec, observed });
      log(`  ${check.ok ? '✓' : '✗'} ${spec.key}（${spec.label}）｜${check.detail}`);
      if (!check.ok) {
        failures.push(`${spec.key}：${check.detail}`);
      }
    }

    const summary = observations.map((item) => summarize(item.spec, item.observed)).join('｜');
    if (failures.length) {
      return {
        status: 'FAIL',
        detail: `${failures.length} 个子场景断言失败：${failures.join('；')}`,
        evidence: { observations: observations.map((item) => item.observed.evidence) }
      };
    }
    return { status: 'PASS', detail: `四分支与关闭档全部通过：${summary}`, evidence: { observations: observations.map((item) => item.observed.evidence) } };
  } finally {
    // 无条件恢复：回填快照里的地址 / 模型等字段并强制关闭档（密钥留空＝不修改）；即便上面抛异常也要执行
    try {
      await restoreJev(http, snapshot);
      log('  ✓ 已恢复结构化决策原配置（enabled=false，baseUrl/model 等按快照回填；密钥留空不修改）');
    } catch (error) {
      log(`  ! 恢复结构化决策配置失败：${error.message}`);
    }
  }
};

/**
 * 跑一个子场景：改配置 → 新建会话（不指定专家）→ 发消息 → 读 SSE → 轮询编排运行。
 *
 * @param {object} ctx 运行上下文
 * @param {object} spec 子场景定义
 * @returns {Promise<object>} 观测结果（含 evidence）
 */
const runScenario = async (ctx, spec) => {
  const { http, args, resolved, registry, detail, throttle } = ctx;

  await http.request('PUT', '/ai/runtime/jev', { body: jevPayload(spec) });
  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: resolved.entryAgentId, title: `E2E_L12_${spec.key}_${registry.stamp}` }
  });
  registry.sessions.push(session.id);

  const userMessage = await throttle(() =>
    http.request('POST', `/ai/chat/session/${session.id}/message`, {
      body: { content: spec.message, attachmentIds: [] }
    })
  );

  const { events, stopReason } = await readSse(http, '/ai/chat/stream', {
    query: { sessionId: session.id, messageId: userMessage.id },
    timeoutMs: args.sseTimeoutMs,
    idleMs: args.idleTimeoutMs,
    log: detail
  });

  const done = lastOfType(events, 'done');
  const error = lastOfType(events, 'error');
  const run = await waitForRouteRun(http, session.id);

  return {
    sessionId: session.id,
    stopReason,
    eventCount: events.length,
    hasDone: Boolean(done),
    sseError: error ? error.message : null,
    decisionSource: run?.decisionSource ?? null,
    judgeMode: run?.judgeMode ?? null,
    judgeChoice: run?.judgeChoice ?? null,
    judgeConfidence: run?.judgeConfidence ?? null,
    judgeError: run?.judgeError ?? null,
    action: run?.action ?? null,
    taskContext: run?.taskContext ?? null,
    hasRun: Boolean(run),
    evidence: {
      key: spec.key,
      label: spec.label,
      sessionId: session.id,
      threshold: spec.threshold,
      enabled: spec.enabled,
      stopReason,
      eventCount: events.length,
      hasDone: Boolean(done),
      sseError: error ? error.message : null,
      decisionSource: run?.decisionSource ?? null,
      judgeMode: run?.judgeMode ?? null,
      judgeChoice: run?.judgeChoice ?? null,
      judgeConfidence: run?.judgeConfidence ?? null,
      judgeError: head(run?.judgeError, 80),
      action: run?.action ?? null,
      taskContextHead: head(run?.taskContext, 40)
    }
  };
};

/**
 * 按子场景期望断言观测结果。
 *
 * @param {object} spec 子场景定义
 * @param {object} observed 观测结果
 * @returns {{ ok: boolean, detail: string }} 断言结论
 */
const evaluate = (spec, observed) => {
  if (!observed.hasRun) {
    return { ok: false, detail: '编排运行未落库（route run list 为空）' };
  }
  switch (spec.expect) {
    case 'accept':
      return evaluateAccept(observed);
    case 'low':
      return evaluateLow(observed);
    case 'error':
      return evaluateError(observed);
    case 'outOfSet':
      return evaluateOutOfSet(observed);
    case 'disabled':
      return evaluateDisabled(observed);
    default:
      return { ok: false, detail: `未知期望：${spec.expect}` };
  }
};

/** 采信短路：来源 J、模式 G；选中候选专家时动作 route 且 task_context 为服务端模板 */
const evaluateAccept = (observed) => {
  if (observed.decisionSource !== 'J') {
    return { ok: false, detail: `decision_source=${observed.decisionSource}（期望 J）` };
  }
  if (observed.judgeMode !== 'G') {
    return { ok: false, detail: `judge_mode=${observed.judgeMode}（期望 G）` };
  }
  const choice = observed.judgeChoice;
  if (!choice || choice === 'self' || choice === 'other') {
    return { ok: false, detail: `judge_choice=${choice}（期望候选里的非 self/other 专家）` };
  }
  if (observed.action !== 'route') {
    return { ok: false, detail: `action=${observed.action}（期望 route，短路未生效）` };
  }
  if (!String(observed.taskContext ?? '').startsWith('【用户原话】')) {
    return { ok: false, detail: `task_context 非服务端模板（前 40 字：${head(observed.taskContext, 40)}）` };
  }
  if (!observed.hasDone) {
    return { ok: false, detail: `对话未以 done 收尾（stopReason=${observed.stopReason}）` };
  }
  return { ok: true, detail: `decision_source=J｜judge_mode=G｜choice=${choice}｜action=route｜task_context=服务端模板` };
};

/** 低置信回落：来源 F、模式 G、置信度≈0.3，对话正常 */
const evaluateLow = (observed) => {
  if (observed.decisionSource !== 'F') {
    return { ok: false, detail: `decision_source=${observed.decisionSource}（期望 F）` };
  }
  if (observed.judgeMode !== 'G') {
    return { ok: false, detail: `judge_mode=${observed.judgeMode}（期望 G）` };
  }
  if (Math.abs((observed.judgeConfidence ?? -1) - 0.3) >= 0.01) {
    return { ok: false, detail: `judge_confidence=${observed.judgeConfidence}（期望约 0.3）` };
  }
  if (!observed.hasDone) {
    return { ok: false, detail: `对话未以 done 收尾（stopReason=${observed.stopReason}）` };
  }
  return { ok: true, detail: `decision_source=F｜judge_mode=G｜confidence≈0.3｜对话正常` };
};

/** 判定异常回落：来源 F、judge_error 非空、对话正常 */
const evaluateError = (observed) => {
  if (observed.decisionSource !== 'F') {
    return { ok: false, detail: `decision_source=${observed.decisionSource}（期望 F）` };
  }
  if (isBlank(observed.judgeError)) {
    return { ok: false, detail: 'judge_error 为空（期望非空）' };
  }
  if (!observed.hasDone) {
    return { ok: false, detail: `对话未以 done 收尾（stopReason=${observed.stopReason}）` };
  }
  return { ok: true, detail: `decision_source=F｜judge_error=${head(observed.judgeError, 60)}｜对话正常` };
};

/** 选项越界回落：来源 F、judge_error 含「越界」 */
const evaluateOutOfSet = (observed) => {
  if (observed.decisionSource !== 'F') {
    return { ok: false, detail: `decision_source=${observed.decisionSource}（期望 F）` };
  }
  if (!String(observed.judgeError ?? '').includes('越界')) {
    return { ok: false, detail: `judge_error 未含「越界」：${head(observed.judgeError, 60)}` };
  }
  if (!observed.hasDone) {
    return { ok: false, detail: `对话未以 done 收尾（stopReason=${observed.stopReason}）` };
  }
  return { ok: true, detail: `decision_source=F｜judge_error=${head(observed.judgeError, 60)}` };
};

/** 关闭档零写入：judge_mode / decision_source / judge_choice 全为空，对话正常 */
const evaluateDisabled = (observed) => {
  if (!isBlank(observed.judgeMode) || !isBlank(observed.decisionSource) || !isBlank(observed.judgeChoice)) {
    return {
      ok: false,
      detail: `关闭档仍有判定写入（mode=${observed.judgeMode}｜source=${observed.decisionSource}｜choice=${observed.judgeChoice}）`
    };
  }
  if (!observed.hasDone) {
    return { ok: false, detail: `对话未以 done 收尾（stopReason=${observed.stopReason}）` };
  }
  return { ok: true, detail: 'judge_mode/decision_source/judge_choice 全空｜对话正常' };
};

/**
 * 组装保存用的配置体（只含后端 Bo 的字段，不带只读的 apiKeyConfigured）。
 *
 * @param {object} spec 子场景定义
 * @returns {object} 配置体
 */
const jevPayload = (spec) => ({
  enabled: spec.enabled,
  baseUrl: STUB_BASE_URL,
  model: 'decision-model-preview',
  // 密钥一律留空＝不修改已保存密钥（桩不校验 Authorization，本层无需自带密钥，也避免污染真实密钥）
  apiKey: '',
  timeoutSeconds: 5,
  mode: spec.mode,
  confidenceThreshold: spec.threshold,
  failOpen: true,
  stateMaxChars: 4000,
  persistProbabilities: true
});

/**
 * 恢复运行期配置：以快照回填**除密钥外**的所有字段并强制关闭档。
 *
 * <p>密钥传空串（＝不修改）——本层全程未改动密钥，故保持原值即可；快照缺失时退化为「只关闭」。</p>
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {object|null} snapshot 开始前的配置快照
 * @returns {Promise<void>}
 */
const restoreJev = (http, snapshot) => {
  const body = { enabled: false, apiKey: '' };
  if (snapshot) {
    body.baseUrl = snapshot.baseUrl ?? '';
    body.model = snapshot.model ?? '';
    body.timeoutSeconds = snapshot.timeoutSeconds ?? 5;
    body.mode = snapshot.mode ?? 'shadow';
    body.confidenceThreshold = snapshot.confidenceThreshold ?? 0.75;
    body.failOpen = snapshot.failOpen ?? true;
    body.stateMaxChars = snapshot.stateMaxChars ?? 4000;
    body.persistProbabilities = snapshot.persistProbabilities ?? true;
  }
  return http.request('PUT', '/ai/runtime/jev', { body });
};

/**
 * 轮询编排运行直到终态（判定与状态在收尾时才落库，读太早会看到 running）。
 *
 * <p>走 {@code /ai/route/run/list}（随列表 VO 返回判定列）；会话内的 {@code routeRuns} 不含判定列。</p>
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {number|string} sessionId 会话主键
 * @param {number} attempts 最大轮询次数
 * @returns {Promise<object|null>} 最后一条编排运行；超时返回 null
 */
const waitForRouteRun = async (http, sessionId, attempts = 20) => {
  for (let i = 0; i < attempts; i++) {
    const page = await http.request('GET', '/ai/route/run/list', {
      query: { sessionId, pageNum: 1, pageSize: 10 }
    });
    const rows = page?.rows ?? [];
    // 列表按 createTime desc 排序，首行即本会话最近一次编排
    const latest = rows.length ? rows[0] : null;
    if (latest && latest.status && latest.status !== 'running') {
      return latest;
    }
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  return null;
};

/** 桩健康检查（Windows 侧经已发布端口）；3 秒超时。 */
const stubReachable = async () => {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 3000);
    const response = await fetch(STUB_HEALTH_URL, { signal: controller.signal });
    clearTimeout(timer);
    return response.status === 200;
  } catch {
    return false;
  }
};

/** 单场景摘要（PASS 的 detail 用）。 */
const summarize = (spec, observed) => {
  if (spec.expect === 'disabled') {
    return `${spec.key}(空档)`;
  }
  if (spec.expect === 'accept') {
    return `${spec.key}(${observed.decisionSource}/${observed.judgeMode},${observed.action},模板)`;
  }
  if (spec.expect === 'low') {
    return `${spec.key}(${observed.decisionSource}/${observed.judgeMode},${observed.judgeConfidence})`;
  }
  return `${spec.key}(${observed.decisionSource},${head(observed.judgeError, 30)})`;
};

/** 判空（null / undefined / 空串均视为空）。 */
const isBlank = (value) => value === null || value === undefined || String(value) === '';

/** 长字段截断（避免日志被整段文本淹没）。 */
const head = (value, max = 80) => {
  const text = value === null || value === undefined ? '' : String(value);
  return text.length > max ? `${text.slice(0, max)}…` : text;
};