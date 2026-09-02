# MCP 多租户 RBAC 工具 × LangChain：规范综述与本项目现状

> 前置阅读：`07-mcp-tool-config-guide.md`（怎么配一个工具走 MCP）、`11-tenant-isolation-in-module-ai.md`（芋道租户隔离源码）、`12-multi-tenant-rbac-agent-tool.md`（多租户 RBAC 业界综述）。  
> 本文回答两个新问题：**① 规范层面：工具隔离 / 数据隔离 / Agent 分配（定义与调整时机）应该怎么做；② 本项目两个模块的现状与差距**。  
> **③ 两段落地代码已拆到 [`14-mcp-langchain-production-code.md`](14-mcp-langchain-production-code.md)**——module-ai 注册工具（Spring AI 2.0 `@Tool` 最新写法）经 MCP 供 module-agent 使用，以及 `/v2/chat` 生产级全功能会话接口（12 步管线 + 22 项要素）。本文只讲规范与现状，代码篇见文档 14。

---

## 一、规范综述：多租户 RBAC 工具经 MCP 被 LangChain 使用

调研来源：MCP 官方规范（modelcontextprotocol.io，Streamable HTTP 于 2025-03-26 引入、SSE 同步弃用）、langchain-mcp-adapters 官方文档（Python/JS）、Spring AI 2.0 官方博客《Tool Calling in Spring AI 2.0: A Composable, Agentic Architecture》（2026-06）、Spring AI issue #4401（动态工具加载/McpToolFilter）、业界多篇多租户 Agent 鉴权实践（WorkOS/Auth0/Zylos，详见 `12-multi-tenant-rbac-agent-tool.md`）。

### 1.1 MCP 工具的生命周期（先讲机制，隔离设计都建立在它上面）

一次 MCP 连接分三步，隔离点分别落在不同步骤：

| 阶段    | JSON-RPC 方法                  | 说明                | 可做的隔离                  |
| ----- | ---------------------------- | ----------------- | ---------------------- |
| 初始化握手 | `initialize` / `initialized` | 协商协议版本、能力         | 传输层鉴权（HTTP 头/token）    |
| 工具发现  | `tools/list`                 | 服务器返回**服务器级**工具清单 | 连接级过滤：只返回该连接有权用的工具     |
| 工具调用  | `tools/call`                 | 执行单个工具            | 执行级鉴权：工具内部校验 RBAC + 租户 |

关键规范事实：**`tools/list` 返回的是"服务器有什么工具"，协议没有"按调用方过滤工具"的参数**。MCP 的权限模型在 2025-06 规范里只有传输层授权（OAuth 2.1），没有应用层 RBAC 语义。所以多租户 RBAC 必须由**服务器实现自己叠加**，业界叠加位置就是上表后两列。

### 1.2 工具隔离：三招叠加，缺一不可

| 招式                  | 做法                               | 芋道/本项目落点                                                                                                                                                                     |
| ------------------- | -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ① 连接级过滤（tools/list） | 服务器在响应 `tools/list` 时按连接身份过滤工具清单 | Spring AI 侧：`McpToolFilter`（注意：目前是**客户端侧**过滤，服务端按 `SecurityContext` 过滤是社区诉求，见 issue #4401，尚未有稳定 API）。稳妥做法：**在 LangChain 侧 `get_tools()` 之后按角色白名单过滤再绑给 agent**（文档14 代码① §3.3 即此写法） |
| ② 执行级鉴权（tools/call） | 工具函数体内校验登录态/权限点，deny by default  | 两种落点：(a) 工具方法内 `SecurityFrameworkUtils.getLoginUser()` + 权限点校验（文档14 §3.1）；(b) 在 `ToolCallback` 外包 `AuthzToolCallback` 装饰器统一收口、按 `AiChatRoleDO.toolIds` 校验（文档14 §3.2）——后者保证“绕过清单直接 call 无权工具”也被拦。对应 Auth0 的原则"鉴权必须写在工具执行逻辑里，LLM 无法绕过"                                                                              |
| ③ 拆分服务器             | 按权限域拆多个 MCP Server               | 成本高、重复多，B2B SaaS 一般不首选；芋道单 server + ①②叠加即可                                                                                                                                   |

为什么要 ①+② 都做：只做 ①（清单过滤）会被 prompt 注入诱导调用未列出的工具；只做 ②（执行鉴权）LLM 会看到无权工具——浪费 token 且泄露工具存在性。


### 1.3 数据隔离：租户上下文怎么穿过 MCP 边界

MCP Streamable HTTP 是普通 HTTP 端点（默认 `POST /mcp`），所以租户上下文走 **HTTP 请求头**：

```
module-agent ──HTTP──> /mcp
              Header: Authorization: Bearer <用户或服务token>
              Header: tenant-id: 1          ← 芋道网关/租户过滤器的约定头
```

服务器侧链路（芋道真实机制）：

1. `TenantContextWebFilter` 从 `tenant-id` 头解析租户 → 写入 `TenantContextHolder`（ThreadLocal）；
2. 安全过滤器校验 `Authorization` token → 写入登录态；
3. 工具方法内执行业务 → MyBatis-Plus 的 `TenantDatabaseInterceptor` **自动在 SQL 拼 `tenant_id` 条件**，工具代码不用手写租户过滤；
4. 工具返回的数据天然只含本租户行。

两个容易踩的坑（都对应本项目真实代码）：

- **本地调用与 MCP 调用的上下文来源不同**。Java 内部走聊天（`AiChatMessageServiceImpl`）时，租户/用户放在 `AiUtils.buildCommonToolContext()` 构造的 `toolContext`（`LOGIN_USER`/`TENANT_ID` 两个 key）里传递；走 MCP 时，`toolContext` 这条路**不存在**——MCP 调用方是外部进程，上下文只能来自 `/mcp` 请求头。所以工具方法内要读 `TenantContextHolder` / `SecurityFrameworkUtils`，而不是依赖 `toolContext`。
- **谁的头就透传谁**。module-agent 如果拿自己的内部 token（`api_tools.py` 的做法）去调 `/mcp`，Java 侧看到的是"服务身份"，所有租户数据都可访问——等于打穿隔离。正确做法：**把终端用户的 token 原样透传**，让 Java 侧按用户身份走完整 RBAC + 租户链路。


### 1.4 Agent 分配：Agent 的定义，以及"什么时候调"

Agent（以 LangChain `create_agent` 为例）= **模型 + 工具集 + system prompt + 记忆配置（checkpointer/store）**。前三样决定了"Agent 的定义"什么时候定、什么时候变：

| 绑定时机  | 做法                                                 | 优点       | 缺点                    | 适用        |
| ----- | -------------------------------------------------- | -------- | --------------------- | --------- |
| 启动时静态 | 模块 import 时 `create_agent`（本项目 `core/agent.py` 现状） | 零运行时开销   | 工具集/提示词写死             | 工具集全局一致   |
| 会话级缓存 | 按 `(tenant, role)` 缓存 agent，TTL 或事件失效              | 兼顾性能与灵活  | 要管理缓存失效               | **多租户推荐** |
| 请求级组装 | 每请求 `get_tools()` 过滤后重新 `create_agent`             | 最灵活，权限实时 | 每请求一次 `tools/list` 往返 | 权限频繁变化    |

**触发"调整 Agent"的时机**（业界共识 + 本项目对应物）：

1. **意图路由（每请求）**：先分类用户问题再选 agent——本项目 `AgentService.select_agent`（BERT 分类器 + 策略选择器）就是这种；LangChain 官方 agent 文档同样把"router/supervisor"作为标准模式。
2. **角色配置变化（事件驱动）**：管理员改了 `AiChatRoleDO.toolIds` / `mcpClientNames` → 相关租户/角色的 agent 缓存失效重建。
3. **工具集变化（事件/TTL 驱动）**：MCP 服务器有 `tools/list_changed` 通知能力；拿不到通知就用 TTL 兜底（如 5 分钟）。`ai_tool` 表启停工具同理。
4. **租户开通/关闭 MCP server**：连接配置本身变了 → 重建 MCP client。
5. **记忆配置变化**：checkpointer/store 是进程级单例（连接池），**不随 agent 重建**——只有工具与提示词需要动态化。

一个重要的成本事实：`create_agent` 产出的只是 LangGraph 的图定义，构建是轻量的；真正贵的是每次 `tools/list` 的网络往返。所以"请求级组装"瓶颈不在 create_agent，而在工具发现——这也是为什么推荐"会话级缓存 + 事件失效"。

---

## 二、本项目现状（全部对应真实源码）


### 2.1 module-ai（Java，Spring AI 2.0.0）

| 事实                            | 源码                                                                                                                                                          |
| ----------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Spring AI 版本 2.0.0            | `yudao-dependencies/pom.xml` `<spring-ai.version>2.0.0</spring-ai.version>`                                                                                 |
| MCP server/client starter 已引入 | `yudao-module-ai-server/pom.xml`：`spring-ai-starter-mcp-server-webmvc`、`spring-ai-starter-mcp-client`（注释明确禁用 webflux 版）                                     |
| 工具表                           | `AiToolDO`（`ai_tool` 表：name=Spring Bean 名、description、status）                                                                                               |
| 角色绑定工具                        | `AiChatRoleDO.toolIds`（本地工具）+ `mcpClientNames`（MCP 客户端）                                                                                                     |
| 聊天时装配工具                       | `AiChatMessageServiceImpl#getToolCallbackListByRoleId`：toolIds → `ToolCallbackResolver.resolve(beanName)`；mcpClientNames → `SyncMcpToolCallbackProvider` 桥接 |
| 本地调用的上下文                      | `AiUtils.buildCommonToolContext()`：`LOGIN_USER` + `TENANT_ID` 放入 `toolContext`，随 `ChatOptions.toolContext` 传给工具                                             |
| 老式工具写法                        | `tool/function/*ToolFunction`：`Function<Req,Resp>` + `@JsonPropertyDescription` 生成 schema                                                                   |
| 新式工具写法已在项目里                   | `AiAutoConfiguration#toolCallbacks` 用 `ToolCallbacks.from(personService)`；`07` 文档演示了 `@Tool/@ToolParam`                                                     |
| MCP 配置                        | `application.yaml`：`spring.ai.mcp.server/client` 均 `enabled: false`，且只有 `sse-endpoint: /sse`（旧协议；`annotation-scanner` 因 spring-ai#4917 bug 关闭）              |

### 2.2 module-agent（Python，LangChain 1.3 + LangGraph 1.2）

| 事实       | 源码                                                                                                             |
| -------- | -------------------------------------------------------------------------------------------------------------- |
| Agent 工厂 | `core/agent.py`：三个 agent（research/code_reviewer/rag），`langchain.agents.create_agent`（V1 新 API），启动时静态构建         |
| 短期记忆     | `core/memory.py`：`checkpointer`（`InMemorySaver` 或 `AsyncPostgresSaver`），按 `thread_id` 隔离对话                     |
| 长期记忆     | 同文件：`store`（`InMemoryStore` 或 `AsyncPostgresStore`）跨会话持久化；工具层 `core/tool/memory_tools_tenant.py` 的 `make_memory_tools(tenant_id, user_id)` 工厂，namespace 固化为 `(users, 租户, 用户)`（原全局版 `memory_tools.py` 已于 2026-09-01 删除，后按租户化工厂恢复） |
| 微服务工具    | `core/tool/api_tools.py`：`@tool` + httpx，内部 token 注入，LLM 看不到凭证（设计原则正确，但内部 token 不能用于跨租户 MCP，见 1.3）             |
| Agent 分配 | `app/services/agent_service.py#select_agent`：BERT 分类 → 策略选择 → 分发（意图路由型）                                        |
| 会话接口     | `app/routers/chat.py`：`/v1/chat`（非流式/流式 SSE）、`/v1/research`、`/v1/review`                                       |

### 2.3 差距清单（两段落地代码要补的，代码见文档 14）

1. 两个模块**没打通**：`spring.ai.mcp.server.enabled: false`；module-agent 依赖里没有 `langchain-mcp-adapters`，`mcp.json` 只有外部示例服务器。
2. yaml 里 MCP 端点还是弃用的 SSE 写法，未升级 Streamable HTTP（`/mcp`）。
3. ~~module-agent 请求没有租户/用户上下文~~ **部分修复（2026-09-02）**：`ChatRequest` 已加 `tenant_id`/`user_id` 字段，长期记忆 namespace 已按身份隔离；但 MCP 业务工具（`api_tools` 走全局内部 token）的跨租户身份透传仍未打通。
4. 短期记忆 `thread_id` 无租户命名空间——多租户下不同租户撞 `thread_id` 就能读到别人的会话（checkpointer 本身不做鉴权）。
5. 会话接口缺语言分析（中英文提示词切换）与长期记忆的"加载→注入→回写"闭环。
6. ~~长期记忆工具的 namespace 是全局的~~ **已修复（2026-09-02）**：原 `memory_tools.py` 写死 `("users",)` 全服共享，已于 2026-09-01 删除；现由 `memory_tools_tenant.py` 的 `make_memory_tools(tenant_id, user_id)` 恢复，namespace 固化为 `(users, 租户, 用户)`，`core/agent.py#build_agent` 按请求身份组装挂载（见文档14 代码② §4.3 文件2）。

---

## 三、两段落地代码（已拆到文档 14）

| 代码 | 内容 | 文档位置 |
|------|------|---------|
| 代码 ① | module-ai 用 Spring AI 2.0 `@Tool` 注册业务工具（工具内执行级 RBAC + 租户隔离）→ `ToolCallbackProvider` 外包 `AuthzToolCallback` 执行级鉴权装饰器（tools/call 第二道关）→ Streamable HTTP `/mcp` 暴露 → module-agent 用 langchain-mcp-adapters 按租户身份 + 角色白名单 + 透传 role-id 使用 | [`14-mcp-langchain-production-code.md`](14-mcp-langchain-production-code.md) 第一章（原 §3.1–3.3） |
| 代码 ② | `/v2/chat` 生产级全功能会话接口：22 项生产要素（4.1）→ 12 步管线（4.2 流程图 + 分步）→ 四个文件完整代码（4.3）→ 覆盖对照表（4.4）→ 框架内置 vs 自己写（4.5） | 同文档第二章（原 §4.1–4.5） |

> 需要「每个文件该放哪个目录、谁依赖谁」的目录树版，见 [`13-代码目录编排.md`](13-代码目录编排.md)。

---

## 四、一页总结

1. **规范**：MCP 协议本身只有传输层授权（OAuth 2.1），`tools/list` 无按调用方过滤的参数——多租户 RBAC 靠"连接级过滤（tools/list 清单收敛）+ 执行级鉴权（tools/call 内 deny by default）"双层叠加；租户上下文走 `/mcp` 请求头 → 服务器 ThreadLocal → SQL 自动拼租户条件。
2. **Agent 分配**：Agent = 模型 + 工具集 + 提示词 + 记忆配置。分配按"意图路由（每请求）"做；工具集/提示词的**调整时机**是角色配置变化、工具 CRUD、MCP `tools/list_changed`（或 TTL 兜底）；`create_agent` 本身廉价，贵的是 `tools/list`，所以用"会话级缓存 + 事件失效"。
3. **本项目**：module-ai 的工具注册（`ai_tool` 表 + `ToolCallbackResolver` + `SyncMcpToolCallbackProvider`）与租户拦截体系齐备，但 MCP server 关着且还是 SSE 旧配置；module-agent 的 agent/记忆体系齐备，但没接 MCP、无租户上下文、thread_id 与记忆 namespace 都缺租户隔离。
4. **两段落地代码**已拆到文档 14：① module-ai `@Tool` 注册工具 → `ToolCallbackProvider` 外包执行级鉴权装饰器 → Streamable HTTP `/mcp` → module-agent `MultiServerMCPClient`（租户头 + role-id + 角色过滤 + 缓存）；② `/v2/chat` 生产级会话接口（22 项要素 → 12 步管线 → 四个文件代码 → 覆盖对照表）。
