/**
 * e2e 公共流程助手：减少各层重复的建会话 / 发消息 / SSE / 记忆读写代码。
 *
 * <p>只抽「与判定无关」的重复动作；断言仍保留在各自用例内，避免合并判定粒度。</p>
 */

/** 会话默认超时（毫秒） */
const DEFAULT_SSE_TIMEOUT = 120000;

/**
 * 创建会话。
 *
 * @param {import('./http.mjs').HttpClient} http HTTP 客户端
 * @param {string|number} agentId 智能体主键
 * @param {string} title 会话标题
 * @returns {Promise<{id: string|number}>} 会话
 */
export const createSession = (http, agentId, title) =>
  http.request('POST', '/ai/chat/session', { query: { agentId, title } });

/**
 * 发送一条用户消息（不带附件）。
 *
 * @param {import('./http.mjs').HttpClient} http HTTP 客户端
 * @param {string|number} sessionId 会话主键
 * @param {string} content 消息正文
 * @returns {Promise<{id: string|number}>} 用户消息
 */
export const sendUserMessage = (http, sessionId, content) =>
  http.request('POST', `/ai/chat/session/${sessionId}/message`, {
    body: { content, attachmentIds: [] }
  });

/**
 * 读取会话的 SSE 流直到 done / error / aborted。
 *
 * @param {import('./http.mjs').HttpClient} http HTTP 客户端
 * @param {object} args 命令行参数（含 sseTimeoutMs / idleTimeoutMs）
 * @param {string|number} sessionId 会话主键
 * @param {string|number} messageId 用户消息主键
 * @param {{ stopOn?: string[], timeoutMs?: number, idleMs?: number }} options 覆盖项
 * @returns {Promise<{events: object[], stopReason: string}>} SSE 结果
 */
export const streamChat = async (http, args, sessionId, messageId, options = {}) => {
  const { readSse } = await import('./sse.mjs');
  return readSse(http, '/ai/chat/stream', {
    query: { sessionId, messageId },
    stopOn: options.stopOn ?? ['done', 'error', 'aborted'],
    timeoutMs: options.timeoutMs ?? args.sseTimeoutMs ?? DEFAULT_SSE_TIMEOUT,
    idleMs: options.idleMs ?? args.idleTimeoutMs,
    log: () => {}
  });
};

/**
 * 当日流水的工作区相对路径（按宿主本地日期）。
 *
 * @returns {string} memory/YYYY-MM-DD.md
 */
export const todayLedgerPath = () => {
  const now = new Date();
  const pad = (value) => String(value).padStart(2, '0');
  return `memory/${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}.md`;
};

/**
 * 读取记忆文件（不存在或读取失败时返回空串）。
 */
export const readMemory = async (http, agentId, userId, path) => {
  try {
    const result = await http.request('GET', '/ai/memory/content', { query: { agentId, userId, path } });
    return result?.content ?? '';
  } catch {
    return '';
  }
};

/**
 * 写入记忆文件（人工编辑路径同样会经过净化）。
 */
export const writeMemory = (http, agentId, userId, path, content) =>
  http.request('PUT', '/ai/memory/content', { body: { agentId, userId, path, content } });

/**
 * 简短休眠（轮询用）。
 */
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
