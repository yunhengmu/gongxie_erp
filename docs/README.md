# 开发者文档

面向**开发者**（使用与二次开发本项目）。环境搭建、建库、启动请见仓库根 [README](../README.md)；本目录只提供更深入的内容，不重复搭建步骤。

| 文档 | 内容 |
|---|---|
| [architecture.md](architecture.md) | 运行时架构：入口智能体 → 专家链路、装配与缓存、三层权限、工具与 MCP、状态与审计 |
| [development.md](development.md) | **二次开发**：新增一个业务工具（实现 → 工厂登记 → 元数据 → 绑定），含写工具与权限注意事项 |
| [configuration.md](configuration.md) | 配置项参考（`bizbuddy.ai.*`）与生产必改清单 |

## 我要改哪里？

| 想做的事 | 从哪里入手 |
|---|---|
| 加一个业务工具 | [development.md](development.md) |
| 改智能体装配 / 路由 / 权限 | `ruoyi-ai` 的 `core/agent`（先读 [architecture.md](architecture.md)） |
| 改对话交互 / 前端页面 | `plus-ui-Vue`（对话工作台：`src/views/ai/agent/chat`） |
| 调整模型 / 压缩 / 记忆等运行策略 | [configuration.md](configuration.md) |
| 部署到服务器 | [../deploy/docker-compose.yml](../deploy/docker-compose.yml) |

## 常用命令

```bash
# AI 模块测试（改动 core/agent 后必做）
cd RuoYi-Vue-Plus
./mvnw -o -pl ruoyi-modules/ruoyi-ai test
# 覆盖率报告：ruoyi-modules/ruoyi-ai/target/site/jacoco/index.html

# 后端
./mvnw -pl ruoyi-admin spring-boot:run
# 前端
cd plus-ui-Vue && pnpm install && pnpm dev
```

## 代码导览（AI 部分）

AI 能力集中在 `ruoyi-modules/ruoyi-ai`（包 `org.dromara.ai`）：

| 包 | 职责 |
|---|---|
| `core/agent` | 智能体注册与装配、路由编排、权限决策、流式事件桥、工具工厂 |
| `core/agent/tools` | 内置业务工具实现 |
| `core/mcp` | 自建 MCP 出口与 MCP 客户端封装 |
| `core/channel` | 飞书 / 企业微信 / 钉钉 / GitHub 入站 |
| `controller` / `service` | 各管理接口与业务实现 |

## 许可与署名

本项目采用 [MIT License](../LICENSE)。后端底座源自 [Dromara RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus)（MIT），智能体内核为 AgentScope Harness。二次分发时请保留署名。