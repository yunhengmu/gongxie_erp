/**
 * 前端「静默失败」探针。
 *
 * 对应后端发版门禁第 4 道（日志 ERROR 归零）在**前端**的等价物：页面能打开不等于页面正常。
 * 三条判定标准同时成立才算通过——
 *   ① `console.error` / 未捕获异常（`pageerror`）计数为 0；
 *   ② 网络层无 4xx / 5xx；
 *   ③ 关键元素可见（由各 spec 自行断言）。
 *
 * 只做「收集 + 给出可断言的列表」，不做断言，保持无副作用、便于在不同 spec 间复用。
 */

/** 控制台噪声白名单（正则）——与业务无关、不允许因此把用例判失败 */
const DEFAULT_CONSOLE_IGNORE = [
  /favicon/i,
  /ResizeObserver loop/i,
  /Download the Vue Devtools/i,
  /\[vite\]/i
];

/** 网络噪声白名单（正则）——静态资源缺失不上升为关键路径失败 */
const DEFAULT_RESPONSE_IGNORE = [
  /favicon/i,
  /\.map$/i
];

/**
 * 挂载收集器。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @param {object} [options] 选项
 * @param {RegExp[]} [options.consoleIgnore] 控制台白名单（覆盖默认）
 * @param {RegExp[]} [options.responseIgnore] 网络白名单（覆盖默认）
 * @returns {{consoleErrors: string[], pageErrors: string[], failedResponses: string[], reset: Function, summary: Function}}
 */
export const watchPage = (page, options = {}) => {
  const consoleIgnore = options.consoleIgnore ?? DEFAULT_CONSOLE_IGNORE;
  const responseIgnore = options.responseIgnore ?? DEFAULT_RESPONSE_IGNORE;
  const bucket = { consoleErrors: [], pageErrors: [], failedResponses: [] };

  page.on('console', (msg) => {
    if (msg.type() !== 'error') {
      return;
    }
    const text = `${msg.location().url || '(unknown)'} :: ${msg.text()}`;
    if (consoleIgnore.some((pattern) => pattern.test(text))) {
      return;
    }
    bucket.consoleErrors.push(text);
  });

  // 未捕获异常：Vue 无全局 errorHandler（src/main.ts 未注册），渲染期异常会落到这里
  page.on('pageerror', (error) => {
    const text = error?.message || String(error);
    if (consoleIgnore.some((pattern) => pattern.test(text))) {
      return;
    }
    bucket.pageErrors.push(text);
  });

  page.on('response', (response) => {
    const status = response.status();
    if (status < 400) {
      return;
    }
    const url = response.url();
    if (responseIgnore.some((pattern) => pattern.test(url))) {
      return;
    }
    bucket.failedResponses.push(`${status} ${url}`);
  });

  return {
    consoleErrors: bucket.consoleErrors,
    pageErrors: bucket.pageErrors,
    failedResponses: bucket.failedResponses,
    /** 清空计数（每次导航前调用，避免把上一页的问题算到下一页） */
    reset: () => {
      bucket.consoleErrors.length = 0;
      bucket.pageErrors.length = 0;
      bucket.failedResponses.length = 0;
    },
    /** 汇总成一行，塞进断言消息里便于定位 */
    summary: () => JSON.stringify(bucket, null, 2)
  };
};
