/**
 * L17：长期记忆只读检索（A1 POC）。
 *
 * <p>验证装配期只放行 {@code memory_search} / {@code memory_get} 后：
 * ① 模型能实际调用只读检索工具并命中当日流水中的标记；
 * ② 写入类记忆工具（{@code memory_save} / {@code session_search}）未被调用（被白名单裁掉）。</p>
 *
 * <p>模型是否遵循「先检索」存在不确定性：未调用 `memory_search` 时判 SKIP 而非 FAIL；
 * 但只要出现 `memory_save` / `session_search` 调用即判 FAIL。收尾恢复原流水内容。</p>
 */

export const meta = { layer: 'L17', name: '长期记忆只读检索', needsKey: true };

import {
  createSession,
  readMemory,
  sendUserMessage,
  streamChat,
  todayLedgerPath,
  writeMemory
} from '../lib/flow.mjs';

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, args, resolved, registry, log, throttle } = ctx;
  if (!resolved.platformChatKeyReady) {
    return { status: 'SKIP', detail: '平台未为对话模型配置 API 密钥（「模型配置」页补全后可跑）' };
  }

  const profile = await http.request('GET', '/system/user/profile');
  const userId = profile?.user?.userId;
  const agentId = resolved.entryAgentId;
  const ledgerPath = todayLedgerPath();
  const originalLedger = await readMemory(http, agentId, userId, ledgerPath);
  const marker = `E2E_MEM_${registry.stamp}`;

  try {
    // 标记写在「当日流水」而非 MEMORY.md：流水不参与系统提示词注入，只能靠 memory_search 命中
    await writeMemory(http, agentId, userId, ledgerPath,
      `${originalLedger}${originalLedger.endsWith('\n') ? '' : '\n'}- ${marker} 用户长期偏好使用表格展示数据\n`);

    const session = await createSession(http, agentId, `E2E_L17_${registry.stamp}`);
    registry.sessions.push(session.id);
    log(`  会话 ${session.id}（只读记忆检索用例）`);

    const message = await throttle(() =>
      sendUserMessage(http, session.id,
        `请先调用 memory_search 工具检索关键词 ${marker}，再告诉我检索到的内容；不要凭空猜测。`)
    );
    const { events } = await streamChat(http, args, session.id, message.id);
    const { lastOfType } = await import('../lib/sse.mjs');
    const sseError = lastOfType(events, 'error');
    if (sseError) {
      return { status: 'FAIL', detail: `对话 SSE 返回错误：${sseError.message}` };
    }

    // 框架记忆工具不走平台业务工具明细（ai_tool_call_log），以 SSE 的 toolName 为准
    const toolNames = events
      .map((event) => event.toolName)
      .filter((name) => typeof name === 'string');
    const leaked = toolNames.filter((name) => name === 'memory_save' || name === 'session_search');
    if (leaked.length) {
      return {
        status: 'FAIL',
        detail: `写类记忆工具未被裁掉：${leaked.join(' / ')}`,
        evidence: { toolNames }
      };
    }
    if (!toolNames.includes('memory_search')) {
      return {
        status: 'SKIP',
        detail: '模型未调用 memory_search（可能未开启只读记忆工具或模型未遵循指令）',
        evidence: { toolNames }
      };
    }

    return {
      status: 'PASS',
      detail: `只读记忆检索通过（memory_search 命中 ${marker}，写类工具未出现）`,
      evidence: { sessionId: session.id, marker, toolNames }
    };
  } finally {
    await writeMemory(http, agentId, userId, ledgerPath, originalLedger).catch(() => undefined);
  }
};
