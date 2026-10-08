import { decryptResponse, encryptLoginBody } from './crypto.mjs';

/** 业务失败（HTTP 通了但 `R.code !== 200`，或响应结构异常） */
export class BizError extends Error {}

const SUCCESS_CODE = 200;

/**
 * 串行节流器：用于 `POST /ai/chat/session/{id}/message`（后端限 60 秒 20 次）。
 *
 * @param {number} minIntervalMs 两次调用之间的最小间隔
 * @returns {(fn: Function) => Promise<any>} 包装函数
 */
export const createThrottle = (minIntervalMs) => {
  let last = 0;
  let chain = Promise.resolve();
  return (fn) => {
    chain = chain.then(async () => {
      const wait = last + minIntervalMs - Date.now();
      if (wait > 0) {
        await new Promise((resolve) => setTimeout(resolve, wait));
      }
      try {
        return await fn();
      } finally {
        last = Date.now();
      }
    });
    return chain;
  };
};

/**
 * 极简 HTTP 客户端：统一鉴权头、`R{code,msg,data}` 解包、429 退避。
 */
export class HttpClient {
  /**
   * @param {object} cfg 配置（见 lib/env.mjs）
   * @param {{ timeoutMs?: number, verbose?: boolean, log?: Function }} options 选项
   */
  constructor(cfg, options = {}) {
    this.cfg = cfg;
    this.timeoutMs = options.timeoutMs ?? 15000;
    this.verbose = options.verbose ?? false;
    this.log = options.log ?? (() => {});
    this.token = null;
  }

  /**
   * 登录（请求体与响应体按 `@ApiEncrypt` 规则加解密）。
   *
   * @returns {Promise<string>} token
   */
  async login() {
    const { body, encryptKey } = encryptLoginBody(
      {
        clientId: this.cfg.clientId,
        grantType: 'password',
        username: this.cfg.username,
        password: this.cfg.password
      },
      this.cfg.rsaPublicKey
    );

    const response = await this.fetchWithTimeout(`${this.cfg.baseUrl}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', clientid: this.cfg.clientId, 'encrypt-key': encryptKey },
      body
    });
    const text = await response.text();
    if (response.status !== 200) {
      throw new BizError(`登录请求 HTTP ${response.status}：${text.slice(0, 200)}`);
    }
    const payload = decryptResponse(text, response.headers.get('encrypt-key'), this.cfg.rsaPrivateKey);
    if (payload.code !== SUCCESS_CODE) {
      throw new BizError(`登录失败：${payload.msg || JSON.stringify(payload).slice(0, 200)}`);
    }
    this.token = payload.data?.access_token || payload.data?.accessToken || payload.data?.token;
    if (!this.token) {
      throw new BizError(`登录响应中未找到 token：${JSON.stringify(payload.data).slice(0, 200)}`);
    }
    return this.token;
  }

  /**
   * 发起请求并解包 `R{code,msg,data}`。
   *
   * @param {string} method HTTP 方法
   * @param {string} apiPath 接口路径（如 `/ai/model/test`）
   * @param {{ query?: object, body?: any, timeoutMs?: number, retryOn429?: boolean }} options 选项
   * @returns {Promise<any>} `R.data`
   */
  async request(method, apiPath, options = {}) {
    const doFetch = async () => {
      const headers = { clientid: this.cfg.clientId };
      if (this.token) {
        headers.Authorization = `Bearer ${this.token}`;
      }
      let body;
      if (options.body !== undefined) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(options.body);
      }
      const response = await this.fetchWithTimeout(this.buildUrl(apiPath, options.query), {
        method,
        headers,
        body,
        timeoutMs: options.timeoutMs
      });
      const text = await response.text();
      return { response, text };
    };

    let { response, text } = await doFetch();
    if (response.status === 429 && options.retryOn429 !== false) {
      this.log(`  ↻ 命中限流（429），2 秒后重试一次：${method} ${apiPath}`);
      await new Promise((resolve) => setTimeout(resolve, 2000));
      ({ response, text } = await doFetch());
    }

    if (response.status === 401) {
      throw new BizError(`未授权（401）：登录态可能已过期 → ${method} ${apiPath}`);
    }
    let payload;
    try {
      payload = JSON.parse(text);
    } catch {
      throw new BizError(`响应不是 JSON（HTTP ${response.status}）：${text.slice(0, 300)}`);
    }
    if (payload.code !== SUCCESS_CODE) {
      throw new BizError(`${method} ${apiPath} 业务失败（code=${payload.code}）：${payload.msg || ''}`);
    }
    return payload.data;
  }

  /**
   * 打开原始流（供 SSE 使用），不做解包。
   *
   * @param {string} apiPath 接口路径
   * @param {{ query?: object, signal?: AbortSignal }} options 选项
   * @returns {Promise<Response>} fetch 响应
   */
  async openStream(apiPath, options = {}) {
    const headers = { clientid: this.cfg.clientId, Accept: 'text/event-stream' };
    if (this.token) {
      headers.Authorization = `Bearer ${this.token}`;
    }
    const response = await fetch(this.buildUrl(apiPath, options.query), {
      method: 'GET',
      headers,
      signal: options.signal
    });
    if (!response.ok) {
      const text = await response.text().catch(() => '');
      throw new BizError(`建立 SSE 失败（HTTP ${response.status}）：${text.slice(0, 300)}`);
    }
    return response;
  }

  /**
   * 拼装完整 URL。
   *
   * @param {string} apiPath 接口路径
   * @param {object} [query] 查询参数（值为 null/undefined 的项会被跳过）
   * @returns {string} 完整 URL
   */
  buildUrl(apiPath, query) {
    const search = new URLSearchParams();
    Object.entries(query ?? {}).forEach(([key, value]) => {
      if (value !== null && value !== undefined && value !== '') {
        search.append(key, String(value));
      }
    });
    const suffix = search.toString() ? `?${search}` : '';
    return `${this.cfg.baseUrl}${apiPath}${suffix}`;
  }

  /**
   * 带超时的 fetch。
   *
   * @param {string} target URL
   * @param {{ method: string, headers: object, body?: string, timeoutMs?: number }} options 选项
   * @returns {Promise<Response>} 响应
   */
  async fetchWithTimeout(target, options) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), options.timeoutMs ?? this.timeoutMs);
    try {
      return await fetch(target, { ...options, signal: controller.signal });
    } catch (error) {
      if (error.name === 'AbortError') {
        throw new BizError(`请求超时（${(options.timeoutMs ?? this.timeoutMs) / 1000}s）：${options.method} ${target}`);
      }
      throw new BizError(`请求失败：${options.method} ${target} → ${error.message}`);
    } finally {
      clearTimeout(timeout);
    }
  }
}
