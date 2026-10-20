/**
 * L4 对话与编排：建会话（指定专家强制路由）→ 发消息 → SSE 取回答 → 校验编排轨迹落库。
 *
 * <p>**刻意用 `pinnedExpertId` 强制路由**：模型常不主动调用 `route_decision`，自然路由的随机性会让断言不稳；
 * 指定专家时服务端保证「指定了就一定由它答」，断言 `action === 'pinned'` 才是可靠信号。</p>
 */

export const meta = { layer: 'L4', name: '对话与专家编排（SSE + 轨迹落库）', needsKey: true };

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, args, resolved, registry, log, detail, throttle } = ctx;
  if (!resolved.platformChatKeyReady) {
    return { status: 'SKIP', detail: '平台未为对话模型配置 API 密钥（「模型配置」页补全后可跑）' };
  }

  const title = `E2E_L4_${registry.stamp}`;
  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: resolved.entryAgentId, pinnedExpertId: resolved.introExpertId, title }
  });
  registry.sessions.push(session.id);
  log(`  会话 ${session.id}（指定专家：${resolved.introExpertName}）`);

  const userMessage = await throttle(() =>
    http.request('POST', `/ai/chat/session/${session.id}/message`, {
      body: { content: '请简要介绍 BizBuddy 是什么', attachmentIds: [] }
    })
  );

  const { readSse, eventsOfType, lastOfType } = await import('../lib/sse.mjs');
  const { events, stopReason } = await readSse(http, '/ai/chat/stream', {
    query: { sessionId: session.id, messageId: userMessage.id },
    timeoutMs: args.sseTimeoutMs,
    idleMs: args.idleTimeoutMs,
    log: detail
  });
  log(`  收到 ${events.length} 个 SSE 事件（${stopReason}）`);

  const done = lastOfType(events, 'done');
  const error = lastOfType(events, 'error');
  const phases = eventsOfType(events, 'phase');
  const evidence = {
    sessionId: session.id,
    eventTypes: events.map((event) => event.type),
    phases: phases.map((item) => `${item.phase}:${item.text}`),
    stopReason,
    done: done ? { messageId: done.messageId, replyId: done.replyId, durationMs: done.durationMs, totalTokens: done.totalTokens, toolCallCount: done.toolCallCount } : null,
    error: error ? error.message : null
  };

  if (error) {
    return { status: 'FAIL', detail: `SSE 返回错误事件：${error.message}`, evidence };
  }
  if (!eventsOfType(events, 'start').length) {
    return { status: 'FAIL', detail: `未收到 start 事件（停止原因：${stopReason}）`, evidence };
  }
  if (!eventsOfType(events, 'text_delta').length && !eventsOfType(events, 'tool_result').length) {
    return { status: 'FAIL', detail: `未收到任何正文或工具结果事件（停止原因：${stopReason}）`, evidence };
  }
  if (!done || !done.replyId) {
    return { status: 'FAIL', detail: `未以 done 收尾（停止原因：${stopReason}）`, evidence };
  }
  // 对话期进度：提问后、正文/工具结果之前必须已有 phase（否则前端整段静默，只有光标在闪）
  const firstPhaseIndex = events.findIndex((event) => event.type === 'phase');
  const firstOutputIndex = events.findIndex(
    (event) => event.type === 'text_delta' || event.type === 'tool_result'
  );
  if (firstPhaseIndex < 0) {
    return { status: 'FAIL', detail: '本轮未下发任何 phase 进度事件（前端全程只有闪烁光标）', evidence };
  }
  if (firstOutputIndex >= 0 && firstPhaseIndex > firstOutputIndex) {
    return { status: 'FAIL', detail: '首个 phase 晚于首个正文/工具结果，进度播报失去意义', evidence };
  }

  // 编排轨迹的 action/status 是在收尾时**补写**的（行先以 null 插入，随后 UPDATE 填充），
  // 而 `done` 事件在助手消息落库后即下发，故此处必须**短轮询**等收尾写完，否则会读到 null。
  const runs = await pollRouteRun(http, session.id, args);
  const latest = Array.isArray(runs) && runs.length ? runs[0] : null;
  evidence.routeRun = latest
    ? { action: latest.action, status: latest.status, expertCallCount: latest.expertCallCount, degradeReason: latest.degradeReason, durationMs: latest.durationMs, totalTokens: latest.totalTokens }
    : null;

  if (!latest) {
    return { status: 'FAIL', detail: '编排轨迹未落库（routeRuns 为空）', evidence };
  }
  if (latest.action !== 'pinned') {
    return { status: 'FAIL', detail: `编排动作不是 pinned 而是 ${latest.action}`, evidence };
  }
  if (latest.status !== 'success') {
    return { status: 'FAIL', detail: `编排状态为 ${latest.status}${latest.degradeReason ? `（${latest.degradeReason}）` : ''}`, evidence };
  }

  return {
    status: 'PASS',
    detail: `指定专家路由生效（action=pinned），回答完成（${done.durationMs}ms，${done.totalTokens ?? '?'} tokens，工具 ${done.toolCallCount ?? 0} 次）`,
    evidence
  };
};

/**
 * 轮询编排轨迹，直到**最新一条的 `action` 已写入**（或超时）。
 *
 * <p>背景：`ai_route_run` 行在编排开始时即插入（`action` 为 null），`action`/`status` 由收尾时的
 * UPDATE 补写；而 SSE 的 `done` 早于该 UPDATE 下发，直接读会拿到 null。轮询是**等收尾**，不是放宽断言。</p>
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {string|number} sessionId 会话主键
 * @param {{ routePollMs?: number, routePollIntervalMs?: number }} args 参数
 * @returns {Promise<object[]>} 轨迹列表（最新在前）
 */
const pollRouteRun = async (http, sessionId, args) => {
  const deadline = Date.now() + (args.routePollMs ?? 8000);
  const interval = args.routePollIntervalMs ?? 500;
  let runs = [];
  for (;;) {
    runs = (await http.request('GET', `/ai/chat/session/${sessionId}/routeRuns`)) ?? [];
    const latest = runs[0];
    if (latest?.action && latest.status !== 'running') {
      return runs;
    }
    if (Date.now() >= deadline) {
      return runs;
    }
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
};
