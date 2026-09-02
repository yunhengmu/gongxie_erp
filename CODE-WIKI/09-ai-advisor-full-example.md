# AI 顾问后端：完整范例（从零到能跑的一个完整功能）

> 这是一份**完整系统的范例**，不是零散知识点。一个 AI 顾问后端，从「建会话」到「多租户」，
> 9 个产品化问题全部串在一条链路上。所有代码都可以照着拼成一个能跑的服务。
>
> 运行时 LLM 用 `FakeModel` 占位（无需 API Key），换一行即可接真实模型。

---

## 一、先看整体：完整目录结构

```
ai_advisor/                      # 一个独立的服务
├── config.py                    # 配置
├── models.py                    # 模型（FakeModel 占位）
├── memory.py                    # 记忆（checkpointer + store）
├── permissions.py               # 角色 → 工具白名单
├── tools.py                     # 业务工具
├── agent_factory.py             # 按角色构建 Agent
├── schemas.py                   # 请求/响应模型
├── conversation.py              # 会话元数据
├── chat_service.py              # 对话编排
└── main.py                      # FastAPI 入口 + 路由
```

依赖方向（自底向上）：`config` → `models/memory/permissions/tools` → `agent_factory` → `chat_service` → `main`。

---

## 二、完整代码（按依赖顺序）

### 2.1 `config.py` —— 配置

```python
import os

class Settings:
    # True=用 FakeModel（无 Key 可跑），False=接真实 deepseek
    use_fake_model: bool = os.getenv("USE_FAKE_MODEL", "1") == "1"
    model_name: str = os.getenv("MODEL_NAME", "deepseek:deepseek-chat")
    # 【问题3】历史超过该条数触发摘要/截断
    max_context_messages: int = int(os.getenv("MAX_CONTEXT_MESSAGES", "10"))

settings = Settings()
```

### 2.2 `models.py` —— 模型（占位）

```python
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatResult, ChatGeneration
from config import settings

class FakeModel(BaseChatModel):
    """占位模型：返回固定回复，无 API Key 也能跑通全链路。"""
    @property
    def _llm_type(self):
        return "fake"

    def bind_tools(self, tools, **kwargs):
        return self  # FakeModel 不真调工具，忽略绑定

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        last = messages[-1].content if messages else ""
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=f"[假模型回复] 收到：{last}"))])

def get_model():
    if settings.use_fake_model:
        return FakeModel()
    from langchain.chat_models import init_chat_model
    return init_chat_model(settings.model_name)  # 接真实模型：改这一行
```

### 2.3 `memory.py` —— 记忆（短时 + 长时）

```python
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.store.memory import InMemoryStore

# 【问题2/4/6】记忆恢复、thread 隔离、断点续传，都由 checkpointer 内置
checkpointer = InMemorySaver()
# 长时记忆（跨会话）
store = InMemoryStore()
# 生产切 PG：AsyncPostgresSaver / AsyncPostgresStore（重启不丢）
```

### 2.4 `permissions.py` —— 角色 → 工具白名单

```python
# 【问题7】第一层：LLM 只能看到授权工具。生产放芋道后台「角色管理」配置。
ROLE_TOOLS = {
    "admin":     {"query_order", "create_order", "cancel_order"},
    "operator":  {"query_order", "cancel_order"},
    "customer":  {"query_order"},
}

def allowed_tools(role: str) -> set:
    return ROLE_TOOLS.get(role, set())
```

### 2.5 `tools.py` —— 业务工具

```python
from langchain_core.tools import tool

# 生产：这些工具定义在 module-ai（Java），通过 MCP 暴露，Python 端加载；
#       二次鉴权在 module-ai 工具方法内做。本范例用 stub 模拟。

@tool
def query_order(order_id: str) -> str:
    """按订单号查询订单信息。"""
    return f"订单 {order_id}：状态=待发货"

@tool
def create_order(product: str) -> str:
    """创建一个新订单。"""
    return f"已创建订单（商品：{product}）"

@tool
def cancel_order(order_id: str) -> str:
    """取消一个订单。"""
    return f"订单 {order_id} 已取消"

ALL_TOOLS = {
    "query_order": query_order,
    "create_order": create_order,
    "cancel_order": cancel_order,
}
```

### 2.6 `agent_factory.py` —— 按角色构建 Agent

```python
from functools import lru_cache
from langchain.agents import create_agent
from models import get_model
from memory import checkpointer, store
from permissions import allowed_tools
from tools import ALL_TOOLS

SYSTEM_PROMPT = "你是一个 AI 业务顾问，用给定工具回答用户问题。"

@lru_cache
def build_agent_for_role(role: str):
    """【问题7】按角色构建：只挂该角色授权的工具。角色有限，每个只建一次。"""
    names = allowed_tools(role)
    tools = [ALL_TOOLS[n] for n in names if n in ALL_TOOLS]
    return create_agent(
        model=get_model(),
        tools=tools,
        checkpointer=checkpointer,  # 【问题2/4/6】
        store=store,
        system_prompt=SYSTEM_PROMPT,
    )
```

### 2.7 `schemas.py` —— 请求模型

```python
from pydantic import BaseModel

class ThreadCreate(BaseModel):
    role: str = "customer"

class ChatRequest(BaseModel):
    thread_id: str
    message: str
    role: str = "customer"
```

### 2.8 `conversation.py` —— 会话元数据

```python
import uuid

# 【问题1】会话元数据。生产存芋道会话表（AiConversationDO）；
# 注意：对话内容不存这里，存 checkpointer。这里只存"谁的会话、什么角色"。
_threads: dict = {}

def create_thread(tenant_id: str, role: str) -> str:
    tid = uuid.uuid4().hex
    _threads[tid] = {"tenant_id": tenant_id, "role": role}
    return tid

def get_thread(thread_id: str):
    return _threads.get(thread_id)
```

### 2.9 `chat_service.py` —— 对话编排

```python
from langchain_core.messages import HumanMessage
from agent_factory import build_agent_for_role

def _config(thread_id: str) -> dict:
    # 【问题2/4/6】thread_id 是 checkpointer 恢复/隔离/续传的钥匙
    return {"configurable": {"thread_id": thread_id}}

async def chat(thread_id: str, message: str, role: str) -> str:
    """同步对话：invoke 一次，checkpointer 自动恢复该 thread 历史。"""
    agent = build_agent_for_role(role)
    result = await agent.ainvoke(
        {"messages": [HumanMessage(content=message)]},
        config=_config(thread_id),
    )
    return result["messages"][-1].content

async def stream(thread_id: str, message: str, role: str):
    """【问题5】流式：astream 逐状态推。生产可用 astream_events() 拿 token 级流。"""
    agent = build_agent_for_role(role)
    async for state in agent.astream(
        {"messages": [HumanMessage(content=message)]},
        config=_config(thread_id),
        stream_mode="values",
    ):
        msgs = state.get("messages", [])
        if msgs:
            yield msgs[-1].content
```

### 2.10 `main.py` —— 入口 + 路由

```python
import asyncio
import json
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from schemas import ThreadCreate, ChatRequest
from conversation import create_thread, get_thread
from chat_service import chat, stream

app = FastAPI(title="AI 顾问后端 · 完整范例")

@app.post("/threads")
def create_thread_ep(req: ThreadCreate, x_tenant_id: str = Header(...)):
    """【问题1】建会话。【问题9】tenant 只透传、不校验（真正隔离在 module-ai）。"""
    tid = create_thread(x_tenant_id, req.role)
    return {"thread_id": tid}

@app.post("/chat")
async def chat_ep(req: ChatRequest, x_tenant_id: str = Header(...)):
    if not get_thread(req.thread_id):
        raise HTTPException(404, "会话不存在")
    answer = await chat(req.thread_id, req.message, req.role)
    return {"answer": answer}

@app.post("/chat/stream")
async def chat_stream_ep(req: ChatRequest, x_tenant_id: str = Header(...)):
    """【问题5】流式对话（SSE）。"""
    if not get_thread(req.thread_id):
        raise HTTPException(404, "会话不存在")

    async def gen():
        async for content in stream(req.thread_id, req.message, req.role):
            yield f"data: {json.dumps({'content': content}, ensure_ascii=False)}\n\n"
            await asyncio.sleep(0.02)
        yield 'data: {"type": "done"}\n\n'

    return StreamingResponse(gen(), media_type="text/event-stream")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="127.0.0.1", port=8001, reload=True)
```

---

## 三、一条请求怎么流经整个系统

```
用户发消息（带 token + thread_id + role）
   │
   ▼
main.py  POST /chat
   │  ① 从 conversation 拿会话元数据（问题1）
   │  ② 把 thread_id 放进 config（问题2/4/6）
   ▼
chat_service.py  chat()
   │  ③ build_agent_for_role(role)：按角色过滤工具（问题7 第一层）
   │  ④ agent.ainvoke：checkpointer 自动恢复历史 → 推理 → 生成（问题2）
   ▼
（换成真模型后，这里会调用 tools.py 里的工具 → 生产走 MCP 到 module-ai，
  module-ai 做二次鉴权【问题7 第二层】+ 多租户隔离【问题9】）
   │
   ▼
返回 answer 给前端；流式走 /chat/stream（问题5）
```

---

## 四、9 个问题各落在哪

| # | 问题 | 落点 | 谁来干 |
|---|---|---|---|
| 1 | 建会话 | `conversation.py` + `POST /threads` | 你写（会话表） |
| 2 | 记忆恢复 | `checkpointer` 自动 | 框架内置 |
| 3 | 摘要压缩 | 生产用 LangGraph `SummarizationNode` | 框架内置 |
| 4 | thread 隔离 | `checkpointer` 按 thread_id 分桶 | 框架内置 |
| 5 | 流式 | `chat_service.stream()` + SSE | 框架 `astream` + 你转 SSE |
| 6 | 断点续传 | `checkpointer` 状态快照 | 框架内置 |
| 7 | 权限 | 第一层 `permissions.py`；第二层 module-ai 工具内 | 你配置 + Java |
| 8 | trace | LangSmith | 框架内置（配 key） |
| 9 | 多租户 | Python 透传 token；隔离在 module-ai | Java 三层 |

---

## 五、怎么跑 + 怎么换成真模型

**跑起来**（需要 `fastapi`、`uvicorn`、`langchain`、`langgraph`，用项目 venv）：

```bash
python main.py
# 打开 http://127.0.0.1:8001/docs
```

**换成真模型**：`models.py` 的 `get_model()` 里，把 `return FakeModel()` 换成 `return init_chat_model(settings.model_name)`，或设 `USE_FAKE_MODEL=0`。换完工具调用、智能推理自动生效，其余代码不动。

---

## 六、这个范例的"完整"体现在哪

- **不是零散片段**：10 个文件按依赖顺序排列，拼起来就是一个能跑的服务。
- **分层清晰**：接口层（`main`）、编排层（`chat_service`）、构建层（`agent_factory`）、组件层（`models/memory/permissions/tools`）、元数据层（`conversation`）。
- **框架内置 vs 自己写分得清**：记忆、隔离、续传、流式是框架内置；会话表、权限配置、租户边界是你自己写（且在 Java 侧）。
