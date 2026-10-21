/**
 * 关键路径 ⑧：参考来源定位（回答「参考来源」可点开命中原文）。
 *
 * 为什么值得单独测：来源数据早已随消息下发，但此前只是纯文本，用户无法核对答案依据。
 * 本用例验证「点击 → 按文档+切片序号现查 → 命中片段可见且非空」这条新链路。
 *
 * 触发不确定性处置（沿用 06 的纪律）：模型是否检索知识库不可控，
 * 「未产生参考来源」归为 SKIP（非前端接线缺陷）；仅在来源已出现、点击后链路出错时判 FAIL。
 */

import { test, expect } from '@playwright/test';

import { messageErrorText, openChat, pickExpert, sendMessage, startNewTask } from '../lib/chat-ui.mjs';
import { CONFIG, stackSkipReason } from '../lib/config.mjs';

/** 种子中绑定了演示知识库的专家（ry_ai.sql） */
const EXPERT_NAME = 'BizBuddy项目介绍专家';

/** 明确要求检索知识库，提高来源出现概率 */
const PROMPT = '请检索知识库后回答：BizBuddy 是什么？';

test.describe('参考来源定位', () => {
  test('回答给出参考来源 → 点击 → 展示命中切片原文', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());
    test.setTimeout(240_000);

    const { noEntryAgent } = await openChat(page);
    test.skip(noEntryAgent, '未启用入口智能体「小Z」——本层 SKIP，非失败');

    await startNewTask(page);
    // 指定专家，避免自动路由带来的触发不确定性
    await pickExpert(page, EXPERT_NAME);
    await sendMessage(page, PROMPT);

    const assistant = page.locator('.msg-row--assistant').last();
    await expect(assistant.locator('.msg-meta')).toBeVisible({ timeout: 150_000 });

    const error = await messageErrorText(page);
    test.skip(!!error, `对话链路前置不满足（${error}）——本层 SKIP，非失败`);

    // 模型可能选择不检索：无参考来源即 SKIP（与 06「未触发写工具」同口径）
    const sources = assistant.locator('.msg-sources');
    if (!(await sources.isVisible().catch(() => false))) {
      test.skip(true, '本轮回答未引用知识库，未产生参考来源（模型选择所致，非前端缺陷）');
    }

    // 来源默认收起：未展开时先点标题展开
    const body = sources.locator('.msg-sources__body');
    if (!(await body.isVisible().catch(() => false))) {
      await sources.locator('.msg-sources__head').click();
    }

    const firstSource = sources.locator('.msg-sources__item.is-clickable').first();
    await expect(firstSource).toBeVisible({ timeout: 10_000 });
    await firstSource.click();

    // 抽屉内命中片段：必须可见且正文非空（只断言结构，不断言具体文字）
    const target = page.locator('.source-detail__chunk--target');
    await expect(target).toBeVisible({ timeout: 20_000 });
    await expect(target.locator('.source-detail__text')).not.toBeEmpty();
  });
});
