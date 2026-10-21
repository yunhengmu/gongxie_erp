/**
 * 关键路径 ⑨：知识库「多选 → 批量解析 → 异步进度」。
 *
 * 这条链路的风险不在接口本身（`../e2e` L14 已覆盖），而在页面接线：
 * 多选是否真的把 id 带进请求、批量下拉是否触发、二次确认后进度抽屉能否拿到轮询回执。
 *
 * 断言只到「结构 + 状态」：抽屉出现、批次结束、演示文档解析进入「已解析」；
 * 不断言耗时、不断言切片内容。演示文档不存在时 SKIP（与后端 e2e 的 SKIP 口径一致）。
 */

import { test, expect } from '@playwright/test';

import { CONFIG, stackSkipReason } from '../lib/config.mjs';

/** 种子演示文档名（postgres_ry_ai.sql §83） */
const DEMO_DOC_NAME = 'BizBuddy简介';

test.describe('知识库批量处理', () => {
  test('勾选演示文档 → 批量解析 → 进度抽屉显示已完成', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());
    test.setTimeout(180_000);

    await page.goto('/bizbuddy/kb/doc');
    await expect(page.locator('.app-container')).toBeVisible({ timeout: 15_000 });

    const row = page.locator('.data-table .el-table__body-wrapper tbody tr', { hasText: DEMO_DOC_NAME }).first();
    if (!(await row.isVisible().catch(() => false))) {
      test.skip(true, `未找到演示文档「${DEMO_DOC_NAME}」——没有可批量处理的文档，SKIP`);
    }

    // 勾选该行（选择列在行首）
    await row.locator('.el-checkbox').first().click();

    // 批量处理 → 批量解析
    await page.locator('button:has-text("批量处理")').click();
    await page.locator('.el-dropdown-menu__item', { hasText: '批量解析' }).first().click();
    // 二次确认
    await page.locator('.el-message-box button:has-text("确定")').click();

    // 进度抽屉：标题 + 批次标签
    const summary = page.locator('.batch-summary');
    await expect(summary).toBeVisible({ timeout: 15_000 });
    await expect(summary).toContainText('批量解析');

    // 轮询到「已完成」（后端异步解析，演示文档很小，给 90s 余量）
    await expect(summary.locator('.el-tag', { hasText: '已完成' })).toBeVisible({ timeout: 90_000 });

    // 命中项解析状态必须落到「已解析」（失败属于链路缺陷，不放过）
    const item = page.locator('.batch-item', { hasText: DEMO_DOC_NAME }).first();
    await expect(item.locator('.el-tag', { hasText: '解析：已解析' })).toBeVisible({ timeout: 15_000 });
  });
});
