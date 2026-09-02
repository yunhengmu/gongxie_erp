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
