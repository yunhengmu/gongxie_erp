/**
 * 前置：登录一次并落盘登录态（storageState），供后续用例复用。
 *
 * 选择**走 UI 登录**而不是直接调 `/auth/login`：登录请求带 `isEncrypt`（RSA 加密），
 * 前端加密链路（`src/utils/request.ts` + `VITE_APP_ENCRYPT`）与后端判定口径之间存在
 * 未证实的细节，走 UI 可绕开这层不确定性，顺带真实验证登录页。
 */

import { test as setup, expect } from '@playwright/test';

import { CONFIG, STATE_FILE, stackSkipReason } from '../lib/config.mjs';
import { watchPage } from '../lib/observe.mjs';

setup('登录并保存登录态', async ({ page }) => {
  setup.skip(!CONFIG.stackUp, stackSkipReason());

  const probe = watchPage(page);
  await page.goto('/login');

  // 验证码已关闭（captcha.enable=false，见 docs/00 #4）：控件不应渲染
  await expect(page.locator('.captcha-row')).toHaveCount(0);

  await page.fill('input[placeholder="用户名"]', CONFIG.username);
  await page.fill('input[placeholder="密码"]', CONFIG.password);
  await page.click('button.submit-button');

  // 登录成功：离开 /login 并进入主框架
  await expect(page).not.toHaveURL(/\/login/, { timeout: 30_000 });
  await expect(page.locator('.app-main')).toBeVisible();

  expect(probe.consoleErrors, `登录链路控制台报错：\n${probe.summary()}`).toEqual([]);
  expect(probe.failedResponses, `登录链路网络失败：\n${probe.summary()}`).toEqual([]);

  await page.context().storageState({ path: STATE_FILE });
});
