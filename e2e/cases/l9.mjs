/**
 * L9 鉴权与参数校验边界：未登录 / 伪造 token 访问受保护接口 → 401；缺参 / 空消息 / 不存在会话 → 业务拒绝。
 *
 * <p>**单测覆盖不到**：单测不起容器，验证不了真实 Sa-Token 拦截器与参数校验链
 * （`@SaCheckPermission`、`@NotNull`、service 侧入参校验）。本层**全程不触发模型调用**，只看失败路径。</p>
 */

export const meta = { layer: 'L9', name: '鉴权与参数校验边界', needsKey: false };

/**
 * 判定「未授权」：HTTP 401 或业务码 401。
 *
 * <p>Sa-Token 的 `NotLoginException` 由 `SaTokenExceptionHandler` 处理，回 `{code:401,...}` 且 **HTTP 仍为 200**，
 * 故两类都要认。</p>
 *
 * @param {{ status: number, payload: any }} res 原始响应
 * @returns {boolean} 是否被判定为未授权
 */
const isUnauthorized = (res) => res.status === 401 || res.payload?.code === 401;

/**
 * 判定响应为「业务成功」。
 *
 * @param {{ status: number, payload: any }} res 原始响应
 * @returns {boolean} 是否成功
 */
const isOk = (res) => res.status === 200 && res.payload?.code === 200;

/**
 * 提取失败证据。
 *
 * @param {{ status: number, payload: any }} res 原始响应
 * @returns {{ status: number, code: any, msg: any }} 证据
 */
const brief = (res) => ({ status: res.status, code: res.payload?.code, msg: res.payload?.msg });

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, resolved, registry } = ctx;
  const evidence = {};

  // 1) 未携带 token 访问受保护接口
  const noToken = await http.requestRaw('GET', '/ai/session/list', {
    query: { pageNum: 1, pageSize: 1 },
    omitAuth: true
  });
  evidence.noToken = brief(noToken);
  if (!isUnauthorized(noToken)) {
    return { status: 'FAIL', detail: '未携带 token 访问受保护接口未被拒绝（期望 401）', evidence };
  }

  // 2) 伪造 token
  const badToken = await http.requestRaw('GET', '/ai/session/list', {
    query: { pageNum: 1, pageSize: 1 },
    token: 'e2e-invalid-token'
  });
  evidence.badToken = brief(badToken);
  if (!isUnauthorized(badToken)) {
    return { status: 'FAIL', detail: '伪造 token 访问未被拒绝（期望 401）', evidence };
  }

  // 3) 缺少必填参数（agentId）
  const missingParam = await http.requestRaw('POST', '/ai/chat/session', { query: {} });
  evidence.missingParam = brief(missingParam);
  if (isOk(missingParam)) {
    return { status: 'FAIL', detail: '缺少必填参数 agentId 仍创建成功', evidence };
  }

  // 4) 空消息（内容与附件均为空）→ 业务拒绝
  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: resolved.entryAgentId, title: `E2E_L9_${registry.stamp}` }
  });
  registry.sessions.push(session.id);
  const blank = await http.requestRaw('POST', `/ai/chat/session/${session.id}/message`, {
    body: { content: '   ', attachmentIds: [] }
  });
  evidence.blankMessage = brief(blank);
  if (isOk(blank)) {
    return { status: 'FAIL', detail: '空内容 + 空附件的消息未被拒绝', evidence };
  }

  // 5) 不存在的会话 → 业务拒绝
  const ghost = await http.requestRaw('POST', '/ai/chat/session/999999999999999999/message', {
    body: { content: '不存在的会话', attachmentIds: [] }
  });
  evidence.ghostSession = brief(ghost);
  if (isOk(ghost)) {
    return { status: 'FAIL', detail: '不存在的会话仍被接受提问', evidence };
  }

  return {
    status: 'PASS',
    detail: '鉴权与参数校验边界均按预期拒绝（未登录 / 伪造 token → 401；缺参 / 空消息 / 不存在会话 → 业务拒绝）',
    evidence
  };
};