# My Agents — 生产级多 Agent 智能体服务

基于 **FastAPI + LangChain + LangGraph** 构建的多 Agent 服务，提供通用聊天、智能路由、代码审查、RAG 知识库问答、微服务编排等 AI 智能体能力。

---

## 项目架构

项目采用类 Spring 的分层架构（Controller → Service → Core），各层职责清晰：

```
┌──────────────────────────────────────────────────────────────────┐
│                         FastAPI 服务                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │   Router      │  │   Service    │  │  WebSocket   │           │
│  │  (routers/)   │→ │  (services/) │  │  (ws.py)     │  ── 路由层 │
│  │  chat/rag/qa  │  │  agent/rag   │  │  双向实时通信  │           │
│  └──────────────┘  └──────┬───────┘  └──────────────┘           │
│                           │                                       │
│                    ┌──────▼──────────────────────────┐          │
│                    │          Core 业务核心层          │          │
│                    │  agent/  tool/  models/  memory  │          │
│                    │  prompts/  text_utils/           │          │
│                    │  ┌────────────────────────────┐  │          │
│                    │  │     RAG 模块 (core/rag/)    │  │          │
│                    │  │  retriever  chunker  cache  │  │          │
│                    │  │  classifier strategy        │  │          │
│                    │  │  document/  evaluate        │  │          │
│                    │  └────────────────────────────┘  │          │
│                    └─────────────────────────────────┘          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │   Config     │  │   Schemas    │  │ Dependencies │           │
│  │  (settings)  │  │  (DTO 模型)  │  │  (DI 装配)   │           │
│  └──────────────┘  └──────────────┘  └──────────────┘           │
└──────────────────────────────────────────────────────────────────┘
```

### Spring 类比对照

| 本项目 | Spring 类比 | 职责 |
|--------|------------|------|
| `app/main.py` | `@SpringBootApplication` | 应用入口，注册中间件和路由 |
| `app/routers/chat.py` | `@RestController` | 路由分发，不包含业务逻辑 |
| `app/services/agent_service.py` | `@Service` | 业务逻辑，通过 `Depends()` 注入 |
| `app/schemas/chat.py` | DTO | 请求/响应数据结构 |
| `app/dependencies.py` | `@Configuration` + `@Bean` | 依赖装配，管理 Bean 生命周期 |
| `config/settings.py` | `application.yml` | 集中配置管理 |
| `core/agent.py` | `@Bean` 工厂 | 创建 Agent 实例 |
| `core/tool/` | 工具类 | 定义 Agent 可调用的工具 |
| `core/models.py` | 数据源配置 | 初始化 LLM 模型 |

---

## 目录结构

```
├── app/                              # 应用层（FastAPI Web 层）
│   ├── main.py                       # FastAPI 入口 + 中间件 + 路由注册
│   ├── dependencies.py               # 依赖注入装配（Bean 工厂）
│   ├── routers/                      # 路由层（Controller）
│   │   ├── chat.py                   # 聊天/Agent 调用端点（智能路由）
│   │   ├── rag.py                    # RAG 知识库问答 + 评估
│   │   ├── qa_router.py             # 问答端点
│   │   ├── ws.py                     # WebSocket 双向实时通信
│   │   ├── health.py                 # 健康检查端点
│   │   └── file_up_content.py       # 文件上传端点
│   ├── services/                     # 服务层（Service）
│   │   ├── agent_service.py          # Agent 调用/流式/智能路由
│   │   ├── rag_service.py            # RAG 业务编排
│   │   ├── qa_service.py             # QA 服务
│   │   └── upload_file_service.py   # 文件上传服务
│   └── schemas/                      # 数据模型层（DTO）
│       ├── chat.py                   # ChatRequest / ChatResponse
│       ├── rag.py                    # RAGRequest / RAGResponse
│       ├── qa.py                     # QARequest / QAResponse
│       ├── common.py                 # ApiResponse 统一响应格式
│       └── file_and_ask.py          # 文件上传相关模型
│
├── core/                             # 核心业务逻辑层
│   ├── agent.py                      # Agent 工厂：3 个 Agent
│   ├── models.py                     # LLM 模型初始化
│   ├── memory.py                     # 记忆系统（短时记忆 + 长时记忆）
│   ├── text_utils.py                 # jieba 中文分词工具
│   ├── dicts/                        # 自定义词典
│   │   └── user_dict.txt             # AI 领域专业词汇
│   ├── tool/                         # 工具定义
│   │   ├── tools.py                  # 搜索 + 代码审查 + 向量库检索
│   │   ├── memory_tools.py           # 长期记忆工具（增删改查）
│   │   └── api_tools.py              # 微服务调用工具（安全网关）
│   ├── prompts/                      # 提示词模块
│   │   ├── __init__.py               # 统一出口
│   │   ├── builders.py               # Prompt 构建工厂
│   │   ├── loader.py                 # 模板加载器（LRU 缓存）
│   │   └── templates/                # 模板文件
│   │       ├── research.md           # 研究助手提示词
│   │       ├── code_review.md        # 代码审查提示词
│   │       └── rag.md                # RAG 知识库问答提示词
│   └── rag/                          # RAG 核心模块
│       ├── retriever.py              # 混合检索器（BM25 + 向量 + RRF）
│       ├── chunker.py                # 中文分割 + 父子分块
│       ├── cache.py                  # Redis 缓存层
│       ├── classifier.py             # BERT 问题分类器
│       ├── strategy.py               # 检索策略选择器
│       ├── evaluate.py               # Ragas 评估框架
│       └── document/                 # 文档处理
│           ├── loader.py             # 统一加载器（PDF/PPT/Word/图片）
│           └── ocr.py                # OCR 文字识别
│
├── config/                           # 配置层
│   └── settings.py                   # Pydantic Settings（50+ 配置项）
│
├── utils/                            # 工具函数
│   └── file_util.py                  # 文件工具函数
│
├── tests/                            # 测试
│   ├── test_agent.py                 # Agent 单元测试
│   ├── test_api.py                   # API 集成测试
│   ├── test_rag.py                   # RAG 模块测试
│   └── rag_evaluate_data.json        # RAG 评估数据集
│
├── install.ps1                       # 一键安装脚本
├── .env.example                      # 环境变量模板
├── pyproject.toml                    # 项目依赖管理（9 组可选依赖）
├── Dockerfile                        # 容器化构建
└── docker-compose.yml                # 容器编排
```

---

## 核心概念

### 1. Agent（智能体）

基于 `langchain.agents.create_agent`（LangGraph 实现）构建，目前有三个 Agent：

| Agent | 工具 | 提示词 | 用途 |
|-------|------|--------|------|
| `research_agent` | web_search + memory + api_tools + kb_search（可选） | research.md | 通用研究/问答/微服务编排 |
| `code_reviewer_agent` | code_review + memory | code_review.md | 代码审查 |
| `rag_agent` | kb_search（优先）+ web_search + memory + api_tools | rag.md | RAG 知识库问答 |

Agent 自带 **记忆系统**（Checkpointer + Store），支持：
- **短时记忆（Checkpointer）**：同一 thread_id 内的对话历史，支持内存模式（默认）和 Postgres 持久化
- **长时记忆（Store）**：跨会话的用户信息持久化（如偏好、技术栈），支持内存模式和 Postgres 持久化

### 2. 智能路由

```
用户问题
    ↓
BERT 分类器（5 分类）
    ├── 问候 → 直接回复
    ├── 代码审查 → code_reviewer_agent
    ├── 专业知识 → 策略选择器
    │       ├── MySQL 可用 → 精确匹配 + 混合检索
    │       ├── 向量库可用 → 混合检索（BM25 + 向量 + RRF）
    │       └── 都不可用 → 降级为直接 LLM
    ├── 向量搜索 → 向量语义检索
    └── 通用知识 → research_agent
```

分类器支持 BERT 模型推理和规则回退两种模式，BERT 不可用时自动降级为规则匹配。

### 3. 工具（Tools）

所有工具均为真实实现，按模块组织在 `core/tool/` 目录下。

#### 3.1 内部工具

| 工具 | 文件 | 实现方式 | 说明 |
|------|------|---------|------|
| `web_search` | tools.py | DuckDuckGo + Tavily 双引擎 | 免费默认，Tavily 可选高质量搜索，自动降级 |
| `code_review` | tools.py | LLM（deepseek_model） | 覆盖 Bug/安全/性能/风格/建议 5 维度 |
| `kb_search` | tools.py | Chroma 向量库（可选） | 向量语义检索，依赖 BGE-M3 + fastembed |

#### 3.2 长期记忆工具

| 工具 | 说明 |
|------|------|
| `save_user_info` | 保存用户信息到长期记忆 |
| `get_user_info` | 读取用户长期记忆 |
| `forget_user_info` | 删除用户记忆 |
| `list_user_memories` | 列出所有记忆条目 |

使用 LangGraph 的 `InjectedStore` 机制，Agent 调用时自动注入 Store 实例。

#### 3.3 微服务调用工具（安全网关）

遵循 **LLM 不接触凭证** 的安全原则：

| 工具 | 微服务 | 说明 |
|------|--------|------|
| `create_order` | 订单服务 | 创建订单 |
| `query_order` | 订单服务 | 查询订单详情 |
| `cancel_order` | 订单服务 | 取消订单 |
| `check_inventory` | 库存服务 | 查询商品库存 |
| `query_user_profile` | 用户服务 | 查询用户信息 |
| `send_notification` | 通知服务 | 发送消息通知 |

**安全架构：**

```
用户 → FastAPI → Agent(LLM) → Tool 包装器 → httpx → 微服务(MySQL/Redis/业务)
                   ↑              ↑                      ↑
              只看到工具描述    注入 API Token          真正的权限边界
              看不到凭证/URL    不暴露给 LLM            鉴权/校验/限流
```

关键设计原则：
- **LLM 不知道 API 地址**：微服务 URL 从 `settings.py` 读取，工具描述中不暴露
- **LLM 不知道鉴权 Token**：`internal_api_token` 在 `_request()` 内部注入，不通过工具参数传递
- **微服务侧做校验**：参数合法性、权限控制、业务规则全部在微服务侧完成
- **工具失败友好返回**：异常捕获后返回字符串，不抛异常，让 LLM 可继续处理

### 4. RAG（检索增强生成）

完整的 RAG 技术栈，覆盖从文档加载到评估的全链路：

```
文档加载（PDF/PPT/Word/图片/OCR）
    ↓
文本分割（中文递归 + 父子分块）
    ↓
向量索引（Chroma / Milvus）
    ↓
用户提问 → BERT 分类 → 策略选择
    ↓
混合检索（BM25 + 向量 + RRF 融合）
    ↓
构建 Prompt（上下文 + 问题）
    ↓
LLM 生成 → Redis 缓存 → 返回答案
    ↓
Ragas 评估（5 维度）
```

#### 4.1 混合检索器

| 组件 | 技术 | 说明 |
|------|------|------|
| 关键词检索 | BM25 + jieba 分词 | 精确匹配，处理专业术语 |
| 语义检索 | BGE-M3 + Chroma/Milvus | 语义理解，处理同义表达 |
| 融合排序 | RRF (Reciprocal Rank Fusion) | 双路结果融合，取长补短 |

#### 4.2 父子分块

| 概念 | 大小 | 用途 |
|------|------|------|
| Parent Chunk | 800 字符 | 返回给 LLM 的完整上下文 |
| Child Chunk | 200 字符 | 向量检索的精确粒度 |

#### 4.3 文档处理

| 格式 | 依赖 | 说明 |
|------|------|------|
| PDF | pdfplumber / PyPDF2 | 含表格提取 |
| Word | python-docx | 含表格提取 |
| PPT | python-pptx | 按页提取 |
| 图片 | PaddleOCR / Tesseract / EasyOCR | 三引擎自适应 |
| 纯文本 | 内置 | .txt / .md |

#### 4.4 检索策略

| 策略 | 触发条件 | 行为 |
|------|---------|------|
| `direct_llm` | 通用知识 | 直接调用 LLM |
| `hybrid_search` | 专业知识 | BM25 + 向量 + RRF |
| `mysql_exact` | 专业知识 + MySQL 可用 | 先精确匹配，再混合检索 |
| `code_review` | 代码审查请求 | code_reviewer_agent |
| `greeting` | 问候/闲聊 | 直接回复 |

#### 4.5 缓存与评估

- **Redis 缓存**：热点查询结果缓存（TTL 1 小时），支持 `@cache_result` 装饰器，Redis 不可用时自动降级
- **Ragas 评估**：Context Precision / Recall / Faithfulness / Answer Relevancy / Correctness 五维度

### 5. 中文分词

集成 jieba 分词，提供以下能力：

| 函数 | 说明 |
|------|------|
| `jieba_tokenize()` | 精确/全模式分词 |
| `jieba_cut_for_search()` | 搜索引擎模式（长词二次切分） |
| `jieba_extract_keywords()` | TF-IDF 关键词提取 |
| `jieba_textrank_keywords()` | TextRank 关键词提取 |
| `jieba_tokenize_cached()` | LRU 缓存分词 |
| `load_user_dict()` | 自定义词典加载 |

### 6. 配置管理

采用 **settings.py 定义 + .env 覆盖** 的模式，共 50+ 配置项：

| 分类 | 配置项 | 说明 |
|------|-------|------|
| LLM | deepseek_api_key, llm_model, temperature, timeout | 模型配置 |
| Server | host, port, debug, api_prefix | 服务配置 |
| Memory | memory_type, store_type, postgres_uri | 记忆持久化 |
| Vector Store | chroma_persist_dir, embedding_model, milvus_host, milvus_port | 向量库配置 |
| Redis | host, port, db, password | 缓存配置 |
| MySQL | host, port, user, password, database | 数据库配置 |
| Microservices | 订单/库存/用户/通知服务地址 + internal_api_token | 微服务网关 |
| Tools | search_provider, tavily_api_key, duckduckgo_enabled | 搜索工具 |
| RAG | hybrid_search, top_k, rerank_enabled, bert_model_name | RAG 配置 |
| Security | api_key | API 认证 |
| LangSmith | langsmith_tracing, langsmith_api_key | 链路追踪 |

### 7. 请求日志与监控

项目内置双层日志监控：

| Logger 名称 | 用途 | 记录内容 |
|-------------|------|---------|
| `api.request` | FastAPI 接口请求/响应 | 方法、路径、客户端IP、查询参数、响应状态码、耗时(ms) |
| `microservice.call` | 微服务调用 | HTTP 方法、完整URL、响应状态码、耗时(ms) |
| `__name__`（默认） | 应用生命周期与异常 | 服务启动/停止、未处理异常（含堆栈） |

完整链路示例：

```
api.request       | → POST /v1/chat | client=127.0.0.1 | query=-
microservice.call | → GET http://localhost:9001/api/orders/ORD-001
microservice.call | ← GET http://localhost:9001/api/orders/ORD-001 | status=200 | duration=45ms
api.request       | ← POST /v1/chat | status=200 | duration=2420ms
```

---

## 快速开始

### 前置条件

- Python >= 3.12
- 有效的 DeepSeek API Key（[获取](https://platform.deepseek.com/)）

### 1. 克隆并配置

```bash
# 复制环境变量模板
cp .env.example .env

# 编辑 .env，填入 API Key
# DEEPSEEK_API_KEY=sk-your-key-here

# 可选：配置微服务地址（如需使用微服务调用工具）
# ORDER_SERVICE_URL=http://localhost:9001
# INVENTORY_SERVICE_URL=http://localhost:9002
# USER_SERVICE_URL=http://localhost:9003
# NOTIFICATION_SERVICE_URL=http://localhost:9004
# INTERNAL_API_TOKEN=your-internal-token
```

### 2. 安装依赖

```powershell
# Windows PowerShell（一键安装）
.\install.ps1              # 安装全部依赖
.\install.ps1 -Mode core   # 仅核心依赖
.\install.ps1 -Mode dev    # 开发环境
```

或手动安装：

```bash
# 使用 uv（推荐）
uv sync

# 含 RAG 能力
uv sync --group dev

# 或使用 pip
pip install -e .
```

可选依赖组：

| 组名 | 用途 |
|------|------|
| `rag` | 向量检索（Chroma + BGE-M3） |
| `postgres` | Postgres 持久化记忆 |
| `search` | Tavily 高质量搜索 |
| `documents` | 文档处理（PDF/Word/PPT/OCR） |
| `redis` | Redis 缓存 |
| `evaluate` | Ragas 评估框架 |
| `classify` | BERT 问题分类 |
| `retrieval` | BM25 关键词检索 |

### 3. 启动服务

```bash
# 方式一：直接运行
uv run python -m app.main

# 方式二：uvicorn
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

# 方式三：Docker
docker compose up -d
```

服务启动后访问：
- API 文档：http://localhost:8000/docs
- 健康检查：http://localhost:8000/health

---

## API 参考

所有 API 端点（除健康检查和 WebSocket 外）统一使用 `/v1` 前缀。

### 健康检查

```bash
GET /health       # 存活检查
GET /ready        # 就绪检查（容器编排用）
```

### 聊天（智能路由）

```json
POST /v1/chat
POST /v1/chat/stream

{
  "messages": [{"role": "user", "content": "帮我查一下订单 ORD-001 的状态"}],
  "thread_id": "",
  "stream": false
}
```

- 自动通过 BERT 分类器 + 策略选择器路由到最优 Agent
- Agent 可通过微服务调用工具查询订单、库存、用户信息等
- 流式模式返回 SSE（Server-Sent Events）格式

### 指定 Agent

```json
POST /v1/research    → research_agent（含微服务调用能力）
POST /v1/review      → code_reviewer_agent

{
  "messages": [{"role": "user", "content": "..."}],
  "thread_id": "",
  "stream": false
}
```

### RAG 知识库问答

```json
POST /v1/rag/ask
POST /v1/rag/ask/stream

{
  "question": "什么是检索增强生成？",
  "thread_id": "",
  "use_hybrid": true,
  "top_k": 5,
  "stream": false
}
```

### RAG 评估

```json
POST /v1/rag/evaluate

{
  "samples": [
    {
      "question": "什么是RAG？",
      "ground_truth": "RAG是检索增强生成...",
      "answer": "RAG结合了检索和生成...",
      "contexts": ["RAG将检索系统..."]
    }
  ]
}
```

### 问答

```json
POST /v1/qa

{
  "question": "什么是依赖倒置原则？",
  "thread_id": "",
  "mode": "normal"
}
```

### WebSocket

```
ws://localhost:8000/ws/chat/{client_id}

消息格式:
  发送: {"type": "question", "content": "你好", "thread_id": ""}
  接收: {"type": "token", "content": "你"}
        {"type": "done", "thread_id": "ws-abc123"}
  心跳: {"type": "ping"} → {"type": "pong"}
```

---

## Docker 部署

```bash
# 构建并启动
docker compose up -d --build

# 查看日志
docker compose logs -f

# 停止
docker compose down
```

### Postgres 持久化记忆

编辑 `docker-compose.yml`，取消 `db` 服务的注释，并添加环境变量：

```yaml
environment:
  - MEMORY_TYPE=postgres
  - STORE_TYPE=postgres
  - POSTGRES_URI=postgresql://langgraph:changeme@db:5432/langgraph
```

---

## 开发指南

### 添加新 Agent

1. 在 `core/prompts/templates/` 下创建 `xxx.md` 提示词模板
2. 在 `core/prompts/builders.py` 中添加 `build_xxx_prompt()`
3. 在 `core/prompts/__init__.py` 中导出
4. 在 `core/agent.py` 中使用 `create_agent()` 创建实例
5. 在 `app/routers/` 中添加路由端点

### 添加新工具

1. 在 `core/tool/` 下使用 `@tool` 装饰器定义函数
2. 将其加入对应 Agent 的 tools 列表（`core/agent.py`）

### 添加微服务调用工具

1. 在 `core/tool/api_tools.py` 中定义新工具（使用 `@tool` + `_request()` 封装）
2. 在 `config/settings.py` 中添加对应的微服务地址配置
3. 该工具会自动加入 `api_tools` 列表，所有 Agent 即刻可用

安全注意事项：
- 微服务地址和鉴权 Token 从 `settings.py` 读取，**不要在工具描述中暴露**
- 鉴权 Token 在 `_request()` 内部注入，**不要作为工具参数传递**
- 所有敏感校验由微服务侧完成，**不在工具中实现业务校验**

### 替换搜索引擎

```bash
# 使用 Tavily 高质量搜索（需 API Key）
SEARCH_PROVIDER=tavily
TAVILY_API_KEY=tvly-xxx

# 使用 DuckDuckGo 免费搜索（默认）
SEARCH_PROVIDER=duckduckgo
```

### 启用完整 RAG 能力

```bash
# 1. 安装全部可选依赖
uv sync --group dev

# 2. 上传文档到知识库后开始 RAG 问答
curl -X POST http://localhost:8000/v1/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "文档中讲了什么？"}'
```

### 运行测试

```bash
# 全部测试
pytest tests/ -v

# 分类测试
pytest tests/test_agent.py -v     # Agent + jieba
pytest tests/test_api.py -v       # API 集成
pytest tests/test_rag.py -v       # RAG 模块
```

---

## 技术栈

| 组件 | 技术 |
|------|------|
| Web 框架 | FastAPI + uvicorn |
| AI 智能体 | LangChain + LangGraph |
| LLM 模型 | DeepSeek（可切换 OpenAI / Anthropic） |
| 配置管理 | Pydantic Settings |
| 中文分词 | jieba |
| 网络搜索 | DuckDuckGo + Tavily（双引擎，自动降级） |
| 微服务调用 | httpx（异步 HTTP 客户端，安全工具网关） |
| 向量模型 | BGE-M3（通过 FastEmbed） |
| 向量数据库 | Chroma（默认）/ Milvus（可选） |
| 关键词检索 | BM25（rank-bm25 + jieba） |
| 文本分割 | ChineseRecursiveTextSplitter + 父子分块 |
| 文档处理 | PDF / Word / PPT / 图片 / OCR |
| OCR | PaddleOCR / Tesseract / EasyOCR |
| 问题分类 | BERT（bert-base-chinese）+ 规则回退 |
| 对话记忆 | InMemorySaver / PostgresSaver（短时记忆） |
| 长期记忆 | InMemoryStore / PostgresStore（跨会话） |
| 缓存 | Redis（带自动降级） |
| 评估 | Ragas（5 维度） |
| 实时通信 | WebSocket + SSE |
| 容器化 | Docker + Docker Compose |
| 链路追踪 | LangSmith（可选） |
| 包管理 | uv |