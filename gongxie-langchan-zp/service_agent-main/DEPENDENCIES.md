# 项目依赖清单

> 项目: my-agents v0.2.0
> Python: >= 3.12
> 生成时间: 2026-08-13

---

## 一、核心依赖（必装）

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `langchain[deepseek,community]` | >=1.3.14 | AI 应用框架（含 DeepSeek 集成 + 社区工具） |
| 2 | `langgraph` | >=1.2.10 | Agent 状态图编排（LangGraph） |
| 3 | `fastapi` | >=0.115.0 | 高性能异步 Web 框架 |
| 4 | `uvicorn[standard]` | >=0.34.0 | ASGI 服务器（含 uvloop + httptools） |
| 5 | `pydantic-settings` | >=2.7.0 | 类型安全配置管理（.env + 环境变量） |
| 6 | `python-dotenv` | >=1.0.0 | .env 文件加载 |
| 7 | `jieba` | >=0.42.1 | 中文分词 |
| 8 | `duckduckgo-search` | >=7.0.0 | DuckDuckGo 免费网络搜索 |

**安装命令**:
```bash
pip install -e .
# 或
uv sync
```

---

## 二、可选依赖（按功能分组）

### 2.1 RAG 向量库 — `[rag]`

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `fastembed` | >=0.5.0 | BGE-M3 等向量模型本地推理 |
| 2 | `langchain-chroma` | >=0.2.0 | Chroma 向量数据库集成 |

```bash
pip install -e ".[rag]"
```

### 2.2 网络搜索（高质量） — `[search]`

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `tavily-python` | >=0.5.0 | Tavily 搜索（需 API Key，质量更高） |

```bash
pip install -e ".[search]"
```

### 2.3 文档处理 — `[documents]`

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `pdfplumber` | >=0.11.0 | PDF 文档解析（含表格提取） |
| 2 | `python-docx` | >=1.0.0 | Word 文档解析 |
| 3 | `python-pptx` | >=1.0.0 | PPT 文档解析 |
| 4 | `paddleocr` | >=2.9.0 | OCR 文字识别（推荐，中文精度高） |
| 5 | `paddlepaddle` | >=3.0.0 | PaddleOCR 深度学习框架依赖 |

```bash
pip install -e ".[documents]"
```

### 2.4 混合检索 — `[retrieval]`

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `rank-bm25` | >=0.2.2 | BM25 关键词检索算法 |

```bash
pip install -e ".[retrieval]"
```

### 2.5 Redis 缓存 — `[redis]`

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `redis` | >=5.0.0 | Redis 缓存客户端 |

```bash
pip install -e ".[redis]"
```

### 2.6 Postgres 持久化记忆 — `[postgres]`

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `langgraph-checkpoint-postgres` | >=2.0.0 | LangGraph Postgres 状态持久化 |
| 2 | `psycopg` | >=3.2.0 | Postgres 数据库驱动 |

```bash
pip install -e ".[postgres]"
```

### 2.7 BERT 问题分类 — `[classify]`

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `transformers` | >=4.40.0 | HuggingFace 模型加载（BERT） |
| 2 | `torch` | >=2.0.0 | 深度学习推理框架 |

```bash
pip install -e ".[classify]"
```

### 2.8 RAG 评估 — `[evaluate]`

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `ragas` | >=0.2.0 | RAG 系统评估框架 |
| 2 | `datasets` | >=3.0.0 | HuggingFace 数据集工具 |

```bash
pip install -e ".[evaluate]"
```

---

## 三、开发依赖

| 序号 | 包名 | 版本要求 | 用途 |
|------|------|---------|------|
| 1 | `my-agents[rag]` | — | 含 RAG 向量库依赖 |
| 2 | `httpx` | >=0.28.1 | 异步 HTTP 客户端（API 测试用） |
| 3 | `pytest` | >=9.1.1 | 测试框架 |
| 4 | `pytest-asyncio` | >=0.24.0 | pytest 异步支持 |

```bash
pip install -e ".[dev]"
# 或
uv sync --group dev
```

---

## 四、全部安装（一键）

```bash
# 所有功能全部启用
pip install -e ".[rag,search,documents,retrieval,redis,postgres,classify,evaluate]"

# 开发环境 + 全部功能
pip install -e ".[rag,search,documents,retrieval,redis,postgres,classify,evaluate]"
pip install httpx pytest pytest-asyncio

# 或使用 uv
uv sync --group dev
uv pip install my-agents[search,documents,retrieval,redis,postgres,classify,evaluate]
```

---

## 五、依赖统计

| 类别 | 数量 |
|------|------|
| 核心依赖 | 8 |
| 可选依赖（8 组） | 17 |
| 开发依赖 | 4 |
| **总计** | **29** |

---

## 六、LangChain 子依赖说明

`langchain[deepseek,community]` 实际安装的包:

| 子包 | 说明 |
|------|------|
| `langchain-core` | 核心抽象（Messages, Tools, Prompts, Runnables） |
| `langchain-deepseek` | DeepSeek 模型集成 |
| `langchain-community` | 社区工具和集成 |
| `langchain-text-splitters` | 文本分割器 |
| `langgraph` | 独立依赖，不在 langchain extras 中 |
| `langgraph-checkpoint` | 状态持久化基础库 |
| `langgraph-prebuilt` | 预构建 Agent（create_react_agent 等） |

---

## 七、按使用场景选择

| 场景 | 需要安装的依赖组 |
|------|-----------------|
| 最小部署（仅对话） | 核心依赖 |
| 启用网络搜索 | + `[search]` |
| 启用 RAG 知识库 | + `[rag, retrieval]` |
| 启用文档处理 | + `[documents]` |
| 启用缓存加速 | + `[redis]` |
| 启用持久化记忆 | + `[postgres]` |
| 启用智能路由 | + `[classify]` |
| 系统评估 | + `[evaluate]` |
| 全功能生产环境 | 全部 |
| 开发环境 | 核心 + `[dev]` |