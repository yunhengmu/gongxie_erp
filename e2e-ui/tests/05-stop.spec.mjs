/**
 * 关键路径 ⑤：停止生成（真中断）。
 *
 * 与 `../e2e` L13 是**互补**而非重复：L13 从接口侧断言后端语义（编排 aborted、专家调用 cancelled），
 * 本条断言的是**前端按钮接线**——点「停止」是否真的发出了取消请求并让页面收尾。
 *
 * 断言的 DOM 变化序列（取自 chat/index.vue）：
 *  停止按钮可见（:376）→ 点击 → `.typing-cursor`（:209）消失、发送按钮回归（:377）、
 *  已流出的助手正文保留（后端按「已中止」落库部分正文）。
 * 注意**不会有** el-message 提示，也不会有 `.msg-meta`（取消路径不发 done）。
 */

import { test, expect } from '@playwright/test';

import { messageErrorText, openChat, sendMessage, startNewTask, waitStreaming } from '../lib/chat-ui.mjs';
import { CONFIG, stackSkipReason } from '../lib/config.mjs';

/** 依赖真实模型的用例开关（快速回归可设 false 跳过） */
const LLM_ENABLED = process.env.BIZBUDDY_E2E_UI_LLM !== 'false';

/** 刻意要求长回答，以便稳定捕获到「流式中」这个时间窗 */
const LONG_PROMPT = '请详细介绍一下 BizBuddy 平台的定位、核心能力、集成方式与部署形态，分点展开，不少于 500 字。';

test.describe('停止生成', () => {
  test('流式中点「停止」→ 前端收尾且已发出的取消请求成功', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());
    test.skip(!LLM_ENABLED, '已设 BIZBUDDY_E2E_UI_LLM=false，跳过依赖真实模型的用例');
    test.setTimeout(180_000);

    const { noEntryAgent } = await openChat(page);
    test.skip(noEntryAgent, '未启用入口智能体「小Z」——请在「小Z 配置」页启用');

    await startNewTask(page);
    await sendMessage(page, LONG_PROMPT);

    // 必须真的进入流式窗口，否则「停止」按钮不会出现——捕获不到即 SKIP（回答过快 / 模型不可用）
    const streaming = await waitStreaming(page, 20_000);
    if (!streaming) {
      const error = await messageErrorText(page);
      test.skip(true, error ? `模型/链路不可用：${error}` : '未捕获到流式窗口（回答过快），本层无法验证停止');
    }

    // 等「已有正文流出」再停：停止按钮一出现就点的话，气泡可能还是空的，
    // 「已流出正文保留」这条断言就无从成立（首次实跑即踩到）
    const assistantBody = page.locator('.msg-row--assistant .ai-markdown').last();
    const hasText = await expect
      .poll(async () => (await assistantBody.innerText().catch(() => '')).trim().length, { timeout: 30_000 })
      .toBeGreaterThan(0)
      .then(() => true)
      .catch(() => false);
    if (!hasText) {
      test.skip(true, '30s 内未等到有效正文，无法验证「停止后已流出正文保留」');
    }
    const before = (await assistantBody.innerText()).trim();

    const [stopResponse] = await Promise.all([
      page
        .waitForResponse((res) => res.request().method() === 'POST' && res.url().includes('/stop'), {
          timeout: 15_000
        })
        .catch(() => null),
      page.locator('button:has-text("停止")').click()
    ]);

    // 前端收尾：游标消失、发送按钮回归、输入框解禁
    await expect(page.locator('.typing-cursor')).toHaveCount(0, { timeout: 20_000 });
    await expect(page.locator('button:has-text("发送")')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('textarea[placeholder="今天帮您干点什么？"]')).toBeEnabled({ timeout: 10_000 });

    // 已流出的正文保留（不应被清空或截断），且不是整轮失败
    const after = (await assistantBody.innerText().catch(() => '')).trim();
    expect(after.length, `停止后助手正文被截断：${before.length} → ${after.length}`).toBeGreaterThanOrEqual(
      before.length
    );
    expect(after.length, '停止后助手气泡内容被清空').toBeGreaterThan(0);
    expect(await messageErrorText(page), '停止被当成整轮失败（出现 .msg-error）').toBe('');

    // 后端取消接口应被调用成功（失败被前端吞掉，故这里单独断言，便于定位接线问题）
    expect(stopResponse, '未捕获到 POST /ai/chat/session/{id}/stop 请求').not.toBeNull();
    expect(stopResponse.status(), `取消接口返回 ${stopResponse.status()}`).toBeLessThan(400);
  });
});
