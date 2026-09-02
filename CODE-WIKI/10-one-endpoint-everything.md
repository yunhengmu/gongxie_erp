# 一个接口串起全部功能：AI 顾问对话接口，从第一行到最后一行

> 前面几篇是把功能拆成「9 个问题 / 10 个文件」来讲的，所以你感觉碎。
> 这一篇反过来：**只讲一个接口**，一个 `POST /v1/advisor/chat`，从 HTTP 请求进来，
> 到 SSE 流式出去，中间经过的每一个环节都标清楚「这是哪个功能点」「是框架内置还是你自己写」。
> 看完你会发现：之前那 9 个问题，其实是**同一个请求身上的 9 个侧面**。

---

## 〇、先立一个心智模型：一个请求 = 一层层往下走

一个接口不是孤立的一个函数，它是「一根竹签」，把下面这些层串起来：

```
HTTP 请求（带 token + tenant_id + message + thread_id）
   │
   ├─[横切层] 中间件：鉴权、请求日志、异常兜底        ← 不管调哪个接口都先过这里
   │
   ├─[第1层] 路由 Controller：收参数、做校验、调 Service
   │
   ├─[第2层] 编排 Service：会话管理、RBAC 过滤工具、选 Agent
   │
   ├─[第3层] Agent：LangGraph 图，推理 + 决定调不调工具
   │
   ├─[第4层] 工具 Tool：真去调微服务（凭证在内部注入）
   │
   ├─[第5层] 记忆：checkpointer（短时）+ store（长时）
   │
   └─[横切层] trace：LangSmith 全程记录
```

**关键认知**：接口函数本身可能只有 20 行，但它的「能力」是这几层合力给的。
你要学的不是「写 20 行」，而是「这 20 行如何调动下面 5 层 + 2 个横切层」。

---

## 一、先看完整接口（建立整体感，先别逐行懂）

下面是这个接口**入口 + 编排**两段核心代码。先通读一遍有个印象，后面逐行拆。

### 1.1 路由层 `app/routers/advisor.py`

```python
from fastapi import APIRouter, Depends, Header
from fastapi.responses import StreamingResponse

from app.schemas.advisor import AdvisorRequest
from app.services.advisor_service import AdvisorService

router = APIRouter(prefix="/v1", tags=["advisor"])


@router.post("/advisor/chat")
async def advisor_chat(
    req: AdvisorRequest,
    svc: AdvisorService = Depends(),          # ① 依赖注入（类比 @Autowired）
    x_tenant_id: str = Header(default=""),    # ② 多租户：从 header 透传，不校验
    authorization: str = Header(default=""),  # ③ 身份：token，用于解析用户
):
    # ④ 会话校验：thread_id 要么空（新建）要么已存在
    # ⑤ 身份解析：从 token 拿到 user_id + role
    # ⑥ 下面两条路径：流式 / 非流式，共用同一套编排
    if req.stream:
        return await svc.stream(req, x_tenant_id, authorization)
    return await svc.invoke(req, x_tenant_id, authorization)
```

### 1.2 编排层 `app/services/advisor_service.py`

```python
import uuid, json, logging
from fastapi.responses import StreamingResponse
from langchain_core.messages import HumanMessage

logger = logging.getLogger(__name__)


class AdvisorService:
    async def invoke(self, req, tenant_id: str, authorization: str):
        """非流式：ainvoke 一次拿到完整回复。"""
        ctx = await self._build_context(req, tenant_id, authorization)  # ⑤⑥⑦⑧
        result = await ctx["agent"].ainvoke(
            {"messages": [HumanMessage(content=req.message)]},
            config=ctx["config"],
        )
        return {
            "thread_id": ctx["thread_id"],
            "answer": result["messages"][-1].content,
        }

    async def stream(self, req, tenant_id: str, authorization: str):
        """流式：astream_events 拿 token 级流，转 SSE。"""
        ctx = await self._build_context(req, tenant_id, authorization)

        async def gen():
            # 先告诉前端 thread_id（新会话时前端拿它续聊）
            yield f"data: {json.dumps({'type': 'meta', 'thread_id': ctx['thread_id']})}\n\n"
            async for event in ctx["agent"].astream_events(
                {"messages": [HumanMessage(content=req.message)]},
                config=ctx["config"],
                version="v2",
            ):
                if event.get("event") == "on_chat_model_stream":
                    chunk = event["data"]["chunk"]
                    if chunk.content:
                        yield f"data: {json.dumps({'type': 'token', 'content': chunk.content}, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"

        return StreamingResponse(
            gen(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    async def _build_context(self, req, tenant_id: str, authorization: str) -> dict:
        """接口的核心大脑：把散落的输入，组装成 Agent 需要的三样东西。
        返回 {agent, config, thread_id}，三个字段分别对应功能 ⑦⑧④。"""
        # ④ 会话管理：thread_id 空则新建（uuid）
        thread_id = req.thread_id or str(uuid.uuid4())

        # ⑤ 身份解析：token → (user_id, role)
        user_id, role = self._resolve_identity(authorization)

        # ⑥ RBAC 第一层：动态查权限 → 按工具指纹取（复用/新建）Agent
        tool_names = await resolve_tools_for_role(role)
        agent = get_agent_for_tools(tool_names)

        # ⑧ 记忆的钥匙：thread_id 塞进 config，checkpointer 靠它恢复/隔离/续传
        config = {"configurable": {"thread_id": thread_id, "tenant_id": tenant_id, "user_id": user_id}}

        return {"agent": agent, "config": config, "thread_id": thread_id}

    def _resolve_identity(self, authorization: str) -> tuple[str, str]:
        """⑤ 从 token 解析身份。
        生产：把 token 交给 module-system / 网关去解，拿回 user_id + role。
        这里给个 stub 示意，真实实现不落在这。"""
        # TODO(生产): 调用芋道鉴权，或解 JWT 拿 user_id、查角色
        return ("user_123", "customer")
```

---

## 二、逐行拆解：每一行对应哪个功能点

下面按「一个请求进入接口后，实际执行的顺序」走一遍。每一步标三样东西：

- **功能点**：它解决之前 9 个问题里的哪一个
- **谁提供**：`框架内置` / `真实代码已有` / `你自己写`
- **真实锚点**：module-agent 里对应的真实代码位置

### 步骤 ① — 依赖注入（`Depends()`）

```python
svc: AdvisorService = Depends()
```

- **功能点**：对象装配（不属于那 9 个问题，是工程基本功）
- **谁提供**：FastAPI 内置
- **真实锚点**：`app/routers/chat.py` 第 29 行 `svc: AgentService = Depends()`，注释里明写「← @Autowired」

类比：Spring 里 `@RestController` 的方法参数上写 `@Autowired AgentService`，框架自动 new 好传进来。
FastAPI 里用 `Depends()` 表达同一件事。你**不需要**自己 `AdvisorService()` 手动 new。

### 步骤 ② — 多租户透传（`x_tenant_id: Header`）

```python
x_tenant_id: str = Header(default="")
```

- **功能点**：多租户（问题 9）
- **谁提供**：`Header()` 是 FastAPI 内置；但「拿过来之后干什么」是你要想清楚的
- **真实锚点**：现有代码里**没有**这个 header——这是你要补的缺口，但只补「透传」这一半

**这段要划重点（前面几次反复纠正过）**：

Python 这一侧对 `tenant_id` **只做一件事——透传**，把它从 header 接住、塞进 `config`、传给工具层，
最终由 `api_tools.py` 里的 `_request()` 把它放到请求头里转发给 Java 微服务。

**Python 不做租户校验、不做数据隔离**。真正的隔离在 Java 侧三层：

| 层 | Java 真实类 | 干什么 |
|---|---|---|
| 身份 | `TokenAuthenticationFilter` | 从 token 认出「这是哪个租户的人」 |
| 越权 | `TenantSecurityWebFilter` | 校验「你访问的数据是不是你租户的」 |
| 数据 | `TenantDatabaseInterceptor` | MyBatis 拼 SQL 时自动带 `tenant_id` 条件 |

所以这一行的正确心态是：**Python 是快递员，不是安检员**。它把 `tenant_id` 原样搬运，安检在 Java 那里。

### 步骤 ③ — 身份 token（`authorization: Header`）

```python
authorization: str = Header(default="")
```

- **功能点**：鉴权 + RBAC 的入口（问题 7 的前置）
- **谁提供**：`Header()` 内置；「解析 token 拿身份」你要接芋道
- **真实锚点**：`app/main.py` 第 42–57 行 `verify_api_key` 中间件里读 `Authorization` header，但那是**服务间 API Key**，不是**用户身份 token**。两者别混：

| 概念 | 谁用 | 目的 | 真实代码 |
|---|---|---|---|
| API Key | 前端/网关 ↔ agent 服务 | 证明「这个调用方有资格调 agent」 | `verify_api_key` |
| 用户 token | 用户 ↔ 系统 | 证明「我是谁、什么角色、哪个租户」 | 由芋道网关下发，Python 这里接住 |

### 步骤 ④ — 会话管理（thread_id 生成）

```python
thread_id = req.thread_id or str(uuid.uuid4())
```

- **功能点**：会话建/续（问题 1、4、6）
- **谁提供**：这一行你自己写（`uuid` 是标准库）
- **真实锚点**：`app/services/agent_service.py` 第 31 行 `tid = thread_id or str(uuid.uuid4())`，一模一样

**这个 `thread_id` 是整条记忆链路的钥匙**，后面第 ⑧ 步会把它塞进 `config`。
- 前端第一次来，`thread_id` 空 → 你发一个新 uuid，回给前端 → 前端下次带同一个 uuid 来
- 前端带 uuid 来 → 你直接用它 → checkpointer 靠它找到历史

### 步骤 ⑤ — 身份解析（token → user_id + role）

```python
user_id, role = self._resolve_identity(authorization)
```

- **功能点**：RBAC 的依据（问题 7 的前置）
- **谁提供**：**你自己写 + 对接芋道**，这是真实代码里没有的缺口
- **真实锚点**：无。现有 `agent_service.py` 的 `select_agent()` 是按「问题类型（BERT 分类）」路由，不是按「角色」路由

这是你要补的关键一步。生产里的正确做法：

```
token → 交给芋道鉴权中心 / 解 JWT → 得到 user_id + 会话的 AI 聊天角色 id（conversation.roleId）
```

> ⚠️ **这里的「角色」是「AI 聊天角色」，不是 RBAC 权限角色**（本轮核对 `AiChatRoleDO` 源码后修正）。芋道有两套「角色」，别混：
> - **AI 聊天角色（`AiChatRoleDO`）**：会话绑定的「助手角色」，字段是 `systemMessage`/`modelId`/`knowledgeIds`/`toolIds`，决定「这个助手用哪些工具」——**这才是选工具的依据**；
> - **用户权限角色（RBAC）**：通过 `hasAnyPermissions` 校验权限点，是工具执行时的**第二层鉴权**（见文档 07），不是选工具的依据。
>
> 所以下一步 ⑥ 的 `role` 实际是「会话的 AI 聊天角色 id」，它决定挂哪些工具。

### 步骤 ⑥ — RBAC 第一层：按角色过滤工具

```python
tool_names = await resolve_tools_for_role(role)   # ① 动态查权限（灵活）
agent = get_agent_for_tools(tool_names)            # ② 按工具指纹复用/新建 Agent（高效）
```

- **功能点**：分角色不同工具（问题 7 的第一层）
- **谁提供**：**你自己写**（框架给了 `create_agent(tools=...)`，但「按 role 选哪些 tools」是你写）
- **真实锚点**：`core/agent.py` 里 `create_agent(tools=..., checkpointer=..., store=...)` 是真实写法，但它现在写死三种 Agent（research/code_review/rag），**没有按 role 动态选工具**

**先澄清一个关键点，否则会掉进和 `@lru_cache` 一样的坑**：「按角色选 Agent」和「什么时候构建 Agent」是**两个正交维度**，别绑在一起：

- 维度 A（业务）：一个角色能挂哪些工具 → 决定「该角色用哪个 Agent」
- 维度 B（工程）：这些 Agent 什么时候构建

我上一版错在两点：一是用 `@lru_cache` 用到才建（维度 B 错）；二是把 `ROLE_TOOLS` 写死成硬编码 dict（维度 A 不灵活，你这次质疑的正是这个）。正确解是把两件事拆成两个函数：

**① 权限映射（数据，要灵活）—— 每次请求动态查：**

```python
async def resolve_tools_for_role(role: str) -> set[str]:
    """按「AI 聊天角色 id」查它绑定的工具（生产：查芋道 AiChatRoleDO.toolIds）。

    这是「灵活」的来源：后台改了角色的工具，下一次请求就生效，
    不用重启、不用预建、不用改代码。
    """
    role_info = await fetch_role_tools(role)   # 生产：查 ai_chat_role 表（角色绑定的工具）
    return set(role_info.tool_ids)
```

**② Agent 对象（要复用，别阻塞）—— 按「工具集合指纹」缓存：**

```python
# agent_factory.py
from langchain.agents import create_agent
from core.memory import checkpointer, store
from core.tool.tools import ALL_TOOLS          # 所有工具的全集

_agent_cache: dict[frozenset, object] = {}     # key = 工具白名单的指纹（frozenset 可哈希）

def get_agent_for_tools(tool_names: set[str]):
    """按「工具集合」取 Agent：相同集合复用同一实例，集合变了自动新建。

    为什么 key 用工具指纹而不是角色名？
    → Agent 行为只取决于「挂了哪些工具 + system_prompt」，与角色名叫什么无关。
      两个角色若白名单相同（operator 和 supervisor 都能查单+取消单），
      完全可以共享同一个 Agent 实例，省内存、省构建。
    """
    key = frozenset(tool_names)
    agent = _agent_cache.get(key)
    if agent is None:
        agent = create_agent(
            model=deepseek_model,
            tools=[ALL_TOOLS[n] for n in key if n in ALL_TOOLS],  # ← 只挂白名单里的工具
            checkpointer=checkpointer,    # 短时记忆
            store=store,                  # 长时记忆
            system_prompt="你是 AI 业务顾问...",
        )
        _agent_cache[key] = agent
    return agent
```

**这方案为什么比「每次请求都 new」和「写死预建」都强**：

| 诉求 | 谁满足 |
|---|---|
| 权限改了立刻生效、角色动态增删 | `resolve_tools_for_role` 动态查（数据层） |
| 相同权限共享实例、不重复构建 | `get_agent_for_tools` 按指纹缓存（对象层） |
| 请求不阻塞、无首次延迟 | 只有「新工具组合」首次出现才建一次；常用组合也可在服务启动时先建好 |

**它和 `@lru_cache` 的本质区别**（否则你会问「这不还是用到才建吗」）：

- `lru_cache` 把「查权限」和「建 Agent」**耦合在一个函数里**：一旦查权限要 `await`，整个函数变 async，lru_cache 缓存 coroutine 就坏。新方案把 async 的「查权限」和同步的「建 Agent」拆成两个函数，各管各的。
- `lru_cache` 的 key 是「函数参数（role）」，永远命中、永不失效，**无法表达「权限变了要换 Agent」**。新方案 key 是「工具指纹」，权限一变 → key 变 → 自动新建新 Agent，旧的留在缓存里但不再被引用。

**「换账号」场景走一遍（回答你「换账号会不会用不了」）**：

先记住一句话：**这个方案不是「启动时写死所有角色」，而是「用到才建、建了就缓存」。** Agent 的数量 = 「工具组合」的种类数，不是「用户数」、更不是「启动时预知的角色数」——工具全集是有限的，白名单只是它的子集，实际出现的组合就那么几种。

| 请求 | `resolve` 动态查到的工具 | 缓存里发生了什么 | 结果 |
|---|---|---|---|
| 张三（customer）第 1 次 | `{query_order}` | 空 → 新建 agent_A | 用 agent_A |
| 张三 第 2 次（续聊） | `{query_order}` | 命中 agent_A | 复用（thread_id 恢复历史） |
| 李四（admin）第 1 次 | `{query_order, create_order, cancel_order, check_inventory}` | 未命中 → 新建 agent_B | 用 agent_B |
| 王五（vip，后台刚新配的角色）第 1 次 | `{query_order, check_inventory}` | 未命中 → 新建 agent_C | 用 agent_C |
| 王五 权限被后台改成只剩查单 | `{query_order}` | 命中 agent_A | 复用 agent_A（组合一样） |

所以「换一个账号」的三种情况，全都 handle 得住：

- 新账号的角色是**已知角色**（customer/admin）→ 缓存里早就有 → 直接查表用。
- 新账号的角色是**全新角色**（vip）→ 缓存里没有 → 现场 `create_agent` 建一个 → 照样能用，不是「用不了」。
- 已有角色的**权限被改** → 查到的新工具组合和旧的不同 → 指纹变了 → 自动换一个（或新建）Agent。

你担心的「它已经在前面加载过了，换账号就用不了」，**只发生在我第一版那个「启动时硬编码 `ROLE_TOOLS` 预建」的错方案上**——那个才是写死，新角色没在 dict 里就真的挂了。当前方案不是它。

**至于你觉得「动态新建更清楚」，其实我们俩的差别只有一步**：

| | 查权限（你要的「刷新」） | 建 Agent 对象 |
|---|---|---|
| 你理解的动态新建（方案二） | 每次请求查数据库 | **每次都 `create_agent`** |
| 当前方案（方案三） | 每次请求查数据库 | **没有才 `create_agent`，有就复用** |

看清楚：**你要的「每次刷新」是「查权限」这一步，它在我们两个方案里都是「每次请求都查」的，一点没省。** 唯一区别是「建 Agent 对象」——方案二每次都建，方案三缓存复用。而「每次都建」是没意义的：同一套工具建出来的 Agent 一模一样，建一次就够，省下的是每次都白白 `create_agent` 的 CPU 和可能的阻塞。

**关于记忆隔离的关键澄清**：`checkpointer` 和 `store` 是**全局共享的**（`core/memory.py` 里是模块级单例），所有 Agent 实例共用它们。记忆隔离靠的是 `config` 里的 `thread_id`（+ `tenant_id` + `user_id`），**和「有几个 Agent 实例」毫无关系**。所以按工具指纹复用 Agent 不会串记忆——隔离是 checkpointer 按 `thread_id` 分桶做的，不是靠「每个角色一个实例」做的。

**为什么叫「第一层」**：这一层只是让 LLM **看不到**无权工具的名字（LLM 只会调用它「看得到」的工具）。
但 LLM 是概率模型，可能「编造」一个不在列表里的工具名，也可能用合法工具去干越权的事。
所以**真正的边界在第二层**——工具方法内部二次校验（见步骤 ⑨）。

> ⚠️ 分界提醒：`resolve_tools_for_role` 里的「查芋道拿 tool_ids」是你要补的（接
> `AiChatRoleDO` 的 `tool_ids` / `mcp_client_names` 字段）。`get_agent_for_tools` 的
> `_agent_cache` 是框架无关、你自己写的小工具；`create_agent` + `checkpointer/store` 是框架内置。

### 步骤 ⑦ — 装配 config（记忆钥匙 + 租户/用户上下文）

```python
config = {"configurable": {"thread_id": thread_id, "tenant_id": tenant_id, "user_id": user_id}}
```

- **功能点**：记忆钥匙 + 租户/用户上下文（问题 2、4、6、9）
- **谁提供**：`config` 这个 dict 结构是 LangGraph 的约定（`configurable` 键），**框架内置**；里面塞什么字段是你决定
- **真实锚点**：`app/services/agent_service.py` 第 32 行 `config = {"configurable": {"thread_id": tid}}`，真实代码只塞了 `thread_id`。我在这里**多塞了 `tenant_id` 和 `user_id`**——这是你要补的：让工具层能拿到租户/用户，转给 Java 侧做隔离。

`config` 是 LangGraph 的「上下文口袋」，`ainvoke/astream_events` 都会带着它，工具方法里能用 `RunnableConfig` 把它取出来（步骤 ⑨ 会用到）。

### 步骤 ⑧ — 记忆恢复（checkpointer + store）

- **功能点**：短时记忆恢复 + 隔离 + 断点续传（问题 2、4、6）；长时记忆（问题 2 的另一半）
- **谁提供**：**框架内置**，你只需在 `create_agent` 时把 `checkpointer` 和 `store` 传进去
- **真实锚点**：`core/memory.py`（短时 `InMemorySaver`/`AsyncPostgresSaver`，长时 `InMemoryStore`/`AsyncPostgresStore`）

你在接口代码里**看不到**任何「读历史、存历史」的语句——因为这件事被 `checkpointer` 悄悄做掉了：

```
agent.ainvoke({"messages": [新消息]}, config={"configurable": {"thread_id": "abc"}})
   │
   ├─ checkpointer 看到 thread_id="abc"
   ├─ 自动从存储里取出该 thread 之前的所有消息，拼在「新消息」前面
   ├─ 推理完，自动把「新消息 + 回复」写回存储
   └─ 你什么都不用做
```

**短时 vs 长时**（别混）：

| | 短时记忆 | 长时记忆 |
|---|---|---|
| 载体 | `checkpointer` | `store` |
| 范围 | 一个 `thread_id` 内 | 跨 `thread_id`（跨会话） |
| 典型内容 | 对话历史 | 「用户偏好、技术栈、姓名」等 |
| 谁读写 | 框架自动 | 通过工具（`memory_tools.py` 的 `save_user_info` 等） |
| 真实代码 | `core/memory.py` 第 10–17 行 | `core/memory.py` 第 21–33 行 |

### 步骤 ⑨ — 工具调用（LLM 选工具 → 内部注入凭证调微服务）

- **功能点**：业务能力（问题 7 第二层）+ 凭证安全（问题 9 的搬运）
- **谁提供**：工具定义你写；「LLM 自动决定调哪个工具」是框架内置（create_agent 的能力）
- **真实锚点**：`core/tool/api_tools.py` 的 `_request()` + 各 `@tool` 函数

当一个工具被调用，真实链路是这样的（以「查订单」为例）：

```python
# core/tool/api_tools.py 的真实逻辑（我按原意重排）
@tool
async def query_order(order_id: str) -> str:
    result = await _request("GET", f"{ORDER_SERVICE_URL}/api/orders/{order_id}")
    return f"订单号: {result['order_id']} ..."

async def _request(method, url, **kwargs):
    headers = {"Authorization": f"Bearer {API_TOKEN}", ...}  # ← 凭证在这里注入
    ...
```

**两层设计，这是权限的命脉**：

1. **LLM 看不到凭证**：`ORDER_SERVICE_URL`、`API_TOKEN` 都在工具函数内部写死，LLM 只能传一个 `order_id` 字符串进来。LLM 看到的是「工具名叫 query_order，参数是 order_id」，看不到 URL 和 token。
2. **二次鉴权（问题 7 第二层）**：工具内部、更准确说是 **Java 微服务侧**，还要再做一次「这个 user 有没有权查这个 order」。这才是真正的边界。Python 的 `_request` 会把 `user_id`/`tenant_id` 一起转发过去（这就需要步骤 ⑦ 里塞进 config 的那两个字段）。

> 分界：`_request` 内部注入 `API_TOKEN` 是**真实代码已有**（`api_tools.py` 第 51–54 行）。
> 但「把 user_id/tenant_id 也转发给 Java」是**你要补的**——现有 `_request` 只带了 `API_TOKEN`。

### 步骤 ⑩ — 流式输出（astream_events → SSE）

- **功能点**：流式返回（问题 5）
- **谁提供**：`astream_events(version="v2")` 是框架内置；「过滤 `on_chat_model_stream` 事件 + 拼 SSE」是你写
- **真实锚点**：`app/services/agent_service.py` 第 46–56 行，几乎逐字对应

流式的本质：Agent 不是「憋出一整段再吐给你」，而是模型每生成几个字，就产生一个
`on_chat_model_stream` 事件。你只需：

```python
async for event in agent.astream_events(..., version="v2"):
    if event.get("event") == "on_chat_model_stream":
        chunk = event["data"]["chunk"]
        if chunk.content:
            yield f"data: {json.dumps({'type': 'token', 'content': chunk.content})}\n\n"
```

`yield` 出去的每一帧，前端（EventSource）就立刻渲染一个字——这就是「打字机效果」。

---

## 三、两个横切层（接口自己不用写，但请求会经过）

这两个层不在接口函数里，但它们**包着**接口，每个请求都会穿过。

### 横切层 A — 鉴权 + 日志中间件

- **功能点**：接口级鉴权 + 可观测（不属于 9 问题，是工程必需）
- **谁提供**：**真实代码已有**，你直接复用
- **真实锚点**：`app/main.py`
  - 第 42–57 行 `verify_api_key`：服务间 API Key 校验
  - 第 63–93 行 `request_logging_middleware`：记录每个接口的 `→ 请求` 和 `← 响应`（含耗时）

它们的执行顺序在 FastAPI 里是**洋葱模型**：请求进来先过中间件 → 进你的接口 → 出来再过中间件。
所以你接口里 `logger` 打的日志，和中间件打的日志，合起来就是一条完整的调用链。

### 横切层 B — 统一异常 + trace

- **功能点**：异常兜底（工程必需）+ 全链路 trace（问题 8）
- **谁提供**：**真实代码已有**
- **真实锚点**：
  - `app/main.py` 第 136–179 行三个 `@app.exception_handler`（HTTPException / 参数校验 / 兜底 Exception），统一包装成 `ApiResponse`（`app/schemas/common.py` 里 `code+message+data` 结构，类比若依的 `AjaxResult`）
  - `app/main.py` 第 34–39 行 `setup_langsmith()`：配了 key 就开 LangSmith 追踪

**这意味着**：你的接口函数里**根本不用写 try/except**（除了确实要特殊处理的），任何异常都会掉进全局兜底，返回统一格式。这对应真实代码 `chat.py` 里虽然写了 `try/except`，但最终是包成 `HTTPException` 交给全局处理器。

---

## 四、一张总表：功能点 → 落在哪一行 → 谁提供

| 功能点（原 9 问题） | 落在这接口的哪一行 | 谁提供 |
|---|---|---|
| ① 建会话 | 步骤④ `thread_id = req.thread_id or uuid4()` | 你自己写 |
| ② 记忆恢复 | 步骤⑧（checkpointer 自动） | 框架内置 |
| ③ 摘要压缩 | 不在这个接口，LangGraph 图内 | 框架内置 |
| ④ thread 隔离 | 步骤⑦⑧（thread_id 进 config） | 框架内置 |
| ⑤ 流式 | 步骤⑩（astream_events→SSE） | 框架给事件，你拼 SSE |
| ⑥ 断点续传 | 步骤⑧（checkpointer 快照） | 框架内置 |
| ⑦ 权限 | 步骤⑥（第一层）+ 步骤⑨（第二层） | 你配置 + Java 二次校验 |
| ⑧ trace | 横切层 B（LangSmith） | 框架内置（配 key） |
| ⑨ 多租户 | 步骤②⑦⑨（透传） | Python 搬运 + Java 三层隔离 |

**这张表就是你要的「整体感」**：9 个功能不是 9 个独立模块，而是这一个请求流经 10 个步骤时，顺路触发的 9 个侧面。

---

## 五、诚实的分界：哪些真实代码已有，哪些你要补

这是最容易踩坑的地方，单独列出来：

### 真实代码已经有的（直接抄）

| 能力 | 真实位置 |
|---|---|
| Controller/Service 分层 + Depends 注入 | `app/routers/chat.py` + `app/services/agent_service.py` |
| `thread_id` 生成、`config` 塞 thread_id | `agent_service.py` 第 31–32 行 |
| checkpointer + store 接入 | `core/memory.py` + `core/agent.py` |
| 流式 astream_events → SSE | `agent_service.py` 第 36–66 行 |
| 工具内注入 token 调微服务 | `core/tool/api_tools.py` 的 `_request()` |
| 长期记忆工具（InjectedStore） | `core/tool/memory_tools.py` |
| 鉴权/日志/异常/trace 中间件 | `app/main.py` |

### 你要补的（真实代码里没有）

| 缺口 | 怎么补 | 补在哪 |
|---|---|---|
| **按角色过滤工具** | 现在 `core/agent.py` 写死三种 agent，没有「按 role 选工具」。要加 `resolve_tools_for_role()`（动态查 role→tool_ids）+ `get_agent_for_tools()`（按工具指纹缓存 agent，见步骤⑥） | `agent_factory.py`（新）+ Java 侧配置 |
| **多租户透传** | 现有接口没有 `x_tenant_id` header，`_request` 也没转发 tenant/user。要补 header 接收 + config 携带 + 转发给 Java | 路由层 + `_request()` |
| **用户身份解析** | 现有 `authorization` 是 API Key 不是用户 token。要接芋道鉴权拿 user_id + role | `_resolve_identity()` |
| **工具二次鉴权** | 工具内部（Java 微服务）要对 user/tenant 做校验，现在 `api_tools.py` 是无脑转发 | Java 侧工具方法 |

**一句话总结分界**：LangGraph 把「记忆、隔离、流式、推理、断点」这些**通用能力**内置好了；
但「谁是什么角色、能用什么工具、能碰哪个租户的数据」这些**业务规则**，框架给不了，必须你自己写，而且**权限的最终边界在 Java 侧**。

---

## 六、怎么一步步自己写出来（动手顺序）

不要从上往下照着抄，按这个依赖顺序写，每写完一层就能测：

1. **先写 `config/settings.py`**：LLM key、memory_type、微服务 URL、api_key。这是所有东西的根。
2. **再写 `core/models.py`**：`deepseek_model = init_chat_model(...)`。一个能回话的模型。
3. **再写 `core/memory.py`**：checkpointer + store。两根「记忆管道」。
4. **再写 `core/tool/`**：`tools.py`（web_search 等）+ `api_tools.py`（订单等，含 `_request` 注入 token）+ `memory_tools.py`（InjectedStore）。
5. **再写 `core/agent.py` + `agent_factory.py`**：`create_agent(...)`，按 role 选 tools。
6. **再写 `app/schemas/`**：`AdvisorRequest`（message、thread_id、stream）。
7. **再写 `app/services/advisor_service.py`**：`invoke/stream/_build_context/_resolve_identity`。
8. **再写 `app/routers/advisor.py`**：上面的接口函数。
9. **最后写 `app/main.py`**：`include_router` + 中间件 + 异常处理 + lifespan。

每步之间都能 `uvicorn app.main:app` 起来，用 `/docs` 打一次看效果。这样你不会「写完一堆跑不起来不知道错在哪」。

---

## 七、一句话收尾

**接口是入口，不是全部。** 一个「什么功能都涉及」的接口，厉害在它把
`Depends 注入 → header 透传 → thread_id 会话 → token 解析 → RBAC 选工具 → create_agent →
checkpointer/store 记忆 → LLM 调工具 → 内部注入凭证 → astream_events 流式 → 全局异常/trace`
这一整条链串了起来。你把它一条链写明白，前面 9 个「碎片」就自动长成一张网了。
