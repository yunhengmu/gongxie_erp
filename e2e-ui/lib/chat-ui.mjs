/**
 * 会话工作台的共享交互助手。
 *
 * 三个 P0 用例（指定专家 / 停止生成 / HITL 确认卡片）都从同一个页面出发、
 * 走同一套交互（开页 → 新建任务 → 选专家 → 发消息 → 等流式），故抽出此处避免三份重复。
 *
 * 选择器与文案均取自真实组件 `src/views/ai/agent/chat/index.vue`（行号见各方法注释）。
 */

import { expect } from '@playwright/test';

/** 会话工作台路由（菜单种子 path=chat，父级 agent → bizbuddy） */
export const CHAT_URL = '/bizbuddy/agent/chat';

/** 输入框 placeholder 原文（chat/index.vue:286） */
const INPUT_PLACEHOLDER = '今天帮您干点什么？';

/**
 * 打开会话工作台。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @returns {Promise<{noEntryAgent: boolean}>} 入口智能体是否缺失（缺失时调用方应 SKIP）
 */
export const openChat = async (page) => {
  await page.goto(CHAT_URL);
  await expect(page.locator('.app-container')).toBeVisible({ timeout: 15_000 });
  // 「未找到入口智能体「小Z」，请先在「小Z 配置」中启用它」（chat/index.vue:1755）
  const noEntryAgent = await page
    .locator('.el-message', { hasText: '未找到入口智能体' })
    .isVisible()
    .catch(() => false);
  return { noEntryAgent };
};

/**
 * 新建任务，确保是干净会话（chat/index.vue:6，按钮文案「新建任务」）。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @returns {Promise<void>}
 */
export const startNewTask = async (page) => {
  const button = page.locator('button.new-session-btn');
  if (await button.isVisible().catch(() => false)) {
    await button.click();
  }
};

/**
 * 通过底栏「+」资源选择器指定专家。
 *
 * 交互链（chat/index.vue）：入口按钮 `.resource-add-btn`（:292）→ 二级分组
 * `.resource-menu__group`（文案「专家（单选）」:1075）→ 三级飞出项 `.resource-flyout__item`
 * （文案＝专家名，:331）→ 选中后落 chip `.resource-chip--expert`（:346）。
 * 注意 `el-popover` 内容 teleport 到 `body`，故定位一律用页面级选择器。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @param {string} expertName 专家名（如「公告管理专家」）
 * @returns {Promise<void>}
 */
export const pickExpert = async (page, expertName) => {
  await page.locator('.resource-add-btn').click();
  const group = page.locator('.resource-menu__group', { hasText: '专家（单选）' }).first();
  await expect(group).toBeVisible({ timeout: 10_000 });
  // hover 与 click 都会置 activeGroup（chat/index.vue:318-319）
  await group.hover();
  await group.click();

  const item = page.locator('.resource-flyout__item', { hasText: expertName }).first();
  await expect(item, `资源选择器里未找到专家「${expertName}」`).toBeVisible({ timeout: 10_000 });
  await item.click();

  // 选中成功的即时可见判据：指定 chip 出现。
  // 注意**不能**在此处等 PUT：会话尚不存在时，选专家只在前端暂存，
  // PUT `/ai/chat/session/{id}/expert` 仅在「已有会话再改专家」时才会发出；
  // 首次暂存会随建会话请求的 `pinnedExpertId` 一起提交（见 04 用例）。
  await expect(
    page.locator('.resource-chip.resource-chip--expert', { hasText: expertName }),
    '指定专家后未出现 resource-chip--expert'
  ).toBeVisible({ timeout: 10_000 });
};

/**
 * 发送一条消息（chat/index.vue:1841 handleSend）。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @param {string} text 消息正文
 * @returns {Promise<void>}
 */
export const sendMessage = async (page, text) => {
  await page.fill(`textarea[placeholder="${INPUT_PLACEHOLDER}"]`, text);
  await page.locator('button:has-text("发送")').click();
};

/**
 * 用快捷键发送（裸 Enter）。
 *
 * 专门用于验证「Enter 发送、Shift+Enter 换行」这条绑定（原先只绑 Ctrl+Enter）；
 * 其余用例仍走 {@link sendMessage} 点按钮，避免耦合到快捷键实现。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @param {string} text 消息正文
 * @returns {Promise<void>}
 */
export const sendMessageByEnter = async (page, text) => {
  await page.fill(`textarea[placeholder="${INPUT_PLACEHOLDER}"]`, text);
  await page.keyboard.press('Enter');
};

/**
 * 等待流式开始（判据：发送按钮被「停止」替换，chat/index.vue:376-377）。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @param {number} [timeoutMs] 超时
 * @returns {Promise<boolean>} 是否捕获到流式窗口
 */
export const waitStreaming = async (page, timeoutMs = 20_000) =>
  page
    .locator('button:has-text("停止")')
    .waitFor({ state: 'visible', timeout: timeoutMs })
    .then(() => true)
    .catch(() => false);

/**
 * 等待一轮回答正常收尾（判据：结束元信息 `.msg-meta` 出现，chat/index.vue:248）。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @param {number} [timeoutMs] 超时
 * @returns {Promise<void>}
 */
export const waitAssistantDone = async (page, timeoutMs = 120_000) => {
  await expect(page.locator('.msg-row--assistant .msg-meta').last()).toBeVisible({ timeout: timeoutMs });
};

/**
 * 是否出现整轮失败提示（`.msg-error` 仅在 status==='1' 时渲染，chat/index.vue:244）。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @returns {Promise<string>} 错误文案（无错误时为空串）
 */
export const messageErrorText = async (page) =>
  (await page.locator('.msg-error').first().innerText().catch(() => '')).trim();
