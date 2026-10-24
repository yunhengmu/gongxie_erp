/**
 * BizBuddy 前端关键路径 e2e（Playwright）。
 *
 * <p>与 `../http`(HTTP 层，零依赖) 互补：本套件跑**真实浏览器 + 真实容器栈**，
 * 覆盖登录、菜单可达性与会话主路径。判定标准见 `lib/observe.mjs`。</p>
 *
 * <p>前置：容器栈已起（PG `http://localhost` / MySQL `http://localhost:81`），
 * 首次运行需 `pnpm i && pnpm run install-browser`。</p>
 */

import { defineConfig, devices } from '@playwright/test';

import { ARTIFACTS_DIR, CONFIG, STATE_FILE } from './lib/config.mjs';

export default defineConfig({
  testDir: './tests',
  // globalSetup 探测栈可达性；不可达时各用例 SKIP 而非 FAIL
  globalSetup: './global-setup.mjs',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  // 关键路径用例串行：共享同一栈与登录态，避免相互干扰
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [
    ['list'],
    ['json', { outputFile: `${ARTIFACTS_DIR}/report.json` }],
    ['html', { outputFolder: `${ARTIFACTS_DIR}/html`, open: 'never' }]
  ],
  outputDir: `${ARTIFACTS_DIR}/output`,
  use: {
    baseURL: CONFIG.uiBaseUrl,
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 20_000,
    navigationTimeout: 30_000
  },
  projects: [
    // ① 登录一次并落盘登录态（storageState），供后续用例复用
    {
      name: 'auth',
      testMatch: /00-auth\.setup\.mjs/,
      use: { ...devices['Desktop Chrome'] }
    },
    // ② 关键路径用例：依赖 ① 的登录态
    {
      name: 'chromium',
      testMatch: /\d{2}-.*\.spec\.mjs/,
      dependencies: ['auth'],
      use: {
        ...devices['Desktop Chrome'],
        storageState: STATE_FILE
      }
    }
  ]
});
