/**
 * L6 停止生成：发一条**长回答**提问 → SSE 取流 → 首个正文增量时请求 `POST /ai/chat/session/{id}/stop`
 * → 断言流以 `aborted` 收尾（非 error），且助手消息以「已中止」落库。
 *
 * <p>刻意要求长文（约 800 字）制造足够的生成时长，保证停止请求能在收尾前落地；
 * 若模型先跑完（收到 done），判 FAIL 并提示可重跑——属模型行为不确定性，非脚本缺陷。</p>
 */

export const meta = { layer: 'L6', name: '停止生成（取消后端执行）', needsKey: true };

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, args, resolved, registry, log, detail, throttle } = ctx;
  if (!resolved.platformChatKeyReady) {
    return { status: 'SKIP', detail: '平台未为对话模型配置 API 密钥（「模型配置」页补全后可跑）' };
  }

  const title = `E2E_L6_${registry.stamp}`;
  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: resolved.entryAgentId, title }
  });
  registry.sessions.push(session.id);
  log(`  会话 ${session.id}（停止生成用例）`);

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
  // 长文提问的首 token 可能偏慢，给该用例一个 60s 的空闲下限，避免误判为卡死
  const idleMs = Math.max(args.idleTimeoutMs ?? 20000, 60000);
  const { events, stopReason } = await readSse(http, '/ai/chat/stream', {
    query: { sessionId: session.id, messageId: userMessage.id },
    stopOn: ['done', 'error', 'aborted'],
    timeoutMs: args.sseTimeoutMs,
    idleMs,
    log: detail,
    // 首个正文增量即请求停止：把「后端仍在长跑」变成可断言的中止
    onEvent: (event) => {
      if (!stopSent && event.type === 'text_delta') {
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
    stopReason,
    eventTypes: events.map((event) => event.type),
    aborted: Boolean(aborted),
    done: done ? { messageId: done.messageId, durationMs: done.durationMs } : null,
    error: error ? error.message : null
  };

  if (!stopSent) {
    return { status: 'FAIL', detail: '流内未出现正文增量，未能触发停止请求', evidence };
  }
  if (error) {
    return { status: 'FAIL', detail: `停止后仍收到 error 事件：${error.message}`, evidence };
  }
  if (done) {
    return { status: 'FAIL', detail: '模型在停止请求落地前已完成（收到 done）——属模型行为不确定性，可重跑', evidence };
  }
  if (!aborted) {
    return { status: 'FAIL', detail: `未以 aborted 收尾（停止原因：${stopReason}）`, evidence };
  }

  // 断言助手消息以「已中止」（status=3）落库：取消收尾是异步的，故短轮询
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

  return {
    status: 'PASS',
    detail: `停止生成闭环通过（aborted 收尾；助手消息已中止，保留正文 ${evidence.assistant.contentChars} 字）`,
    evidence
  };
};

/**
 * 轮询会话消息，直到出现「已中止」的助手消息（或超时）。
 *
 * <p>取消收尾（落库 + 链路留痕）在取消信号之后异步完成，`aborted` 事件不等于已落库，故必须轮询等待。</p>
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