/**
 * L10 MCP 出口（服务端协议）：`GET /ai/mcp/server` 读出口状态与口令 → 直连 `/mcp` 发
 * `initialize` / `tools/list` / `tools/call`，并做口令负例。
 *
 * <p>**单测覆盖不到**：单测不起容器，验证不了真实 `/mcp` 端点（官方 MCP SDK 无状态传输 + 口令过滤器 +
 * 运行期热挂载的工具清单，以及工具真能被调用执行）。</p>
 *
 * <p>**能力闸门**：出口未挂载系统信息工具（运行中的 jar 早于该工具）、或开启口令校验却没配口令时 **SKIP**，
 * 与「部署陈旧 → SKIP 而非误报 FAIL」同一口径。**全程只读，无副作用**。</p>
 */

export const meta = { layer: 'L10', name: 'MCP 出口（协议 + 口令）', needsKey: false };

/** 系统信息工具名（与 `ai_tool` 种子 / `McpSelfBootstrap` 白名单一致） */
const SYSTEM_INFO_TOOL = 'get_system_info';

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http } = ctx;

  // 1) 出口状态与口令（VO 会回显口令以便交付外部调用方）
  const server = await http.request('GET', '/ai/mcp/server');
  const endpoint = server?.endpoint || '/mcp';
  const liveTools = server?.liveTools ?? [];
  const token = server?.token;
  const evidence = { endpoint, authEnabled: server?.authEnabled, liveTools };

  if (!liveTools.includes(SYSTEM_INFO_TOOL)) {
    return {
      status: 'SKIP',
      detail: `出口未挂载 ${SYSTEM_INFO_TOOL}（liveTools=${liveTools.join(',') || '(空)'}）——运行中的 jar 早于该工具，重建镜像后可跑`,
      evidence
    };
  }
  if (server?.authEnabled && !token) {
    return { status: 'SKIP', detail: '出口已开启口令校验但未配置口令（任何请求都会被拒）', evidence };
  }

  // 2) initialize
  const init = await http.mcpCall('initialize', {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'bizbuddy-e2e', version: '1.0.0' }
  }, { token, endpoint });
  evidence.initialize = {
    status: init.status,
    serverInfo: init.payload?.result?.serverInfo ?? null,
    error: init.payload?.error ?? null
  };
  if (!init.payload?.result?.serverInfo) {
    return { status: 'FAIL', detail: `initialize 未返回 serverInfo（HTTP ${init.status}）`, evidence };
  }

  // 3) tools/list
  const list = await http.mcpCall('tools/list', {}, { token, endpoint });
  const names = (list.payload?.result?.tools ?? []).map((tool) => tool.name);
  evidence.tools = names;
  if (!names.includes(SYSTEM_INFO_TOOL)) {
    return { status: 'FAIL', detail: `tools/list 未包含 ${SYSTEM_INFO_TOOL}：${names.join(',') || '(空)'}`, evidence };
  }

  // 4) tools/call：真执行系统信息工具
  const call = await http.mcpCall('tools/call', { name: SYSTEM_INFO_TOOL, arguments: {} }, { token, endpoint });
  const callText = extractText(call.payload?.result);
  evidence.callPreview = callText.slice(0, 120);
  if (!callText.includes('操作系统：')) {
    return {
      status: 'FAIL',
      detail: `tools/call 未返回系统信息（error=${JSON.stringify(call.payload?.error ?? null)}）`,
      evidence
    };
  }

  // 5) 口令负例：错误口令应被拒（HTTP 401 或 JSON-RPC error.code = -32001）
  const rejected = await http.mcpCall('tools/list', {}, { token: 'e2e-wrong-token', endpoint });
  evidence.rejected = {
    status: rejected.status,
    code: rejected.payload?.error?.code,
    msg: rejected.payload?.error?.message
  };
  if (rejected.status !== 401 && rejected.payload?.error?.code !== -32001) {
    return { status: 'FAIL', detail: '错误口令未被拒绝（期望 HTTP 401 或 error.code=-32001）', evidence };
  }

  return {
    status: 'PASS',
    detail: `出口协议通过（tools=${names.length}，含 ${SYSTEM_INFO_TOOL}；tools/call 实执行成功；错误口令被拒）`,
    evidence
  };
};

/**
 * 从 `tools/call` 结果里拼出文本内容。
 *
 * @param {object} result JSON-RPC 的 result
 * @returns {string} 文本
 */
const extractText = (result) => {
  const content = result?.content;
  if (!Array.isArray(content)) {
    return '';
  }
  return content
    .filter((item) => item?.type === 'text')
    .map((item) => item.text ?? '')
    .join('\n');
};