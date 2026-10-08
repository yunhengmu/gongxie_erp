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
| 部署到服务器 | PostgreSQL 栈 [../deploy/README.md](../deploy/README.md)、MySQL 8.0 栈 [../deploy-mysql/README.md](../deploy-mysql/README.md)（均含构建前置与已知坑） |
| **验证「真的跑通了」** | [../e2e/](../e2e)（外部驱动端到端脚本，见下文常用命令） |

## 常用命令

```bash
# AI 模块单测（改动 core/agent 后必做）
cd RuoYi-Vue-Plus
./mvnw -o -pl ruoyi-modules/ruoyi-ai test
# 覆盖率报告：ruoyi-modules/ruoyi-ai/target/site/jacoco/index.html

# 后端
./mvnw -pl ruoyi-admin spring-boot:run
# 前端
cd plus-ui-Vue && pnpm install && pnpm dev
```

### 端到端（e2e）回归

单测基于 Mockito 打桩、**不起 Spring 容器**；要验证「模型连通 / 知识库检索 / 专家编排 / 写工具人工确认」在真实环境下的行为，用仓库根目录的 `e2e/`（Node.js 零依赖，对**已运行**的栈发真实 HTTP 断言）：

```bash
cd ..                       # 回到仓库根（biz-buddy/）
node e2e/run.mjs                                  # 全量（未配置密钥的层自动 SKIP）
node e2e/run.mjs --only L1,L3 --verbose            # 指定层级
node e2e/run.mjs --base-url http://localhost:8081  # 指向 MySQL 栈（PG 栈默认 8080）
```

- 覆盖 **L1 模型连通 → L2 提示词生成 → L3 解析+向量化+检索 → L4 对话与专家编排（SSE）→ L5 HITL 写工具确认闭环**；
- 配置见 `e2e/.env.example`（唯一机密 `BIZBUDDY_LLM_API_KEY` **仅 L1 需要**，放 `e2e/.env.local`，已 gitignore）；
- 退出码 `0/1/2/3` = 无失败 / 有 FAIL / 前置检查失败 / 参数错误；**L5 默认「拒绝」路径无副作用**，`--allow-writes` 才真写并在收尾自动清理；
- 它不进 `mvn test`，与单测互不影响；**改了工具 / 装配 / 路由后，建议「单测 + e2e」两道都过**。

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