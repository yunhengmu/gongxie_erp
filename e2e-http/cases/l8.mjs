/**
 * L8 会话治理闭环：创建 → 重命名 → 置顶 → 消息 / 工具明细查询 → 关闭 → 删除（含逻辑删除校验）。
 *
 * <p>**单测覆盖不到**：单测把 service 层打桩，证明不了「接口 → DB 持久化 → 逻辑删除」这条真实链路
 * （重命名 / 置顶是否真落库、关闭后是否拒绝继续提问、删除后是否再查不到）。</p>
 *
 * <p>本层**不需要模型密钥**：「关闭后拒绝提问」在落库前即被拒（`appendUserMessage` 校验会话状态），
 * 不触发模型调用。</p>
 */

export const meta = { layer: 'L8', name: '会话治理闭环（CRUD + 逻辑删除）', needsKey: false };

/** 会话状态：0 进行中 / 1 已结束（AiSessionServiceImpl） */
const STATUS_ACTIVE = '0';
const STATUS_CLOSED = '1';

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, resolved, registry, log } = ctx;
  const originalTitle = `E2E_L8_${registry.stamp}`;
  const renamedTitle = `${originalTitle}_renamed`;

  // 1) 创建
  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: resolved.entryAgentId, title: originalTitle }
  });
  registry.sessions.push(session.id);
  log(`  会话 ${session.id}（标题：${session.sessionTitle}）`);
  const evidence = { sessionId: session.id, createdTitle: session.sessionTitle };
  if (session.status !== STATUS_ACTIVE) {
    return { status: 'FAIL', detail: `新建会话状态不是「进行中」（status=${session.status}）`, evidence };
  }

  // 2) 重命名 + 回读断言
  await http.request('PUT', `/ai/chat/session/${session.id}/title`, { query: { title: renamedTitle } });
  const afterRename = await http.request('GET', `/ai/chat/session/${session.id}`);
  evidence.renamedTitle = afterRename.sessionTitle;
  if (afterRename.sessionTitle !== renamedTitle) {
    return { status: 'FAIL', detail: `重命名未落库（期望 ${renamedTitle}，实际 ${afterRename.sessionTitle}）`, evidence };
  }

  // 3) 置顶 + 回读断言
  await http.request('PUT', `/ai/chat/session/${session.id}/top`, { query: { top: true } });
  const afterTop = await http.request('GET', `/ai/chat/session/${session.id}`);
  evidence.topFlag = afterTop.topFlag;
  if (afterTop.topFlag !== '1') {
    return { status: 'FAIL', detail: `置顶未落库（topFlag=${afterTop.topFlag}）`, evidence };
  }

  // 4) 治理列表（全部用户视角）能查到
  const list = await http.request('GET', '/ai/session/list', { query: { pageNum: 1, pageSize: 10 } });
  evidence.listTotal = list?.total;
  evidence.foundInList = (list?.rows ?? []).some((row) => String(row.id) === String(session.id));

  // 5) 空会话的消息 / 工具明细应为空数组
  const messages = await http.request('GET', `/ai/chat/session/${session.id}/messages`);
  const toolCalls = await http.request('GET', `/ai/chat/session/${session.id}/toolCalls`);
  evidence.messageCount = Array.isArray(messages) ? messages.length : -1;
  evidence.toolCallCount = Array.isArray(toolCalls) ? toolCalls.length : -1;

  // 6) 关闭 → 状态变为「已结束」，且拒绝继续提问（在落库前即拒，不调模型）
  await http.request('DELETE', `/ai/chat/session/${session.id}`);
  const afterClose = await http.request('GET', `/ai/chat/session/${session.id}`);
  evidence.closedStatus = afterClose.status;
  if (afterClose.status !== STATUS_CLOSED) {
    return { status: 'FAIL', detail: `关闭后状态不是「已结束」（status=${afterClose.status}）`, evidence };
  }
  const rejectedSend = await http.requestRaw('POST', `/ai/chat/session/${session.id}/message`, {
    body: { content: '已结束的会话不应接受新提问', attachmentIds: [] }
  });
  evidence.closedSendRejected = !isOk(rejectedSend);
  evidence.closedSendMsg = rejectedSend.payload?.msg ?? null;
  if (!evidence.closedSendRejected) {
    return { status: 'FAIL', detail: '会话关闭后仍接受了新提问', evidence };
  }

  // 7) 删除（逻辑删除）→ 再查应失败
  await http.request('DELETE', `/ai/session/${session.id}`);
  const at = registry.sessions.indexOf(session.id);
  if (at >= 0) {
    registry.sessions.splice(at, 1); // 已删除，收尾无需再删
  }
  const afterRemove = await http.requestRaw('GET', `/ai/chat/session/${session.id}`);
  evidence.afterRemoveOk = isOk(afterRemove);
  if (evidence.afterRemoveOk) {
    return { status: 'FAIL', detail: '删除后仍能查到该会话（逻辑删除未生效）', evidence };
  }

  return {
    status: 'PASS',
    detail: '会话治理闭环通过（重命名 / 置顶已落库、关闭后拒绝提问、删除后不可查）',
    evidence
  };
};

/**
 * 判定响应为「业务成功」。
 *
 * @param {{ status: number, payload: any }} res 原始响应
 * @returns {boolean} 是否成功
 */
const isOk = (res) => res.status === 200 && res.payload?.code === 200;