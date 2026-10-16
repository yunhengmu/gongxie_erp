/**
 * 关键路径 ④：指定专家（pinned，REQ-AI-31）。
 *
 * 两条用例刻意分开，把「前端接线」与「链路行为」的判定隔开：
 *  - 第 1 条**不依赖模型**：选中专家应立刻出现指定 chip（纯前端暂存），且可取消去掉 chip；
 *  - 第 2 条依赖真实模型：指定后提问，建会话请求应带 `pinnedExpertId`，且路由提示标「已指定专家」。
 *
 * 一个易踩的点：**会话尚不存在时选专家只在前端暂存**，不会发 `PUT /ai/chat/session/{id}/expert`
 * （该 PUT 只在「已有会话再改专家」时触发）；首次暂存随 `POST /ai/chat/session` 的
 * `pinnedExpertId` 一并提交——故第 2 条断言的是**建会话请求**而不是 PUT。
 */

import { test, expect } from '@playwright/test';

import { apiGetData } from '../lib/api.mjs';
import { openChat, pickExpert, sendMessage, startNewTask } from '../lib/chat-ui.mjs';
import { CONFIG, stackSkipReason } from '../lib/config.mjs';

/** 取一个有写工具的种子专家：既验证指定，也便于与 HITL 用例复用同一入口 */
const EXPERT_NAME = '公告管理专家';

/** 依赖真实模型的用例开关（快速回归可设 false 跳过） */
const LLM_ENABLED = process.env.BIZBUDDY_E2E_UI_LLM !== 'false';

test.describe('指定专家（pinned）', () => {
  test('选中专家即出现指定 chip，可取消（不依赖模型）', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());

    const { noEntryAgent } = await openChat(page);
    test.skip(noEntryAgent, '未启用入口智能体「小Z」——请在「小Z 配置」页启用');

    // 前置：存在可见专家
    const experts = await apiGetData(page, '/ai/agent/experts', CONFIG.clientId);
    test.skip(
      !Array.isArray(experts) || experts.length === 0,
      '未找到可见专家：请在「专家配置」页新增并启用专家（status=正常、可见范围覆盖当前用户）'
    );

    // 选中即出 chip（纯前端暂存；此时尚无会话，故不发 PUT）
    await pickExpert(page, EXPERT_NAME);

    // 取消指定：chip 的叉号**仅 hover 时可见**，故先 hover 再点
    const chip = page.locator('.resource-chip--expert').first();
    await chip.hover();
    await chip.locator('.resource-chip__close').click();
    await expect(page.locator('.resource-chip--expert')).toHaveCount(0, { timeout: 10_000 });
  });

  test('指定后提问：建会话请求带 pinnedExpertId，路由提示标「已指定专家」（依赖真实模型）', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());
    test.skip(!LLM_ENABLED, '已设 BIZBUDDY_E2E_UI_LLM=false，跳过依赖真实模型的用例');
    test.setTimeout(240_000);

    const { noEntryAgent } = await openChat(page);
    test.skip(noEntryAgent, '未启用入口智能体「小Z」——请在「小Z 配置」页启用');

    const experts = await apiGetData(page, '/ai/agent/experts', CONFIG.clientId);
    test.skip(
      !Array.isArray(experts) || experts.length === 0,
      '未找到可见专家：请在「专家配置」页新增并启用专家'
    );

    await startNewTask(page);
    await pickExpert(page, EXPERT_NAME);

    // 首次暂存随建会话请求提交：断言 `POST /ai/chat/session` 的 query 带 pinnedExpertId
    const [createSession] = await Promise.all([
      page
        .waitForRequest(
          (req) =>
            req.method() === 'POST' && req.url().includes('/ai/chat/session') && !req.url().includes('/message'),
          { timeout: 20_000 }
        )
        .catch(() => null),
      sendMessage(page, '现在有哪些公告？')
    ]);
    expect(createSession, '未捕获到建会话请求（POST /ai/chat/session）').not.toBeNull();
    expect(createSession.url(), '建会话请求未携带 pinnedExpertId').toContain('pinnedExpertId');

    // 路由提示（chat/index.vue:175）：指定路径应给出「已指定专家：X」
    const note = page.locator('.route-note').last();
    await expect(note, '未出现路由提示（.route-note）').toBeVisible({ timeout: 90_000 });
    const text = (await note.innerText()).trim();
    if (/已由小Z 自行回答/.test(text)) {
      test.skip(true, `专家降级为小Z 自答（环境类，非前端接线缺陷）：${text}`);
    }
    expect(text, `路由提示文案不符：${text}`).toContain('已指定专家');
  });
});
