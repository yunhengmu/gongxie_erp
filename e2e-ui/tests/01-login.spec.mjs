/**
 * 关键路径 ①：登录（正路径 + 未登录访问受保护路由的负路径）。
 *
 * 负路径刻意**不使用「错误密码」**案例：RuoYi 有失败次数锁定（`maxRetryCount`），
 * 多次错误密码会把超管账号锁死、毒化后续所有用例。改用「未登录访问受保护路由」，
 * 无副作用且同样验证路由守卫（`src/permission.ts` 的 whiteList 与重定向）。
 */

import { test, expect } from '@playwright/test';

import { CONFIG, stackSkipReason } from '../lib/config.mjs';
import { watchPage } from '../lib/observe.mjs';

// 本文件验证「未登录」行为，必须清掉登录态
test.use({ storageState: { cookies: [], origins: [] } });

test.describe('登录与路由守卫', () => {
  test('未登录访问受保护路由 → 跳登录页并带 redirect', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());

    await page.goto('/bizbuddy/agent/chat');
    await expect(page).toHaveURL(/\/login\b/);
    await expect(page).toHaveURL(/redirect=/);
    await expect(page.locator('.login')).toBeVisible();
  });

  test('账号密码登录成功 → 进入主框架且无验证码控件', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());

    const probe = watchPage(page);
    await page.goto('/login');

    await expect(page.locator('.captcha-row')).toHaveCount(0);
    await expect(page.locator('input[placeholder="用户名"]')).toBeVisible();

    await page.fill('input[placeholder="用户名"]', CONFIG.username);
    await page.fill('input[placeholder="密码"]', CONFIG.password);
    await page.click('button.submit-button');

    await expect(page).not.toHaveURL(/\/login/, { timeout: 30_000 });
    // '/index' 是登录后的默认落点（router 把 '/' 重定向到 '/index'）
    await expect(page.locator('.app-main')).toBeVisible();
    await expect(page.locator('.sidebar-shell')).toBeVisible();

    expect(probe.consoleErrors, `登录链路控制台报错：\n${probe.summary()}`).toEqual([]);
    expect(probe.failedResponses, `登录链路网络失败：\n${probe.summary()}`).toEqual([]);
  });
});
