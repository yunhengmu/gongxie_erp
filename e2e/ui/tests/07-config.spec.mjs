/**
 * 关键路径 ⑦：配置页「保存 → 重载 → 生效」。
 *
 * 这一类缺陷有**直接历史佐证**：本项目踩过「提示词注入防护开关只读 yml，页面切换根本不生效」。
 * `../http` 覆盖不到它——那条只测接口返回；真正会坏的是**页面保存后是否落库、刷新后是否回填**。
 *
 * 三页共用同一形状，逐步升级证据强度：
 *   ① 先**导航到页面**（必须：`apiGet` 依赖页面上下文的 localStorage，停在 about:blank 会 SecurityError）
 *   ② 用只读接口**快照原值**并断言页面初始值与接口一致（先抓"初始回填"这一层）
 *   ③ 页面上改值 → 点保存 → 等「保存成功」提示
 *   ④ **刷新页面** → 断言 UI 回填为新值（测「回填」）
 *   ⑤ 再读接口 → 断言服务端也是新值（测「真的落库」，区分"只改了前端本地状态"）
 *   ⑥ `finally` 用快照复原——用例必须自复原，避免把测试配置遗留在运行期配置里
 *
 * 与 `../http` L7 的做法一致（快照 → 改 → 复原）。
 */

import { test, expect } from '@playwright/test';

import { apiGetData, callViaPage } from '../lib/api.mjs';
import { CONFIG, stackSkipReason } from '../lib/config.mjs';

/** 三页的保存成功提示（用于 await 保存完成，文案取自各页 `modal.msgSuccess`） */
const SAVED_SECURITY = '保存成功，下一次对话 / 编排即按新策略生效';
const SAVED_JEV = '保存成功，下一次编排即按新模式 / 阈值生效';
const SAVED_PREF = '保存成功，下一轮对话即生效';

/**
 * 打开某个配置页并等主框架就绪。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @param {string} url 页面路由
 * @returns {Promise<void>}
 */
const openPage = async (page, url) => {
  await page.goto(url);
  await expect(page.locator('.app-container')).toBeVisible({ timeout: 15_000 });
};

/**
 * 点保存并等「保存成功」提示。
 *
 * 提示用 `ElMessage` 展示、默认几秒后自动消失，故与点击**并行**等待，避免错过。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @param {string} buttonSelector 保存按钮选择器
 * @param {string} messageText 成功提示文案
 * @returns {Promise<void>}
 */
const saveAndWait = async (page, buttonSelector, messageText) => {
  const message = page.locator('.el-message', { hasText: messageText }).first();
  await Promise.all([
    message.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => null),
    page.locator(buttonSelector).first().click()
  ]);
  await expect(message, `未出现保存成功提示：${messageText}`).toBeVisible({ timeout: 20_000 });
};

/**
 * 刷新页面并等主框架就绪（用于验证「重载后回填」）。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @returns {Promise<void>}
 */
const reloadAndWait = async (page) => {
  await page.reload();
  await expect(page.locator('.app-main')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('.app-container')).toBeVisible({ timeout: 20_000 });
};

/**
 * 用快照复原一个运行期/偏好配置（`GET` 的返回体可直接作为 `PUT` 请求体）。
 *
 * @param {import('@playwright/test').Page} page 页面
 * @param {string} path PUT 路径
 * @param {object} snapshot 快照
 * @returns {Promise<void>}
 */
const restore = async (page, path, snapshot) => {
  const { status } = await callViaPage(page, `/prod-api${path}`, CONFIG.clientId, {
    method: 'PUT',
    body: snapshot
  });
  expect(status, `复原 ${path} 失败（HTTP ${status}）`).toBeLessThan(400);
};

test.describe('配置页：保存 → 重载 → 生效', () => {
  test('安全合规配置：改「命中留证片段字符数」→ 保存 → 刷新回填且落库', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());

    await openPage(page, '/bizbuddy/safety/security');
    const snapshot = await apiGetData(page, '/ai/runtime/security', CONFIG.clientId);
    test.skip(!snapshot, '读取安全合规配置失败（GET /ai/runtime/security 无数据）——请确认接口可用且已登录');

    // 修复 #13 的回归护栏：密钥不再回显明文，只给掩码
    expect(snapshot.providerApiKey, 'GET /ai/runtime/security 仍回显明文密钥').toBeUndefined();
    expect(snapshot, '缺少密钥掩码字段 providerApiKeyMask').toHaveProperty('providerApiKeyMask');

    // 该字段在「内容审核」卡片内（另一个 PII 卡片也有保存按钮，故用容器限定）
    const field = page.locator('.el-form-item:has-text("命中留证片段字符数") input').first();
    const before = Number(snapshot.excerptChars ?? 200);
    expect(Number(await field.inputValue()), '页面初始值与接口不一致（初始回填就有问题）').toBe(before);
    const next = before + 10 > 2000 ? before - 10 : before + 10;

    try {
      await field.fill(String(next));
      await field.blur();
      await saveAndWait(page, 'div.table-panel:not(.pii-panel) button:has-text("保存")', SAVED_SECURITY);

      // 重载后 UI 回填为新值
      await reloadAndWait(page);
      await expect(
        page.locator('.el-form-item:has-text("命中留证片段字符数") input').first(),
        '刷新后页面未回填保存的新值'
      ).toHaveValue(String(next), { timeout: 15_000 });

      // 再读接口确认服务端也是新值（区分"只改了前端本地状态"）
      const persisted = await apiGetData(page, '/ai/runtime/security', CONFIG.clientId);
      expect(Number(persisted?.excerptChars), '服务端未落库新值').toBe(next);
    } finally {
      await restore(page, '/ai/runtime/security', snapshot);
    }
  });

  test('结构化决策路由：改「判定超时」→ 保存 → 刷新回填且落库', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());

    await openPage(page, '/bizbuddy/agent/jev');
    const snapshot = await apiGetData(page, '/ai/runtime/jev', CONFIG.clientId);
    test.skip(!snapshot, '读取结构化决策配置失败（GET /ai/runtime/jev 无数据）');

    const field = page.locator('.el-form-item:has-text("判定超时（秒）") input').first();
    // 关闭档下后端不做决策模型校验（validateDecisionModel 首行即 return），故可安全改值
    const before = Number(snapshot.timeoutSeconds ?? 1);
    expect(Number(await field.inputValue()), '页面初始值与接口不一致（初始回填就有问题）').toBe(before);
    const next = before >= 120 ? before - 1 : before + 1;

    try {
      await field.fill(String(next));
      await field.blur();
      await saveAndWait(page, 'button:has-text("保存")', SAVED_JEV);

      await reloadAndWait(page);
      await expect(
        page.locator('.el-form-item:has-text("判定超时（秒）") input').first(),
        '刷新后页面未回填保存的新值'
      ).toHaveValue(String(next), { timeout: 15_000 });

      const persisted = await apiGetData(page, '/ai/runtime/jev', CONFIG.clientId);
      expect(Number(persisted?.timeoutSeconds), '服务端未落库新值').toBe(next);
    } finally {
      await restore(page, '/ai/runtime/jev', snapshot);
    }
  });

  test('对话偏好：设时区 → 保存 → 刷新回填，清空后回落平台默认', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());

    await openPage(page, '/bizbuddy/agent/pref');
    const snapshot = await apiGetData(page, '/ai/chat/pref', CONFIG.clientId);
    test.skip(snapshot === null, '读取对话偏好失败（GET /ai/chat/pref 无数据）');

    try {
      // 时区下拉（filterable + allow-create）：直接从候选项里选「Asia/Tokyo（东京）」
      await page.locator('.el-form-item:has-text("时区") .el-select').click();
      await page
        .locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: 'Asia/Tokyo' })
        .first()
        .click();

      await saveAndWait(page, '.toolbar-actions button:has-text("保存")', SAVED_PREF);

      // 重载后「我已设置 / 实际生效」两段都应含新值（值来自 GET /ai/chat/pref，属服务端回填）
      await reloadAndWait(page);
      const effective = page.locator('.effective-shell');
      await expect(effective, '刷新后「我已设置」未回填时区').toContainText('Asia/Tokyo', { timeout: 15_000 });

      // 服务端确认
      const persisted = await apiGetData(page, '/ai/chat/pref', CONFIG.clientId);
      expect(persisted?.timezone, '服务端未落库时区').toBe('Asia/Tokyo');

      // 清空我的设置 → **有二次确认弹窗**（pref/index.vue:135 modal.confirm）→ 回落平台默认
      const cleared = page.locator('button:has-text("清空我的设置")');
      if (await cleared.isVisible().catch(() => false)) {
        await cleared.click();
        await page.locator('.el-message-box button:has-text("确定")').click();
        await expect(page.locator('.el-message', { hasText: '已清空，回落平台默认' })).toBeVisible({
          timeout: 20_000
        });
        await reloadAndWait(page);
        await expect(effective, '清空后「我已设置」仍显示时区').not.toContainText('Asia/Tokyo', {
          timeout: 15_000
        });
        const clearedPref = await apiGetData(page, '/ai/chat/pref', CONFIG.clientId);
        expect(clearedPref?.timezone ?? null, '清空后服务端仍保留时区').toBeNull();
      }
    } finally {
      // 快照为空串时等价于清空，故 PUT 空值即可复原两种初始状态
      await restore(page, '/ai/chat/pref', {
        timezone: snapshot?.timezone ?? '',
        locale: snapshot?.locale ?? ''
      });
    }
  });
});
