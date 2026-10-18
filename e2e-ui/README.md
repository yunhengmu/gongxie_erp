# e2e-ui：前端关键路径端到端测试

测的是**已部署的容器栈**（真实浏览器 + 真实前后端），覆盖登录、菜单可达性与会话主路径。

与 [`../e2e`](../e2e/) 的分工：

| | `../e2e`（HTTP 层） | 本目录（浏览器层） |
|---|---|---|
| 手段 | 零依赖纯 Node，直接发 HTTP/SSE | Playwright 驱动 chromium |
| 覆盖 | 接口契约、编排行为、SSE 事件序列 | 页面渲染、动态菜单、前端关键路径 |
| 依赖 | **零 npm 依赖** | `@playwright/test`（重依赖，故独立目录承载） |

> 之所以另建目录：`../e2e` 的零依赖是刻意设计（无需 `npm i` 即可在任意机器跑），
> 浏览器依赖会破坏这一点。

## 前置

1. 容器栈已起：PG 栈 `deploy/deploy-pg`（前端 `http://localhost`）、MySQL 栈 `deploy/deploy-mysql`（前端 `http://localhost:81`）；
2. 首次运行：

   ```bash
   cd e2e-ui
   pnpm install
   pnpm run install-browser      # 下载 chromium
   ```

3. 登录凭证复用 [`../e2e/.env.local`](../e2e/.env.example)（默认 `admin` / `admin123`，超管）。也可用环境变量覆盖：`BIZBUDDY_UI_BASE_URL` / `BIZBUDDY_USERNAME` / `BIZBUDDY_PASSWORD`。

## 运行

```bash
# PG 栈（默认 http://localhost）
pnpm test

# MySQL 栈
BIZBUDDY_UI_BASE_URL=http://localhost:81 pnpm test
```

报告：`playwright show-report .artifacts/html`；失败时截图与 trace 落在 `.artifacts/output/`。

## 判定标准（三条须同时成立）

1. **`console.error` / 未捕获异常计数 = 0**——前端无全局 `errorHandler`（`src/main.ts` 未注册），渲染期异常会落到这里；这是后端「日志 ERROR 归零」在**前端**的等价物；
2. **网络层无 4xx / 5xx**——抓「接口改了、页面没跟上」；
3. **关键元素可见**——如 `.app-main` / `.app-container` / 助手气泡。

静态资源类噪声（favicon、sourcemap、`ResizeObserver loop`）在白名单里，见 [`lib/observe.mjs`](lib/observe.mjs)。

## SKIP 语义（不是 FAIL）

栈不可达、入口智能体未启用、对话链路前置不满足（如未配模型密钥）→ **SKIP**。理由与 `../e2e` 一致：本套件测的是「已部署的栈」，前置不满足属环境问题而非代码缺陷。

## 实跑记录与三个坑（2026-10-16，PG 栈）

环境：WSL（**发行版按本机实际**，当次为 Ubuntu-22.04）、node 22.23.0、pnpm 11.24.0、`@playwright/test` 1.64.1；PG 栈 `docker compose up -d` 运行中（**最终那次实跑前已重建 `ruoyi-admin`**）。

结果：**13 passed（3.4m，含 1 条登录态 setup）**——登录/路由守卫、菜单与 32/33 页渲染、会话主路径、指定专家、停止生成（「已中止」护栏）、HITL 写工具确认、配置页三组（安全合规 / 结构化决策 / 对话偏好）。

> **必须跑在「已重建」的栈上**：重建后端镜像前，`07-config` 的「密钥不回显」断言会 FAIL——`GET /ai/runtime/security` 仍回显 `providerApiKey`，根因是运行中的 jar 陈旧（源码已实现掩码）。重建 `ruoyi-admin` 后即 13/13；顺带一提，重建容器会换 IP，需 `docker compose restart web` 让 nginx 重新解析。

> 首次运行前先 `pnpm install && pnpm run install-browser`。国内网络下如卡在浏览器下载，走镜像即可（实测可用）：
> `PLAYWRIGHT_DOWNLOAD_HOST=https://cdn.npmmirror.com/binaries/playwright pnpm run install-browser`
> （Windows PowerShell：先 `$env:PLAYWRIGHT_DOWNLOAD_HOST='https://cdn.npmmirror.com/binaries/playwright'` 再执行。）

### 坑 1：请求必须带 `clientid` 头（否则被判未登录）

前端**每个请求都带两个头**（[`src/utils/request.ts:94-102`](../plus-ui-Vue/src/utils/request.ts)）：

```ts
Authorization: 'Bearer ' + getToken(),
clientid: import.meta.env.VITE_APP_CLIENT_ID
```

少了 `clientid`，后端按 Sa-Token 客户端校验直接把请求判成未登录（返回 `{"code":401,"data":null}`）。本套件调菜单接口时已带上该头（[`lib/menu.mjs`](lib/menu.mjs)），客户端 id 默认取 `plus-ui-Vue/.env.production` 的 `VITE_APP_CLIENT_ID`（已公开），可用 `BIZBUDDY_CLIENT_ID` 覆盖。

### 坑 2：栈比仓库旧会被误判成「页面坏了」

首次实跑时报 `[对话偏好] /bizbuddy/agent/pref 未渲染出主体容器`。查运行库发现 **32** 个功能页、且 `path='pref'` 查询 0 行——即**运行栈是 3 天前的镜像与库**，而菜单清单来自仓库最新种子（33 项）。

因此套件**先读运行时的 `getRouters`**，把清单按「已注册」与「未注册」分开：

- 未注册 → 归类为**栈旧**，只告警不失败（并给出需重建的页面清单）；
- 已注册但渲染不出来 → 归类为**真页面缺陷**，失败。

发版门禁场景应设 `BIZBUDDY_E2E_STRICT_MENU=true`，把「未注册」也视为失败——否则一次「忘了重建栈」会以「跳过」蒙混过关（已实测该开关会如期拦住）。

### 坑 3：WSL 发行版名不写死（环境适配）

本地开发环境同时存在 **Ubuntu-22.04 与 Ubuntu-24.04**，所以脚本与文档里一律不写死发行版名。
`../e2e` 的 [`lib/env.mjs`](../e2e/lib/env.mjs) 提供两个解析器，L12 的 SKIP 提示与 `docs/release-checklist.md` 的起桩命令都由它们生成／占位：

| 解析器 | 作用 | 取值优先级 |
|---|---|---|
| `wslDistro()` | 本机 WSL 发行版名 | `BIZBUDDY_WSL_DISTRO` > 自动探测（`wsl --list --verbose` 里带 `*` 的默认项）> 占位符 `<distro>` |
| `wslRepoPath()` | 仓库根在 WSL 下的路径（`D:\a\b` → `/mnt/d/a/b`） | 自动换算（已在 WSL 内时原样返回） |

> 本机实测值供参考：发行版 `Ubuntu-22.04`、仓库根 `/mnt/d/test/mybuddy/biz-buddy`。
> 注意 `wsl --list --verbose` 在 Windows 上输出 **UTF-16LE**，按 utf8 解会得到夹 `\0` 的乱码——`wslDistro()` 已处理这一点。

## 用例清单

| 文件 | 覆盖 | 依赖模型 |
|---|---|---|
| `tests/00-auth.setup.mjs` | UI 登录并落 `storageState`（供后续复用） | 否 |
| `tests/01-login.spec.mjs` | 未登录访问受保护路由 → 跳登录；登录成功进入主框架；**验证码控件不存在**（锁 `docs/00 #4` 口径） | 否 |
| `tests/02-menu.spec.mjs` | 侧边栏由后端菜单生成（6 分组）；**34 个功能页逐个渲染干净** | 否 |
| `tests/03-chat.spec.mjs` | 新建任务 → 发送 → 助手流式回答结束（覆盖 **SSE + nginx + 编排**） | 是 |
| `tests/04-pinned.spec.mjs` | **指定专家**：①选中即出指定 chip 且可取消（纯前端）②建会话请求带 `pinnedExpertId` + 路由提示标「已指定专家」 | ②是 |
| `tests/05-stop.spec.mjs` | **停止生成**：流式中点「停止」→ 游标消失、发送回归、**已流出正文保留**、取消请求成功 | 是 |
| `tests/06-hitl.spec.mjs` | **写操作人工确认**：卡片出现 → 允许本次 → 转「已允许」且动作按钮消失 → 恢复流续跑收尾 | 是 |
| `tests/07-config.spec.mjs` | **配置页「保存 → 重载 → 生效」**：security「留证片段字符数」／jev「判定超时」／pref「时区」——改值 → 保存 → **刷新断言回填** → 再核服务端落库 → 复原 | 否 |

### 依赖真实模型的用例：开关与 SKIP 语义

`03` / `04②` / `05` / `06` 会真调模型（慢，且受额度影响）。两点约定：

- **开关**：设 `BIZBUDDY_E2E_UI_LLM=false` 可整体跳过这四条，用于快速回归；
- **触发不确定即 SKIP 而非 FAIL**：模型是否调用写工具、回答是否够长（能否捕获流式窗口）都不可控，
  这类「模型行为导致未触发」按 SKIP 处理并打印原因；只有**卡片/按钮出现之后处置链路出错**才判 FAIL。
  这也是三条用例的核心分工——**验前端接线，不验模型输出**。

`06` 为提高触发率会**两次尝试**：先自然话术，不中再换「点名工具 + 明确参数」的强指令。

共享设施：[`lib/api.mjs`](lib/api.mjs)（页面上下文内带鉴权的只读接口调用，供前置判定）、
[`lib/chat-ui.mjs`](lib/chat-ui.mjs)（会话工作台的共享交互：开页 / 新建任务 / 选专家 / 发消息 / 等流式）。

> **易踩的点（写用例时必读）**：
> ① 会话尚不存在时选专家**只在前端暂存**，不会发 `PUT /ai/chat/session/{id}/expert`——该 PUT 只在「已有会话再改专家」时触发；首次暂存随 `POST /ai/chat/session` 的 `pinnedExpertId` 一并提交。
> ② `/ai/agent/experts` 的项用 **`agentId`** 字段（不是 `id`），取错会把有效前置误判成缺失、导致用例被错误 SKIP。
> ③ `apiGet` / `callViaPage` 依赖页面上下文的 `localStorage` 取登录 token，**必须先导航到应用页面**再调用；停在 `about:blank` 会 `SecurityError`。
> ④ 配置页的「清空我的设置」**有二次确认弹窗**（`pref/index.vue:135` `modal.confirm`），不点「确定」不会生效。

### 维护点：菜单清单

`02-menu.spec.mjs` 的 33 条 URL **硬编码自建库脚本的菜单种子**（`RuoYi-Vue-Plus/script/sql/ry_ai.sql` 的 `sys_menu` insert，与 [docs/15](../docs/15-BizBuddy页面与接口清单.md) 页面清单一致）。
**菜单增删时必须同步该清单**——刻意不使用「从侧边栏抓链接」的自动方式：Element Plus 可能惰性渲染未展开项，会导致**静默少跑**（覆盖变少却依然全绿）。

运行时还会把该清单与**栈上真实的 `getRouters`** 比对，未注册的页单独归类为「栈旧」而非「页面缺陷」（见上方坑 2）。

## 明确不做

视觉回归（截图 diff）、跨浏览器矩阵（只跑 chromium）、移动端/触摸屏、无障碍、性能指标。
理由：这些在未接 CI 的阶段是纯负债；真要做，等本层稳定后再单独评估。

## 与发版门禁的关系

建议作为发版门禁的**第 2b 道**（两栈各跑一遍，零 FAIL）：与 `../e2e` 的第 2 道互补——
后者证明「接口对」，本层证明「**页面真的能用**」。OT（纯后端发版）可跳过本层。
