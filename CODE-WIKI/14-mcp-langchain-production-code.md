# 代码 ① ②：MCP 工具注册与生产级会话接口（完整落地代码）

> 本文是 [`13-mcp-langchain-multitenant-agent-chat.md`](13-mcp-langchain-multitenant-agent-chat.md) **第三、四章两段落地代码的原样迁出**，独立成文便于对照落位。
>
> - **代码 ①（第一章）**：module-ai 注册工具（Spring AI 2.0 `@Tool` 最新写法）→ 经 Streamable HTTP `/mcp` 暴露 → module-agent 用 langchain-mcp-adapters 按租户身份 + 角色白名单使用。
> - **代码 ②（第二章）**：`/v2/chat` 生产级全功能会话接口（按 module-agent 的 md 文档 + `core/` 全量要素盘点重构的 12 步管线设计模式）。
> - 小节号保留原文档的 `3.1–3.3` / `4.1–4.5`，便于与文档 13、[`13-代码目录编排.md`](13-代码目录编排.md)（文件落点目录树版）交叉引用。文中出现的「规范综述 1.1–1.4」「现状 2.x」「差距清单 2.3」等编号均指向文档 13。
>
> 约定：以下代码是**讲解用完整范例**，未写入项目工程；包名/类名遵循项目现有结构，可直接对照落位。



---

## 一、代码 ①：module-ai 注册工具（Spring AI 2.0 最新写法）→ module-agent 经 MCP 使用


### 3.1 Java：用 `@Tool` 定义带 RBAC + 租户隔离的工具

放在 `yudao-module-ai-server/.../module/ai/tool/function/` 下（与 `DbQueryToolFunction` 同目录）。Spring AI 2.0 推荐 `@Tool/@ToolParam` 注解写法（对比项目里旧的 `Function<Req,Resp>` 写法，schema 由方法签名自动生成，不需要 `@JsonPropertyDescription`）：

```java
package cn.iocoder.yudao.module.ai.tool.function;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 工具：查询当前租户的订单（示例业务工具，走 MCP 暴露给 module-agent）
 *
 * 隔离设计：
 *  - RBAC（执行级）：MCP 调用不经过 AiChatMessageServiceImpl 的 toolContext，
 *    登录态来自 /mcp 请求头（安全过滤器写入），这里必须自己校验、deny by default
 *  - 数据隔离：orderService 走 MyBatis-Plus，TenantDatabaseInterceptor 自动拼
 *    tenant_id，方法内不手写租户条件
 */
@Component
public class OrderQueryToolFunction {

    // @Resource private AdminOrderService orderService; // 真实实现注入业务 Service
    @Resource
    private SecurityFrameworkService securityFrameworkService; // 权限点校验（yudao-spring-boot-starter-security）

    @Tool(name = "query_tenant_order", description = "查询当前租户下指定订单号的状态与金额。仅返回当前登录用户所属租户的数据")
    public String queryTenantOrder(
            @ToolParam(description = "订单编号，例如 1024", required = true)
            String orderNo) {

        // ① 执行级鉴权：未登录直接拒绝（LLM 无法绕过这里）
        var loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null) {
            return "[拒绝] 未登录，无法查询订单";
        }
        // ② 权限点校验（功能权限粒度；工具内部最后一道兜底，与 3.2 执行级鉴权装饰器互补）
        if (!securityFrameworkService.hasAnyPermissions("ai:order:query")) {
            return "[拒绝] 当前用户无 ai:order:query 权限";
        }

        // ③ 数据隔离：TenantContextHolder 由租户过滤器从请求头写入；
        //    业务 SQL 由 TenantDatabaseInterceptor 自动追加 tenant_id
        Long tenantId = TenantContextHolder.getTenantId();
        // OrderDO order = orderService.getOrder(orderNo); // 真实实现
        return "订单 " + orderNo + "（租户 " + tenantId + "）状态: SHIPPED, 金额: 199.00";
    }
}
```

要点复述：**MCP 路径下工具方法里读 `TenantContextHolder`/`SecurityFrameworkUtils`，而不是 `toolContext`**——后者只在 Java 内部聊天链路存在（`AiUtils.buildCommonToolContext()`）。`toolContext` 与 MCP 请求头是两套不同的上下文来源；工具方法内统一以 ThreadLocal（`TenantContextHolder`/`SecurityFrameworkUtils`）为准，不依赖 `toolContext`。


### 3.2 Java：注册为 ToolCallbackProvider，经 Streamable HTTP 暴露

`spring-ai-starter-mcp-server-webmvc` 已在 pom（真实存在），`ToolCallbackProvider` Bean 会被 MCP server 自动转成 MCP 工具对外暴露——这也是项目自己选的路线（`application.yaml` 里 `annotation-scanner` 因 spring-ai#4917 关闭，所以不用 `@McpTool` 注解扫描路线）。

**但 `ToolCallbackProvider` 只负责"把工具暴露出去"，不管"当前角色能不能调"**。`tools/list` 的清单过滤在 Python 侧做（3.3），而 `tools/call` 的执行级鉴权必须在 Java 侧再拦一道——否则 prompt 注入可绕过清单直调无权工具（文档 13 §1.2：只做清单过滤会被注入诱导调用未列出的工具）。做法：给每个 `ToolCallback` 包一层执行级鉴权装饰器，`deny by default`。

**① 角色上下文 `ChatRoleContextHolder`（ThreadLocal）**：`/mcp` 请求头里的 `role-id` 由 WebFilter 解析写入，供装饰器读取当前角色。注意区分芋道两套角色——这里是「AI 聊天角色」`AiChatRoleDO`（决定工具白名单），不是 RBAC 权限角色（决定功能权限点）：

```java
package cn.iocoder.yudao.module.ai.framework.ai.core;

/**
 * AI 聊天角色上下文（MCP 调用链路的角色载体）。
 * /mcp 请求头 role-id 由 WebFilter 解析写入（需在过滤器链末尾 clear() 防线程复用串号）。
 */
public class ChatRoleContextHolder {

    private static final ThreadLocal<Long> ROLE_ID = new ThreadLocal<>();

    public static void setRoleId(Long roleId) { ROLE_ID.set(roleId); }
    public static Long getRoleId() { return ROLE_ID.get(); }
    public static void clear() { ROLE_ID.remove(); }
}
```

**② 执行级鉴权装饰器 `AuthzToolCallback`**：包住真实 `ToolCallback`，`call()` 前先查「角色 → 工具」白名单，通过才委托：

```java
package cn.iocoder.yudao.module.ai.framework.ai.core;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * tools/call 执行级鉴权装饰器（与 tools/list 清单过滤互补的第二道关）。
 * 白名单以 AiChatRoleDO.toolIds 为准；租户隔离由 TenantDatabaseInterceptor 自动保证。
 */
public class AuthzToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AiChatRoleService chatRoleService; // 查询 AiChatRoleDO.toolIds

    public AuthzToolCallback(ToolCallback delegate, AiChatRoleService chatRoleService) {
        this.delegate = delegate;
        this.chatRoleService = chatRoleService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        // ① 登录态：MCP 调用不经过 toolContext，登录态来自 /mcp 请求头（安全过滤器写入）
        if (SecurityFrameworkUtils.getLoginUser() == null) {
            return "[拒绝] 未登录，无法调用工具";
        }
        // ② 角色 → 工具白名单：当前角色不含此工具 → 拒（LLM 无法绕过这里）
        Long roleId = ChatRoleContextHolder.getRoleId();
        String toolName = getToolDefinition().name();
        if (roleId == null || !chatRoleService.containsTool(roleId, toolName)) {
            return "[拒绝] 当前角色无权调用工具 " + toolName;
        }
        return delegate.call(toolInput); // 通过才委托真实工具
    }
}
```

**③ 注册时包一层**：`MethodToolCallbackProvider` 产出的 `ToolCallback` 逐个包成 `AuthzToolCallback` 再交给 MCP server：

```java
package cn.iocoder.yudao.module.ai.framework.ai.config;

import cn.iocoder.yudao.module.ai.framework.ai.core.AuthzToolCallback;
import cn.iocoder.yudao.module.ai.service.chat.AiChatRoleService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class AiMcpToolConfiguration {

    @Bean
    public ToolCallbackProvider yudaoAiToolCallbackProvider(
            OrderQueryToolFunction orderQueryTool,
            AiChatRoleService chatRoleService) {
        ToolCallbackProvider raw = MethodToolCallbackProvider.builder()
                .toolObjects(orderQueryTool)
                .build();
        // 关键：暴露给 /mcp 前，给每个 ToolCallback 包执行级鉴权装饰器（tools/call 第二道关）
        ToolCallback[] authzCallbacks = Arrays.stream(raw.getToolCallbacks())
                .map(cb -> (ToolCallback) new AuthzToolCallback(cb, chatRoleService))
                .toArray(ToolCallback[]::new);
        return () -> authzCallbacks;
    }
}
```

这样 `tools/list`（Python 侧按角色过滤清单）与 `tools/call`（Java 侧按 `AiChatRoleDO.toolIds` 鉴权调用）形成双层防御，缺一不可。

`application.yaml` 打开 MCP server（在现有 `spring.ai.mcp.server` 段上改）：

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        name: yudao-mcp-server
        version: 1.0.0
        instructions: 芋道 AI 业务工具服务（订单/知识库/用户域）
        # Spring AI 2.0 推荐 Streamable HTTP（默认端点 /mcp），SSE 已被 MCP 规范弃用。
        # 注意：本项目 yaml 里现存的 sse-endpoint: /sse 是旧写法，应删除。
        # streamable-http 端点的具体配置键名请以所用 spring-ai-autoconfigure-mcp-server-common
        # 版本的配置元数据为准（此处不臆造键名），端点默认即 POST /mcp。
```

三个必须做的配套（不然隔离是空的）：

1. **网关/安全链路放行并鉴权 `/mcp/**`**：`/mcp` 是普通 HTTP 端点，要把它纳入 token 校验路径（不能 permitAll），让安全过滤器能写登录态、`TenantContextWebFilter` 能从 `tenant-id` 头写 `TenantContextHolder`。
2. **`role-id` 头要由 WebFilter 写入 `ChatRoleContextHolder`**：执行级鉴权装饰器靠它拿当前「AI 聊天角色」；roleId 由 module-agent 从会话（`AiChatConversationDO.roleId`）透传（见 3.3），过滤器链末尾记得 `clear()` 防线程复用串号。
3. **工具清单 ≠ 权限清单**：`tools/list` 会返回全部工具（见 1.2 ①），真正的收敛靠"清单过滤 + 执行级鉴权 + 工具内鉴权"三层叠加。


### 3.3 Python：module-agent 经 langchain-mcp-adapters 使用（租户头 + 角色过滤）

新增依赖（`pyproject.toml` 当前没有）：`uv add langchain-mcp-adapters`。

`core/tool/mcp_tools.py`——**每个请求按租户身份建连接、透传 token**：

```python
"""module-ai MCP 工具加载器。

设计要点（对应规范综述 1.2/1.3）：
  - 传输用 Streamable HTTP（/mcp），SSE 已被规范弃用
  - 租户/用户身份放在连接 headers 里原样透传终端用户 token，
    绝不使用 module-agent 自己的内部 token（否则打穿租户隔离）
  - role-id 头把「AI 聊天角色」透传给 Java 侧，供 tools/call 执行级鉴权
    （AiChatRoleDO.toolIds 白名单，见 3.2 AuthzToolCallback）
  - tools/list 的角色白名单过滤只是清单过滤（第一道关）；
    执行级鉴权由 Java 侧 AiChatRoleDO.toolIds 兜底（第二道关，双层防御）
"""
import logging
import time

from langchain_mcp_adapters.client import MultiServerMCPClient

from config.settings import settings

logger = logging.getLogger(__name__)

# 缓存：tenant_id + 角色白名单 -> (client, tools, expire_at)
# 对应规范综述 1.4 的"会话级缓存 + TTL 失效"
_TOOL_CACHE: dict[tuple[str, frozenset | None], tuple[MultiServerMCPClient, list, float]] = {}
_TOOL_CACHE_TTL = 300  # 秒；生产上建议叠加 MCP tools/list_changed 通知主动失效


async def get_yudao_ai_tools(
    tenant_id: str,
    user_token: str,
    role_id: str = "",
    allowed_tools: set[str] | None = None,
    force_refresh: bool = False,
):
    """加载 module-ai 经 MCP 暴露的工具，按租户身份连接 + 角色白名单过滤。

    Args:
        tenant_id:  租户编号（透传给 Java 的 tenant-id 头）
        user_token: 终端用户 token（原样透传，Java 侧做完整鉴权）
        role_id:    AI 聊天角色 id（透传给 Java 的 role-id 头，供 tools/call 执行级鉴权）
        allowed_tools: 允许的工具名集合（角色白名单，tools/list 清单过滤）；None 表示不过滤
        force_refresh: 强制刷新缓存（如 MCP 工具变更）
    """
    cache_key = (tenant_id, frozenset(allowed_tools) if allowed_tools is not None else None)

    if not force_refresh and cache_key in _TOOL_CACHE:
        client, tools, expire_at = _TOOL_CACHE[cache_key]
        if time.monotonic() < expire_at:
            return client, tools

    client = MultiServerMCPClient(
        {
            "yudao-ai": {
                "transport": "streamable_http",   # 旧版本 langchain-mcp-adapters 写作 "http"
                "url": settings.yudao_ai_mcp_url,  # 例如 http://127.0.0.1:48086/mcp
                "headers": {                       # 租户/身份上下文走请求头（规范综述 1.3）
                    "Authorization": f"Bearer {user_token}",
                    "tenant-id": tenant_id,
                    "role-id": role_id,            # 供 Java 侧 ChatRoleContextHolder → 执行级鉴权
                },
            }
        }
    )
    tools = await client.get_tools()               # 一次 tools/list 网络往返

    if allowed_tools is not None:                  # 角色白名单过滤（清单过滤，第一道关）
        tools = [t for t in tools if t.name in allowed_tools]

    _TOOL_CACHE[cache_key] = (client, tools, time.monotonic() + _TOOL_CACHE_TTL)
    return client, tools
```

在 agent 里使用（对照 `core/agent.py` 现有静态写法，改成"会话级缓存组装"）：

```python
"""按租户组装 agent（替代启动时静态构建中"工具集固定"的部分）。
模型、checkpointer、store 仍是进程级单例（core/models.py / core/memory.py）。"""
from langchain.agents import create_agent

from core.models import deepseek_model
from core.memory import checkpointer, store
from core.tool.memory_tools_tenant import make_memory_tools  # 租户化记忆工具（4.3 文件 2；原全局版已删除）
from core.tool.mcp_tools import get_yudao_ai_tools
from core.prompts import RESEARCH_PROMPT


async def build_research_agent(tenant_id: str, user_id: str, user_token: str, role_id: str = "", allowed: set[str] | None = None):
    _, mcp_tools = await get_yudao_ai_tools(tenant_id, user_token, role_id=role_id, allowed_tools=allowed)
    # create_agent 只是构建 LangGraph 图定义，开销很小（规范综述 1.4）；
    # 贵的是上面那次 tools/list，已被 _TOOL_CACHE 缓存
    return create_agent(
        model=deepseek_model,
        tools=mcp_tools + make_memory_tools(tenant_id, user_id),   # MCP 工具 + 租户化长期记忆工具
        system_prompt=RESEARCH_PROMPT,
        checkpointer=checkpointer,        # 短期记忆：单例复用
        store=store,                      # 长期记忆：单例复用
    )
```

---

## 二、代码 ②：生产级全功能会话接口（按 core/ 要素盘点重构）

> 重构方法（对应本次要求的三步）：  
> **第一步**：通读 module-agent 的三份文档——`README.md`（架构/目录/能力清单）、`AI应用开发笔记.md`（14 章生产实践，含 SSE/安全/部署）、`DEPENDENCIES.md`（依赖组划分）；  
> **第二步**：把 core/ 下所有能力盘点成生产要素清单（4.1，每一项标注真实代码锚点）；  
> **第三步**：写一个覆盖全部要素的会话接口设计模式（4.2 管线 + 4.3 完整代码 + 4.4 逐项对照表）。


### 4.1 第二步的产出：生产考虑要素清单（core/ 里几乎都有现成的）

一个生产级会话接口要考虑的要素，按请求流经顺序分六组（编号在后文代码与对照表中引用）：

**A. 输入理解——回答之前先"听懂"**

| #  | 要素         | core/ 真实落点                                                                                    | 在接口中的作用                     |
| -- | ---------- | --------------------------------------------------------------------------------------------- | --------------------------- |
| A1 | 语言检测       | **缺**，需补约 10 行启发式（多语种再换 langdetect）                                                           | 决定 system prompt 的回答语言指令    |
| A2 | 中文分词/关键词提取 | `core/text_utils.py`：`jieba_cut_for_search` / `jieba_extract_keywords`                        | 检索 query 增强；日志里留下可观测的"理解结果" |
| A3 | 意图分类       | `core/rag/classifier.py`：`classify_query`（BERT 5 分类，**内置规则回退降级**）                             | 路由依据；confidence 供低置信度降级判断   |
| A4 | 检索策略选择     | `core/rag/strategy.py`：`select_strategy`（按资源可用性自动降级 mysql_exact → hybrid_search → direct_llm） | 决定是否预检索、走哪类 Agent           |

**B. 知识与检索**

| #  | 要素   | core/ 真实落点                                                                                            | 在接口中的作用                                      |
| -- | ---- | ----------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| B5 | 混合检索 | `core/rag/retriever.py`：`HybridRetriever.search_as_context`（BM25 + jieba + 向量 + RRF 融合）               | 专业知识类策略的预检索，产出注入 system 的上下文                 |
| B6 | 结果缓存 | `core/rag/cache.py`：`RedisCache`（TTL + **Redis 不可用自动降级**）                                             | 非流式确定性问答命中即返，省一次 LLM 调用                      |
| B7 | 质量评估 | `core/rag/evaluate.py`：`EvalSample` / `save_eval_samples` / `RagasEvaluator.evaluate_from_file`（5 维度） | 在线采样落盘、离线批量评估（在线没有 ground_truth，不能现场跑 Ragas） |

**C. 工具**

| #   | 要素       | core/ 真实落点                                                                                                                    | 在接口中的作用                                      |
| --- | -------- | ----------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| C8  | 本地工具     | `core/tool/tools.py`：`web_search` / `code_review` / `make_retriever_tool`（kb_search）                                          | Agent 自主检索与代码审查能力                            |
| C9  | MCP 租户工具 | 本文 3.3 `mcp_tools.get_yudao_ai_tools` + 3.2 `AuthzToolCallback`                                                               | 业务工具按租户身份 + 角色白名单装配；tools/call 执行级鉴权兜底（双层防御） |
| C10 | 长期记忆工具   | `core/tool/memory_tools_tenant.py`：`make_memory_tools(tenant_id, user_id)` 工厂（namespace 固化为 `(users, 租户, 用户)`，**已落地**，替代原全局版） | Agent 读写长期记忆（D13 的"写"半边）                     |
| C11 | 凭证安全     | `api_tools.py` 的设计原则：LLM 不见凭证/URL/内部地址                                                                                        | token 只出现在编排层与 MCP headers，绝不进工具参数           |

**D. 记忆**

| #   | 要素   | core/ 真实落点                                                | 在接口中的作用                              |
| --- | ---- | --------------------------------------------------------- | ------------------------------------ |
| D12 | 短期记忆 | `core/memory.py`：`checkpointer`（in_memory / postgres 双模式） | `thread_id = 租户:用户:会话` 命名空间，自动存取对话历史 |
| D13 | 长期记忆 | `core/memory.py`：`store`；加载→注入 system→工具回写                | 三段闭环：编排层读、prompt 注入、agent 工具自主读写     |

**E. 编排与输出**

| #   | 要素         | core/ 真实落点                                                                   | 在接口中的作用                                            |
| --- | ---------- | ---------------------------------------------------------------------------- | -------------------------------------------------- |
| E14 | Agent 装配时机 | 1.4 的三时机表 + 3.3 的 tools/list 缓存                                              | 请求级组装（贵的 tools/list 已缓存，create_agent 本身廉价）         |
| E15 | 提示词工程      | `core/prompts/`：`loader.py`（LRU 缓存读 md 模板）+ `builders.py` + `templates/*.md` | system prompt 从模板体系组装，不硬编码字符串                      |
| E16 | 流式输出       | `AI应用开发笔记.md` 12.1：`astream_events(version="v2")` + `StreamingResponse`      | SSE 事件协议：meta → tool* → token* → done/error        |
| E17 | 统一响应与异常    | `app/schemas/common.py`：`ApiResponse`；`app/main.py`：三个全局异常处理器                | 非流式走 ApiResponse；**流式 headers 已发出后异常只能发 error 事件** |

**F. 工程化**

| #   | 要素     | core/ 真实落点                                                              | 在接口中的作用                          |
| --- | ------ | ----------------------------------------------------------------------- | -------------------------------- |
| F18 | 配置集中管理 | `config/settings.py`（50+ 项，.env/环境变量覆盖）                                 | 新增参数全进 settings，不在代码里散落          |
| F19 | 可观测    | `main.py` 双层日志（api.request / microservice.call）+ LangSmith（lifespan 开启） | trace_id 贯穿一次会话全链路；耗时统计          |
| F20 | 入口鉴权   | `main.py`：`verify_api_key` 中间件（生产换 JWT/OAuth2）                          | 请求进来第一道门                         |
| F21 | 降级容错   | 全项目一致风格：BERT→规则、Redis→无缓存、向量库→跳过、搜索 Tavily→DuckDuckGo                   | **每个外部依赖都有 Plan B**，接口不因组件故障整体失败 |
| F22 | 生命周期   | `main.py`：`lifespan`（启动时建重资源，见笔记 4.2/前文教训：不用请求时才建）                      | 检索器预热、能力探测集中在启动期                 |


### 4.2 管线设计：一个请求流经的 12 步

#### 4.2.0 整体流程图（先看图把握全局，再看分步说明）

> 语法采用最保守写法：`graph` 关键字 + `-->|标签|` 连线，不用 subgraph、链式连线、菱形节点和节点内注释，旧版渲染器也能渲染；节点里括号夹带的细节说明全部移到 4.2.1 分步说明。

```mermaid
graph TB
    client["POST /v2/chat<br/>tenant_id · user_id · token"]
    auth["① 入口鉴权 F20"]
    e401["401 · ApiResponse"]
    lang["② 输入理解 A1 A2"]
    cls["③ 意图分类 A3"]
    strat["④ 策略选择 A4"]
    cache["⑤ 缓存探测 B6"]
    hit["直接返回缓存答案"]
    mem["⑥ 长期记忆加载 D13"]
    rag["⑦ 预检索 B5"]
    prompt["⑧ system prompt 组装 E15"]
    agent["⑨ Agent 装配 E14"]
    call["⑩ 调用 D12"]
    ainvoke["非流式 ainvoke"]
    sse["流式 E16 astream_events"]
    tools["执行期 Agent 自主调用工具"]
    post["⑪ 后处理 B6 B7"]
    posts["⑪ 评估采样 B7"]
    resp["ApiResponse E17"]
    sdone["SSE done 收尾"]

    client --> auth
    auth -->|失败| e401
    auth --> lang
    lang --> cls
    cls --> strat
    strat --> cache
    cache -->|命中| hit
    cache -->|未命中| mem
    mem --> rag
    rag --> prompt
    prompt --> agent
    agent --> call
    agent -.-> tools
    call -->|非流式| ainvoke
    call -->|流式| sse
    ainvoke --> post
    post --> resp
    sse --> posts
    posts --> sdone
```

读图要领（三条线）：

- **一条主线**：鉴权 → 听懂（②③④）→ 取数（⑤⑥⑦）→ 组装（⑧⑨）→ 调用（⑩）→ 收尾（⑪），即下方分步说明的 ①–⑫；
- **分叉看连线标签**：⑤ 命中/未命中、⑩ 非流式/流式是图中显式分叉；④ 选出的策略里只有 HYBRID / MYSQL_EXACT 才走 ⑦ 预检索（DIRECT_LLM 跳过），这一条件图中未展开，见分步说明 ⑦；两条支路的后处理不同——流式不写缓存（设计决策 2）；两支路收尾节点都标 ⑪，对应分步说明 ⑪"后处理"在非流式（写缓存 B6 + 采样 B7）与流式（只采样 B7）两分支，并非编号重复；
- **一条虚线**：⑨ 装配的工具在 ⑩ 执行期由 Agent **自主**调用（LLM 决定何时用），这是"编排层预检索"与"工具自主检索"并存的关键（设计决策 1）。

#### 4.2.1 分步说明

```
POST /v2/chat（身份三元组：tenant_id / user_id / token）
  ① 中间件：API Key 校验（F20）→ Router 依赖注入 Service
  ② 语言检测（A1）+ 关键词提取（A2）
  ③ 意图分类 classify_query（A3，BERT 不可用自动规则回退）
  ④ 策略选择 select_strategy（A4，按资源可用性降级）
  ⑤ 缓存探测（B6，仅非流式 + 确定性策略）──命中→ 直接返回
  ⑥ 长期记忆加载 store.search(namespace=(users,租户,用户))（D13 读）
  ⑦ 预检索 HybridRetriever.search_as_context（B5，仅 HYBRID/MYSQL_EXACT 策略）
  ⑧ system prompt 组装（E15：模板 + 语言指令 + 记忆块 + 检索上下文）
  ⑨ Agent 装配（E14：本地工具 C8 + 租户记忆工具 C10 + MCP 工具 C9，失败降级）
  ⑩ 调用：thread_id=租户:用户:会话（D12）→ ainvoke（非流式）/ astream_events（E16）
  ⑪ 后处理：写缓存（B6）+ 评估采样落盘（B7，异步不阻塞）
  ⑫ 全程：trace_id 日志（F19）+ 每步 try/except 降级（F21）
```

五个关键设计决策（都是"生产"与"能跑"的差别所在）：

1. **预检索 vs 工具自主检索**：`rag.md` 模板自带 `{context}` 占位符（`build_rag_prompt` 的设计意图）、`AI应用开发笔记.md` 12.1 的 rag_service 也是"检索→拼 system→调 agent"——所以专业知识类策略在编排层预检索注入；通用问题不预检索，让 agent 用 `web_search`/`kb_search` 自主决定（省检索开销）。
2. **缓存只对非流式 + 确定性策略生效**：流式无法"命中缓存"（用户要的就是过程）；HYBRID 策略答案依赖检索时效，不缓存。
3. **流式生成器里绝不能 raise**：SSE 的 headers 在 `StreamingResponse` 返回时就已发出，之后抛异常只会断连——必须 try/except 包住整个循环，yield 一条 `error` 事件后正常收尾（E17）。
4. **Agent 每请求组装，但贵的部分全部缓存**：`create_agent` 只是图定义（1.4 成本事实），真正贵的 `tools/list` 已被 3.3 的 `(tenant, role)` 缓存挡住；模型/checkpointer/store 都是单例。这等价于"会话级缓存"的性价比，又免掉了缓存失效管理的复杂度。
5. **评估在线采样、离线评估**：在线没有 ground_truth，`EvalSample` 的 `ground_truth` 是必填——所以接口只按采样率把 (question, answer, contexts) 落盘，离线补标注后用 `RagasEvaluator.evaluate_from_file` 批量跑（这是项目 evaluate.py 已提供的现成能力）。


### 4.3 第三步的产出：完整示例代码（四个文件，覆盖 A1–F22 全部要素）

> 约定同第一章（代码 ①）：讲解用范例，未写入工程；所有 import 的函数/类都在 core/ 真实存在（个别标注"需新增"）。

#### 文件 1：`app/schemas/chat_v2.py`（请求/响应/元信息模型）

```python
"""v2 会话接口的数据模型。

身份三元组（tenant_id/user_id/token）由网关从请求头注入（F20 鉴权之后落到请求体），
module-agent 只透传、不做权限裁决——最终裁决在 Java 侧（规范综述 1.3）。
"""
from pydantic import BaseModel, Field


class ChatRequestV2(BaseModel):
    tenant_id: str = Field(..., examples=["1"], description="租户编号")
    user_id: str = Field(..., examples=["1024"], description="用户编号")
    token: str = Field(..., description="终端用户 token，原样透传给 MCP /mcp")
    role: str = Field("member", description="AI 聊天角色，用于工具白名单（文档12的两套角色之'AI 聊天角色'）")
    conversation_id: str = Field("", description="会话编号；空则新开")
    message: str = Field(..., min_length=1, description="本轮用户输入")
    stream: bool = True
    lang: str = Field("", description="zh/en；空则自动检测（A1）")
    top_k: int = Field(5, ge=1, le=20, description="预检索条数（B5）")


class ChatMeta(BaseModel):
    """SSE 首个 meta 事件 / 非流式响应的元信息（前端可展示'本次走了什么链路'）。"""
    thread_id: str
    lang: str          # A1 检测结果
    category: str      # A3 分类结果
    confidence: float  # A3 置信度
    strategy: str      # A4 策略
    cached: bool = False  # B6 是否命中缓存
    trace_id: str      # F19 全链路追踪
```

#### 文件 2：两个小增量（语言检测 + 租户化记忆工具）

`core/text_utils.py` 末尾追加（A1，需新增）：

```python
def detect_language(text: str) -> str:
    """语言检测 — 轻量启发式（CJK 字符占比判定）。

    够用且零依赖；多语种场景换 langdetect/fasttext，
    保持签名 detect_language(text) -> "zh" | "en" 不变即可替换。
    """
    if not text:
        return "zh"
    cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
    return "zh" if cjk * 2 >= len(text.replace(" ", "")) else "en"
```

`core/tool/memory_tools_tenant.py`（C10，**已落地**——原全局版已删除，本文件是租户化恢复实现）：

```python
"""租户化的长期记忆工具工厂。

原 core/tool/memory_tools.py（2026-09-01 已从工程删除）的 namespace 写死
("users",) —— 全局共享，A 租户的 agent 可以读写 B 租户的记忆。工厂闭包把
namespace 固化为 (租户, 用户)，LLM 在工具参数里无法指定/越权访问其他命名空间。
"""
from langchain.tools import tool

from core.memory import store  # 单例，与编排层同一实例


def make_memory_tools(tenant_id: str, user_id: str) -> list:
    ns = ("users", tenant_id, user_id)  # 与编排层 _load_memory 同一 namespace

    @tool
    def save_user_info(key: str, info: str) -> str:
        """将用户信息保存到长期记忆（跨会话保留）。

        Args:
            key: 记忆键名，如 name / preferences / tech_stack
            info: 要保存的信息
        """
        store.put(ns, key, {"info": info})
        return f"已记住: {key}"

    @tool
    def get_user_info(key: str) -> str:
        """从长期记忆读取用户信息。"""
        item = store.get(ns, key)
        return str(item.value.get("info", "")) if item else f"未找到关于 {key} 的记忆"

    @tool
    def forget_user_info(key: str) -> str:
        """删除用户要求遗忘的长期记忆。"""
        store.delete(ns, key)
        return f"已遗忘: {key}"

    @tool
    def list_user_memories() -> str:
        """列出当前用户全部长期记忆。"""
        items = store.search(ns)
        lines = [f"- {i.key}: {i.value.get('info', '')}" for i in items]
        return "\n".join(lines) if lines else "暂无任何长期记忆"

    return [save_user_info, get_user_info, forget_user_info, list_user_memories]
```

`config/settings.py` 追加（F18，新参数全进 settings）：

```python
    # ========== Chat v2（新增）==========
    yudao_ai_mcp_url: str = "http://127.0.0.1:48086/mcp"  # module-ai 的 MCP 端点
    chat_cache_ttl: int = 3600        # B6：确定性问答缓存时长（秒）
    chat_eval_sample_rate: float = 0.05  # B7：评估采样率
    chat_eval_file: str = "./data/eval_samples.json"  # B7：采样落盘路径
```

#### 文件 3：`app/services/chat_orchestrator.py`（核心——一个类串起 A1–F22）

```python
"""生产级会话编排服务 — 一个接口的设计模式（对应 4.2 的 12 步管线）。

代码里 ①…⑫ 注释与 4.2 管线一一对应；【A1】…【F22】标注与 4.1 要素表一一对应。
"""
import asyncio
import hashlib
import json
import logging
import time
import uuid

from fastapi import HTTPException
from fastapi.responses import StreamingResponse
from langchain.agents import create_agent
from langchain_core.messages import HumanMessage, SystemMessage

from app.schemas.chat_v2 import ChatMeta, ChatRequestV2
from app.schemas.common import ApiResponse
from config.settings import settings
from core.memory import checkpointer, store            # D12 / D13（单例）
from core.models import deepseek_model                 # 模型单例（E14）
from core.prompts.loader import load_template          # E15（LRU 缓存）
from core.rag.cache import RedisCache                  # B6
from core.rag.classifier import classify_query         # A3
from core.rag.evaluate import EvalSample, save_eval_samples  # B7
from core.rag.retriever import HybridRetriever         # B5
from core.rag.strategy import RetrievalStrategy, select_strategy  # A4
from core.text_utils import detect_language, jieba_extract_keywords  # A1 / A2
from core.tool.api_tools import api_tools              # C11（安全网关工具）
from core.tool.memory_tools_tenant import make_memory_tools  # C10
from core.tool.mcp_tools import get_yudao_ai_tools     # C9（3.3 的加载器）
from core.tool.tools import web_search                 # C8

logger = logging.getLogger("chat.orchestrator")

# 角色 → 允许的 MCP 工具名集合（示意：静态写死只为讲清「注册时过滤」这一层）
# 生产：不能写死，要按租户查 module-ai 的 ai_chat_role.tool_ids（见下方注释）
# 注：query_order 等是代码②的演示工具集，与代码① §3.1 注册的 query_tenant_order 各自独立
ROLE_ALLOWED_TOOLS: dict[str, set[str]] = {
    "admin":     {"db_query", "query_order", "create_order", "cancel_order"},
    "operator":  {"query_order", "cancel_order"},
    "member":    {"query_order"},
}

# 生产按租户查工具（不能写死 ROLE_ALLOWED_TOOLS，文档 12 §4.3 两条路）：
#   路 A：Python 侧跨服务查 module-ai
#     resolve_role_tools(tenant_id, user_token, thread_id) → 调 module-ai 查会话 roleId → 查角色 toolIds
#     需 module-ai 补「查会话角色 / 查角色工具」两个接口 + Python 透传 token
#   路 B：MCP 端点按用户过滤工具（per-user tool filtering）
#     Python 拉到的天然是过滤后的工具，不用自己查角色；需改 MCP 端鉴权（当前 permitAll、不做 per-user filtering）

# B6：Redis 不可用时 RedisCache 内部自动降级为无缓存
_cache = RedisCache(prefix="chat:")

# B5：lifespan 预热（F22），避免首个请求挨初始化
_retriever: HybridRetriever | None = None


def init_orchestrator():
    """启动时调用（main.py 的 lifespan 里）——重资源提前建好。"""
    global _retriever
    try:
        _retriever = HybridRetriever()
        logger.info("混合检索器预热完成")
    except Exception as e:                       # F21：降级，不阻断启动
        logger.warning("混合检索器初始化失败，RAG 策略将降级: %s", e)


class ChatOrchestrator:
    """会话编排（无状态，Depends 注入；重资源全部模块级单例/缓存）。"""

    async def chat(self, req: ChatRequestV2):
        trace_id = uuid.uuid4().hex[:12]                       # F19
        start = time.monotonic()

        # ②【A1】语言检测 +【A2】关键词（jieba，与 BM25 同一分词体系）
        lang = req.lang or detect_language(req.message)
        keywords = jieba_extract_keywords(req.message, top_k=5)

        # ③【A3】意图分类（classifier 内置 BERT→规则回退）
        classification = classify_query(req.message)

        # ④【A4】策略选择（strategy 内置资源可用性降级链）
        strategy, note = select_strategy(classification, req.message)
        logger.info(
            "[%s] 路由: category=%s conf=%.2f strategy=%s(%s) lang=%s kw=%s",
            trace_id, classification.category, classification.confidence,
            strategy.value, note, lang, keywords,
        )

        # ⑤【B6】缓存探测：仅非流式 + 确定性策略（4.2 决策 2）
        cache_key = None
        if not req.stream and strategy in (RetrievalStrategy.DIRECT_LLM,
                                           RetrievalStrategy.GREETING):
            cache_key = "v2:" + hashlib.sha1(
                f"{req.tenant_id}:{req.message}".encode()).hexdigest()
            try:
                hit = _cache.get(cache_key)
            except Exception:
                hit = None
            if hit:
                logger.info("[%s] 命中缓存", trace_id)
                return ApiResponse.success(data={
                    "meta": {"trace_id": trace_id, "cached": True},
                    "answer": hit,
                })

        # ⑥【D13-读】长期记忆加载（namespace 按租户+用户隔离）
        memory_block = self._load_memory(req)

        # ⑦【B5】预检索：仅专业知识类策略（4.2 决策 1）；失败→空上下文继续（F21）
        context = ""
        if strategy in (RetrievalStrategy.HYBRID_SEARCH,
                        RetrievalStrategy.MYSQL_EXACT) and _retriever:
            try:
                query = " ".join(keywords) or req.message   # A2 关键词增强检索
                context = _retriever.search_as_context(query, top_k=req.top_k)
            except Exception as e:
                logger.warning("[%s] 预检索失败，空上下文继续: %s", trace_id, e)

        # ⑧【E15】system prompt 组装：模板体系（loader LRU）+ 动态段
        system_text = self._compose_system(strategy, lang, memory_block, context)

        # ⑨【E14/C8/C9/C10】Agent 装配（每请求组装，贵的 tools/list 已被 3.3 缓存）
        agent = await self._build_agent(req, strategy)

        # ⑩【D12】短期记忆键：租户命名空间（安全关键，见 2.3 缺口 4）
        conv = req.conversation_id or str(uuid.uuid4())
        thread_id = f"{req.tenant_id}:{req.user_id}:{conv}"
        config = {"configurable": {"thread_id": thread_id}}

        meta = ChatMeta(thread_id=thread_id, lang=lang,
                        category=classification.category,
                        confidence=round(classification.confidence, 2),
                        strategy=strategy.value, cached=False, trace_id=trace_id)

        if req.stream:
            return self._stream(agent, req, system_text, meta, start, context)
        return await self._invoke(agent, req, system_text, meta, start,
                                   context, cache_key)

    # ==================== 私有方法 ====================

    @staticmethod
    def _load_memory(req: ChatRequestV2) -> str:
        """⑥ 长期记忆 → 注入段。namespace 与 memory_tools_tenant 一致。"""
        try:
            items = store.search(("users", req.tenant_id, req.user_id), limit=20)
        except Exception:
            return ""                              # F21：记忆不可用→跳过
        if not items:
            return ""
        lines = [f"- {i.key}: {i.value.get('info', '')}" for i in items]
        return "已知用户长期记忆：\n" + "\n".join(lines)

    @staticmethod
    def _compose_system(strategy, lang, memory_block, context) -> str:
        """⑧ 提示词组装：基座模板（E15）+ 上下文 + 记忆 + 语言指令。

        rag.md 模板自带 {context} 占位是给 builders.build_rag_prompt() 的
        ChatPromptTemplate 路线用的；这里走 rag_service 同款"手动拼 system"
        路线（AI应用开发笔记 12.1），两路线二选一，本文取后者便于动态段叠加。
        """
        template = {
            RetrievalStrategy.CODE_REVIEW: "code_review",
            RetrievalStrategy.HYBRID_SEARCH: "rag",
            RetrievalStrategy.MYSQL_EXACT: "rag",
        }.get(strategy, "research")
        parts = [load_template(template)]          # E15：md 模板，LRU 缓存
        if context:
            parts.append(f"参考以下知识库内容：\n{context}")
        if memory_block:
            parts.append(memory_block)
        parts.append("请使用中文回答。" if lang == "zh"
                     else "Please respond in English.")
        return "\n\n".join(parts)

    @staticmethod
    async def _build_agent(req: ChatRequestV2, strategy):
        """⑨ Agent 装配：模型/记忆单例 + 工具按租户/策略组装。

        为什么敢每请求 create_agent：贵的是 tools/list（已被 3.3 的
        租户级缓存挡住），create_agent 只是图定义（1.4 成本事实）。
        """
        tools = [web_search]                       # C8：通用检索兜底
        tools += api_tools                         # C11：微服务安全网关工具
        tools += make_memory_tools(req.tenant_id, req.user_id)  # C10：租户化记忆工具

        if strategy == RetrievalStrategy.CODE_REVIEW:
            from core.tool.tools import code_review
            tools.append(code_review)              # C8：代码审查工具

        try:                                       # C9：MCP 租户工具；失败降级（F21）
            # role_id：透传「AI 聊天角色」给 Java 侧 tools/call 执行级鉴权。
            # 演示用 req.role（member/admin）；生产应传 AiChatRoleDO.id，由 module-ai 查 toolIds 白名单。
            _, mcp_tools = await get_yudao_ai_tools(req.tenant_id, req.token, role_id=req.role, allowed_tools=ROLE_ALLOWED_TOOLS.get(req.role))
            tools += mcp_tools
        except Exception as e:
            logger.warning("MCP 工具不可用，本次会话降级为本地工具: %s", e)

        return create_agent(
            model=deepseek_model,                  # 模型单例（core/models.py）
            tools=tools,
            checkpointer=checkpointer,             # D12：短期记忆单例
            store=store,                           # D13：长期记忆单例
        )

    async def _invoke(self, agent, req, system_text, meta, start,
                      context, cache_key):
        """⑩-非流式：ainvoke + ⑪ 后处理（写缓存、评估采样）。"""
        messages = [SystemMessage(content=system_text),
                    HumanMessage(content=req.message)]
        try:
            result = await agent.ainvoke(messages, config={
                "configurable": {"thread_id": meta.thread_id}})
            answer = result["messages"][-1].content
        except Exception:
            logger.exception("[%s] 会话失败", meta.trace_id)
            raise HTTPException(status_code=500, detail="会话处理失败")  # E17：全局处理器接住

        # ⑪【B6】写缓存（仅命中探测的那类请求）
        if cache_key:
            try:
                _cache.set(cache_key, answer, ttl=settings.chat_cache_ttl)
            except Exception:
                logger.warning("[%s] 缓存写入失败（Redis 降级中）", meta.trace_id)

        # ⑪【B7】评估采样落盘（4.2 决策 5：在线采样、离线评估）
        self._eval_sample_async(req, answer, context)

        logger.info("[%s] 完成 duration=%.0fms",
                    meta.trace_id, (time.monotonic() - start) * 1000)
        return ApiResponse.success(data={"meta": meta.model_dump(), "answer": answer})

    def _stream(self, agent, req, system_text, meta, start, context):
        """⑩-流式：astream_events（E16）+ SSE 事件协议。

        E17 关键：headers 已随 StreamingResponse 发出，生成器内部绝不能 raise，
        只能 yield error 事件后正常收尾（4.2 决策 3）。
        """
        messages = [SystemMessage(content=system_text),
                    HumanMessage(content=req.message)]

        def _sse(payload: dict) -> str:
            return "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"

        async def _gen():
            answer_parts: list[str] = []
            yield _sse({"type": "meta", **meta.model_dump()})
            try:
                async for event in agent.astream_events(
                        messages,
                        config={"configurable": {"thread_id": meta.thread_id}},
                        version="v2"):
                    if event["event"] == "on_chat_model_stream":
                        token = event["data"]["chunk"].content
                        if token:                              # 逐 token 推送
                            answer_parts.append(token)
                            yield _sse({"type": "token", "content": token})
                    elif event["event"] == "on_tool_start":   # 工具调用可视化
                        yield _sse({"type": "tool", "name": event["name"]})
            except Exception:
                logger.exception("[%s] 流式中断", meta.trace_id)
                yield _sse({"type": "error", "message": "会话处理中断，请重试"})
                return

            # ⑪ 后处理（流式路径）：评估采样；流式不写缓存（4.2 决策 2）
            self._eval_sample_async(req, "".join(answer_parts), context)
            yield _sse({"type": "done",
                        "duration_ms": int((time.monotonic() - start) * 1000)})

        return StreamingResponse(
            _gen(), media_type="text/event-stream",
            headers={"Cache-Control": "no-cache",
                     "Connection": "keep-alive",
                     "X-Accel-Buffering": "no"},   # 禁 Nginx 缓冲（笔记 12.1）
        )

    @staticmethod
    def _eval_sample_async(req, answer, context):
        """⑪【B7】按采样率落盘 EvalSample；fire-and-forget，绝不阻塞响应。"""
        import random
        if random.random() > settings.chat_eval_sample_rate:
            return

        async def _task():
            try:
                # ground_truth 离线补标注后用 RagasEvaluator.evaluate_from_file 批量评估
                sample = EvalSample(question=req.message, ground_truth="",
                                    answer=answer,
                                    contexts=[context] if context else [])
                save_eval_samples([sample], settings.chat_eval_file)
            except Exception:
                logger.debug("评估采样落盘跳过")

        asyncio.create_task(_task())
```

#### 文件 4：`app/routers/chat_v2.py` + `app/main.py` 增量

```python
# app/routers/chat_v2.py —— 路由层零业务逻辑（README 的分层约定）
from fastapi import APIRouter, Depends

from app.schemas.chat_v2 import ChatRequestV2
from app.services.chat_orchestrator import ChatOrchestrator

router = APIRouter(prefix="/v2", tags=["chat-v2"])


@router.post("/chat")
async def chat_v2(request: ChatRequestV2, orch: ChatOrchestrator = Depends()):
    """生产级全功能会话入口（要素覆盖对照见 4.4）。"""
    return await orch.chat(request)
```

```python
# app/main.py 的 lifespan 增量（F22：重资源启动时建好）
# 依赖文件顶部已有 import：from contextlib import asynccontextmanager、
#   from fastapi import FastAPI、import logging、from config.settings import settings
from app.services.chat_orchestrator import init_orchestrator

@asynccontextmanager
async def lifespan(app: FastAPI):
    setup_logging()
    setup_langsmith()          # F19：LangSmith 追踪
    init_orchestrator()        # B5：混合检索器预热
    logger = logging.getLogger(__name__)
    logger.info("service starting | model=%s | memory=%s",
                settings.llm_model, settings.memory_type)
    yield
    logger.info("service stopped")

# 路由注册处追加：
# app.include_router(chat_v2.router)
```

### 4.4 要素覆盖对照表（示例代码用到"全部"的证明）

| 要素             | 代码位置（文件 3 为主）                                                         |
| -------------- | --------------------------------------------------------------------- |
| A1 语言检测        | `chat()` ② `detect_language`；`_compose_system` 语言指令                   |
| A2 分词/关键词      | `chat()` ② `jieba_extract_keywords` → ⑦ 检索 query 增强                   |
| A3 意图分类        | `chat()` ③ `classify_query`（规则回退在 classifier 内置）                      |
| A4 策略选择        | `chat()` ④ `select_strategy`（降级链在 strategy 内置）                        |
| B5 混合检索        | `chat()` ⑦ `HybridRetriever.search_as_context`；`init_orchestrator` 预热 |
| B6 缓存          | `chat()` ⑤ 探测 / `_invoke` ⑪ 写入（Redis 降级在 RedisCache 内置）               |
| B7 评估          | `_eval_sample_async`（采样落盘 + 离线 `evaluate_from_file`）                  |
| C8 本地工具        | `_build_agent`：`web_search`、`code_review`                             |
| C9 MCP 租户工具    | `_build_agent`：`get_yudao_ai_tools`（白名单 + 失败降级）                       |
| C10 租户记忆工具     | 文件 2 `make_memory_tools`（namespace 闭包固化）                              |
| C11 凭证安全       | token 只出现在请求体→MCP headers；工具参数/描述中不出现                                 |
| D12 短期记忆       | `chat()` ⑩ `thread_id = 租户:用户:会话` + checkpointer                      |
| D13 长期记忆       | ⑥ `_load_memory` 读 → ⑧ 注入 → C10 工具写（三段闭环）                             |
| E14 Agent 装配时机 | `_build_agent`（每请求组装 + tools/list 缓存的性价比论证）                           |
| E15 提示词工程      | `_compose_system`：`load_template` + 动态段叠加                             |
| E16 流式输出       | `_stream`：`astream_events(v2)` + meta/tool/token/error/done 协议        |
| E17 统一响应/异常    | 非流式 `ApiResponse` + `HTTPException`；流式 error 事件                       |
| F18 配置         | 文件 2 settings 增量（4 个新参数全部进 settings）                                  |
| F19 可观测        | trace_id 贯穿全部日志；耗时统计；lifespan 开 LangSmith                             |
| F20 鉴权         | 复用 `main.py` 的 `verify_api_key` 中间件（不重复实现）                            |
| F21 降级容错       | ⑤⑥⑦⑨⑪ 每步 try/except：检索失败空上下文、MCP 失败本地工具、缓存失败跳过、记忆失败跳过                 |
| F22 生命周期       | `init_orchestrator` 在 lifespan 预热，不在首个请求里做重活                          |

### 4.5 框架内置 vs 自己写（分界不糊）

| 能力       | 框架/项目已有（不用写）                                      | 自己写（本文示例补的）                           |
| -------- | ------------------------------------------------- | ------------------------------------- |
| 短期记忆读写   | LangGraph `checkpointer` 自动存取                     | thread_id 的租户命名空间（安全关键）               |
| 长期记忆读写   | LangGraph `store`；`InjectedStore` 机制              | 租户化 namespace 工具工厂（修复全局 `("users",)`） |
| 意图/策略    | `classifier.py` / `strategy.py` 整套                | 只是调用顺序编排                              |
| 混合检索     | `HybridRetriever.search_as_context`               | 何时预检索的决策（4.2 决策 1）                    |
| 缓存       | `RedisCache`（含降级）                                 | 缓存键设计 + 哪些策略可缓存的决策                    |
| 评估       | `EvalSample`/`save_eval_samples`/`RagasEvaluator` | 采样率 + 异步落盘的接线                         |
| MCP 工具   | `langchain-mcp-adapters` 全包（3.3）                  | 租户头 + role-id 透传 + 白名单 + 缓存失效         |
| SSE 流式   | `astream_events` + `StreamingResponse`            | 事件协议 + 生成器内异常处理（E17）                  |
| 提示词      | `loader`/`builders`/`templates/*.md`              | 动态段（语言/记忆/上下文）的组装规则                   |
| 鉴权/日志/异常 | `main.py` 中间件与三个 exception_handler                | 直接复用，v2 不重复实现                         |
