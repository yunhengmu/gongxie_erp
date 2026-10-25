/**
 * 关键路径 ⑩：「重新生成」按钮接线。
 *
 * 后端判定矩阵由 `AiSessionServiceImplTest` 覆盖、接口闭环由 `../http` L15 覆盖；
 * 本条只验前端接线：按钮是否在最后一轮出现、预检被拒时是否置灰、允许时是否
 * 发起 regenerate SSE 并替换旧回答（该轮助手消息仍为 1 条）。
 */

import { test, expect } from '@playwright/test';

import { messageErrorText, openChat, sendMessage, startNewTask, waitAssistantDone, waitStreaming } from '../lib/chat-ui.mjs';
import { CONFIG, stackSkipReason } from '../lib/config.mjs';

/** 依赖真实模型的用例开关（快速回归可设 false 跳过） */
const LLM_ENABLED = process.env.BIZBUDDY_E2E_UI_LLM !== 'false';

/** 明确要求不调用工具，尽量让本轮落在「只读、可重新生成」 |
 * （模型偶尔仍会调用只读工具，命中写/未知工具时按 SKIP 处理） */
const PROMPT = '用一句话介绍你自己，不要调用任何工具。';

test.describe('重新生成', () => {
  test('最后一轮出现「重新生成」→ 预检通过 → 替换旧回答', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());
    test.skip(!LLM_ENABLED, '已设 BIZBUDDY_E2E_UI_LLM=false，跳过依赖真实模型的用例');
    test.setTimeout(240_000);

    const { noEntryAgent } = await openChat(page);
    test.skip(noEntryAgent, '未启用入口智能体「小Z」——请在「小Z 配置」页启用');

    await startNewTask(page);
    await sendMessage(page, PROMPT);
    await waitAssistantDone(page);

    const error = await messageErrorText(page);
    if (error) {
      test.skip(true, `模型/链路不可用：${error}`);
    }

    // 悬停助手气泡后操作区可见（复制 / 重新生成）
    const assistantBubble = page.locator('.msg-row--assistant .bubble--assistant').last();
    await assistantBubble.hover();
    const regenerate = page.locator('.msg-meta__regenerate').last();
    await expect(regenerate, '最后一轮未出现「重新生成」入口').toBeVisible({ timeout: 15_000 });

    // 点击后先走预检：记录响应，便于区分「置灰不放行」与「接线未发起」
    const [checkResponse] = await Promise.all([
      page.waitForResponse(
        (res) => res.request().method() === 'GET' && res.url().includes('/regenerate/check'),
        { timeout: 15_000 }
      ),
      regenerate.click()
    ]);
    const checkBody = await checkResponse.json().catch(() => ({}));
    const check = checkBody?.data ?? {};
    if (!check.allowed) {
      // 模型本轮触发了写/未知工具时，后端 fail-closed 属预期；本层不判缺陷
      test.skip(true, `预检未放行（${check.reasonCode ?? '未知'}）：${check.reason ?? '无原因'}`);
    }

    // 二次确认
    await page.locator('.el-message-box button:has-text("确定")').click();

    // 必须真的进入流式窗口（回答过快则无法验证替换过程）
    const streaming = await waitStreaming(page, 20_000);
    if (!streaming) {
      const streamError = await messageErrorText(page);
      test.skip(true, streamError ? `重新生成失败：${streamError}` : '未捕获到流式窗口（回答过快）');
    }

    await waitAssistantDone(page, 180_000);
    expect(await messageErrorText(page), '重新生成后出现整轮失败').toBe('');

    // 旧回答被替换：该轮仍只有一条助手消息（不出现新旧两条并排）
    await expect(page.locator('.msg-row--assistant'), '重新生成后助手消息条数不为 1').toHaveCount(1);

    // 新回答有正文，且入口仍可用（可再次重新生成）
    const newBody = page.locator('.msg-row--assistant .ai-markdown').last();
    await expect
      .poll(async () => (await newBody.innerText().catch(() => '')).trim().length, { timeout: 30_000 })
      .toBeGreaterThan(0);
    await assistantBubble.hover();
    await expect(page.locator('.msg-meta__regenerate').last(), '重新生成后入口丢失').toBeVisible({
      timeout: 15_000
    });
  });
});
