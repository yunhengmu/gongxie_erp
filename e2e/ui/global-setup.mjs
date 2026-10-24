/**
 * 全局前置检查（对齐 ../http 的 preflight 语义）：
 * **栈不可达时整层 SKIP，而不是 FAIL**——因为本套件测的是「已部署的容器栈」，
 * 栈没起属于前置不满足，不是代码缺陷。
 *
 * 做法：探测前端入口 → 不可达则落「stack-down.flag」，后续各 spec 读 flag 后 `test.skip()`。
 * 同时补一份空登录态文件，避免 `storageState` 指向不存在的路径时报错。
 */

import fs from 'node:fs';

import { ARTIFACTS_DIR, CONFIG, STATE_FILE } from './lib/config.mjs';

const PROBE_TIMEOUT_MS = 5000;

/**
 * 探测前端入口是否可达。
 *
 * @param {string} url 待探测地址
 * @returns {Promise<boolean>} 是否可达
 */
const probe = async (url) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
  try {
    const response = await fetch(url, { signal: controller.signal, redirect: 'manual' });
    // nginx 未就绪常见 502/503；只要不是网络层失败、且状态码 < 500 就认为栈在跑
    return response.status < 500;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
};

export default async function globalSetup() {
  fs.mkdirSync(ARTIFACTS_DIR, { recursive: true });

  const reachable = await probe(`${CONFIG.uiBaseUrl}/`);
  if (!reachable) {
    fs.writeFileSync(CONFIG.stackDownFlag, `unreachable at ${new Date().toISOString()}\n`, 'utf8');
    fs.writeFileSync(STATE_FILE, JSON.stringify({ cookies: [], origins: [] }, null, 2), 'utf8');
    console.warn(`· 前置检查：${CONFIG.uiBaseUrl} 不可达 → 本次整层 SKIP（请先起容器栈）`);
  } else {
    fs.rmSync(CONFIG.stackDownFlag, { force: true });
    console.log(`· 前置检查：前端入口 ${CONFIG.uiBaseUrl} 可达｜账号 ${CONFIG.username}`);
  }
}
