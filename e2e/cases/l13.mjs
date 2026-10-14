/**
 * L13 「停止生成」应中断**后端编排**（指定专家路径）。
 *
 * <p><b>为什么单列一层</b>：L6 覆盖的是**入口轮**（小Z 自己跑 ReAct）的停止；而短路轮（决策模型采信后
 * 专家直答）、指定专家兜底、HITL 恢复段走的是**同步阻塞的专家调用**，历史上「停止生成」只停前端消费、
 * 后端仍会跑完并落库（docs/14 §92 ①）。本层用「指定专家 + 长回答」逼出该路径，停止后断言：
 * ① 编排运行终态为 {@code aborted}；② 助手消息落库为「已中止」；③ 专家调用明细为 {@code cancelled}
 * （即取消确实作用到了专家那一层，而不是只停了入口轮）；④ 未发生降级整合轮。</p>
 *
 * <p><b>SKIP 口径</b>：外部对话模型不可用时（平台未配密钥 / 连通性探测未过）返回 SKIP 而非 FAIL——
 * 本层的失败点全在「模型能不能持续出字」，模型抖动不该被记成脚本缺陷。</p>
 */

export const meta = { layer: 'L13', name: '停止生成（中断专家编排）', needsKey: true };

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { cfg, http, args, resolved, registry, log, detail, throttle } = ctx;
  if (!resolved.platformChatKeyReady) {
    return { status: 'SKIP', detail: '平台未为对话模型配置 API 密钥（「模型配置」页补全后可跑）' };
  }
  if (!resolved.noticeExpertId) {
    return { status: 'SKIP', detail: '未解析到公告管理专家（无「指定专家」可指定）' };
  }
  // 前置：对话模型连通性（不通则 SKIP，避免把模型抖动记成缺陷）
  const probe = await http
    .request('POST', '/ai/model/test', {
      timeoutMs: 60000,
      body: {
        configName: 'e2e-l13',
        provider: resolved.chatModel.provider || 'dashscope',
        modelType: resolved.chatModel.modelType,
        modelCode: resolved.chatModel.modelCode,
        baseUrl: cfg.llmBaseUrl || resolved.chatModel.baseUrl,
        apiKey: cfg.llmApiKey,
        timeoutSeconds: 30
      }
    })
    .catch(() => null);
  if (!probe?.success) {
    return {
      status: 'SKIP',
      detail: `对话模型连通性探测未通过（${probe?.message ?? '调用异常'}）——外部模型当前不可用，本层跳过`
    };
  }

  const title = `E2E_L13_${registry.stamp}`;
  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: resolved.entryAgentId, title }
  });
  registry.sessions.push(session.id);
  // 指定专家：本轮强制走「专家编排」路径（短路轮 / 兜底 / 恢复段共用同一段阻塞式专家调用）
  await http.request('PUT', `/ai/chat/session/${session.id}/expert`, {
    body: { expertAgentId: resolved.noticeExpertId }
  });
  log(`  会话 ${session.id}（指定专家 ${resolved.noticeExpertName}，停止中断编排用例）`);

  const userMessage = await throttle(() =>
    http.request('POST', `/ai/chat/session/${session.id}/message`, {
      body: {
        content: '请用中文写一篇约 800 字的短文，主题：企业如何落地 AI 助手。需要小标题和具体例子。',
        attachmentIds: []
      }
    })
  );

  const { readSse, lastOfType } = await import('../lib/sse.mjs');
  let stopSent = false;
  let sawExpertStart = false;
  // 长文提问的首 token 可能偏慢，给该用例一个 60s 的空闲下限，避免误判为卡死
  const idleMs = Math.max(args.idleTimeoutMs ?? 20000, 60000);
  const { events, stopReason } = await readSse(http, '/ai/chat/stream', {
    query: { sessionId: session.id, messageId: userMessage.id },
    stopOn: ['done', 'error', 'aborted'],
    timeoutMs: args.sseTimeoutMs,
    idleMs,
    log: detail,
    onEvent: (event) => {
      if (event.type === 'expert_start') {
        sawExpertStart = true;
      }
      // 等专家已开始（expert_start 之后）再于首个正文增量请求停止：确保句柄已登记、取消落在专家那一层
      if (!stopSent && sawExpertStart && event.type === 'text_delta') {
        stopSent = true;
        http.request('POST', `/ai/chat/session/${session.id}/stop`).catch(() => undefined);
      }
    }
  });
  log(`  收到 ${events.length} 个 SSE 事件（${stopReason}），已发停止请求=${stopSent}`);

  const error = lastOfType(events, 'error');
  const aborted = lastOfType(events, 'aborted');
  const done = lastOfType(events, 'done');
  const evidence = {
    sessionId: session.id,
    stopSent,
    sawExpertStart,
    stopReason,
    eventTypes: events.map((event) => event.type),
    aborted: Boolean(aborted),
    done: done ? { messageId: done.messageId, durationMs: done.durationMs } : null,
    error: error ? error.message : null
  };

  if (!sawExpertStart) {
    return {
      status: 'FAIL',
      detail: '流内未出现 expert_start（未进入专家编排路径）——属模型行为不确定性，可重跑',
      evidence
    };
  }
  if (!stopSent) {
    return { status: 'FAIL', detail: '专家接管后未出现正文增量，未能触发停止请求', evidence };
  }
  if (error) {
    return { status: 'FAIL', detail: `停止后仍收到 error 事件：${error.message}`, evidence };
  }
  if (done) {
    return { status: 'FAIL', detail: '专家在停止请求落地前已完成（收到 done）——属模型行为不确定性，可重跑', evidence };
  }
  if (!aborted) {
    return { status: 'FAIL', detail: `未以 aborted 收尾（停止原因：${stopReason}）`, evidence };
  }

  // 助手消息「已中止」：取消收尾异步完成，须短轮询
  const assistant = await pollAbortedAssistant(http, session.id, args);
  evidence.assistant = assistant
    ? { messageId: assistant.id, status: assistant.status, contentChars: (assistant.content ?? '').length }
    : null;
  if (!assistant) {
    return { status: 'FAIL', detail: '停止后未在 8s 内落库助手消息', evidence };
  }
  if (assistant.status !== '3') {
    return { status: 'FAIL', detail: `助手消息状态不是「已中止」（status=${assistant.status}）`, evidence };
  }

  // 编排运行终态 + 专家调用明细：这是本层的核心断言（取消必须落到专家那一层）
  const run = await pollRouteRun(http, session.id, args);
  evidence.routeRun = run
    ? { status: run.status, expertCalls: (run.expertCalls ?? []).map((call) => call.status) }
    : null;
  if (!run) {
    return { status: 'FAIL', detail: '停止后未在 8s 内查询到编排运行记录', evidence };
  }
  if (run.status !== 'aborted') {
    return { status: 'FAIL', detail: `编排运行终态不是 aborted（status=${run.status}）`, evidence };
  }
  const cancelledCall = (run.expertCalls ?? []).find((call) => call.status === 'cancelled');
  if (!cancelledCall) {
    return {
      status: 'FAIL',
      detail: `专家调用明细未出现 cancelled（实际：${(run.expertCalls ?? []).map((c) => c.status).join(',') || '空'}）`,
      evidence
    };
  }

  return {
    status: 'PASS',
    detail: `停止中断专家编排通过（编排 aborted；助手消息已中止，保留正文 ${evidence.assistant.contentChars} 字；`
      + `专家调用 cancelled）`,
    evidence
  };
};

/**
 * 轮询会话消息，直到出现「已中止」的助手消息（或超时）。
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {string|number} sessionId 会话主键
 * @param {{ routePollMs?: number, routePollIntervalMs?: number }} args 参数
 * @returns {Promise<object|null>} 已中止的助手消息；超时返回 null
 */
const pollAbortedAssistant = async (http, sessionId, args) => {
  const deadline = Date.now() + (args.routePollMs ?? 8000);
  const interval = args.routePollIntervalMs ?? 500;
  for (;;) {
    const messages = (await http.request('GET', `/ai/chat/session/${sessionId}/messages`)) ?? [];
    const assistant = messages.filter((message) => message.role === 'assistant').pop();
    if (assistant && assistant.status === '3') {
      return assistant;
    }
    if (Date.now() >= deadline) {
      return null;
    }
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
};

/**
 * 轮询会话的编排运行（等它到终态、且专家调用明细已落库）。
 *
 * <p>注意：编排运行的终态由**收尾方**先写入（入口轮的取消收尾可能早于专家那次调用的收尾），
 * 故不能一看到非 running 就返回——需再等专家调用明细出现，否则会读到一个「已 aborted 但明细还是空」的中间态。</p>
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {string|number} sessionId 会话主键
 * @param {{ routePollMs?: number, routePollIntervalMs?: number }} args 参数
 * @returns {Promise<object|null>} 终态且带明细的编排运行；超时返回最后观测到的运行
 */
const pollRouteRun = async (http, sessionId, args) => {
  const deadline = Date.now() + (args.routePollMs ?? 8000);
  const interval = args.routePollIntervalMs ?? 500;
  let latest = null;
  for (;;) {
    const runs = (await http.request('GET', `/ai/chat/session/${sessionId}/routeRuns`)) ?? [];
    const current = runs.length ? runs[runs.length - 1] : null;
    if (current && current.status && current.status !== 'running') {
      latest = current;
      if ((current.expertCalls ?? []).length > 0) {
        return current;
      }
    }
    if (Date.now() >= deadline) {
      return latest;
    }
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
};
