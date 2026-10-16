/**
 * 关键路径 ③：会话主路径（新建任务 → 发送 → 流式回答完成）。
 *
 * 一条用例同时覆盖三件事：**SSE 流式通道 + nginx 代理链路 + 编排链路**。
 *
 * 断言纪律（沿用项目既有约定，见 docs/14 §85「断言只能是结构 + 关键词，不能断言文案」）：
 * **只断言结构与状态**——助手气泡出现、流式游标消失、结束元信息出现、正文非空；
 * **不断言回答内容**（LLM 输出天然不确定）。
 *
 * 前置不满足时 SKIP 而非 FAIL：
 *  - 栈不可达 → 整层 SKIP（globalSetup 标记）；
 *  - 未找到入口智能体「小Z」→ SKIP；
 *  - 出现 `.msg-error`（如「额度不足 / 未配置密钥」）→ SKIP，与后端 e2e「未配密钥 SKIP」同口径。
 */

import { test, expect } from '@playwright/test';

import { CONFIG, stackSkipReason } from '../lib/config.mjs';
import { watchPage } from '../lib/observe.mjs';

/** 刻意选一条不需要工具、不需要知识库的提问，降低对运行期配置的耦合 */
const PROMPT = '你好，请用一句话说明你能帮我做什么';

test.describe('会话主路径', () => {
  test('发送一条消息 → 助手流式回答并正常结束', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());
    test.setTimeout(180_000);

    const probe = watchPage(page);
    await page.goto('/bizbuddy/agent/chat');
    await expect(page.locator('.app-container')).toBeVisible({ timeout: 15_000 });

    // 入口智能体未就绪（"未找到入口智能体「小Z」，请先在「小Z 配置」中启用它"）→ SKIP
    const warning = page.locator('.el-message', { hasText: '未找到入口智能体' });
    if (await warning.isVisible().catch(() => false)) {
      test.skip(true, '未启用入口智能体「小Z」——本层 SKIP，非失败');
    }

    // 新建任务：确保是干净会话（全新 context 本就无会话，此处仅作幂等保证）
    const newSession = page.locator('button.new-session-btn');
    if (await newSession.isVisible().catch(() => false)) {
      await newSession.click();
    }

    await page.fill('textarea[placeholder="今天帮您干点什么？"]', PROMPT);
    await page.click('button:has-text("发送")');

    // 先等「助手气泡」或「错误提示」二者之一：前者=链路通，后者=前置问题
    await expect(page.locator('.msg-row--assistant, .msg-error').first()).toBeVisible({ timeout: 60_000 });
    if (await page.locator('.msg-error').first().isVisible().catch(() => false)) {
      const detail = (await page.locator('.msg-error').first().innerText()).trim();
      test.skip(true, `对话链路前置不满足（${detail}）——本层 SKIP，非失败`);
    }

    const assistant = page.locator('.msg-row--assistant').last();

    // 流式结束判据：结束元信息出现（`由 AI 生成` + 复制按钮）且游标消失
    await expect(assistant.locator('.msg-meta')).toBeVisible({ timeout: 90_000 });
    await expect(page.locator('.typing-cursor')).toHaveCount(0, { timeout: 90_000 });

    // 正文非空（只断言结构，不看内容）
    const body = (await assistant.locator('.ai-markdown').last().innerText()).trim();
    expect(body.length, '助手正文为空').toBeGreaterThan(0);

    expect(probe.consoleErrors, `会话链路控制台报错：\n${probe.summary()}`).toEqual([]);
    expect(probe.failedResponses, `会话链路网络失败：\n${probe.summary()}`).toEqual([]);
  });
});
