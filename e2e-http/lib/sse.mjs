/**
 * SSE 读取：后端统一发**无名事件 + JSON 信封**（`SseEmitter.event().data(json)`），
 * 故按空行切帧、解析 `data:` 后的 JSON，并以 `type` 字段区分语义。
 *
 * <p>双超时兜底：`timeoutMs` 为整轮上限，`idleMs` 为「多久没收到任何数据」即判定卡死。
 * 命中 `stopOn` 中的类型（默认 `done` / `error` / `tool_confirm`）或流自然关闭即结束。</p>
 */

/** 默认的停止事件类型 */
export const DEFAULT_STOP_ON = ['done', 'error', 'tool_confirm'];

/**
 * 读取一条 SSE 流直到停止事件或超时。
 *
 * @param {import('./http.mjs').HttpClient} client HTTP 客户端
 * @param {string} apiPath 接口路径
 * @param {{ query?: object, stopOn?: string[], timeoutMs?: number, idleMs?: number, log?: Function, onEvent?: Function }} options 选项
 *        {@code onEvent} 为每收到一个事件就调用的回调（同步、不 await），用于「读流过程中触发动作」
 *        （如 L6 在首个正文增量时请求停止）
 * @returns {Promise<{ events: object[], stopReason: string }>} 已收事件与停止原因
 */
export const readSse = async (client, apiPath, options = {}) => {
  const stopOn = options.stopOn ?? DEFAULT_STOP_ON;
  const timeoutMs = options.timeoutMs ?? 90000;
  const idleMs = options.idleMs ?? 20000;
  const log = options.log ?? (() => {});
  const onEvent = options.onEvent;

  const events = [];
  let stopReason = 'stream-closed';
  const controller = new AbortController();
  let idleTimer;

  const overallTimer = setTimeout(() => {
    stopReason = 'timeout';
    controller.abort();
  }, timeoutMs);
  const resetIdle = () => {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      stopReason = 'idle-timeout';
      controller.abort();
    }, idleMs);
  };

  try {
    const response = await client.openStream(apiPath, { query: options.query, signal: controller.signal });
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    resetIdle();

    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
      let boundary = buffer.indexOf('\n\n');
      let stopped = false;
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const event = parseFrame(frame);
        if (event) {
          events.push(event);
          log(`  ← SSE ${event.type}`);
          if (onEvent) {
            try {
              onEvent(event);
            } catch {
              // 回调异常不影响读流
            }
          }
          if (stopOn.includes(event.type)) {
            stopReason = 'stop-event';
            stopped = true;
            break;
          }
        }
        boundary = buffer.indexOf('\n\n');
      }
      if (stopped) {
        await reader.cancel().catch(() => {});
        break;
      }
      resetIdle();
    }
    return { events, stopReason };
  } catch (error) {
    if (error.name === 'AbortError') {
      return { events, stopReason };
    }
    throw error;
  } finally {
    clearTimeout(overallTimer);
    clearTimeout(idleTimer);
  }
};

/**
 * 解析单个 SSE 帧为 JSON 对象（非 JSON 或空帧返回 null）。
 *
 * @param {string} frame 帧文本
 * @returns {object|null} 事件对象
 */
const parseFrame = (frame) => {
  const dataLines = [];
  for (const line of frame.split('\n')) {
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''));
    }
  }
  if (!dataLines.length) {
    return null;
  }
  const text = dataLines.join('\n').trim();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return { type: 'unparsed', raw: text.slice(0, 200) };
  }
};

/**
 * 从事件列表中挑出指定类型的事件。
 *
 * @param {object[]} events 事件列表
 * @param {string} type 事件类型
 * @returns {object[]} 命中事件
 */
export const eventsOfType = (events, type) => events.filter((event) => event.type === type);

/**
 * 取最后一个指定类型的事件。
 *
 * @param {object[]} events 事件列表
 * @param {string} type 事件类型
 * @returns {object|null} 命中事件
 */
export const lastOfType = (events, type) => {
  const matched = eventsOfType(events, type);
  return matched.length ? matched[matched.length - 1] : null;
};
