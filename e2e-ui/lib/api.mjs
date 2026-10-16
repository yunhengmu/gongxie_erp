/**
 * 页面上下文内的 API 调用（用于用例**前置判定**，不用于业务断言）。
 *
 * 为什么不用 Playwright 的 `APIRequestContext`：登录 token 存在 `localStorage` 的
 * `Admin-Token` 里，且后端要求每个请求带 `clientid` 头（缺它会被判未登录）——
 * 只有页面上下文能同时满足这两点。
 */

/** 生产经 nginx 的接口前缀（对应 `plus-ui-Vue/.env.production` 的 `VITE_APP_BASE_API`） */
export const API_PREFIX = '/prod-api';

/**
 * 在页面上下文内发一个带鉴权的请求并解析 JSON。
 *
 * @param {import('@playwright/test').Page} page 已登录的页面
 * @param {string} url 完整 URL（同源，通常以 {@link API_PREFIX} 开头）
 * @param {string} clientId 客户端 id
 * @param {object} [options] 选项
 * @param {string} [options.method] HTTP 方法（默认 GET）
 * @param {object} [options.body] JSON 请求体
 * @returns {Promise<{status: number, body: any}>} 状态码与响应体（解析失败时 body 为 null）
 */
export const callViaPage = async (page, url, clientId, options = {}) => {
  const method = options.method ?? 'GET';
  const payload = options.body === undefined ? undefined : JSON.stringify(options.body);
  return page.evaluate(
    async (args) => {
      const raw = window.localStorage.getItem('Admin-Token');
      let token = raw;
      if (raw && raw.startsWith('"')) {
        try {
          token = JSON.parse(raw);
        } catch {
          // 保持原值（useStorage 序列化形态可能是裸串）
        }
      }
      const response = await fetch(args.url, {
        method: args.method,
        headers: {
          Authorization: `Bearer ${token}`,
          clientid: args.clientId,
          ...(args.payload === undefined ? {} : { 'Content-Type': 'application/json' })
        },
        ...(args.payload === undefined ? {} : { body: args.payload })
      });
      let body = null;
      try {
        body = await response.json();
      } catch {
        // 非 JSON 响应（如 nginx 的 HTML 兜底）——保留 null
      }
      return { status: response.status, body };
    },
    { url, clientId, method, payload }
  );
};

/**
 * GET 某个后端接口（自动补 {@link API_PREFIX}）。
 *
 * @param {import('@playwright/test').Page} page 已登录的页面
 * @param {string} path 形如 `/ai/agent/experts` 的路径
 * @param {string} clientId 客户端 id
 * @returns {Promise<{status: number, body: any}>} 状态码与响应体
 */
export const apiGet = (page, path, clientId) => callViaPage(page, `${API_PREFIX}${path}`, clientId);

/**
 * 取后端 `R` 包装里的 `data`（非 200 或结构不符时返回 null）。
 *
 * @param {import('@playwright/test').Page} page 已登录的页面
 * @param {string} path 路径
 * @param {string} clientId 客户端 id
 * @returns {Promise<any>} data 字段
 */
export const apiGetData = async (page, path, clientId) => {
  const { status, body } = await apiGet(page, path, clientId);
  if (status !== 200 || !body || body.code !== 200) {
    return null;
  }
  return body.data ?? null;
};
