/**
 * L5 HITL 闭环：写工具触发人工确认 → 确认/拒绝 → 恢复执行到 done。
 *
 * <p>**默认走「拒绝」路径（`confirmed=false`）——不产生任何业务副作用**；
 * 传 `--allow-writes` 才真正执行写操作，此时按标题 `E2E_NOTICE_<时间戳>` 在收尾时清理公告。</p>
 */

export const meta = { layer: 'L5', name: 'HITL 写工具人工确认闭环', needsKey: true };

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
  const noticeTitle = `E2E_NOTICE_${registry.stamp}`;
  registry.noticeTitles.push(noticeTitle);

  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: resolved.entryAgentId, pinnedExpertId: resolved.noticeExpertId, title: `E2E_L5_${registry.stamp}` }
  });
  registry.sessions.push(session.id);
  log(`  会话 ${session.id}（指定专家：${resolved.noticeExpertName}）`);

  const userMessage = await throttle(() =>
    http.request('POST', `/ai/chat/session/${session.id}/message`, {
      body: {
        content: `请发布一条公告，标题为「${noticeTitle}」，正文为「端到端集成测试自动创建，可忽略」，类型为通知。`,
        attachmentIds: []
      }
    })
  );

  const first = await readSse(http, '/ai/chat/stream', {
    query: { sessionId: session.id, messageId: userMessage.id },
    stopOn: ['done', 'error', 'tool_confirm'],
    timeoutMs: args.sseTimeoutMs,
    idleMs: args.idleTimeoutMs,
    log: detail
  });
  log(`  首段收到 ${first.events.length} 个 SSE 事件（${first.stopReason}）`);

  const evidence = { sessionId: session.id, noticeTitle, firstEventTypes: first.events.map((event) => event.type) };
  const firstError = lastOfType(first.events, 'error');
  if (firstError) {
    return { status: 'FAIL', detail: `首段 SSE 返回错误：${firstError.message}`, evidence };
  }
  const confirm = lastOfType(first.events, 'tool_confirm');
  if (!confirm) {
    return {
      status: 'FAIL',
      detail: '未触发人工确认（模型本轮没有调用写工具）——属模型行为不确定性，非脚本缺陷；可重跑或改用更明确的提问',
      evidence
    };
  }
  const toolNames = (confirm.toolCalls ?? []).map((call) => call.toolName);
  evidence.toolNames = toolNames;
  evidence.confirmMessageId = confirm.messageId;
  if (!toolNames.includes('create_notice')) {
    return { status: 'FAIL', detail: `确认卡里的工具不是 create_notice：${toolNames.join(',') || '(空)'}`, evidence };
  }

  const confirmed = Boolean(args.allowWrites);
  await http.request('POST', `/ai/chat/session/${session.id}/confirm`, {
    body: { messageId: confirm.messageId, confirmed, remember: false }
  });
  evidence.confirmed = confirmed;

  const second = await readSse(http, '/ai/chat/resume', {
    query: { sessionId: session.id, messageId: confirm.messageId },
    stopOn: ['done', 'error', 'tool_confirm'],
    timeoutMs: args.sseTimeoutMs,
    idleMs: args.idleTimeoutMs,
    log: detail
  });
  log(`  恢复段收到 ${second.events.length} 个 SSE 事件（${second.stopReason}）`);
  evidence.resumeEventTypes = second.events.map((event) => event.type);

  const resumeError = lastOfType(second.events, 'error');
  if (resumeError) {
    return { status: 'FAIL', detail: `恢复段 SSE 返回错误：${resumeError.message}`, evidence };
  }
  if (lastOfType(second.events, 'tool_confirm')) {
    return { status: 'FAIL', detail: '恢复段再次暂停等待确认（本轮仅覆盖单次确认闭环）', evidence };
  }
  const done = lastOfType(second.events, 'done');
  if (!done) {
    return { status: 'FAIL', detail: `恢复段未以 done 收尾（停止原因：${second.stopReason}）`, evidence };
  }
  evidence.done = { replyId: done.replyId, durationMs: done.durationMs, toolCallCount: done.toolCallCount };

  return {
    status: 'PASS',
    detail: `确认闭环通过（${confirmed ? '接受并已创建公告，收尾将清理' : '拒绝，无副作用'}；恢复段 ${done.durationMs}ms）`,
    evidence
  };
};
