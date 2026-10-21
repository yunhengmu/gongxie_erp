/**
 * L15：只读轮次的「重新生成」闭环。
 *
 * <p>覆盖：
 * ① `GET .../regenerate/check` 对最后一轮只读回答返回 allowed=true；
 * ② `GET .../regenerate` 以 SSE 重跑并落一条新的助手消息；
 * ③ 重新生成后该轮只剩一条助手消息（旧回答逻辑删除，保留审计行）。</p>
 *
 * <p>写操作拦截矩阵由 `AiSessionServiceImplTest` 覆盖（真机触发写工具依赖模型行为，不作为本层判据）。</p>
 */

export const meta = { layer: 'L15', name: '只读轮次重新生成', needsKey: true };

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, args, resolved, registry, log, detail, throttle } = ctx;
  if (!resolved.platformChatKeyReady) {
    return { status: 'SKIP', detail: '平台未为对话模型配置 API 密钥（「模型配置」页补全后可跑）' };
  }

  const { readSse, lastOfType } = await import('../lib/sse.mjs');

  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: resolved.entryAgentId, title: `E2E_L15_${registry.stamp}` }
  });
  registry.sessions.push(session.id);
  log(`  会话 ${session.id}`);

  const userMessage = await throttle(() =>
    http.request('POST', `/ai/chat/session/${session.id}/message`, {
      body: { content: '用一句话介绍你自己，不要调用任何工具。', attachmentIds: [] }
    })
  );

  const first = await readSse(http, '/ai/chat/stream', {
    query: { sessionId: session.id, messageId: userMessage.id },
    stopOn: ['done', 'error'],
    timeoutMs: args.sseTimeoutMs,
    idleMs: args.idleTimeoutMs,
    log: detail
  });
  const firstError = lastOfType(first.events, 'error');
  if (firstError) {
    return { status: 'FAIL', detail: `首轮 SSE 返回错误：${firstError.message}` };
  }
  const firstDone = lastOfType(first.events, 'done');
  if (!firstDone) {
    return { status: 'FAIL', detail: `首轮未以 done 收尾（停止原因：${first.stopReason}）` };
  }

  const messagesBefore = (await http.request('GET', `/ai/chat/session/${session.id}/messages`)) ?? [];
  const roundBefore = messagesBefore.filter(
    (item) => String(item.id) > String(userMessage.id) && item.role === 'assistant'
  );
  const evidence = {
    sessionId: session.id,
    userMessageId: userMessage.id,
    firstAssistantIds: roundBefore.map((item) => item.id)
  };
  if (roundBefore.length !== 1) {
    return {
      status: 'FAIL',
      detail: `首轮助手消息应为 1 条，实际 ${roundBefore.length} 条（可能存在额外的暂停/失败消息）`,
      evidence
    };
  }

  // ① 预检：最后一轮只读回答应允许重新生成
  const check = await http.request('GET', `/ai/chat/session/${session.id}/message/${userMessage.id}/regenerate/check`);
  evidence.check = { allowed: check?.allowed, reasonCode: check?.reasonCode, reason: check?.reason };
  if (!check?.allowed) {
    return {
      status: 'FAIL',
      detail: `只读轮次预检未放行（${check?.reasonCode ?? '未知'}：${check?.reason ?? ''}）`,
      evidence
    };
  }

  // ② 重新生成：以 SSE 重跑
  const second = await readSse(http, `/ai/chat/session/${session.id}/message/${userMessage.id}/regenerate`, {
    timeoutMs: args.sseTimeoutMs,
    idleMs: args.idleTimeoutMs,
    log: detail
  });
  evidence.regenerateEventTypes = second.events.map((event) => event.type);
  const regenerateError = lastOfType(second.events, 'error');
  if (regenerateError) {
    return {
      status: 'FAIL',
      detail: `重新生成返回错误（${regenerateError.reasonCode ?? '无原因码'}）：${regenerateError.message}`,
      evidence
    };
  }
  const secondDone = lastOfType(second.events, 'done');
  if (!secondDone) {
    return { status: 'FAIL', detail: `重新生成未以 done 收尾（停止原因：${second.stopReason}）`, evidence };
  }
  evidence.secondAssistantId = secondDone.messageId;

  // ③ 旧回答逻辑删除：该轮只保留一条助手消息，且是新落的那条
  const messagesAfter = (await http.request('GET', `/ai/chat/session/${session.id}/messages`)) ?? [];
  const roundAfter = messagesAfter.filter(
    (item) => String(item.id) > String(userMessage.id) && item.role === 'assistant'
  );
  evidence.afterAssistantIds = roundAfter.map((item) => item.id);
  if (roundAfter.length !== 1) {
    return {
      status: 'FAIL',
      detail: `重新生成后该轮助手消息应为 1 条，实际 ${roundAfter.length} 条（旧回答未被逻辑删除）`,
      evidence
    };
  }
  if (String(roundAfter[0].id) !== String(secondDone.messageId)) {
    return {
      status: 'FAIL',
      detail: `重新生成后保留的助手消息与 done.messageId 不一致（${roundAfter[0].id} vs ${secondDone.messageId}）`,
      evidence
    };
  }
  if (!String(roundAfter[0].content ?? '').trim()) {
    return { status: 'FAIL', detail: '重新生成后的回答为空', evidence };
  }

  return {
    status: 'PASS',
    detail: `只读轮次预检放行 → 重新生成完成（新消息 ${secondDone.messageId}，${secondDone.durationMs ?? '-'}ms），旧回答已逻辑删除`,
    evidence
  };
};
