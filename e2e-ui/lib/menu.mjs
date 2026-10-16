/**
 * 从运行中的栈读取**已注册路由**，用于区分两类失败：
 *   - 期望页面**未在菜单注册** → 「栈比仓库旧」（前端 dist 或库种子落后），不是页面缺陷；
 *   - 已注册但渲染不出来 → **真的页面缺陷**。
 *
 * 这一层判别是刻意加的：菜单硬编码自仓库最新种子，而运行时栈可能落后数天；
 * 若不区分，一次「栈旧」会被误报成「33 页里有 1 页坏了」，把真实缺陷淹没在噪声里。
 */

/** 菜单接口（生产经 nginx：`/prod-api` 前缀） */
const ROUTERS_API = '/prod-api/system/menu/getRouters';

/**
 * 递归把路由树摊平成**完整路径集合**（与前端 `resolvePath` / `getNormalPath` 口径一致：
 * 逐级用 `/` 拼接并折叠重复斜杠）。
 *
 * @param {Array} nodes 路由节点
 * @param {string} [parent] 父路径
 * @param {Set<string>} [acc] 累积集合
 * @returns {Set<string>} 完整路径集合
 */
const flatten = (nodes, parent = '', acc = new Set()) => {
  for (const node of nodes ?? []) {
    const raw = String(node?.path ?? '').trim();
    if (!raw || raw === '#') {
      flatten(node?.children, parent, acc);
      continue;
    }
    // 一级菜单后端已给出以 '/' 开头的绝对路径；其余相对父路径拼接
    const joined = raw.startsWith('/') ? raw : `${parent}/${raw}`;
    const full = joined.replace(/\/{2,}/g, '/').replace(/\/$/, '');
    acc.add(full);
    flatten(node?.children, full, acc);
  }
  return acc;
};

/**
 * 读取当前登录态下已注册的路由路径。
 *
 * 在页面上下文内发请求（而非 `APIRequestContext`）：登录 token 存在 `localStorage`
 * 的 `Admin-Token` 里，只有页面上下文能直接取到；`useStorage` 的序列化可能是裸串或 JSON，
 * 故两种形态都兼容。
 *
 * @param {import('@playwright/test').Page} page 已登录的页面
 * @param {string} clientId 客户端 id（前端每个请求都带 `clientid` 头，缺失会被判未登录）
 * @returns {Promise<{paths: Set<string>|null, error: string|null}>} 路径集合；失败时 paths 为 null
 */
export const fetchRegisteredPaths = async (page, clientId) => {
  try {
    const result = await page.evaluate(async ({ api, clientId: cid }) => {
      const raw = window.localStorage.getItem('Admin-Token');
      let token = raw;
      if (raw && raw.startsWith('"')) {
        try {
          token = JSON.parse(raw);
        } catch {
          // 保留原值
        }
      }
      const response = await fetch(api, {
        headers: { Authorization: `Bearer ${token}`, clientid: cid }
      });
      if (!response.ok) {
        return { error: `${response.status}` };
      }
      return { body: await response.json() };
    }, { api: ROUTERS_API, clientId });

    if (result?.error) {
      return { paths: null, error: `菜单接口返回 ${result.error}` };
    }
    const nodes = result?.body?.data;
    if (!Array.isArray(nodes)) {
      const code = result?.body?.code;
      return { paths: null, error: `菜单接口响应不含 data 数组（code=${code ?? '未知'}）` };
    }
    return { paths: flatten(nodes), error: null };
  } catch (error) {
    return { paths: null, error: error?.message || String(error) };
  }
};
