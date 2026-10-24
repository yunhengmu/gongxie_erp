/**
 * L11 MCP 客户端（连通与自演示登记）：核对 `McpSelfBootstrap` 登记的自演示连接，
 * 并用**该连接自身的地址**（容器内可达）经 `POST /ai/mcp/client/test` 探测工具发现。
 *
 * <p>**单测覆盖不到**：单测 mock 了 Mapper 与装配，验证不了「管理面 → 真实 HTTP 连接 → 工具发现」这条链路。</p>
 *
 * <p><b>为何用自演示连接的 url 而不是 `--base-url`</b>：探测在**服务端**发起，而 `--base-url` 是**宿主机**
 * 地址（如 MySQL 栈为 `localhost:8081`，容器内后端实际监听 `8080`），容器内不可达；自演示连接的 url
 * 是容器内可达地址（`http://localhost:8080/mcp`），两栈通用。</p>
 *
 * <p>**无副作用**：`test` 只连接与列举工具、**不落库**；`list` 只读。出口未挂系统信息工具（jar 陈旧）
 * 或口令未配时 **SKIP**。</p>
 */

export const meta = { layer: 'L11', name: 'MCP 客户端（连通 + 自演示登记）', needsKey: false };

/** 系统信息工具名 */
const SYSTEM_INFO_TOOL = 'get_system_info';

/** 自演示连接的 Server 名（见 McpSelfBootstrap） */
const SELF_SERVER_NAME = 'platform_system_info';

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http } = ctx;

  const server = await http.request('GET', '/ai/mcp/server');
  const endpoint = server?.endpoint || '/mcp';
  const liveTools = server?.liveTools ?? [];
  const token = server?.token;
  const evidence = { endpoint, liveTools };

  if (!liveTools.includes(SYSTEM_INFO_TOOL)) {
    return { status: 'SKIP', detail: `出口未挂载 ${SYSTEM_INFO_TOOL}（jar 陈旧），跳过客户端连通用例`, evidence };
  }
  if (server?.authEnabled && !token) {
    return { status: 'SKIP', detail: '出口已开启口令校验但未配置口令', evidence };
  }

  // 1) 自演示连接（由启动引导登记；其 url 为容器内可达地址）
  const page = await http.request('GET', '/ai/mcp/client/list', {
    query: { keyword: SELF_SERVER_NAME, pageNum: 1, pageSize: 50 }
  });
  const self = (page?.rows ?? []).find((row) => row.serverName === SELF_SERVER_NAME);
  evidence.selfClient = self
    ? { id: self.id, transport: self.transport, url: self.url, enableTools: self.enableTools, status: self.status }
    : null;
  if (!self) {
    return { status: 'FAIL', detail: `未找到自演示连接 ${SELF_SERVER_NAME}（McpSelfBootstrap 未生效？）`, evidence };
  }
  if ((self.enableTools ?? '') !== SYSTEM_INFO_TOOL) {
    return { status: 'FAIL', detail: `自演示连接白名单不是 ${SYSTEM_INFO_TOOL}：${self.enableTools}`, evidence };
  }

  // 2) 用自演示连接的地址探测（服务端发起、容器内可达）
  const probe = await http.request('POST', '/ai/mcp/client/test', {
    timeoutMs: 30000,
    body: {
      serverName: 'e2e-probe',
      transport: self.transport,
      url: self.url,
      headersJson: JSON.stringify({ Authorization: `Bearer ${token}` }),
      enableTools: SYSTEM_INFO_TOOL
    }
  });
  evidence.probe = { success: probe?.success, tools: probe?.tools, message: probe?.message };
  if (!probe?.success) {
    return { status: 'FAIL', detail: `客户端探测自演示出口失败：${probe?.message || '(无说明)'}`, evidence };
  }
  if (!(probe.tools ?? []).includes(SYSTEM_INFO_TOOL)) {
    return {
      status: 'FAIL',
      detail: `探测到的工具不含 ${SYSTEM_INFO_TOOL}：${(probe.tools ?? []).join(',') || '(空)'}`,
      evidence
    };
  }

  return {
    status: 'PASS',
    detail: `客户端连通通过（探测到 ${(probe.tools ?? []).length} 个工具，含 ${SYSTEM_INFO_TOOL}）；自演示连接已登记`,
    evidence
  };
};