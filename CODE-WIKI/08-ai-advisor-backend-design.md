# AI 顾问后端设计：从「一次对话」到「一个产品」要补的 9 件事

> 配套可运行示例：`yudao-module-agent/examples/ai_advisor_backend.py`（本文所有代码片段都来自它，可对照运行）。
>
> 本文回答一个核心问题：**当客户真的使用你的 AI 顾问产品，先聊一个任务、再持续聊下去时，后端除了"调 Agent"之外还要做什么？**

---

## 开场：先破除一个误区

很多人以为"AI 顾问 = 接个 LLM + Agent 就完了"。真相是：

> **Agent 自动完成的，只有"这一次怎么回答"这一件事。** 而一个真实产品要解决的是"这场对话、乃至这个用户的一生"怎么持续、怎么安全、怎么不串。

下面 9 件事，Agent 一件都不替你干，全在后端。

---

## ⭐ 关键前提：先分清「框架内置」和「你要自己写」

这是本文最重要的一节，先把它读透，否则后面 9 个问题全都会看歪。

示例为了"零依赖、可运行"，用 `dict`、手写字段、`gen()` 把很多逻辑**手动模拟**了一遍。但生产里，这些能力**大部分是 LangGraph / 芋道框架内置的，根本不用你手写**。对照表：

| 能力        | 示例里的写法（手写模拟）       | 生产真相（框架内置）                                |
| --------- | ------------------ | ----------------------------------------- |
| 对话历史存储    | 手写 `dict`          | LangGraph `checkpointer` 内置               |
| thread 隔离 | `dict` 分桶          | `checkpointer` 按 `thread_id` 自动隔离         |
| 记忆恢复      | 手写 `get_history()` | `checkpointer` 按 `thread_id` 自动恢复         |
| 断点续传      | 手写 `checkpoint` 字段 | `checkpointer` 每个 super-step 自动存状态，内置续传   |
| 流式输出      | 手写 `gen()` 逐字推     | LangGraph `astream_events()` 内置事件流        |
| 摘要压缩      | 手写 `summarize()`   | LangGraph `SummarizationNode`（或手动 LLM 摘要） |
| trace 追踪  | 手写 `logger`        | LangSmith 内置（配 key 即自动）                   |
| 权限过滤      | 手写 `ROLE_TOOLS`    | 芋道后台「角色管理」+ 工具方法内鉴权（Java）                 |
| 多租户隔离     | 手写 `tenant` 校验     | 芋道三层（Java，见问题 9）                          |

**一句话总结这张表**：示例里你看到的"手写代码"，**绝大多数是教学示意，生产不用写**。真正要你自己写的只有：**会话表、接入层、权限/租户配置**——而这几样恰恰是框架帮不了你的"产品壳"。

---

## ⭐ 一次请求的完整数据流

9 个问题不是孤立的知识点，它们落在同一条链路上。先建立这个全局视角，再逐条看，就不会"只见树木不见森林"：

你没有给我一个好的方案，你应该做一个范本，然后供我学习，我已经知道了大概每个子内容的意思，我缺少的是整个的功能

**记住这条链路的两个分界**：

1. **会话/记忆的边界在 Python（module-agent）**——问题 1~6 主要落在这里，靠 LangGraph 的 `checkpointer`/`store`。
2. **权限/租户的边界在 Java（module-ai）**——问题 7、9 落在这里，靠芋道安全框架。

分清这两个分界，你就不会再犯"在 Python 端做租户隔离"这种错。

---

## 九件事总览

| # | 用户会遇到什么        | 后端要解决什么 | 边界在哪                         |
| - | -------------- | ------- | ---------------------------- |
| 1 | 第一次来说"我想做个 XX" | 建会话     | Python 入口 + 芋道会话表            |
| 2 | 聊到一半走了，明天回来    | 记忆恢复    | LangGraph checkpointer（内置）   |
| 3 | 越聊越长           | 上下文压缩   | LangGraph 摘要（内置）             |
| 4 | 同时开两个任务        | 防串记忆    | checkpointer 按 thread_id（内置） |
| 5 | 问了个要跑很久的事      | 别让人干等   | LangGraph astream（内置）        |
| 6 | 聊到一半断了         | 接着跑不从头来 | checkpointer（内置）             |
| 7 | 不同客户、不同角色      | 工具/数据权限 | Java 侧 RBAC + 二次鉴权           |
| 8 | 答错了 / 幻觉了      | 能查到为什么  | LangSmith（内置）                |
| 9 | A 客户的数据被 B 看到  | 租户隔离    | Java 侧三层                     |

---

## 问题 1：怎么让用户「接着上次聊」——会话与 thread_id

**场景**：用户第一次进来，说要做一个任务。你后端得有个东西能把这之后的每一句话都串到同一个"话题"下。

**核心思路**：给每次"任务对话"发一个唯一 ID（`thread_id`），之后所有消息都挂它下面。

```python
def create_thread(self, tenant_id: str, user_id: str) -> str:
    thread_id = uuid.uuid4().hex        # 全局唯一 ID
    self._threads[thread_id] = {        # 这个 ID 就是后续一切的锚点
        "tenant_id": tenant_id,
        "user_id": user_id,
        "messages": [],                  # 对话历史（示例手写，生产见下方）
        "checkpoint": "idle",            # 问题 6 用（示例手写，生产内置）
    }
    return thread_id
```

**接口**：`POST /threads` 返回 `thread_id`，前端保存下来，之后每次请求都带。

**⭐ 示例 vs 生产（关键澄清）**：示例用 `dict` 存 thread，但生产要拆成两处存，别混为一谈：

- **会话元数据**（这个 thread 属于谁、标题、创建时间、绑定的角色）→ 存**芋道的会话表**（module-ai 已有 `AiConversationDO`），因为它要和芋道的用户、角色、租户关联。
- **对话内容 + 执行状态**（messages、checkpoint）→ 存 **LangGraph `checkpointer`**，这才是 `thread_id` 真正的"消费方"。

所以 `thread_id` 最好由芋道（会话表）生成、下发，Python 端只是接收并传给 checkpointer 使用。示例里 Python 自己生成，是教学简化。

---

## 问题 2：记忆恢复——明天回来还记得昨天聊了啥

**场景**：用户昨天聊到一半走了，今天回来。你不能让他从头说一遍。

**核心思路**：`thread_id` 就是"找回记忆的钥匙"。用同一个 `thread_id` 进来，就能把历史捞出来喂给模型。

**⭐ 示例 vs 生产（关键澄清）**：示例里手写了 `get_history()` 返回 `messages`，但**生产里这一步是 `checkpointer` 内置的**——你把 `thread_id` 传给 LangGraph，它自动把该 thread 的历史状态加载回来，不用你写任何"捞历史"的代码。

```python
# 示例：手写捞历史（教学示意）
def get_history(self, thread: dict) -> list:
    return thread["messages"]

# 生产：LangGraph 内置，你只需传 thread_id
# config = {"configurable": {"thread_id": "xxx"}}
# agent.invoke({"messages": [...]}, config=config)   # 自动恢复该 thread 的历史
```

**关键点**：记忆的"存"和"取"都靠 `thread_id` 对齐。这也是问题 1 为什么要先建会话——没有它，记忆无处安放。

---

## 问题 3：越聊越长怎么办——上下文压缩

**场景**：聊了 50 轮，如果每次都把 50 轮全塞给 LLM，token 成本和延迟都会爆炸。

**核心思路**：历史超过阈值时，把**旧消息压成一段摘要**，只保留「摘要 + 最近 N 条原文」。

```python
MAX_HISTORY_BEFORE_SUMMARY = 10   # 超过 10 条就触发
RECENT_KEEP = 5                   # 保留最近 5 条原文

def build_context(self, thread: dict) -> list:
    msgs = thread["messages"]
    if len(msgs) <= MAX_HISTORY_BEFORE_SUMMARY:
        return msgs                # 没超，全量
    old = msgs[:-RECENT_KEEP]
    summary = summarize(old)       # 旧消息 -> 摘要
    return [{"role": "system", "content": f"[历史摘要] {summary}"}] + msgs[-RECENT_KEEP:]
```

**⭐ 示例 vs 生产**：示例手写 `summarize()`（朴素拼接），生产两种做法：

1. 用 **LangGraph `SummarizationNode`**：在图里内置"历史超长就触发 LLM 摘要"的节点，自动做，不用你手写。
2. 或在 `checkpointer` 之外，用 **`store`** 存"跨会话的长期记忆"，实现另一种压缩。

**触发时机**：通常是**每次请求前同步检查**（示例这样），也可以做成后台任务异步压缩，视你的延迟要求定。

---

## 问题 4：同时开两个任务会不会串——thread 隔离

**场景**：用户一边聊"写周报"，一边聊"查订单"。如果记忆混在一个池子里，两个话题就串味了。

**⭐ 示例 vs 生产（关键澄清）**：示例里靠 `dict` 分桶实现隔离，但**生产里隔离是 `checkpointer` 内置的**——LangGraph 把状态按 `thread_id` 分开存，同一个 checkpointer 实例服务所有线程，也天然互不干扰。你**不需要写任何隔离逻辑**。

```python
# 示例：dict 分桶（教学示意）
# threadA 和 threadB 是两个独立的 dict，各自存自己的 messages

# 生产：checkpointer 内置隔离，你只需保证每次请求带上正确的 thread_id
# config = {"configurable": {"thread_id": "A"}}   # 只操作 A 的状态
```

**关键点**：隔离不是额外写的逻辑，而是"状态按 thread_id 分开存"这个机制本身带来的。你唯一要保证的是**每个请求都正确带上自己的 thread_id**——传错了，就会串（这是你自己要负责的，不是框架的锅）。

---

## 问题 5：长任务别让人干等——流式输出

**场景**：用户问了个要查库、要搜索的问题，Agent 要跑好几步。如果等全部跑完再一次性返回，用户干瞪眼几十秒。

**核心思路**：把"中间过程 + 最终答案"一点点推给前端。

```python
# 示例：手写 gen() 逐段推（教学示意）
@app.post("/chat/stream")
async def chat_stream(req: ChatRequest, x_tenant_id: str = Header(...)):
    async def gen():
        yield sse({"type": "thinking", "content": "正在思考…"})
        steps, answer = run_agent(...)
        for s in steps:
            yield sse({"type": "tool", "content": s})     # 中间步骤
        for ch in answer:
            yield sse({"type": "token", "content": ch})   # 逐字答案
        yield sse({"type": "done"})
    return StreamingResponse(gen(), media_type="text/event-stream")
```

**⭐ 示例 vs 生产**：生产用 **LangGraph `astream_events()`**，它把 Agent 运行的每一步（开始、工具调用、token 生成）作为事件流吐出来，你只需订阅事件、转成 SSE 推给前端，**不用手写 `gen()` 去模拟**。

**关键点**：流式的价值不只是"快"，更是**透明**——用户能看到"它在调工具、搜到了什么"。这也和问题 8（trace）呼应。

**易混提醒**：这里的 SSE 是"产品输出流"，跟 MCP 的传输协议（SSE vs Streamable HTTP）是两码事，别搞混。

---

## 问题 6：聊到一半断了——断点续传

**场景**：Agent 一个长任务跑到第 3 步时网络断了/超时了。用户回来，希望**接着第 3 步跑，而不是从头重来**。

**⭐ 示例 vs 生产（关键澄清）**：示例手写了一个 `checkpoint` 字段来模拟，但**生产里断点续传是 `checkpointer` 内置的**——LangGraph 每执行完一个 super-step 就自动把 state 存进 `checkpointer`，中断后用同一个 `thread_id` 恢复，自动从断点继续，**不用你手写 `save_checkpoint`**。

```python
# 示例：手写 checkpoint 字段（教学示意）
def save_checkpoint(self, thread: dict, node: str):
    thread["checkpoint"] = node

# 生产：checkpointer 内置，你只需用同一个 thread_id 再调一次 invoke
# 上次跑了一半的 state 自动恢复，从断点续跑
```

**关键点**：示例用字符串 `"idle"/"calling_tool"` 模拟"执行到哪"，真实粒度是 LangGraph 的 **state snapshot**（每个节点执行后的完整状态快照），比你想象的更细、更可靠。生产要持久化，就用 `AsyncPostgresSaver` 存 PG，否则服务重启 checkpointer 也失忆（和问题 2 同源）。

---


## 问题 7：谁能用什么工具——RBAC + 二次鉴权

**场景**：管理员能删订单，普通客户只能查自己的订单。不能让客户调"删除"工具。

**核心思路**：两层防线。

**第一层：让 LLM 看不到未授权工具**（省 token，也断念想）

```python
ROLE_TOOLS = {
    "admin":    {"create_order", "query_order", "cancel_order", "query_user"},
    "operator": {"query_order", "cancel_order"},
    "customer": {"query_order"},
}

def filter_tools_by_role(role):
    return ROLE_TOOLS.get(role, set())   # customer 拿不到 cancel_order
```

**第二层：工具执行时二次鉴权**（真正的边界，挡 prompt 注入）

```python
# 示例：Python 端 raise（教学示意）
def execute_tool(role, tool, args):
    if tool not in filter_tools_by_role(role):
        raise HTTPException(403, f"角色 {role} 无权使用工具 {tool}")
```

**⭐ 示例 vs 生产（关键澄清）**：示例在 Python 端 `raise` 403，但**真正的二次鉴权边界在 Java（module-ai）侧**——和问题 9 的多租户同理，理由也相同：

1. Python 端没有权限权威，它的"角色"是前端传的，可伪造。
2. 边界要落在离工具执行最近的地方——module-ai 的工具方法内部。

生产做法：在 module-ai 的工具方法（`apply()` / `@Tool` 方法）内部加芋道鉴权：

```java
@Override
public Response apply(Request request) {
    Long userId = SecurityFrameworkUtils.getLoginUserId();
    if (userId == null) throw new AccessDeniedException("未登录");
    // 校验当前用户是否有该工具的权限标识（在芋道菜单/角色里配）
    if (!permissionApi.hasAnyPermissions(userId, "ai:tool:query-order")) {
        throw new AccessDeniedException("无权限使用该工具");
    }
    // ... 业务逻辑
}
```

- **第一层（工具列表过滤）**：仍可在 Python 端做（省 token、让 LLM 看不到），这只是"体验优化"。
- **第二层（二次鉴权）**：必须在 module-ai 工具内部做，用芋道 `@PreAuthorize` / `hasPermission`，这才是不能绕过的最终边界。

---

## 问题 8：答错了怎么查——trace 日志

**场景**：用户反馈"它答错了/幻觉了"。你不能只说"是模型的锅"，得能回&#x7B54;**"它当时看到了什么、调了哪个工具、为什么这么答"**。

**核心思路**：记录 Agent 运行的完整决策链。

**⭐ 示例 vs 生产（关键澄清）**：示例手写 `logger` 打几条日志，但生产直接上 **LangSmith**（`module-agent` 的 `setup_langsmith()` 已经留好了入口）——配好 API key，LangGraph 的每一步（prompt、工具输入输出、中间思考、token 消耗、耗时）**自动被追踪**，不用你手写。

```python
# 示例：手写日志（教学示意）
trace.info("Agent 运行 | role=%s | 可用工具=%s | 步骤=%s", role, tools, steps)

# 生产：LangSmith 自动 trace，配置见 config/settings.py
# LANGCHAIN_TRACING_V2=true + LANGCHAIN_API_KEY=xxx
```

**trace 要覆盖的维度**（不管用日志还是 LangSmith，这几样都要有）：谁（user）→ 哪个会话（thread）→ 用了什么工具（tool 输入/输出）→ 模型看到了什么（prompt）→ 结果是什么（answer）。缺一样，"查问题"就少一环。

---

## 问题 9：A 客户的数据别被 B 看到——多租户隔离

**场景**：两个客户共用一套服务，但数据绝不能串。A 不能查到 B 的会话、订单、数据。

**⚠️ 先纠正一个错误认知（重要）**：多租户隔离**不能只在 Python（module-agent）端做**。两条原因：

1. **Python 端没有租户权威**。它拿到的 `tenant_id` 要么是前端传的（可伪造），要么是它自己拼的，都不靠谱。在 Python 里写 `if t["tenant_id"] != tenant_id` 这种校验，校验的只是"自己内存里存的一份虚拟数据"，而真正的业务数据在 Java 微服务的数据库里。
2. **边界必须落在离数据最近的地方**——Java 的数据访问层。否则攻击者绕过 Python、直接调 Java 接口，就能拿到任意租户的数据。

**正确设计：多租户在 module-ai（Java）做三层，Python 只透传身份。**

### 第一层：身份权威（芋道登录态）

用户登录芋道，`TokenAuthenticationFilter` 解析 token，得到 `LoginUser`（里面带 `tenant_id`）。这是租户信息的**唯一可信来源**，不是前端、也不是 Python 传的。

### 第二层：越权校验（`TenantSecurityWebFilter`）

芋道框架内置了这个过滤器，核心逻辑是：**用登录用户的 `tenant_id` 和请求携带的租户比对，不一致直接 403**：

```java
// TenantSecurityWebFilter.doFilterInternal 核心逻辑（已核对源码）
LoginUser user = SecurityFrameworkUtils.getLoginUser();
if (user != null) {
    if (tenantId == null) {
        tenantId = user.getTenantId();          // 没带就用登录用户的租户
        TenantContextHolder.setTenantId(tenantId);
    } else if (!Objects.equals(user.getTenantId(), tenantId)) {
        // 带了但和登录用户不一致 → 越权，直接返回"您无权访问该租户的数据"
        return;
    }
}
```

### 第三层：数据隔离（`TenantDatabaseInterceptor`）

这是最狠的一层——芋道实现了 MyBatis-Plus 的 `TenantLineHandler`，**查数据库时自动在 SQL 里拼 `tenant_id = ?`**：

```java
// TenantDatabaseInterceptor 实现 TenantLineHandler
public Expression getTenantId() {
    return new LongValue(TenantContextHolder.getRequiredTenantId()); // SQL 自动追加 tenant_id 条件
}
```

凡是继承 `TenantBaseDO` 的表，增删改查都会被自动加上租户条件。**你写业务代码时根本不用手动拼 `tenant_id`，隔离是数据库层自动完成的。**

### module-ai 端需要补的功能

所以你要补的是这几件事（都在 Java 侧，不是 Python）：

1. **MCP 端点必须 `authenticated()`**：如果 `/mcp`、`/sse` 还是 `permitAll` 匿名开放，`LoginUser` 就是 null，第二层越权校验直接跳过——攻击者可以伪造 `tenant-id` header 访问任意租户。这是"租户隔离失效"的关键。
2. **工具执行时拿到租户**：芋道已经提供了 `AiUtils.buildCommonToolContext()`，把 `LoginUser` + `TenantContextHolder.getTenantId()` 塞进工具的 `ToolContext`，工具里直接取用。
3. **工具查数据走 MyBatis**：只要工具内部用芋道的 Service/Mapper 查库，第三层自动隔离；不要自己写裸 SQL。

### Python（module-agent）端要做的

只有一件事：**透传 token**。把用户请求里的芋道 token 原样带到 MCP 连接的 header（`Authorization: Bearer {token}`），让 module-ai 能解析出 `LoginUser`。Python 端**不判断租户、不存租户数据、不校验归属**。

> 一句话：**租户的"判官"在 Java（身份校验 + 数据隔离），Python 只是个"传话的"。**

---

## 会话数据存在哪（一张表说清）

这个问题贯穿 1、2、4、6，单独拎出来：

| 数据                               | 存哪                                             | 谁负责                  |
| -------------------------------- | ---------------------------------------------- | -------------------- |
| 会话元数据（thread 列表、标题、归属用户/角色）      | 芋道会话表（`AiConversationDO`）                      | module-ai（Java）      |
| 对话历史 + 执行状态（messages、checkpoint） | LangGraph `checkpointer`（`AsyncPostgresSaver`） | module-agent（Python） |
| 跨会话长期记忆（用户偏好等）                   | LangGraph `store`（`AsyncPostgresStore`）        | module-agent（Python） |
| 业务数据（订单、客户等）                     | 芋道各业务表，自动带 `tenant_id`                         | module-ai（Java）      |

**一句话**：**"这是谁的会话"在芋道，"聊了什么、跑到哪"在 checkpointer，"记住了什么"在 store，"业务数据"在芋道业务表。** 别再用一个 dict 全包了。

---

## 落到真实项目：示例里的 stub 对应什么

| 示例里的 stub                    | 生产真实组件                                                     | 内置还是自己写 |
| ---------------------------- | ---------------------------------------------------------- | ------- |
| `MemoryStore._threads`（dict） | 芋道会话表 + `checkpointer`                                     | 芋道/框架   |
| `MemoryStore._user_memory`   | `core/memory.py` 的 `store`                                 | 框架内置    |
| `run_agent()`                | `core/agent.py` 的 `create_agent(...)`                      | 框架内置    |
| 手写 `gen()` 流式                | LangGraph `astream_events()`                               | 框架内置    |
| 手写 `checkpoint` 字段           | `checkpointer` 状态快照                                        | 框架内置    |
| `ROLE_TOOLS`（硬编码）            | 芋道后台「角色管理」+ 工具内 `@PreAuthorize`                            | 自己配置    |
| `trace` 日志                   | LangSmith                                                  | 框架内置    |
| `get_thread()` 租户校验（Python）  | 芋道 `TenantSecurityWebFilter` + `TenantDatabaseInterceptor` | 框架内置    |

**一句话总结**：示例里手写的代码，**大部分生产都不用写（框架内置）**。真正要你自己动手的，是**会话表设计、接入层、权限/租户配置**这几样"框架帮不了你的产品壳"。

---

## 自查清单

- [ ] 建会话：`thread_id` 由芋道会话表下发，前端保存、每次请求都带
- [ ] 记忆恢复：靠 `checkpointer` 按 `thread_id` 自动恢复（不手写捞历史）
- [ ] 上下文压缩：超阈值「摘要 + 最近 N 条」，用 LangGraph 摘要节点
- [ ] thread 隔离：每请求正确带自己的 `thread_id`（checkpointer 内置隔离）
- [ ] 流式：用 LangGraph `astream_events()`，订阅事件转 SSE
- [ ] 断点续传：`checkpointer` 用 PG 持久化（内置续传，不手写）
- [ ] 权限：第一层 Python 过滤 + 第二层 module-ai 工具内 `hasPermission`
- [ ] trace：接 LangSmith，覆盖 user/thread/tool/prompt/answer 五维度
- [ ] 多租户：Java 侧三层（登录态 + 越权校验 + DB 自动隔离），Python 只透传 token；MCP 端点改 `authenticated()`
