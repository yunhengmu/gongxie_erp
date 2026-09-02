# 在 module-ai 配置 Tool 并通过 HTTP 版 MCP 供 module-agent 使用（含权限防越权）

> 主线一句话：**工具定义在 module-ai，用 `/mcp`（HTTP 版）暴露；module-agent 用 FastAPI 的 `lifespan` 加载并使用；"谁能用哪个工具"由芋道角色配置决定，Agent 端不持有权限。**

---

## 阶段一：module-ai 定义工具（Java）

在 `yudao-module-ai-server/src/main/java/cn/iocoder/yudao/module/ai/tool/function/` 下新建一个工具。以"查当前时间"为例（**Spring AI 2.0 写法，与真实代码 `DbQueryToolFunction` 一致**）：

```java
package cn.iocoder.yudao.module.ai.tool.function;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component                                        // ① 注册成 Spring Bean（工具名不在这里）
public class CurrentTimeToolFunction {

    @Tool(name = "current_time", description = "查询指定时区的当前时间")  // ② 工具名 + 描述（给 LLM 看）
    public Response getCurrentTime(
            @ToolParam(description = "时区，例如 Asia/Shanghai、UTC")    // ③ 参数描述（给 LLM 看）
            String timezone
    ) {
        String tz = timezone == null || timezone.isBlank()
                ? "Asia/Shanghai" : timezone;                        // ④ 真实业务逻辑
        try {
            LocalDateTime now = LocalDateTime.now(ZoneId.of(tz));
            return new Response(tz, now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (Exception e) {
            return new Response(tz, "无效的时区：" + tz);
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Response {
        private String timezone;
        private String currentTime;
    }
}
```

三个必记要点：

1. **工具名由 `@Tool(name="current_time")` 定义**，不是 `@Component`。`@Component` 只是把类注册成 Spring Bean（括号里是 Bean 名，跟工具名无关）。
2. `@Tool(description=...)` / `@ToolParam(description=...)` 是**写给 LLM 的说明书**，写不清楚模型就不会正确调用。
3. `getCurrentTime()` 方法体是业务逻辑，也是**权限校验的最后一道关卡**（阶段三回来改它）。

---

## 阶段二：module-ai 开启 HTTP 版 MCP Server

改 `application.yaml`（当前 `spring.ai.mcp.server` 是 `enabled: false`）：

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true                    # ① 开启
        name: yudao-mcp-server
        version: 1.0.0
        instructions: 一个 MCP 示例服务
        streamable-http-endpoint: /mcp   # ② HTTP 版端点（关键，删掉原来的 sse-endpoint）
```

三个事实（照文档配必踩的坑）：

| 项        | 值                                     | 说明                          |
| -------- | ------------------------------------- | --------------------------- |
| AI 服务端口  | **48090**（不是 48080）                   | Agent 直连要写 48090            |
| HTTP 版开关 | `streamable-http-endpoint: /mcp`      | 与 SSE 的 `/sse` 是两个不同协议、不同端点 |
| MCP 依赖   | `spring-ai-starter-mcp-server-webmvc` | pom 里已有，**不要用 webflux 版**   |

**如果走网关**（生产环境），`yudao-gateway` 的路由现在只匹配 `/sse, /mcp/message`，漏了 `/mcp`，要补：

```yaml
- id: ai-mcp-server
  uri: grayLb://ai-server
  predicates:
    - Path=/sse, /mcp/message, /mcp   # ← 加 /mcp
```

**开发阶段建议直连 48090，绕过网关**，链路最短最好排查。

改完**必须重启 `AiServerApplication`**，Spring 才会把阶段一的 `current_time` 注册进 MCP Server。

---

## 阶段三：防越权（两层防线 + 一个前提）

越权有四种来源，对应两层防线：

**前提 —— 让 MCP 端点识别用户**：改 `SecurityConfiguration.java`，把 MCP 三个端点从 `permitAll()` 改成 `authenticated()`：

```java
// 改前（匿名，拿不到用户）
registry.requestMatchers(mcpSseEndpoint).permitAll();
registry.requestMatchers(mcpSseMessageEndpoint).permitAll();
registry.requestMatchers(mcpStreamableHttpEndpoint).permitAll();

// 改后（强制带 token）
registry.requestMatchers(mcpSseEndpoint).authenticated();
registry.requestMatchers(mcpSseMessageEndpoint).authenticated();
registry.requestMatchers(mcpStreamableHttpEndpoint).authenticated();
```

> 说明：芋道的 `TokenAuthenticationFilter` 本来就会解析所有请求的 token，`permitAll` 的隐患是"允许不带 token 匿名连"。改成 `authenticated()` 后，无 token 直接 401，`getLoginUserId()` 才稳定拿得到用户。

**第一层 —— Agent 只加载授权工具**（让 LLM 看不到未授权工具）。注意：本文档阶段五只演示「把 MCP 工具加载并挂到 Agent」，**不展开按角色过滤**；完整的「按角色动态查权限 + 按工具指纹缓存 Agent」方案见 **文档 10 步骤⑥**。

**第二层 —— 工具内二次鉴权**（真正的边界，挡 prompt 注入）。回到阶段一的 `getCurrentTime()`：

```java
@Tool(name = "current_time", description = "查询指定时区的当前时间")
public Response getCurrentTime(
        @ToolParam(description = "时区，例如 Asia/Shanghai、UTC") String timezone
) {
    // 权限校验：SecurityFrameworkService 内部从登录上下文拿 userId（带 1 分钟缓存）
    if (!securityFrameworkService.hasAnyPermissions("ai:tool:current-time")) {
        throw new AccessDeniedException("无权限使用该工具");
    }
    // ... 原有业务逻辑 ...
}
```

> `securityFrameworkService` 注入自芋道的 `SecurityFrameworkService`（`yudao-spring-boot-starter-security`）。
> **不要直接调 `permissionApi.hasAnyPermissions(...)`**：它是 Feign 接口，返回的是 `CommonResult<Boolean>` 不是 boolean，
> 而且得自己传 userId；正确做法就是走 `SecurityFrameworkService`，它内部已经 `getLoginUserId()` → 调 `permissionApi` → 缓存 1 分钟。

权限标识 `ai:tool:current-time` 在芋道后台「菜单管理」建按钮权限，角色勾选后才放行。**就算 LLM 被 prompt 注入诱导，这一行也会拦下。**

---

## 阶段四：module-agent 装依赖 + 配置

`pyproject.toml` 的 `dependencies` 加两行：

```toml
"langchain-mcp-adapters>=0.1.0",   # MCP 客户端适配器
"mcp>=1.0.0",                       # MCP 协议 SDK
```

`config/settings.py` 的 `Tools` 段附近加（不硬编码地址）：

```python
# ========== MCP ==========
mcp_enabled: bool = False   # 是否启用 MCP 工具加载
mcp_url: str = ""           # module-ai MCP Server 地址
mcp_token: str = ""         # 通过 MCP 端点 authenticated() 鉴权的 token
```

对应 `.env`：

```bash
MCP_ENABLED=true
MCP_URL=http://localhost:48090/mcp
MCP_TOKEN=xxx               # 阶段三改成 authenticated() 后必填，否则拉工具 401
```

---

## 阶段五：module-agent 规范加载（核心，8 个文件）


### 0. 先理解核心思想（为什么非要这么设计）

> 一句话版本：**把"创建 Agent"这个动作，从"代码被 import 的瞬间"挪到"服务启动完成时"，因为 MCP 工具要先联网异步拉取，拉完才能建 Agent。**

展开讲，这里其实有 5 个点，用你最熟的 Java/Spring 对照就懂：

**① "构建时机"（= 创建这个对象的时间点）是什么？**  
就是 Agent 这个对象在**哪个时间点被 new 出来**。`create_agent(...)` 函数被调用的那一刻，Agent 就诞生了。

**② "模块导入时" ≈ Java 的"类加载时"**  
原来的 `core/agent.py` 里，`research_agent = create_agent(...)` 是写在文件顶层的。Python 的规则是：**`import` 一个模块，顶层的代码会立刻执行一遍**。所以别处只要写 `from core.agent import research_agent`，这行 `create_agent` 就当场跑掉。用 Java 类比，就相当于在**类的静态字段**里 new：

```java
static final Agent RESEARCH_AGENT = new Agent(...);  // 类一加载就 new
```

**③ 卡点在哪？—— MCP 工具是"异步加载"的**  
MCP 工具不是现成的，它要**联网去 module-ai 拉**，这个过程要"等"（`await`）。于是产生死结：

1. import 时想建 Agent，但 Agent 里要放 MCP 工具；
2. MCP 工具得 `await` 才能拿到；
3. 可 import 时**不在事件循环里，`await` 根本用不了**；
4. 结果：Agent 建好了，工具还没拉回来 → **Agent 里没有 MCP 工具，白建**。

用 Spring 类比，就相当于在**静态初始化块**里去调一个需要容器注入完才能用的服务，此刻容器还没 ready，拿到的是 null。

**④ "应用启动生命周期"（lifespan）为什么能解决？**  
FastAPI 的 `lifespan` 分「启动阶段」和「关闭阶段」。**启动阶段跑的时候，事件循环已经就绪，`await` 能正常用**。于是顺序摆正了：

```python
mcp_tools = await mcp_loader.load()          # ① 先 await：把工具拉回来
container.agent_registry = build_agent_registry(mcp_tools)  # ② 再建 Agent
yield                                       # ③ 都好了，才对外接请求
await mcp_loader.close()                    # ④ 关停时释放连接
```

用 Spring 类比：`lifespan` 启动阶段 ≈ `@EventListener(ApplicationReadyEvent.class)`，容器完全 ready 后再做初始化——**把 `new Agent` 从"类加载时"挪到"容器 ready 之后"**。

**⑤ AppContainer 是干嘛的？**  
Agent 建好得有个地方放。原来散落成一个个模块级变量，现在统一放进 `container`（类比 Spring 的 `ApplicationContext`，谁用谁取）。好处：对象有唯一归属、同步场景（WebSocket）也能拿到、启动/关闭顺序清晰。

**总之一句话**：旧写法 = 类加载时就 new Agent，工具还没拉；新写法 = 容器 ready 后先拉工具、再 new Agent、再存容器。

---

下面是具体落地，共 8 个文件：

**① 新建 `core/tool/mcp_tools.py`** —— 加载器保存 client 引用，提供 `close()`：

```python
import logging
from langchain_mcp_adapters.client import MultiServerMCPClient

logger = logging.getLogger(__name__)

class McpToolLoader:
    def __init__(self, url: str, token: str = ""):
        self._url = url
        self._token = token
        self._client = None
        self._tools = []

    @property
    def tools(self) -> list:
        return self._tools

    async def load(self) -> list:
        if not self._url:
            logger.info("未配置 MCP 地址，跳过加载")
            return []
        try:
            server_config = {"url": self._url, "transport": "streamable_http"}
            if self._token:  # MCP 端点已 authenticated()，不带 token 会 401
                server_config["headers"] = {"Authorization": f"Bearer {self._token}"}
            # 把 client 对象保存到 self._client，防止它被垃圾回收
            self._client = MultiServerMCPClient({"yudao-ai": server_config})
            self._tools = await self._client.get_tools()
            logger.info("MCP 工具加载成功: %s", [t.name for t in self._tools])
        except Exception as e:
            logger.warning("MCP 工具加载失败，降级为无 MCP 工具: %s", e)
            self._tools = []
        return self._tools

    async def close(self):
        if self._client is not None:
            await self._client.__aexit__(None, None, None)
            self._client = None
            logger.info("MCP 连接已关闭")
```

> 关键点：**不能用 `async with`**（代码块退出时会把连接关掉，工具就没法用了）；`get_tools()` 返回的工具背后绑着这个 client，所以 client 对象必须一直保存在 `self._client` 里，交给 `close()` 统一关掉。
>
> ⚠️ **token 从哪来**：阶段三把 MCP 端点改成了 `authenticated()`，所以这里拉取工具也必须带 token（否则 401）。这个 token 需要能通过 module-ai 的 `TokenAuthenticationFilter`（即一个有效的登录 access_token）。生产里「启动时拉取工具」和「按用户鉴权」如何协调（是用服务级凭证拉工具目录、再在工具执行时按用户做二次鉴权，还是别的方案），是另一个要专门设计的问题——本文档先按「配置一个能通过鉴权的 token」落地。

**② 新建 `core/container.py`** —— 全局唯一容器：

```python
from typing import TYPE_CHECKING
if TYPE_CHECKING:
    from core.agent import AgentRegistry
    from core.tool.mcp_tools import McpToolLoader

class AppContainer:
    agent_registry = None
    mcp_loader = None

container = AppContainer()
```

**③ 改造 `core/agent.py`** —— 从"模块级单例"改成"工厂函数 + 注册表"：

```python
from dataclasses import dataclass
from langchain.agents import create_agent
from core.models import deepseek_model
from core.tool.tools import web_search, code_review, make_retriever_tool
from core.tool.memory_tools import memory_tools
from core.tool.api_tools import api_tools
from core.prompts import RESEARCH_PROMPT, CODE_REVIEW_PROMPT, RAG_PROMPT
from core.memory import checkpointer, store

@dataclass
class AgentRegistry:
    research_agent: object
    code_reviewer_agent: object
    rag_agent: object

def build_agent_registry(mcp_tools: list | None = None) -> AgentRegistry:
    mcp_tools = mcp_tools or []
    base_tools = [web_search] + memory_tools + api_tools
    kb_tool = make_retriever_tool()

    research_agent = create_agent(
        model=deepseek_model, tools=base_tools + mcp_tools,
        system_prompt=RESEARCH_PROMPT, checkpointer=checkpointer, store=store)
    code_reviewer_agent = create_agent(
        model=deepseek_model, tools=[code_review] + memory_tools + mcp_tools,
        system_prompt=CODE_REVIEW_PROMPT, checkpointer=checkpointer, store=store)

    rag_tools = base_tools + mcp_tools
    if kb_tool:
        rag_tools = [kb_tool] + rag_tools
    rag_agent = create_agent(
        model=deepseek_model, tools=rag_tools,
        system_prompt=RAG_PROMPT, checkpointer=checkpointer, store=store)

    return AgentRegistry(research_agent, code_reviewer_agent, rag_agent)
```

> 说明：`build_agent_registry` 是工厂函数，`AgentRegistry` 是注册表（一个 `@dataclass`，用来装三个 Agent）。
> ⚠️ 这里把 `mcp_tools` **全量挂到三个 Agent 上，不区分角色**——本阶段只解决「加载」问题。真正的「按角色只挂授权工具」见文档 10 步骤⑥（`resolve_tools_for_role` + `get_agent_for_tools`）。

**④ 改造 `app/main.py` 的 `lifespan`** —— 启动时加载、关闭时释放：

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    setup_logging(); setup_langsmith()
    logger = logging.getLogger(__name__)

    # ===== 启动阶段（类比 Spring 的 @PostConstruct）=====
    from core.container import container
    from core.tool.mcp_tools import McpToolLoader
    from core.agent import build_agent_registry

    mcp_loader = McpToolLoader(settings.mcp_url, settings.mcp_token)
    mcp_tools = await mcp_loader.load()          # ① 异步加载（必须在事件循环内）
    container.mcp_loader = mcp_loader            # ② 保存到容器
    container.agent_registry = build_agent_registry(mcp_tools)  # ③ 构建 Agent

    logger.info("service started | model=%s | mcp_tools=%d",
                settings.llm_model, len(mcp_tools))
    yield

    # ===== 关闭阶段（类比 Spring 的 @PreDestroy）=====
    await mcp_loader.close()
    logger.info("service stopped")
```

**⑤ 改造 `app/dependencies.py`** —— 从容器取对象：

```python
from fastapi import Depends
from core.container import container
from core.agent import AgentRegistry
from app.services.agent_service import AgentService
from app.services.qa_service import QAService
from app.services.rag_service import RAGService

def _registry() -> AgentRegistry:
    if container.agent_registry is None:
        raise RuntimeError("Agent 注册表未初始化，请检查 lifespan")
    return container.agent_registry

def get_research_agent():       return _registry().research_agent
def get_code_reviewer_agent():  return _registry().code_reviewer_agent
def get_rag_agent():            return _registry().rag_agent

def get_agent_service() -> AgentService:
    return AgentService(registry=_registry())
def get_qa_service(agent=Depends(get_research_agent)) -> QAService:
    return QAService(agent=agent, thread_id_prefix="qa")
def get_rag_service(agent=Depends(get_rag_agent)) -> RAGService:
    return RAGService(agent=agent)
def get_rag_service_sync() -> RAGService:
    return RAGService(agent=_registry().rag_agent)
```

**⑥ 改造 `app/services/agent_service.py`** —— 去掉顶部三个全局 import，改构造传入：

```python
from core.agent import AgentRegistry   # 只 import 类型

class AgentService:
    def __init__(self, registry: AgentRegistry):
        self._registry = registry

    def select_agent(self, message: str):
        # 分类逻辑不变，只把三个全局单例换成 self._registry.xxx
        if strategy == RetrievalStrategy.HYBRID_SEARCH:
            return self._registry.rag_agent, strategy.value
        if strategy == RetrievalStrategy.CODE_REVIEW:
            return self._registry.code_reviewer_agent, strategy.value
        return self._registry.research_agent, strategy.value
```

**⑦ 改造 `app/routers/chat.py`** —— 指定 Agent 也用依赖注入：

```python
from app.dependencies import get_agent_service, get_research_agent, get_code_reviewer_agent

@router.post("/research", response_model=ChatResponse)
async def research(request: ChatRequest,
                   svc: AgentService = Depends(get_agent_service),
                   research_agent = Depends(get_research_agent)):
    ...

@router.post("/review", response_model=ChatResponse)
async def review(request: ChatRequest,
                 svc: AgentService = Depends(get_agent_service),
                 code_reviewer_agent = Depends(get_code_reviewer_agent)):
    ...
```

`/chat`、`/chat/stream` 里的 `Depends()` 也统一改成 `Depends(get_agent_service)`。

**⑧ `app/services/upload_file_service.py`** 里也 import 了三个 Agent，同样改成从 `container` 取（和 ⑦ 同一类处理）。

---

## 阶段六：验证（按顺序，别跳）

**① 验证 module-ai 端点活着**（重启 AI 服务后）：

```bash
curl -X POST http://localhost:48090/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <你的 token>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
```

返回 `serverInfo.name == yudao-mcp-server` 即通。

> ⚠️ 阶段三已把 MCP 端点改成 `authenticated()`，所以验证时**必须带 token**（`Authorization: Bearer ...`），否则 401。
> 想先验证「端点本身通不通」，可临时把阶段三回退成 `permitAll()`，验完再改回。

**② 验证 Agent 拉到工具**（在 `module-agent` 目录跑）：

```python
import asyncio
from core.tool.mcp_tools import McpToolLoader
tools = asyncio.run(McpToolLoader("http://localhost:48090/mcp", "<你的 token>").load())
print([t.name for t in tools])   # 应能看到 current_time
```

**③ 端到端**：启动 Agent 服务，对话问"现在几点了"，LLM 调 `current_time` 返回结果即通。

---

## 自查清单

- [ ] `@Tool(name="current_time")` 工具名一致
- [ ] `enabled: true` + `streamable-http-endpoint: /mcp`
- [ ] MCP 三端点 `authenticated()`，`getCurrentTime()` 内用 `securityFrameworkService.hasAnyPermissions(...)`
- [ ] 芋道后台角色勾选了该工具（`ai_tool` 表 + `ai_chat_role.tool_ids`）
- [ ] `pyproject.toml` 装了 `langchain-mcp-adapters` + `mcp`
- [ ] `transport` 写 `streamable_http`，`url` 端口是 **48090**
- [ ] `mcp_token` 已配置（`authenticated()` 后拉工具不带 token 会 401）
- [ ] lifespan 先 `load()` 再 `build_agent_registry()`，`close()` 在关闭阶段
- [ ] 所有 Agent 引用都走 `container` / 依赖注入，无残留模块级单例
