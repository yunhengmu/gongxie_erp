# AI 应用开发笔记

> 基于 service_agent 项目（v0.2.0）实战整理。读完这篇笔记，你将具备独立开发一个生产级 AI 应用的能力。
>
> 项目仓库：`d:\yudao\gongxie-langchan-zp\service_agent-main`

---

## 目录

- [一、项目概览](#一项目概览)
- [二、快速上手：从零搭建](#二快速上手从零搭建)
- [三、配置系统](#三配置系统)
- [四、分层架构](#四分层架构)
- [五、LLM 模型配置](#五llm-模型配置)
- [六、Prompt 工程](#六prompt-工程)
- [七、Agent 系统](#七agent-系统)
- [八、工具系统](#八工具系统)
- [九、智能路由：分类器 + 策略选择器](#九智能路由分类器--策略选择器)
- [十、RAG 全流程](#十rag-全流程)
- [十一、记忆系统](#十一记忆系统)
- [十二、API 接口实现](#十二api-接口实现)
- [十三、安全与中间件](#十三安全与中间件)
- [十四、部署与运维](#十四部署与运维)

---

## 一、项目概览

### 1.1 这是什么？

**service_agent** 是一个生产级多智能体 AI 服务，基于 **FastAPI + LangChain + LangGraph** 构建。它提供了三大核心能力：

| 能力 | 说明 |
|------|------|
| 智能 Agent | 3 个专职 Agent（研究助手、代码审查、RAG 知识库问答） |
| RAG 检索增强 | 完整流水线：文档加载 → 切片 → 混合检索 → 评估 |
| 工具调用 | 网络搜索、代码审查、长期记忆、微服务网关 |

### 1.2 技术栈一览

| 层级 | 技术选型 |
|------|---------|
| Web 框架 | FastAPI + uvicorn |
| AI Agent | LangChain + LangGraph（`create_agent`） |
| LLM | DeepSeek（`deepseek-chat`），可切换 OpenAI/Anthropic |
| 嵌入模型 | BGE-M3（`BAAI/bge-m3`） |
| 向量库 | Chroma（本地）/ Milvus（分布式） |
| 关键词检索 | BM25（`rank-bm25` + jieba 分词） |
| 中文分词 | jieba |
| 文本分类 | BERT（`bert-base-chinese`）+ 规则兜底 |
| 文档加载 | PDF（pdfplumber/PyPDF2）、Word（python-docx）、PPT（python-pptx）、OCR（PaddleOCR/Tesseract） |
| 记忆系统 | LangGraph Checkpointer + Store（内存 / PostgreSQL） |
| 缓存 | Redis（自动降级） |
| 评估 | Ragas（5 维度） |
| 实时通信 | SSE + WebSocket |
| 配置管理 | Pydantic Settings（50+ 配置项） |
| 包管理 | uv（推荐）/ pip |

### 1.3 项目结构

```
service_agent-main/
├── app/                          # 应用层（FastAPI Web 层）
│   ├── main.py                   # 入口：FastAPI + 中间件 + 全局异常处理
│   ├── dependencies.py           # 依赖注入装配（类似 Spring @Configuration）
│   ├── routers/                  # 路由层（类似 @RestController）
│   │   ├── chat.py               # 聊天/Agent 调用（智能路由）
│   │   ├── rag.py                # RAG 知识库问答 + 评估
│   │   ├── ws.py                 # WebSocket 双向实时通信
│   │   ├── health.py             # 健康检查
│   │   └── file_up_content.py   # 文件上传
│   ├── services/                 # 服务层（类似 @Service）
│   │   ├── agent_service.py      # Agent 调用/流式/智能路由
│   │   ├── rag_service.py        # RAG 业务编排
│   │   └── upload_file_service.py
│   └── schemas/                  # 数据模型层（DTO）
│       ├── chat.py               # ChatRequest / ChatResponse
│       ├── rag.py                # RAGRequest / RAGResponse
│       └── common.py             # ApiResponse 统一响应格式
│
├── core/                         # 核心业务逻辑层
│   ├── agent.py                  # Agent 工厂（创建 3 个 Agent）
│   ├── models.py                 # LLM 模型初始化
│   ├── memory.py                 # 记忆系统配置
│   ├── text_utils.py             # jieba 分词工具
│   ├── tool/                     # 工具定义
│   │   ├── tools.py              # web_search + code_review + kb_search
│   │   ├── memory_tools.py       # 长期记忆工具（增删改查）
│   │   └── api_tools.py          # 微服务调用工具（安全网关）
│   ├── prompts/                  # Prompt 模块
│   │   ├── builders.py           # Prompt 构建工厂
│   │   ├── loader.py             # 模板加载器（LRU 缓存）
│   │   └── templates/            # 模板文件（research.md / code_review.md / rag.md）
│   └── rag/                      # RAG 核心模块
│       ├── retriever.py          # 混合检索器（BM25 + 向量 + RRF）
│       ├── chunker.py            # 中文分割 + 父子分块
│       ├── cache.py              # Redis 缓存层
│       ├── classifier.py         # BERT 问题分类器
│       ├── strategy.py           # 检索策略选择器
│       ├── evaluate.py           # Ragas 评估框架
│       └── document/             # 文档处理
│           ├── loader.py         # 统一加载器（PDF/PPT/Word/图片）
│           └── ocr.py            # OCR 文字识别
│
├── config/
│   └── settings.py               # 配置中心（Pydantic Settings，50+ 配置项）
├── utils/                        # 工具函数
├── tests/                        # 测试
├── .env.example                  # 环境变量模板
├── pyproject.toml                # 项目依赖
├── Dockerfile                    # 容器化
└── docker-compose.yml            # 容器编排
```

---

## 二、快速上手：从零搭建

### 2.1 环境准备

```bash
# 1. 克隆项目
git clone <repo-url> service_agent
cd service_agent

# 2. 安装依赖（推荐 uv）
pip install uv
uv sync

# 3. 配置环境变量
cp .env.example .env
```

### 2.2 最小配置（`.env`）

只需要填写一个 API Key 即可启动：

```bash
# 必填：DeepSeek API Key（在 platform.deepseek.com 获取）
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx

# 以下都有默认值，可以不填
LLM_MODEL=deepseek-chat
APP_HOST=0.0.0.0
APP_PORT=8000
```

### 2.3 启动服务

```bash
# 开发模式（热重载）
uv run python -m app.main

# 或者直接
python app/main.py
```

访问 `http://localhost:8000/docs` 可以看到 Swagger 文档。

### 2.4 验证接口

```bash
# 非流式聊天
curl -X POST http://localhost:8000/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"你好，请用一句话介绍自己"}]}'

# 流式聊天
curl -X POST http://localhost:8000/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"什么是 LangGraph？"}],"stream":true}'
```

---

## 三、配置系统

### 3.1 设计理念

项目使用 **Pydantic Settings** 作为配置中心，遵循"单一数据源"原则：

- `config/settings.py` 是配置的**唯一定义**（类型、默认值、文档）
- `.env` 文件只写**与默认值不同的字段**（如 API Key）
- 生产环境（K8s/Docker）通过**环境变量注入**，不需要 `.env` 文件

### 3.2 配置定义

```python
# config/settings.py
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",              # 自动读取 .env
        env_file_encoding="utf-8",
        extra="ignore",               # 忽略未知环境变量
    )

    # ========== LLM ==========
    deepseek_api_key: str = ""        # 无默认值，必须通过 .env 提供
    llm_model: str = "deepseek-chat"  # 有默认值，可省略
    llm_temperature: float = 0.5
    llm_timeout: int = 300

    # ========== Server ==========
    app_host: str = "0.0.0.0"
    app_port: int = 8000

    # ========== Memory ==========
    memory_type: str = "in_memory"    # in_memory | postgres
    store_type: str = "in_memory"     # in_memory | postgres
    postgres_uri: str = ""

    # ========== Vector Store ==========
    chroma_persist_dir: str = "./.chroma"
    embedding_model: str = "BAAI/bge-m3"

    # ========== Security ==========
    api_key: str = ""                 # 可选，设置后启用 Bearer 认证

    # ========== RAG ==========
    rag_hybrid_search: bool = True
    rag_top_k: int = 5
    bert_model_name: str = "bert-base-chinese"

settings = Settings()  # 全局单例
```

**关键点：** 所有模块都从 `config.settings` 导入配置，不直接读环境变量。这样 IDE 有自动补全，类型安全，修改配置只需改一处。

---

## 四、分层架构

### 4.1 整体分层

项目采用 **Spring 风格的分层架构**，Java 开发者可以无缝理解：

```
┌─────────────────────────────────────────────┐
│  Router 层（@RestController）                 │
│  app/routers/*.py                            │
│  职责：接收请求、参数校验、返回响应              │
├─────────────────────────────────────────────┤
│  Service 层（@Service）                       │
│  app/services/*.py                           │
│  职责：业务编排、调度 Agent、组装数据            │
├─────────────────────────────────────────────┤
│  Core 层（Domain）                            │
│  core/agent.py, core/tool/*, core/rag/*      │
│  职责：Agent 定义、工具、RAG 核心逻辑            │
├─────────────────────────────────────────────┤
│  Config 层                                   │
│  config/settings.py                          │
│  职责：所有配置的单一数据源                     │
└─────────────────────────────────────────────┘
```

### 4.2 依赖注入

FastAPI 的 `Depends()` 机制类比 Spring 的 `@Autowired`：

```python
# app/dependencies.py — 类比 @Configuration + @Bean
from functools import lru_cache
from fastapi import Depends

@lru_cache  # ← 单例（类比 @Scope("singleton")）
def get_research_agent():
    return research_agent

def get_qa_service(
    agent = Depends(get_research_agent),  # ← 注入 Agent Bean
) -> QAService:
    return QAService(agent=agent)  # ← 每次请求新建 Service

# Router 中使用
@router.post("/v1/chat")
async def chat(
    request: ChatRequest,
    svc: AgentService = Depends(),  # ← @Autowired
):
    ...
```

### 4.3 统一响应格式

所有 API 返回统一结构 `ApiResponse<T>`，类比 Spring 的 `Result<T>`：

```python
# app/schemas/common.py
class ApiResponse(BaseModel, Generic[T]):
    code: int = 200          # 业务状态码
    message: str = "success" # 提示信息
    data: Optional[T] = None # 响应数据

# 使用示例
return ApiResponse.success(data=ChatResponse(...))
return ApiResponse.error(code=400, message="参数校验失败")
return ApiResponse.not_found("用户不存在")
```

### 4.4 全局异常处理

类比 Spring 的 `@ControllerAdvice`，FastAPI 通过 `@app.exception_handler` 实现：

```python
# app/main.py
@app.exception_handler(HTTPException)
async def http_exception_handler(request, exc):
    return JSONResponse(
        status_code=exc.status_code,
        content=ApiResponse.error(code=exc.status_code, message=str(exc.detail)).model_dump(),
    )

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request, exc):
    return JSONResponse(
        status_code=422,
        content=ApiResponse.error(code=422, message=f"参数校验失败: {exc.errors()[0]['msg']}").model_dump(),
    )

@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    """兜底处理器 — 捕获所有未处理的异常"""
    logger.error("未处理异常: %s", exc, exc_info=True)
    return JSONResponse(
        status_code=500,
        content=ApiResponse.internal_error().model_dump(),
    )
```

**三层异常处理**：`HTTPException`（业务异常）→ `RequestValidationError`（参数校验）→ `Exception`（兜底）。

---

## 五、LLM 模型配置

### 5.1 模型初始化

使用 LangChain 的 `init_chat_model` 统一初始化，切换模型只需改 `.env` 中的 `LLM_MODEL`：

```python
# core/models.py
import os
from langchain.chat_models import init_chat_model
from config.settings import settings

# 将 API Key 注入环境变量
if settings.deepseek_api_key:
    os.environ.setdefault("DEEPSEEK_API_KEY", settings.deepseek_api_key)

deepseek_model = init_chat_model(
    settings.llm_model,            # "deepseek-chat"
    temperature=settings.llm_temperature,  # 0.5
    timeout=settings.llm_timeout,          # 300s
)
```

### 5.2 切换模型

只需修改 `.env` 中的一行：

```bash
# DeepSeek
LLM_MODEL=deepseek:deepseek-chat

# OpenAI
LLM_MODEL=openai:gpt-4o

# Anthropic
LLM_MODEL=anthropic:claude-sonnet-4-5
```

`init_chat_model` 会根据 `provider:model` 格式自动识别并加载对应的 SDK。

---

## 六、Prompt 工程

### 6.1 设计思路

项目将 Prompt 与代码分离，采用 **模板文件 + 加载器 + 构建器** 的工程化结构：

```
core/prompts/
├── templates/          # Markdown 模板（纯文本，非开发人员也能编辑）
│   ├── research.md     # 研究助手 Prompt
│   ├── code_review.md  # 代码审查 Prompt
│   └── rag.md          # RAG 知识库 Prompt
├── loader.py           # 模板加载器（LRU 缓存，避免重复读文件）
├── builders.py         # 构建工厂（模板 → ChatPromptTemplate）
└── __init__.py         # 统一出口
```

### 6.2 模板文件示例

```markdown
<!-- core/prompts/templates/rag.md -->
You are a RAG-based knowledge assistant.
Answer the user's question based on the provided context.

Rules:
1. If the context contains relevant information, use it to answer accurately.
2. If the context is insufficient, say "知识库中暂未找到相关信息".
3. Cite specific document sources when possible.
4. Be concise and accurate.

Context:
{context}

Chat History:
{chat_history}
```

### 6.3 加载与构建

```python
# core/prompts/loader.py
from functools import lru_cache

@lru_cache(maxsize=32)
def load_template(name: str) -> str:
    """加载模板文件，结果被 LRU 缓存。"""
    path = Path(__file__).parent / "templates" / f"{name}.md"
    return path.read_text(encoding="utf-8")

# core/prompts/builders.py
from langchain_core.prompts import ChatPromptTemplate

def build_rag_prompt() -> ChatPromptTemplate:
    template = load_template("rag")
    return ChatPromptTemplate.from_template(template)

# core/prompts/__init__.py — 统一出口
RESEARCH_PROMPT = build_research_prompt()
CODE_REVIEW_PROMPT = build_code_review_prompt()
RAG_PROMPT = build_rag_prompt()
```

**设计优势：**
- 产品经理可以直接改 `.md` 文件调整 Prompt，无需动代码
- LRU 缓存避免每次请求都读磁盘
- 切换 Prompt 版本只需改 `load_template` 的参数

---

## 七、Agent 系统

### 7.1 Agent 概念

Agent = **LLM（大脑）** + **工具（手脚）** + **系统提示词（职业）** + **记忆（经验）**。

LLM 根据用户问题，自主决定调用哪个工具，然后基于工具返回结果组织回答。

### 7.2 创建 Agent

项目使用 `langchain.agents.create_agent`（底层是 LangGraph 的 `create_react_agent`）：

```python
# core/agent.py
from langchain.agents import create_agent
from core.models import deepseek_model
from core.tool.tools import web_search, code_review, make_retriever_tool
from core.tool.memory_tools import memory_tools
from core.tool.api_tools import api_tools
from core.prompts import RESEARCH_PROMPT, CODE_REVIEW_PROMPT, RAG_PROMPT
from core.memory import checkpointer, store

# 尝试加载向量库检索工具
kb_tool = make_retriever_tool()

# ===== Agent 1: 研究助手 =====
research_agent = create_agent(
    model=deepseek_model,
    tools=[web_search] + memory_tools + api_tools,
    system_prompt=RESEARCH_PROMPT,
    checkpointer=checkpointer,  # 短时记忆
    store=store,                # 长期记忆
)

# ===== Agent 2: 代码审查 =====
code_reviewer_agent = create_agent(
    model=deepseek_model,
    tools=[code_review] + memory_tools,
    system_prompt=CODE_REVIEW_PROMPT,
    checkpointer=checkpointer,
    store=store,
)

# ===== Agent 3: RAG 知识库（kb_search 优先） =====
rag_tools = [web_search] + memory_tools + api_tools
if kb_tool:
    rag_tools = [kb_tool, web_search] + memory_tools + api_tools

rag_agent = create_agent(
    model=deepseek_model,
    tools=rag_tools,
    system_prompt=RAG_PROMPT,
    checkpointer=checkpointer,
    store=store,
)
```

**三个 Agent 的分工：**

| Agent | 挂载工具 | 适用场景 |
|-------|---------|---------|
| `research_agent` | web_search + memory + api | 通用问答、联网搜索、微服务调用 |
| `code_reviewer_agent` | code_review + memory | 代码审查（5 维度分析） |
| `rag_agent` | kb_search（优先）+ web_search + memory + api | 知识库问答 |

### 7.3 Agent 的调用方式

```python
# 非流式调用
config = {"configurable": {"thread_id": "user-123"}}
result = await agent.ainvoke(
    {"messages": [{"role": "user", "content": "2026年AI Agent最新进展"}]},
    config=config,
)
answer = result["messages"][-1].content

# 流式调用（逐 token 输出）
async for event in agent.astream_events(
    {"messages": messages},
    config=config,
    version="v2",
):
    if event["event"] == "on_chat_model_stream":
        token = event["data"]["chunk"].content
        if token:
            yield token  # 每个 token 即时发送
```

**`thread_id` 的作用：** 同一个 `thread_id` 下的多轮对话共享上下文（Agent 会记住之前的对话）。

---

## 八、工具系统

工具是 Agent 的"手脚"。LLM 根据用户问题自主决定调用哪个工具。工具描述写在 `@tool` 装饰器的 **docstring** 中——LLM 会读取这个描述来判断何时调用。

### 8.1 工具的定义方式

```python
from langchain.tools import tool

@tool
def get_weather(city: str) -> str:
    """查询指定城市的实时天气信息。

    当用户询问天气、气温、是否需要带伞等问题时使用此工具。
    city 参数支持中文城市名和英文城市名。
    """
    return f"{city}今天晴，25°C"
```

**LLM 能看到什么？** `@tool` 装饰器会自动将函数名、参数类型注解、docstring 转成 Tool Schema 注入 Prompt。LLM 据此决定何时调用。所以 **docstring 写得越清晰，Agent 调用工具就越准确**。

### 8.2 网络搜索工具

支持双引擎策略：Tavily（高质量，需 API Key）→ DuckDuckGo（免费，自动降级）：

```python
# core/tool/tools.py
@tool
def web_search(query: str) -> str:
    """Search the web for information. Returns formatted search results
    with titles, URLs, and snippets.

    Use this tool when you need to find current information, facts,
    or data from the internet.
    """
    max_results = settings.search_max_results
    provider = settings.search_provider

    if provider == "tavily" and settings.tavily_api_key:
        return _search_tavily(query, max_results)
    else:
        return _search_duckduckgo(query, max_results)
```

### 8.3 代码审查工具

内部调用 DeepSeek 从 5 个维度审查代码：

```python
@tool
async def code_review(code: str) -> str:
    """Review a code snippet for bugs, security issues, and suggest improvements.

    Provide the full code snippet to get a detailed review.
    """
    review_prompt = (
        "你是一位资深代码审查专家。请审查以下代码，从以下几个方面给出反馈：\n"
        "1. 🐛 Bug 和逻辑错误\n"
        "2. 🔒 安全漏洞\n"
        "3. ⚡ 性能问题\n"
        "4. 📝 代码风格和可读性\n"
        "5. 💡 改进建议\n\n"
        f"```\n{code}\n```"
    )
    response = await deepseek_model.ainvoke(review_prompt)
    return response.content
```

### 8.4 长期记忆工具

四个工具实现对用户信息的跨会话持久化：

```python
# core/tool/memory_tools.py
from langgraph.prebuilt import InjectedStore

@tool
def save_user_info(key: str, info: str, store: InjectedStore) -> str:
    """将用户信息保存到长期记忆（跨会话保留）。"""
    store.put(("users",), key, {"info": info})
    return f"已记住: {key} = {info}"

@tool
def get_user_info(key: str, store: InjectedStore) -> str:
    """从长期记忆中读取用户信息。"""
    item = store.get(("users",), key)
    if item is None:
        return f"未找到关于 {key} 的记忆。"
    return str(item.value.get("info", ""))

@tool
def forget_user_info(key: str, store: InjectedStore) -> str:
    """删除用户要求遗忘的长期记忆。"""
    store.delete(("users",), key)
    return f"已遗忘: {key}"

@tool
def list_user_memories(store: InjectedStore) -> str:
    """列出当前用户所有的长期记忆条目。"""
    items = store.search(("users",))
    ...
```

**`InjectedStore` 机制：** LangGraph 在 Agent 调用工具时自动注入 `Store` 实例，LLM 感知不到存储层的存在，只看到工具的名称和描述。

### 8.5 微服务网关工具

设计原则：**LLM 永远看不到凭证、URL、鉴权信息**。所有安全敏感信息在工具内部注入：

```python
# core/tool/api_tools.py
import httpx
from config.settings import settings

# 微服务地址从 settings 读取，LLM 永远看不到
ORDER_SERVICE_URL = settings.order_service_url or "http://localhost:9001"
API_TOKEN = settings.internal_api_token or ""

async def _request(method: str, url: str, **kwargs) -> dict:
    """统一的 HTTP 请求封装，内部注入鉴权 Token。"""
    async with httpx.AsyncClient(timeout=30) as client:
        headers = {
            "Authorization": f"Bearer {API_TOKEN}",  # ← LLM 不可见
            "Content-Type": "application/json",
        }
        response = await client.request(method, url, headers=headers, **kwargs)
        response.raise_for_status()
        return response.json()

@tool
async def create_order(product_id: str, quantity: int) -> str:
    """创建订单。调用订单微服务，传入商品ID和购买数量，返回订单号。

    使用场景：用户要求购买商品、下单时调用。
    """
    result = await _request("POST", f"{ORDER_SERVICE_URL}/api/orders",
                            json={"product_id": product_id, "quantity": quantity})
    return f"订单创建成功，订单号: {result.get('order_id')}"

# 同样的模式：query_order, cancel_order, check_inventory, query_user_profile, send_notification
```

**安全网关模式总结：**

| 元素 | LLM 能看到 | LLM 看不到 |
|------|-----------|-----------|
| 工具名称 | ✓ | - |
| 参数描述 | ✓ | - |
| 返回结果 | ✓ | - |
| 微服务 URL | ✗ | ✓ |
| 鉴权 Token | ✗ | ✓ |
| 内部 API 细节 | ✗ | ✓ |

### 8.6 如何添加新工具

只需三步：

```python
# 1. 定义工具
@tool
def my_tool(param: str) -> str:
    """工具描述：LLM 据此判断何时调用。"""
    # 实现逻辑
    return f"结果: {param}"

# 2. 挂到 Agent 上
my_agent = create_agent(
    model=deepseek_model,
    tools=[my_tool, ...],  # ← 放进去就行
    system_prompt="...",
    checkpointer=checkpointer,
)

# 3. 完成！不需要额外配置
```

---

## 九、智能路由：分类器 + 策略选择器

### 9.1 整体流程

用户的问题不会直接丢给某一个 Agent，而是经过两级决策：

```
用户问题
  │
  ▼
BERT 分类器（classifier.py）
  │  判断问题类型：greeting / general / specialized / code_review
  ▼
策略选择器（strategy.py）
  │  根据分类结果 + 可用资源选择策略
  ▼
Agent 分发
  │  greeting → 直接回复
  │  general → research_agent
  │  specialized → rag_agent（混合检索）
  │  code_review → code_reviewer_agent
```

### 9.2 BERT 分类器

使用 `bert-base-chinese` 做文本分类，**BERT 优先，规则兜底**：

```python
# core/rag/classifier.py
class QueryClassifier:
    def __init__(self):
        self._labels = [
            "general_knowledge",       # 通用知识
            "specialized_knowledge",   # 专业知识（需 RAG）
            "code_review",             # 代码审查
            "greeting",                # 问候
            "vector_search",           # 向量检索
        ]

    def classify(self, query: str) -> ClassificationResult:
        self._init_model()  # 延迟加载 BERT，首次调用才初始化

        if self._model is not None:
            return self._classify_with_model(query)  # BERT 推理
        return self._classify_with_rules(query)       # 规则兜底
```

**规则兜底逻辑（BERT 不可用时）：**

| 优先级 | 匹配规则 | 分类 |
|--------|---------|------|
| 1 | 以 `review:` 或 `审查` 开头 | `code_review` |
| 2 | 包含问候词（你好、hi、hello） | `greeting` |
| 3 | 包含专业关键词（算法、向量、什么是、如何） | `specialized_knowledge` |
| 4 | 以上都不匹配 | `general_knowledge`（默认） |

**延迟初始化：** BERT 模型在首次调用 `classify()` 时才加载，避免启动时就占用大量内存。

### 9.3 策略选择器

```python
# core/rag/strategy.py
class RetrievalStrategy(Enum):
    DIRECT_LLM = "direct_llm"          # 直接 LLM
    HYBRID_SEARCH = "hybrid_search"    # 混合检索（BM25 + 向量）
    MYSQL_EXACT = "mysql_exact"        # MySQL 精确匹配（可选）
    GREETING = "greeting"              # 问候直接回复
    CODE_REVIEW = "code_review"        # 代码审查

class StrategySelector:
    def select(self, classification, query=""):
        if category == "greeting":
            return GREETING, "问候直接回复"
        if category == "code_review":
            return CODE_REVIEW, "代码审查模式"
        if category == "specialized_knowledge":
            if self._mysql_available:
                return MYSQL_EXACT, "先 MySQL 精确匹配"
            if self._vector_available:
                return HYBRID_SEARCH, "混合检索"
            return DIRECT_LLM, "向量库不可用，降级为直接 LLM"
        return DIRECT_LLM, "通用知识，直接 LLM"
```

**降级策略：** 向量库不可用时，专业知识问题自动降级为直接 LLM 回答，不会报错。

### 9.4 Agent 选择

Service 层根据策略选择最优 Agent：

```python
# app/services/agent_service.py
def select_agent(self, message: str):
    classification = classify_query(message)
    strategy, note = select_strategy(classification, message)

    if strategy == RetrievalStrategy.HYBRID_SEARCH:
        return rag_agent, strategy.value        # 走 RAG
    if strategy == RetrievalStrategy.CODE_REVIEW:
        return code_reviewer_agent, strategy.value  # 走代码审查
    return research_agent, strategy.value        # 默认
```

---

## 十、RAG 全流程

RAG（Retrieval-Augmented Generation）是解决 LLM "幻觉"和"知识过时"的核心方案。整个流水线分为 6 个阶段。

### 10.1 阶段一：文档加载

支持多种格式，自动识别：

```python
# core/rag/document/loader.py
def load_document(path: str) -> LoadedDocument:
    ext = Path(path).suffix.lower()

    if ext == ".pdf":
        return _load_pdf(path)      # pdfplumber > PyPDF2
    if ext == ".docx":
        return _load_docx(path)     # python-docx（含表格提取）
    if ext == ".pptx":
        return _load_pptx(path)     # python-pptx（逐页提取）
    if ext in (".png", ".jpg", ".jpeg"):
        return _load_image(path)    # OCR（PaddleOCR > Tesseract > EasyOCR）
    return _load_txt(path)          # 纯文本
```

### 10.2 阶段二：文档切片

使用**中文优化的递归分割器** + **父子分块策略**：

```python
# core/rag/chunker.py
class ChineseRecursiveTextSplitter(RecursiveCharacterTextSplitter):
    # 分割优先级（从大到小）
    _CHINESE_SEPARATORS = [
        "\n\n",                # 段落
        "\n",                  # 换行
        "。", "！", "？",       # 句子结束
        "；",                  # 分号
        "，",                  # 逗号
        "、",                  # 顿号
        " ",                   # 空格
        "",                    # 单字符
    ]
```

**父子分块（Parent-Child Chunking）：**

```
原始文档
  → Parent Chunk（800 字符，给 LLM 看的上下文）
    → Child Chunk（200 字符，用于向量检索）
      → 建立映射关系

检索时：命中 Child → 返回 Parent（保证上下文完整）
```

**为什么要父子分块？** 小块检索精度高，但上下文不完整；大块上下文完整，但检索精度低。父子分块取两者之长：用小粒度检索，用大粒度返回。

### 10.3 阶段三：混合检索

项目实现了三种检索器，在 `retriever.py` 一个文件中：

#### BM25 关键词检索

```
原始文档 → jieba 分词 → 构建倒排索引 → BM25Okapi 算法
用户查询 → jieba 分词 → BM25 计算得分 → 按得分降序取 top_k
```

**特点：** 精确关键词匹配。搜"微服务"一定命中包含"微服务"的文档。

#### 向量语义检索

```
原始文档 → 切片 → BGE-M3 转为向量 → 存入 Chroma
用户查询 → BGE-M3 转为向量 → Chroma 相似度搜索 → 返回 top_k
```

**特点：** 语义理解。搜"服务间通信"能命中"RPC 调用"、"消息队列"。

#### RRF 融合算法

两路检索结果通过 RRF（Reciprocal Rank Fusion）合并排名：

```
RRF_score(文档) = 1/(60 + BM25排名) + 1/(60 + 向量排名)
```

**举例：** 文档 A 在 BM25 排第 1、向量排第 3，文档 B 在 BM25 排第 2、向量排第 2：

```
score(A) = 1/61 + 1/63 = 0.0323
score(B) = 1/62 + 1/62 = 0.0322
→ A 排第一
```

**为什么用 RRF？** BM25 得分和向量相似度得分的量纲不同，直接加权很难调参。RRF 只关心排名，天然跨检索器可比。

#### 三种检索器对比

| 维度 | BM25 关键词 | 向量语义 | 混合检索 |
|------|------------|---------|---------|
| 原理 | 词频统计 | 向量相似度 | 两者 + RRF |
| 分词 | jieba | 不需要 | 两者 |
| 嵌入模型 | 无 | BGE-M3 | 两者 |
| 存储 | 内存 | Chroma/Milvus | 两者 |
| 精确匹配 | 强 | 弱 | 强 |
| 语义理解 | 无 | 强 | 强 |

### 10.4 阶段四：缓存系统

Redis 缓存热点查询结果，减少 LLM 重复调用：

```python
# core/rag/cache.py
class RedisCache:
    def get(self, key: str) -> Optional[Any]:
        """从缓存读取，自动 JSON 反序列化。"""
        if not self._available:  # Redis 不可用时自动降级
            return None
        value = self.client.get(self._key(key))
        return json.loads(value) if value else None

    def set(self, key: str, value: Any, ttl: int = 3600) -> bool:
        """写入缓存，自动 JSON 序列化，默认 1 小时过期。"""
        if not self._available:
            return False
        self.client.setex(self._key(key), ttl, json.dumps(value, ensure_ascii=False))
        return True
```

**自动降级：** Redis 不可用时，缓存读取返回 `None`，写入静默失败，不影响主流程。

### 10.5 阶段五：RAG 问答编排

RAG 问答的完整流程整合在 `RAGService` 中：

```python
# app/services/rag_service.py
class RAGService:
    async def ask(self, question, thread_id="", use_hybrid=True, top_k=5):
        # 1. 问题分类
        classification = classify_query(question)

        # 2. 策略选择
        strategy, note = select_strategy(classification, question)

        # 3. 检索上下文（仅专业知识需要）
        context = ""
        if strategy == RetrievalStrategy.HYBRID_SEARCH:
            context = self._retriever.search_as_context(question, top_k, use_hybrid)

        # 4. 构建消息（上下文拼入 Prompt）
        messages = [{"role": "user", "content": question}]
        if context:
            messages = [
                {"role": "system", "content": f"参考以下知识库内容回答问题:\n\n{context}"},
                {"role": "user", "content": question},
            ]

        # 5. 调用 Agent
        result = await self._agent.ainvoke({"messages": messages}, config=config)

        # 6. 缓存热点查询
        if len(question) < 200:
            self._cache.set(f"rag:answer:{question}", answer, ttl=3600)

        return {"thread_id": thread_id, "answer": answer, "sources": sources}
```

### 10.6 阶段六：Ragas 评估

使用 Ragas 框架从 5 个维度评估 RAG 质量：

| 指标 | 含义 | 检测什么 |
|------|------|---------|
| Context Precision | 检索上下文精确度 | 检索到的文档中噪音比例 |
| Context Recall | 检索上下文召回率 | 是否检索到了必要信息 |
| Faithfulness | 答案忠实度 | 答案是否忠于检索上下文（幻觉检测） |
| Answer Relevancy | 答案相关性 | 答案是否与问题相关 |
| Answer Correctness | 答案正确性 | 答案本身是否正确 |

---

## 十一、记忆系统

项目实现了**两级记忆**：短时记忆（对话历史）和长期记忆（跨会话用户信息）。

### 11.1 短时记忆：Checkpointer

负责同一 `thread_id` 内的对话历史管理：

```python
# core/memory.py
if settings.memory_type == "postgres":
    from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
    checkpointer = AsyncPostgresSaver.from_conn_string(settings.postgres_uri)
else:
    from langgraph.checkpoint.memory import InMemorySaver
    checkpointer = InMemorySaver()
```

**工作原理（全自动，无需手动管理）：**

```
第1轮: thread_id="qa-xxx"
  Q: "我叫张三" → A: "好的张三"
  → LangGraph 自动把状态写入 checkpointer

第2轮: 同一 thread_id
  Q: "我叫什么？" → A: "你叫张三"  ✅ 自动加载历史

第3轮: 新 thread_id="qa-yyy"
  Q: "我叫什么？" → A: "不知道"    ✅ 隔离不同会话
```

**两种存储模式：**

| 维度 | InMemorySaver（默认） | AsyncPostgresSaver |
|------|----------------------|-------------------|
| 存储位置 | Python 进程内存 | PostgreSQL `checkpoints` 表 |
| 生命周期 | 重启即丢失 | 持久化，重启不丢 |
| 性能 | 最快（纯内存） | 有 IO 开销 |
| 并发 | 单进程内安全 | 多进程/多实例共享 |
| 适用场景 | 本地开发、测试 | 生产环境 |

### 11.2 长期记忆：Store

负责跨会话的用户信息持久化（"记住用户是谁"）：

```python
# core/memory.py
if settings.store_type == "postgres":
    from langgraph.store.postgres import AsyncPostgresStore
    store = AsyncPostgresStore.from_conn_string(settings.postgres_uri)
else:
    from langgraph.store.memory import InMemoryStore
    store = InMemoryStore()
```

**使用方式：** Agent 通过 4 个记忆工具（`save_user_info`、`get_user_info`、`forget_user_info`、`list_user_memories`）自动操作 Store，用户无需手动调用。

**配置切换（`.env`）：**

```bash
# 默认：两者都在内存，重启全丢
MEMORY_TYPE=in_memory
STORE_TYPE=in_memory

# 生产环境：两者都持久化
MEMORY_TYPE=postgres
STORE_TYPE=postgres
POSTGRES_URI=postgresql://user:pass@localhost:5432/agent_db
```

`memory_type` 和 `store_type` 是独立控制的，可以组合使用。

---

## 十二、API 接口实现

### 12.1 SSE 流式输出

SSE（Server-Sent Events）是 AI 应用实现"打字机效果"的标准方案。核心链路：

```
用户 POST 请求
  → Router 接收请求
    → Service 调用 agent.astream_events() 逐 token yield
      → Router 包装成 "data: {token}\n\n" 格式
        → StreamingResponse 发送给客户端
```

#### Service 层：异步生成器

```python
# app/services/rag_service.py
async def ask_stream(self, question, thread_id="", ...) -> AsyncGenerator[str, None]:
    # 1. 分类 + 策略
    classification = classify_query(question)
    strategy, _ = select_strategy(classification, question)

    # 2. 检索
    context = ""
    if strategy == RetrievalStrategy.HYBRID_SEARCH:
        context = self._retriever.search_as_context(question, top_k, use_hybrid)

    # 3. 流式调用 Agent
    messages = [{"role": "user", "content": question}]
    if context:
        messages = [{"role": "system", "content": f"参考以下知识库内容:\n\n{context}"},
                    {"role": "user", "content": question}]

    async for event in self._agent.astream_events(
        {"messages": messages}, config=config, version="v2"
    ):
        if event["event"] == "on_chat_model_stream":
            content = event["data"]["chunk"].content
            if content:
                yield content  # ← 每次 yield 一个 token
```

#### Router 层：SSE 包装

```python
# app/routers/rag.py
@router.post("/v1/rag/ask/stream")
async def rag_ask_stream(req: RAGRequest, service: RAGService = Depends(get_rag_service)):
    async def event_generator():
        async for token in service.ask_stream(question=req.question, ...):
            yield f"data: {token}\n\n"   # SSE 格式
        yield "data: [DONE]\n\n"         # 流结束信号

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # 禁用 Nginx 缓冲
        },
    )
```

**SSE 协议格式：** 每条消息以 `data: ` 开头，以 `\n\n`（双换行）结束。双换行是消息边界。

**`astream_events` vs `astream`：**
- `astream` 返回的是每个节点执行完后的**完整状态快照**，不是逐 token
- `astream_events` 返回的是**细粒度事件流**，能捕获 LLM 每次输出一个 token 的瞬间

#### 前端处理 SSE

```javascript
async function askStream(question) {
  const response = await fetch("http://localhost:8000/v1/rag/ask/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() || "";  // 最后一段不完整，留在缓冲区

    for (const line of lines) {
      if (line.startsWith("data: ")) {
        const data = line.slice(6);
        if (data === "[DONE]") return;
        if (data.startsWith("[ERROR]")) throw new Error(data);
        document.getElementById("output").textContent += data;  // 逐 token 渲染
      }
    }
  }
}
```

**为什么需要 `buffer`？** TCP 是字节流，一次 `read()` 拿到的数据可能被截断。必须用缓冲区把不完整的最后一段留下来，等下次读取时拼接。

### 12.2 WebSocket 实时通信

适用于需要双向实时通信的场景（如对话式 UI）：

```python
# app/routers/ws.py
@router.websocket("/ws/chat/{client_id}")
async def websocket_chat(websocket: WebSocket, client_id: str):
    await manager.connect(client_id, websocket)

    try:
        while True:
            data = await websocket.receive_text()
            message = json.loads(data)

            if message.get("type") == "question":
                question = message.get("content", "")
                # 流式推送答案
                async for token in rag_service.ask_stream(question=question, ...):
                    await websocket.send_json({"type": "token", "content": token})
                await websocket.send_json({"type": "done", "thread_id": thread_id})

    except WebSocketDisconnect:
        manager.disconnect(client_id)
```

**消息格式：**

| 方向 | 消息 |
|------|------|
| 客户端 → 服务端 | `{"type": "question", "content": "...", "thread_id": ""}` |
| 服务端 → 客户端 | `{"type": "token", "content": "..."}` |
| 服务端 → 客户端 | `{"type": "done", "thread_id": "..."}` |
| 心跳 | `{"type": "ping"}` / `{"type": "pong"}` |

### 12.3 文件上传与即时问答

```python
# app/routers/file_up_content.py
@router.post("/v1/rag/documents/upload")
async def upload_document(file: UploadFile = File(...)):
    # 1. 保存文件
    file_path = save_uploaded_file(file)
    # 2. 加载文档
    doc = load_document(file_path)
    # 3. 切片
    chunks = splitter.split_text(doc.content)
    # 4. 写入向量库
    retriever.add_documents(chunks)
    return ApiResponse.success(data={"chunks": len(chunks)})
```

---

## 十三、安全与中间件

### 13.1 中间件链

FastAPI 中间件按注册顺序执行（洋葱模型）：

```python
# app/main.py
# 1. CORS（允许前端跨域）
app.add_middleware(CORSMiddleware, allow_origins=["*"], ...)

# 2. API Key 认证（可选）
app.middleware("http")(verify_api_key)

# 3. 请求日志（记录所有接口调用）
app.middleware("http")(request_logging_middleware)
```

### 13.2 API Key 认证

可选认证，通过 `.env` 控制：

```python
async def verify_api_key(request: Request, call_next):
    # 白名单路径直接放行
    if request.url.path in ("/health", "/ready", "/docs", "/openapi.json"):
        return await call_next(request)

    # 如果设置了 API_KEY 则要求认证
    api_key = getattr(settings, "api_key", "")
    if api_key:
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or auth.removeprefix("Bearer ") != api_key:
            raise HTTPException(status_code=401, detail="Invalid or missing API Key")

    return await call_next(request)
```

**设计要点：**
- **白名单机制：** `/health`、`/docs` 等路径直接放行，方便 K8s 探活和 Swagger 调试
- **可选认证：** `api_key` 为空时所有请求放行（本地开发）；设置后启用认证（生产环境）
- **生产建议：** 当前是静态 API Key 方案，适合内部服务间调用。如需用户级鉴权，应升级为 OAuth2 / JWT

### 13.3 请求日志中间件

记录每个接口的请求和响应，方便排查问题：

```python
async def request_logging_middleware(request: Request, call_next):
    start_time = time.time()
    logger.info("→ %s %s | client=%s", request.method, request.url.path, ...)

    response = await call_next(request)

    duration_ms = (time.time() - start_time) * 1000
    logger.info("← %s %s | status=%s | duration=%.2fms", ...)
    return response
```

---

## 十四、部署与运维

### 14.1 Docker 部署

```bash
# 使用 docker-compose 一键启动
docker-compose up -d

# 包含的服务：
# - service_agent API（端口 8000）
# - PostgreSQL（可选，持久化记忆）
```

### 14.2 环境变量注入

生产环境通过环境变量注入配置，不需要 `.env` 文件：

```bash
docker run -d \
  -e DEEPSEEK_API_KEY=sk-xxx \
  -e MEMORY_TYPE=postgres \
  -e POSTGRES_URI=postgresql://user:pass@host:5432/agent_db \
  -e API_KEY=my-secret-key \
  -p 8000:8000 \
  service_agent:latest
```

### 14.3 健康检查

K8s 探活配置：

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8000
readinessProbe:
  httpGet:
    path: /ready
    port: 8000
```

### 14.4 LangSmith 追踪

调试 Agent 行为时启用 LangSmith 追踪：

```bash
# .env
LANGSMITH_TRACING=true
LANGSMITH_API_KEY=ls_xxxxxxxx
```

启动后，所有 Agent 调用链（工具调用、LLM 推理、状态变化）都会被记录到 LangSmith 平台。

---

## 附录：核心概念速查

| 概念 | 一句话解释 | 在这个项目中的位置 |
|------|-----------|------------------|
| **Agent** | LLM + 工具 + 提示词 + 记忆，能自主决策调用工具 | `core/agent.py` |
| **Tool** | Agent 的"手脚"，`@tool` 装饰器定义 | `core/tool/tools.py` |
| **RAG** | 检索增强生成，先查知识库再回答 | `core/rag/` 整个目录 |
| **BM25** | 关键词检索算法，基于词频和逆文档频率 | `core/rag/retriever.py` |
| **BGE-M3** | BAAI 开发的文本嵌入模型，文本转向量 | `core/rag/retriever.py` |
| **Chroma** | 向量数据库，存储和检索向量 | `core/rag/retriever.py` |
| **RRF** | 倒数排名融合算法，合并多路检索结果 | `core/rag/retriever.py` |
| **Checkpointer** | 短时记忆，保存同一 thread_id 的对话历史 | `core/memory.py` |
| **Store** | 长期记忆，跨会话持久化用户信息 | `core/memory.py` |
| **SSE** | 服务端推送事件，实现流式输出 | `app/routers/rag.py` |
| **BERT** | 预训练语言模型，这里用于问题分类 | `core/rag/classifier.py` |
| **Ragas** | RAG 评估框架，5 维度打分 | `core/rag/evaluate.py` |
| **Pydantic Settings** | 配置管理，类型安全 + 环境变量自动映射 | `config/settings.py` |
| **Depends** | FastAPI 依赖注入，类比 Spring @Autowired | `app/dependencies.py` |

---

> **学习路径建议：**
> 1. 先看 [项目概览](#一项目概览) 和 [快速上手](#二快速上手从零搭建)，把项目跑起来
> 2. 理解 [分层架构](#四分层架构) 和 [配置系统](#三配置系统)
> 3. 深入 [Agent 系统](#七agent-系统) 和 [工具系统](#八工具系统)，理解 Agent 是怎么"干活"的
> 4. 掌握 [RAG 全流程](#十rag-全流程)，这是 AI 应用最核心的能力
> 5. 学习 [API 接口实现](#十二api-接口实现)，理解 SSE 流式输出和 WebSocket
> 6. 最后看 [部署与运维](#十四部署与运维)，把项目部署到生产环境