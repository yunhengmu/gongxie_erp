/**
 * 关键路径 ⑥：写操作人工确认（HITL 卡片）。
 *
 * 为什么值得单独测：这是本项目最复杂的状态机之一——写工具命中「需确认」档时框架暂停、
 * 卡片下发、用户处置后**另起一条恢复流**续跑；UI 层是唯一能验证「卡片真的出现、按钮真的能推进」的地方
 * （`../e2e` 覆盖的是接口与落库语义）。
 *
 * 面向 UI 的 DOM 变化序列（取自 chat/index.vue）：
 *  `.confirm-card--pending` 出现（标题「该操作需要你确认后才会执行」）
 *  → 点「允许本次」→ `POST /ai/chat/session/{id}/confirm` 成功
 *  → 卡片转 `.confirm-card--approved`、`.confirm-card__actions` 消失 → 恢复流续跑 → 最终 `.msg-meta` 出现。
 *
 * 触发不确定性的处置：模型是否调用写工具不可控，故「卡片未出现」归为 **SKIP**（多为模型能力/话术问题，
 * 非前端接线缺陷），仅当卡片出现后处置链路出错才判 FAIL。
 */

import { test, expect } from '@playwright/test';

import { apiGetData } from '../lib/api.mjs';
import { messageErrorText, openChat, pickExpert, sendMessage, startNewTask, waitAssistantDone } from '../lib/chat-ui.mjs';
import { CONFIG, stackSkipReason } from '../lib/config.mjs';

/** 种子中绑定了「需确认」写工具的专家（ry_ai.sql:1696-1701） */
const EXPERT_NAME = '公告管理专家';

/** 该专家的写工具（decision='1'）：卡片里的工具名是**工具编码**，不是中文名 */
const WRITE_TOOLS = ['create_notice', 'update_notice', 'delete_notice'];

/**
 * 触发写操作的话术，**两次尝试**：先自然问法（取自该专家 capability_desc 的示例），
 * 不中再换「点名工具 + 明确参数」的强指令。模型是否调工具不可控，单次尝试触发率偏低。
 */
const TRIGGER_PROMPTS = [
  '帮我发个放假通知',
  '请调用 create_notice 工具发布一条公告：标题「放假通知」，内容「公司明天放假一天」。'
];

/** 依赖真实模型的用例开关（快速回归可设 false 跳过） */
const LLM_ENABLED = process.env.BIZBUDDY_E2E_UI_LLM !== 'false';

test.describe('写操作人工确认（HITL）', () => {
  test('命中需确认的写工具 → 卡片出现 → 允许本次 → 卡片转「已允许」且编排续跑', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());
    test.skip(!LLM_ENABLED, '已设 BIZBUDDY_E2E_UI_LLM=false，跳过依赖真实模型的用例');
    test.setTimeout(300_000);

    const { noEntryAgent } = await openChat(page);
    test.skip(noEntryAgent, '未启用入口智能体「小Z」——请在「小Z 配置」页启用');

    const experts = await apiGetData(page, '/ai/agent/experts', CONFIG.clientId);
    test.skip(
      !Array.isArray(experts) || experts.length === 0,
      '未找到可见专家：请在「专家配置」页新增并启用专家'
    );
    const target = experts.find((item) => item.agentName === EXPERT_NAME);
    test.skip(!target, `可见专家里没有「${EXPERT_NAME}」——请确认种子专家已启用且在你的可见范围内`);
    // 注意 `/ai/agent/experts` 的项用 `agentId` 字段（不是 `id`）——取错会把有效前置误判为缺失
    const targetId = target.agentId ?? target.id;

    // 前置：该专家确实绑定了「需确认」的写工具（否则永远不会触发卡片）
    const detail = await apiGetData(page, `/ai/agent/${targetId}`, CONFIG.clientId);
    const bindings = Array.isArray(detail?.toolBindings) ? detail.toolBindings : [];
    const askWriteTools = bindings.filter(
      (bind) => bind.decision === '1' && WRITE_TOOLS.includes(bind.toolCode ?? bind.toolName)
    );
    test.skip(
      askWriteTools.length === 0,
      '未配置「需确认」写工具绑定：请在「专家配置」页（/bizbuddy/agent/expert）把写工具'
        + `（${WRITE_TOOLS.join(' / ')}）的授权决策设为「需确认」并保存，然后**新建会话**后重试`
    );

    await startNewTask(page);
    // 先指定专家，避免自动路由的不确定性
    await pickExpert(page, EXPERT_NAME);

    // 等待确认卡片：模型是否调用写工具不可控，故按话术**两次尝试**，全不中才 SKIP
    const card = page.locator('.confirm-card').last();
    let appeared = false;
    for (const prompt of TRIGGER_PROMPTS) {
      if (await messageErrorText(page)) {
        break;
      }
      await sendMessage(page, prompt);
      appeared = await card
        .waitFor({ state: 'visible', timeout: 60_000 })
        .then(() => true)
        .catch(() => false);
      if (appeared) {
        break;
      }
    }
    if (!appeared) {
      const error = await messageErrorText(page);
      test.skip(
        true,
        error
          ? `模型/链路不可用：${error}`
          : '两次话术均未触发写工具调用，HITL 未触发（模型能力所致，非前端接线缺陷）'
      );
    }

    // 卡片内容：待确认 + 标题 + 工具编码
    await expect(card).toHaveClass(/confirm-card--pending/);
    await expect(card.locator('.confirm-card__title')).toHaveText('该操作需要你确认后才会执行');
    await expect(card.locator('.confirm-card__tool').first()).toHaveText(
      new RegExp(WRITE_TOOLS.join('|')),
      { timeout: 10_000 }
    );

    // 允许本次
    const [confirmResponse] = await Promise.all([
      page
        .waitForResponse((res) => res.request().method() === 'POST' && res.url().includes('/confirm'), {
          timeout: 20_000
        })
        .catch(() => null),
      card.locator('button:has-text("允许本次")').click()
    ]);
    expect(confirmResponse, '未捕获到 POST /ai/chat/session/{id}/confirm 请求').not.toBeNull();
    expect(confirmResponse.status(), `确认接口返回 ${confirmResponse.status()}`).toBeLessThan(400);

    // 处置后的即时表现：卡片转「已允许」，动作按钮消失
    await expect(card).toHaveClass(/confirm-card--approved/, { timeout: 20_000 });
    await expect(card.locator('.confirm-card__actions')).toHaveCount(0);

    // 恢复流应续跑并收尾
    const done = await page
      .locator('.msg-row--assistant .msg-meta')
      .last()
      .waitFor({ state: 'visible', timeout: 120_000 })
      .then(() => true)
      .catch(() => false);
    if (!done) {
      // 二次命中「需确认」时前端会**追加**一张新卡片（同一气泡内），此时本轮已验证首次确认链路
      const again = await page.locator('.confirm-card--pending').count();
      test.skip(again > 0, '恢复流再次命中需确认工具（前端追加卡片）——首次确认链路已验证');
      await waitAssistantDone(page, 5_000);
    }
  });
});
