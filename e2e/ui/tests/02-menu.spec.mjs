/**
 * 关键路径 ②：菜单可达性巡检。
 *
 * 一次巡检覆盖 BizBuddy 全部 34 个功能页，专抓「改了一处组件、崩了别的页」这类
 * 跨页回归——这是前端投入产出最高的一条用例。
 *
 * 做法说明（两个刻意的选择）：
 * 1. **按 URL 直连导航**，不逐级点击侧边栏：`el-menu` 是 `unique-opened`，
 *    点击展开会互相折叠，逐级点选既慢又脆；页面渲染本身才是要验的东西。
 * 2. URL 清单**硬编码自建库脚本的菜单种子**（`script/sql/ry_ai.sql` 的 `sys_menu` insert，
 *    与 docs/15 页面清单一致）。**菜单有增删时必须同步本清单**——之所以不用「从侧边栏
 *    抓链接」的自动方式，是因为 Element Plus 可能惰性渲染未展开项，会造成**静默少跑**
 *    （覆盖数变少却依然全绿），那比清单过期更危险。
 */

import { test, expect } from '@playwright/test';

import { CONFIG, stackSkipReason } from '../lib/config.mjs';
import { fetchRegisteredPaths } from '../lib/menu.mjs';
import { watchPage } from '../lib/observe.mjs';

/** 6 个二级分组 → 一级前缀（用于验证菜单确已由后端动态生成） */
const GROUPS = [
  '/bizbuddy/agent/',
  '/bizbuddy/model/',
  '/bizbuddy/kb/',
  '/bizbuddy/integration/',
  '/bizbuddy/audit/',
  '/bizbuddy/safety/'
];

/** 34 个功能页（顺序与菜单种子一致，便于对照 docs/15） */
const PAGES = [
  // 智能体（13）
  ['/bizbuddy/agent/chat', '对话工作台'],
  ['/bizbuddy/agent/entry', '小Z 配置'],
  ['/bizbuddy/agent/jev', '结构化决策路由'],
  ['/bizbuddy/agent/pref', '对话偏好'],
  ['/bizbuddy/agent/expert', '专家配置'],
  ['/bizbuddy/agent/session', '会话管理'],
  ['/bizbuddy/agent/plan', '计划模式'],
  ['/bizbuddy/agent/task', '定时任务'],
  ['/bizbuddy/agent/memory', '长期记忆'],
  ['/bizbuddy/agent/skill', '技能仓库'],
  ['/bizbuddy/agent/workspace', '工作区文件'],
  ['/bizbuddy/agent/subagent', '子智能体'],
  ['/bizbuddy/agent/pack', '场景包'],
  // 模型与推理（4）
  ['/bizbuddy/model/config', '模型配置'],
  ['/bizbuddy/model/usage', '用量与配额'],
  ['/bizbuddy/model/compaction', '上下文压缩'],
  ['/bizbuddy/model/routeBudget', '编排预算'],
  // 知识库（3）
  ['/bizbuddy/kb/base', '知识库管理'],
  ['/bizbuddy/kb/doc', '文档管理'],
  ['/bizbuddy/kb/retrieval', '检索测试'],
  // 工具与集成（4）
  ['/bizbuddy/integration/registry', '工具注册'],
  ['/bizbuddy/integration/mcp-server', 'MCP 服务端'],
  ['/bizbuddy/integration/mcp-client', 'MCP 客户端'],
  ['/bizbuddy/integration/channel', '渠道接入'],
  // 审计与观测（7）
  ['/bizbuddy/audit/message', '消息审计'],
  ['/bizbuddy/audit/toolCall', '工具调用'],
  ['/bizbuddy/audit/permission', '权限决策'],
  ['/bizbuddy/audit/trace', '链路追踪'],
  ['/bizbuddy/audit/routeRun', '编排运行'],
  ['/bizbuddy/audit/health', '健康自检'],
  ['/bizbuddy/audit/digest', '平台运行日报'],
  // 安全合规（3）
  ['/bizbuddy/safety/safetyWord', '敏感词库'],
  ['/bizbuddy/safety/safetyEvent', '安全事件'],
  ['/bizbuddy/safety/security', '安全合规配置']
];

test.describe('菜单可达性巡检', () => {
  test('侧边栏由后端菜单生成：6 个分组均存在', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());

    await page.goto('/index');
    const sidebar = page.locator('.sidebar-shell');
    await expect(sidebar).toBeVisible();

    for (const prefix of GROUPS) {
      // 用 toBeAttached（不要求可见）以兼容折叠态与惰性渲染
      await expect(
        sidebar.locator(`a[href^="${prefix}"]`).first(),
        `侧边栏缺少分组 ${prefix} 下的功能页链接`
      ).toBeAttached();
    }
  });

  test('全部功能页渲染干净（console error 0 / 无 4xx·5xx）', async ({ page }) => {
    test.skip(!CONFIG.stackUp, stackSkipReason());
    test.setTimeout(PAGES.length * 25_000);

    const probe = watchPage(page);
    await page.goto('/index');

    // 先取运行时的已注册路由：据此刻分「栈旧（未注册）」与「真渲染失败」两类
    const { paths, error } = await fetchRegisteredPaths(page, CONFIG.clientId);
    expect(error, `读取已注册路由失败（${error}）`).toBeNull();

    const stale = PAGES.filter(([url]) => !paths.has(url));
    const covered = PAGES.filter(([url]) => paths.has(url));
    test.info().annotations.push({
      type: '覆盖情况',
      description: `已注册 ${covered.length}/${PAGES.length} 页；未注册（栈比仓库旧）${stale.length} 页`
    });
    if (stale.length) {
      console.warn(
        `· 有 ${stale.length} 个页面在当前栈的菜单中**未注册**——多为「栈比仓库旧」，`
          + `请重建前端与库后再跑：\n${stale.map(([url, name]) => `   - ${name} ${url}`).join('\n')}`
      );
    }

    probe.reset();
    const problems = [];
    for (const [url, name] of covered) {
      probe.reset();
      await page.goto(url, { waitUntil: 'domcontentloaded' });

      // 渲染成功判据：主框架容器 + 页面根容器（AI 功能页统一带 app-container）
      try {
        await expect(page.locator('.app-main')).toBeVisible({ timeout: 10_000 });
        await expect(page.locator('.app-container')).toBeVisible({ timeout: 10_000 });
      } catch {
        problems.push(`[${name}] ${url} 未渲染出主体容器`);
        continue;
      }

      if (probe.consoleErrors.length || probe.pageErrors.length || probe.failedResponses.length) {
        problems.push(`[${name}] ${url}\n${probe.summary()}`);
      }
    }

    expect(problems, `以下页面渲染异常：\n${problems.join('\n---\n')}`).toEqual([]);

    // 发版场景（BIZBUDDY_E2E_STRICT_MENU=true）下，未注册页视为失败：
    // 它意味着「栈没跟上仓库」，门禁必须拦住，而不是以「跳过」蒙混过关。
    if (process.env.BIZBUDDY_E2E_STRICT_MENU === 'true') {
      expect(
        stale.map(([url, name]) => `[${name}] ${url}`),
        '菜单清单中的页面在运行栈未注册（栈比仓库旧，需重建前端与库）'
      ).toEqual([]);
    }
  });
});
