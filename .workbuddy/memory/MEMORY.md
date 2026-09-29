# 项目长期记忆

## 用户偏好与约束（重要，必须遵守）

- **只分析、讲解、写文档，绝不擅自创建或修改代码文件。** 用户多次表达"我让你分析/教我/给我看"，却多次被我在项目里擅自新建 `examples/` 等代码文件而激怒。除非用户**明确说"改代码/写实现/落地"**，否则一律只做：讲解、画图、写 `.md` 文档、给代码片段（写在文档里，不写入项目）。
- 用户重视"示例（stub）与生产（框架内置）的分界"，讲解必须讲透哪些是框架内置、哪些要自己写。
- 用户会指出我不严谨/瞎编的词，要求每个说法都对应真实代码/源码。
- 用户在学习芋道 `module-ai`（Java）+ `module-agent`（Python）的 MCP 工具链路、多租户、RBAC、会话记忆等产品化设计。

## 项目技术要点（供后续参考）

- 多租户边界在 Java（`TenantSecurityWebFilter` 越权校验 + `TenantDatabaseInterceptor` SQL 自动拼 tenant_id），Python 只透传 token。
- MCP transport 默认用 Streamable HTTP（`/mcp`），SSE 已被规范弃用。
- module-agent 的对话记忆 = LangGraph `checkpointer`（短时/按 thread_id）+ `store`（长时），重启持久化需切 `AsyncPostgresSaver`。
- **`core/tool/memory_tools.py`（全局版）已于 2026-09-01 删除**（用户授权）：namespace 写死 `("users",)` 全局共享，多租户下跨租户可读写，且读写不出 Python 进程、Java 三层隔离管不到。`core/agent.py` 已移除挂载，三个 prompt 模板（research/rag/code_review.md）已删除 Long-term Memory 段，README 已同步。租户安全替代 = CODE-WIKI/13 文件 2 的 `make_memory_tools(tenant_id, user_id)` 闭包工厂。注意：`AI应用开发笔记.md` 里仍有旧代码引用（用户的历史笔记，未动）。

- **术语约定（2026-09-17 用户纠正）**：撤回机制一律用 **messageId**，不要用设计文档原文里的 "turnId"。
  系统内只有 `ai_chat_message.id` 一个消息 ID，不存在第二个 turn 编号。我混用两个词让用户以为存在两套 ID 而提出质疑。
  唯一需区分处：前端可能传 assistant 行 id，需归一化到其 `reply_id`（= user 行 id）后再用。

- **术语约定（2026-09-22 用户纠正）**：**禁止使用「薄壳」**。这不是 Spring 术语，也不是任何规范术语，
  是我写 CODE-WIKI/15 时自己造的口语化叫法（对应 thin adapter），且已被我自己污染进 16/18 号文档、
  工作记忆和 `ReversibleLedgerAspect` 的报错字符串。统一改用 **「冲账适配器」/ `XxxReversibleAdapter`**（GoF 的 Adapter）。
  用户对「无出处的词」会直接质疑，凡是术语必须能指到官方文档 / 规范 / 具体类名。

- **代码归属边界（2026-09-22 用户确认）**：`module-ai-server/.../ai/framework/reversible/core` 下 8 个文件是**用户自己写的**；
  `framework` 下其余 `ai/`（含 `config`、`core/model/*`、`core/webserch`）、`rpc/`、`security/` 是**芋道原生代码，禁止改动**。
- **注释约定（2026-09-22 用户要求，已全量执行）**：自己的代码里「描述性注释」一律不写。
  类/接口/枚举/record 头部只留 `/** @author hengmu */`；字段、方法的 javadoc（含 `@param`/`@return`）和所有 `//` 行内注释全部删除。
  作者统一 `hengmu`。立码时不要再往这些文件里补注释。
