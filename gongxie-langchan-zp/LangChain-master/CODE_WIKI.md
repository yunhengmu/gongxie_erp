# LangChain 代码 Wiki 文档

> 本文档基于对 LangChain 仓库（`d:\yudao\langchan-gongxie-zp\LangChain-master`）的源代码分析编写，旨在为开发者提供一份结构化的代码导览，覆盖项目整体架构、模块职责、关键类与函数、依赖关系以及运行方式。

---

## 目录

1. [项目概览](#1-项目概览)
2. [仓库整体架构](#2-仓库整体架构)
3. [核心包：`libs/core`（`langchain-core`）](#3-核心包libscore-langchain-core)
4. [主包：`libs/langchain_v1`（`langchain`）](#4-主包libslangchain_v1langchain)
5. [遗留包：`libs/langchain`（`langchain-classic`）](#5-遗留包libslangchainlangchain-classic)
6. [`libs/partners` 第三方集成](#6-libspartners-第三方集成)
7. [`libs/model-profiles` 模型档案工具](#7-libsmodel-profiles-模型档案工具)
8. [关键类与函数详解](#8-关键类与函数详解)
9. [依赖关系与运行机制](#9-依赖关系与运行机制)
10. [运行与开发指南](#10-运行与开发指南)
11. [CI/CD 与发布流程](#11-cicd-与发布流程)

---

## 1. 项目概览

**LangChain** 是一个面向 LLM（大语言模型）应用开发的开源 Python 框架，旨在通过可组合的标准化接口（chat models、LLMs、vector stores、retrievers、tools 等）以及"LangChain Expression Language (LCEL)"声明式组合语法，帮助开发者快速搭建生产级 AI Agent 与应用。

- **官方定位**："The agent engineering platform."
- **仓库根目录**：`d:\yudao\langchan-gongxie-zp\LangChain-master`
- **许可证**：MIT
- **Python 版本要求**：≥ 3.10，< 4.0
- **包管理工具**：`uv`
- **生态相关**：
  - LangGraph：底层 Agent 编排框架（被 `langchain` v1 直接依赖）
  - Deep Agents：基于 LangChain 的高级 Agent 包
  - LangSmith：观测、评估、调试平台
  - LangChain.js：等价的 JS/TS 实现

---

## 2. 仓库整体架构

LangChain 采用 **Python monorepo** 结构，由 `uv` 统一管理。仓库根目录与 `libs/` 子目录结构如下：

```text
LangChain-master/
├── README.md                       # 项目主说明
├── AGENTS.md                       # Agent 开发指南
├── CLAUDE.md                       # Claude 相关说明
├── CITATION.cff                    # 学术引用信息
├── LICENSE
├── .github/                        # CI/CD 工作流与模板
├── .vscode/                        # 推荐 IDE 设置
├── .devcontainer/                  # Dev Container 配置
└── libs/
    ├── core/             # langchain-core：核心抽象
    ├── langchain/        # langchain-classic：遗留代码
    ├── langchain_v1/     # langchain（主包，1.x 系列）
    ├── partners/         # LangChain 团队维护的第三方集成
    ├── model-profiles/   # 模型档案 CLI 工具
    ├── standard-tests/   # 集成标准测试包（被 partners 引用）
    └── text-splitters/   # 文本分块工具
```

### 2.1 架构层次

LangChain 的整体架构遵循"**核心抽象 → 实现 → 集成**"三层模型：

```text
┌──────────────────────────────────────────────────────────┐
│  应用层（用户代码、Agent 工作流）                            │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────┐
│  langchain v1.x（libs/langchain_v1）                       │
│  · create_agent、init_chat_model、middleware 系统         │
│  · 依赖 langchain-core、langgraph                         │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────┐
│  langchain-core（libs/core）                              │
│  · Runnable、BaseChatModel、BaseLLM、Embeddings、Tools    │
│  · Messages、Prompts、VectorStores、Retrievers、Tracers   │
│  · 第三方无关，依赖极轻量                                   │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────┐
│  第三方集成（libs/partners/*）                             │
│  · OpenAI、Anthropic、Ollama、DeepSeek、Groq 等          │
│  · Chroma 等向量库                                         │
│  · 各自独立的 pyproject、版本号、CI                         │
└──────────────────────────────────────────────────────────┘
```

### 2.2 包之间的版本关系

| 包 | 版本 | 主要依赖 |
|---|---|---|
| `langchain-core` | 1.5.1 | `langsmith`, `tenacity`, `pydantic`, `PyYAML`, `uuid-utils`, `langchain-protocol` |
| `langchain`（v1） | 1.3.14 | `langchain-core>=1.4.9,<2.0.0`, `langgraph>=1.2.5,<1.3.0`, `pydantic` |
| `langchain-classic` | 1.0.8 | `langchain-core>=1.4.7`, `langchain-text-splitters`, `SQLAlchemy`, `requests` |

---

## 3. 核心包：`libs/core`（`langchain-core`）

### 3.1 包定位

`langchain-core` 定义了 LangChain 生态的**基础抽象**与**统一调用协议（Runnable / LCEL）**。其设计原则：

- 第三方依赖极轻量（不引入任何 provider SDK）。
- 所有可执行组件均实现 `Runnable` 协议。
- 启用**动态导入（PEP 562）**以优化 import 时间和避免循环依赖。

### 3.2 目录结构与模块职责

```text
libs/core/langchain_core/
├── _api/                  # 内部 API（beta_decorator、deprecation、internal、path）
├── _security/             # 沙箱安全策略与传输
├── callbacks/             # 回调系统：BaseCallbackHandler、CallbackManager
├── document_loaders/      # 文档加载抽象（BaseLoader、BlobLoader）
├── documents/             # Document、压缩器、转换器
├── embeddings/            # Embeddings 接口、Fake 实现
├── example_selectors/     # （占位）示例选择器
├── indexing/              # 索引 API、内存索引
├── language_models/       # BaseChatModel、BaseLLM、SimpleChatModel、fake 模型
├── load/                  # dump/load 序列化（Serializable）
├── messages/              # AIMessage、HumanMessage、SystemMessage、ToolMessage...
├── output_parsers/        # JSON、List、XML、Pydantic、Transform...
├── outputs/               # ChatResult、Generation、LLMResult、RunInfo
├── prompts/               # PromptTemplate、ChatPromptTemplate、MessagesPlaceholder
├── runnables/             # Runnable 协议核心（base、branch、fallbacks、passthrough、router...）
├── tools/                 # BaseTool、StructuredTool、Tool、@tool 装饰器
├── tracers/               # LangSmith 追踪、事件流、日志流
├── utils/                 # 工具函数：aiter、iter、pydantic、json_schema、usage...
├── vectorstores/          # VectorStore 接口、InMemoryVectorStore
├── agents.py              # 旧式 Agent 抽象
├── caches.py              # BaseCache 接口
├── chat_history.py        # ChatHistory 抽象
├── chat_loaders.py        # 聊天加载器
├── chat_sessions.py       # 会话管理
├── cross_encoders.py      # CrossEncoder 接口
├── env.py                 # 环境变量
├── exceptions.py          # LangChainException、OutputParserException、ErrorCode
├── globals.py             # 全局 verbose/debug/llm_cache
├── prompt_values.py       # PromptValue 类型
├── rate_limiters.py       # 限速器
├── retrievers.py          # BaseRetriever
├── stores.py              # BaseStore K/V 接口
├── structured_query.py    # 结构化查询 IR
├── sys_info.py            # 系统信息
└── version.py             # VERSION = "1.5.1"
```

### 3.3 关键抽象一览

| 抽象 | 文件 | 用途 |
|---|---|---|
| `Runnable[Input, Output]` | `runnables/base.py` | LCEL 核心协议，所有可执行对象基类 |
| `BaseLanguageModel` | `language_models/base.py` | 所有 LM 的祖先，提供 token 计数等 |
| `BaseChatModel` / `SimpleChatModel` | `language_models/chat_models.py` | 聊天模型接口 |
| `BaseLLM` / `LLM` | `language_models/llms.py` | 字符串进/出的旧式 LLM |
| `Embeddings` | `embeddings/embeddings.py` | 文本向量化接口 |
| `BaseTool` / `StructuredTool` / `Tool` | `tools/base.py`, `tools/structured.py`, `tools/simple.py` | 工具抽象 |
| `BaseRetriever` | `retrievers.py` | 检索器抽象 |
| `VectorStore` | `vectorstores/base.py` | 向量库抽象 |
| `BaseStore[K, V]` | `stores.py` | 通用 K/V 存储 |
| `BaseCallbackHandler` | `callbacks/base.py` | 回调钩子 |
| `BaseTracer` | `tracers/base.py` | 追踪基类 |
| `BaseCache` | `caches.py` | LLM 缓存接口 |
| `Document` | `documents/base.py` | RAG 数据单元 |

### 3.4 动态导入机制

`langchain_core` 大量使用 PEP 562 的 `__getattr__` 延迟加载（如 `messages/__init__.py`、`prompts/__init__.py`、`runnables/__init__.py` 等）。每个子包的 `__init__.py` 中维护 `_dynamic_imports` 字典，键为公开符号名，值为子模块名：

```python
_dynamic_imports = {
    "Runnable": "base",
    "RunnableSequence": "base",
    "RunnableBranch": "branch",
    "RunnableWithFallbacks": "fallbacks",
    ...
}

def __getattr__(attr_name: str) -> object:
    module_name = _dynamic_imports.get(attr_name)
    result = import_attr(attr_name, module_name, __spec__.parent)
    globals()[attr_name] = result
    return result
```

带来的好处：

- 首次按需 import 子模块，缩短 import 启动时间。
- 避免因顶层相互引用导致的循环依赖。

### 3.5 LCEL（LangChain Expression Language）

通过 `Runnable` 协议实现"管道式"组合：

```python
# 概念示例
chain = prompt | model | output_parser
result = chain.invoke({"topic": "AI"})
```

`Runnable` 提供的关键方法：

- 同步 / 异步：`invoke` / `ainvoke`
- 批量 / 异步批量：`batch` / `abatch`
- 流式 / 异步流式：`stream` / `astream`
- 事件流：`astream_events`
- 工具转换：`as_tool`、`.bind_tools(...)`、`.with_config(...)`、`.with_fallbacks(...)`、`.with_retry(...)`

支持的内置 Runnable（均位于 `langchain_core.runnables`）：

- `RunnableSequence`（`|` 运算符）
- `RunnableParallel`（`{...}` 字面量构造）
- `RunnableBranch`（条件分支）
- `RunnablePassthrough`、`RunnableAssign`、`RunnablePick`
- `RunnableLambda`、`RunnableGenerator`
- `RunnableWithFallbacks`、`RunnableWithMessageHistory`、`RouterRunnable`
- `chain` 装饰器

### 3.6 消息系统（`messages/`）

对话相关的"消息"对象，提供多模态内容块：

- 角色类：`SystemMessage`、`HumanMessage`、`AIMessage`、`ChatMessage`、`FunctionMessage`、`ToolMessage`、`RemoveMessage`
- 流式分块：`*Chunk` 后缀类
- 工具调用：`ToolCall`、`ToolCallChunk`、`InvalidToolCall`
- 内容块（`messages/content.py`）：`TextContentBlock`、`ImageContentBlock`、`AudioContentBlock`、`VideoContentBlock`、`FileContentBlock`、`DataContentBlock`、`ReasoningContentBlock`、`Citation`、`ServerToolCall`、`ServerToolResult` 等
- 工具函数（`messages/utils.py`）：`convert_to_messages`、`convert_to_openai_messages`、`trim_messages`、`filter_messages`、`merge_message_runs`、`get_buffer_string` 等

### 3.7 提示词模板（`prompts/`）

- `BasePromptTemplate`：抽象基类
- `PromptTemplate`（字符串模板，支持 Jinja2 / f-string）
- `ChatPromptTemplate` / `BaseChatPromptTemplate`
- `MessagesPlaceholder`：在 chat 模板中预留消息位置
- `FewShotPromptTemplate`、`FewShotChatMessagePromptTemplate`
- `DictPromptTemplate`：字典模板
- `load_prompt`：从 JSON/YAML/文件加载模板

### 3.8 输出解析器（`output_parsers/`）

- `BaseOutputParser`、`BaseLLMOutputParser`、`BaseGenerationOutputParser`
- `StrOutputParser`、`JsonOutputParser`、`SimpleJsonOutputParser`
- `PydanticOutputParser`、`XMLOutputParser`
- `ListOutputParser`、`CommaSeparatedListOutputParser`、`NumberedListOutputParser`、`MarkdownListOutputParser`
- `JsonOutputToolsParser`、`PydanticToolsParser`（与 OpenAI 工具调用协同）
- `BaseTransformOutputParser`、`BaseCumulativeTransformOutputParser`

> 注意：随着模型原生结构化输出（tool_choice / JSON schema）越来越成熟，部分 `OutputParser` 已逐步被原生能力替代。

### 3.9 追踪与回调（`tracers/`、`callbacks/`）

- `BaseTracer` 与 `Run`、`RunLog`、`RunLogPatch`
- `LangChainTracer`：将追踪数据上报至 LangSmith
- `LogStreamCallbackHandler`：按 token 输出中间日志
- `ConsoleCallbackHandler`：控制台输出
- `EvaluatorCallbackHandler`：评估触发
- 事件流相关：`event_stream.py` 提供 `_astream_events_implementation_v1` / `_astream_events_implementation_v2`

回调系统配套：

- `CallbackManager` / `AsyncCallbackManager`
- 各运行上下文的 Manager：`CallbackManagerForLLMRun`、`CallbackManagerForChainRun`、`CallbackManagerForToolRun`、`CallbackManagerForRetrieverRun`
- `dispatch_custom_event` / `adispatch_custom_event`：自定义事件分发
- `UsageMetadataCallbackHandler`、`get_usage_metadata_callback`：token 用量统计

### 3.10 全局配置（`globals.py`、`env.py`）

- `_verbose`、`_debug`、`_llm_cache` 三个进程级全局变量；
- 必须通过 `set_verbose/get_verbose`、`set_debug/get_debug`、`set_llm_cache/get_llm_cache` 访问，避免被多线程或外部库污染。
- `env.py` 集中处理 `LANGCHAIN_*` 环境变量（tracing、API key、endpoint、project 等）。

### 3.11 异常体系（`exceptions.py`）

```text
Exception
└── LangChainException
    ├── TracerException
    └── (其他特定异常)
OutputParserException(ValueError, LangChainException)
ContextOverflowError(LangChainException)
```

`OutputParserException` 携带 `observation`、`llm_output`、`send_to_llm` 等字段，可被 Agent 用于"把错误回灌给 LLM 自我修复"。

---

## 4. 主包：`libs/langchain_v1`（`langchain`）

### 4.1 包定位

`libs/langchain_v1/langchain` 是 LangChain 当前积极维护的 **主包（1.x 系列）**。它基于 `langchain-core` 与 `langgraph` 构建，提供：

- `create_agent` 工厂函数（推荐入口）
- `init_chat_model` 模型统一工厂
- 丰富的 **middleware 系统**（中间件式 Agent 编排）
- 工具节点（`ToolNode`）的兼容层

### 4.2 目录结构

```text
libs/langchain_v1/langchain/
├── agents/
│   ├── factory.py                  # create_agent 工厂实现
│   ├── _subagent_transformer.py    # 子 Agent 转换器
│   ├── structured_output.py        # 结构化输出策略
│   ├── middleware/                 # 中间件集合
│   │   ├── types.py                # AgentMiddleware / AgentState / ModelRequest...
│   │   ├── _execution.py / _redaction.py / _retry.py
│   │   ├── context_editing.py      # 上下文编辑
│   │   ├── file_search.py          # 文件系统搜索
│   │   ├── human_in_the_loop.py    # 人机协同
│   │   ├── model_call_limit.py     # 模型调用上限
│   │   ├── model_fallback.py       # 模型 fallback
│   │   ├── model_retry.py          # 模型重试
│   │   ├── pii.py                  # PII 检测
│   │   ├── provider_tool_search.py # provider 工具搜索
│   │   ├── shell_tool.py           # Shell 工具（含沙箱策略）
│   │   ├── summarization.py        # 对话摘要
│   │   ├── todo.py                 # TodoList
│   │   ├── tool_call_limit.py      # 工具调用次数限制
│   │   ├── tool_emulator.py        # LLM 工具仿真
│   │   ├── tool_error.py           # 工具错误处理
│   │   ├── tool_retry.py           # 工具重试
│   │   └── tool_selection.py       # 工具选择
│   └── __init__.py
├── chat_models/
│   └── base.py                     # init_chat_model 工厂
├── embeddings/
│   └── base.py                     # init_embeddings 工厂
├── messages/
│   └── __init__.py                 # 重新导出 langchain_core.messages
├── tools/
│   ├── __init__.py
│   └── tool_node.py                # ToolNode / InjectedState / InjectedStore / ToolRuntime
└── __init__.py                     # 仅暴露 __version__ = "1.3.14"
```

> **注意**：`langchain/__init__.py` 只暴露版本号，刻意保持简洁，避免破坏性顶层导出。

### 4.3 关键入口：`create_agent`

位置：`libs/langchain_v1/langchain/agents/factory.py`

```python
from langchain.agents.factory import create_agent
from langchain.agents.middleware.types import AgentState
```

`create_agent` 是 LangChain 1.x 推荐的 Agent 创建入口。其特点：

1. 返回一个 `CompiledStateGraph`（langgraph 编译后的有状态图）。
2. 基于 `langgraph.prebuilt.tool_node.ToolNode` 与 `StateGraph` 构造。
3. 支持 `middleware` 列表参数，串联多个 `AgentMiddleware`：
   - `before_agent` / `after_agent`
   - `before_model` / `after_model`
   - `wrap_model_call` / `wrap_tool_call`
   - `dynamic_prompt` / `hook_config`
4. 内置结构化输出策略：`AutoStrategy`、`ProviderStrategy`、`ToolStrategy`，通过 `ProviderStrategyBinding` / `OutputToolBinding` 与具体 provider 的工具调用能力对接。
5. 错误类型：`MultipleStructuredOutputsError`、`StructuredOutputError`、`StructuredOutputValidationError`。
6. 内部使用 `langsmith.traceable` 提供观测能力。

### 4.4 中间件系统（`agents/middleware/`）

中间件是 LangChain 1.x 的核心扩展点。基类与协议定义在 `types.py`：

| 名称 | 作用 |
|---|---|
| `AgentMiddleware` | 中间件抽象类，组合 before/after/wrap 钩子 |
| `AgentState` / `InputAgentState` / `OutputAgentState` | 状态 TypedDict |
| `ModelRequest` / `ModelResponse` / `ExtendedModelResponse` / `ModelCallResult` | 模型调用包装对象 |
| `ToolCallRequest` / `ToolCallWrapper` | 工具调用包装 |
| `JumpTo` | 中间件可跳到 `tools` / `model` / `end` |
| `OmitFromSchema` | 在生成 schema 时省略字段 |

预置中间件（`middleware/__init__.py`）：

- `ContextEditingMiddleware`、`ClearToolUsesEdit`
- `FilesystemFileSearchMiddleware`
- `HumanInTheLoopMiddleware`、`InterruptOnConfig`
- `ModelCallLimitMiddleware`、`ModelFallbackMiddleware`、`ModelRetryMiddleware`
- `PIIMiddleware`、`PIIDetectionError`
- `ProviderToolSearchMiddleware`
- `ShellToolMiddleware`（含 `DockerExecutionPolicy`、`HostExecutionPolicy`、`CodexSandboxExecutionPolicy`、`RedactionRule`）
- `SummarizationMiddleware`、`TriggerClause`
- `TodoListMiddleware`
- `ToolCallLimitMiddleware`、`ToolErrorMiddleware`、`ToolRetryMiddleware`
- `LLMToolEmulator`、`LLMToolSelectorMiddleware`
- 钩子装饰器：`before_agent`、`after_agent`、`before_model`、`after_model`、`wrap_model_call`、`wrap_tool_call`、`dynamic_prompt`、`hook_config`

### 4.5 模型工厂（`chat_models/base.py`、`embeddings/base.py`）

- `init_chat_model("openai:gpt-5.5")`：根据 `provider:model` 形式自动加载对应 provider 集成。
- `_BUILTIN_PROVIDERS` 字典维护内置 provider → `(module, class, creator)` 映射（如 `openai`、`anthropic`、`azure_openai`、`bedrock`、`google_genai`、`ollama`、`mistralai`、`groq`、`fireworks` 等）。
- 若 provider 不在内置列表，仍可显式传入 `model_provider` 使用，只要对应集成包已安装。
- 同样提供 `init_embeddings` 工厂（`embeddings/base.py`）。

### 4.6 工具节点（`tools/tool_node.py`）

为了向后兼容，重新导出 `langgraph.prebuilt` 中的关键类型：

- `ToolNode`（重命名为 `_ToolNode`）
- `ToolCallRequest`、`ToolCallWithContext`、`ToolCallWrapper`
- `InjectedState`、`InjectedStore`、`ToolRuntime`

`tools/__init__.py` 统一暴露：

```python
from langchain_core.tools import BaseTool, InjectedToolArg, InjectedToolCallId, ToolException, tool
from langchain.tools.tool_node import InjectedState, InjectedStore, ToolRuntime
```

### 4.7 消息（`messages/__init__.py`）

`langchain.messages` 是 `langchain_core.messages` 的**直接重导出层**，因此开发者可以同时使用 `from langchain.messages import ...` 或 `from langchain_core.messages import ...`。

---

## 5. 遗留包：`libs/langchain`（`langchain-classic`）

### 5.1 包定位

`libs/langchain` 的实际 Python 包名为 **`langchain-classic`**（参见 `pyproject.toml` 的 `name = "langchain-classic"`）。该包聚合了 LangChain 0.x 时代的全部实现，**不再接受新特性**，仅做 bugfix 与必要的安全维护。任何新功能应在 `langchain-core` 或 `langchain`（v1） 中实现。

### 5.2 模块概览

| 模块 | 主要内容 |
|---|---|
| `agents/` | Agent Executor、各种 ReAct/MRKL/Structured Chat/OpenAI Tools Agent；`agent_toolkits/`、`initialize.py`、`load_tools.py` |
| `chains/` | `Chain` 基类、`LLMChain`、`ConversationChain`、`RetrievalQA`、`MapReduceChain`、`SQLDatabaseChain`、`api/`（OpenAPI、news、podcast 等）、`router/`、`hyde/`、`flare/` 等 |
| `chat_models/` | 旧版 chat model 集成（OpenAI、Azure、Anthropic、Cohere、Bedrock、Databricks、Google PaLM、Hunyuan、MLflow 等） |
| `document_loaders/` | 90+ 数据源加载器（Notion、PDF、S3、HTML、Slack、Wikipedia、YouTube、arXiv、Discord、GitHub、RSS、Twitter...） |
| `embeddings/` | 30+ Embeddings 实现（OpenAI、Cohere、HuggingFace、Bedrock、Voyage、Jina、MosaicML、LocalAI 等） |
| `llms/` | 旧式 LLM 接口 + 50+ provider（AI21、Anthropic、Cohere、OpenAI、VertexAI、Replicate、Writer、Yandex、OpenLLM、MLflow...） |
| `memory/` | `ConversationBufferMemory`、`ConversationSummaryMemory`、`EntityMemory`、`KGConversationMemory`、`VectorStoreMemory` |
| `prompts/` | 旧版 prompt 抽象与加载 |
| `retrievers/` | `KAY`、`KNN`、`SVM`、`You.com`、`Zep` |
| `callbacks/` | 各类 callback（Comet、W&B、Argilla、Arize、Arthur、ClearML、MLflow、WhyLabs、Flyte、Infino 等）；内含 tracers 子模块 |
| `chat_loaders/` | 聊天历史加载（Gmail、Slack、WhatsApp、Telegram、LangSmith） |
| `docstore/` | `Docstore`/`Document` 抽象与 `Wikipedia`、`InMemory` |
| `graphs/` | 图数据库集成（Neo4j、Memgraph、Nebula、ArangoDB、FalkorDB、Kuzu、HugeGraph、Neptune、RDF、NetworkX） |
| `indexes/` | 索引抽象与图谱检索 |
| `evaluation/` | 评估器（QA、Criterion、Exact Match、Regex Match、Parsing、Scoring） |
| `runnables/hub.py` | 旧 hub 集成 |
| `smith/` | LangSmith 集成 |
| `storage/redis.py` | Redis K/V 存储 |
| `tools/` | 工具集（`ifttt.py`、`plugin.py`） |
| `utilities/` | `jira.py`、`nasa.py` |
| `adapters/openai.py` | OpenAI 协议适配器（兼容旧式 OpenAI SDK） |
| `base_language.py` / `base_memory.py` / `cache.py` / `globals.py` / `env.py` / `hub.py` / `text_splitter.py` / `formatting.py` / `input.py` / `requests.py` / `serpapi.py` / `sql_database.py` / `python.py` | 通用工具 |

### 5.3 兼容性策略

`__init__.py` 通过 `_warn_on_import` 在非交互式环境中提示：

> "Importing `<name>` from langchain root module is no longer supported. …"

引导用户迁移到 `langchain-classic`、`langchain-core` 或新 `langchain`（v1）。

---

## 6. `libs/partners` 第三方集成

`partners/` 是 LangChain 团队**直接维护**的第三方集成子集（多数其它集成已迁移至独立仓库，例如 `langchain-google`、`langchain-aws`）。每个子目录都是一个**独立可发布的 Python 包**，具备自己的 `pyproject.toml`、`uv.lock`、`Makefile`、`README.md`。

### 6.1 当前在仓库内的合作伙伴包

| 包名 | 子目录 | 主要导出 | 备注 |
|---|---|---|---|
| `langchain-openai` | `partners/openai` | `ChatOpenAI`、`AzureChatOpenAI`、`OpenAI`、`AzureOpenAI`、`OpenAIEmbeddings`、`AzureOpenAIEmbeddings`、`custom_tool`、`StreamChunkTimeoutError` | 提供 Codex 专属测试装置与 cassettes |
| `langchain-anthropic` | `partners/anthropic` | `ChatAnthropic`、`AnthropicLLM`、`convert_to_anthropic_tool` | 含中间件 `bash`、`file_search`、`prompt_caching`、`anthropic_tools` |
| `langchain-chroma` | `partners/chroma` | `Chroma`（vectorstore） | |
| `langchain-deepseek` | `partners/deepseek` | `ChatDeepSeek` | 含 model profile augmentations |
| `langchain-exa` | `partners/exa` | `ExaSearchRetriever`、`ExaFindSimilarTool`、`ExaSearchTool` | 检索 + 工具 |
| `langchain-fireworks` | `partners/fireworks` | `ChatFireworks`、`Fireworks`、`FireworksEmbeddings` | |
| `langchain-groq` | `partners/groq` | `ChatGroq` | 含 web_search、code_interpreter 测试 cassettes |
| `langchain-huggingface` | `partners/huggingface` | `ChatHuggingFace`、`HuggingFaceEmbeddings`、`HuggingFaceEndpoint`、`HuggingFacePipeline` | |
| `langchain-mistralai` | `partners/mistralai` | `ChatMistralAI`、`MistralAIEmbeddings` | |
| `langchain-nomic` | `partners/nomic` | `NomicEmbeddings` | |
| `langchain-ollama` | `partners/ollama` | `ChatOllama`、`OllamaLLM`、`OllamaEmbeddings` | |
| `model-profiles`（CLI） | `libs/model-profiles` | `langchain-profiles refresh` | 跨 partner 工具 |

### 6.2 每个 partner 包的统一结构

```text
partners/<provider>/
├── langchain_<provider>/
│   ├── __init__.py
│   ├── _version.py
│   ├── chat_models.py / llms.py / embeddings.py / vectorstores.py / retrievers.py / tools.py
│   ├── data/                         # 模型档案、profile_augmentations.toml
│   ├── middleware/                   # 部分包提供（如 anthropic）
│   └── py.typed
├── scripts/
│   ├── check_imports.py
│   ├── check_version.py
│   └── lint_imports.sh
├── tests/
│   ├── cassettes/                    # VCR 录制文件（gzip 压缩）
│   ├── unit_tests/
│   └── integration_tests/
├── README.md
├── Makefile
├── pyproject.toml
└── uv.lock
```

### 6.3 测试策略

- 单元测试（`unit_tests/`）：禁止网络访问。
- 集成测试（`integration_tests/`）：允许网络，可与真实 API 交互（CI 上需相应 API key）。
- **VCR cassettes**：多数 partner 包通过 `vcrpy` 重放 HTTP 请求，避免在测试中真实调用模型。
- 复用 `libs/standard-tests` 的标准测试套件（`langchain_tests`），保证各 provider 行为一致。

### 6.4 中间件（以 Anthropic 为例）

`libs/partners/anthropic/langchain_anthropic/middleware/`：

- `bash.py`：受限 Bash 工具
- `file_search.py`：文件搜索
- `prompt_caching.py`：提示缓存
- `anthropic_tools.py`：Anthropic 特有工具封装

`libs/partners/openai/langchain_openai/middleware/`：

- `openai_moderation.py`：内容审核

---

## 7. `libs/model-profiles` 模型档案工具

### 7.1 作用

`langchain-model-profiles` 是一个独立的 **CLI 工具包**，用于从 [models.dev](https://github.com/sst/models.dev) 拉取最新模型能力数据，并结合各 partner 包的 `profile_augmentations.toml` 增广项，生成 `_profiles.py` 文件。

### 7.2 命令

```bash
# 更新特定 provider 的 profile（需在 libs/model-profiles 下）
uv run langchain-profiles refresh --provider anthropic --data-dir ../../partners/anthropic/langchain_anthropic/data

# 仓库外集成（需 echo y 确认）
echo y | uv run langchain-profiles refresh --provider google --data-dir /path/to/langchain-google/libs/genai/langchain_google_genai/data
```

### 7.3 关键文件

- `langchain_model_profiles/cli.py`：CLI 入口
- `langchain_model_profiles/_summary.py`：汇总生成逻辑
- 各 partner 的 `data/profile_augmentations.toml`：人工增广项
- 各 partner 的 `data/_profiles.py`：生成产物（受版本控制）

---

## 8. 关键类与函数详解

### 8.1 `Runnable`（`langchain_core.runnables.base`）

```python
class Runnable(ABC, Generic[Input, Output]):
    """A unit of work that can be invoked, batched, streamed, transformed and composed."""
```

**主要方法：**

| 方法 | 说明 |
|---|---|
| `invoke(input, config=None, **kwargs)` | 同步调用 |
| `ainvoke(input, config=None, **kwargs)` | 异步调用 |
| `batch(inputs, config=None, *, return_exceptions=False, **kwargs)` | 同步批处理（默认线程池并发） |
| `abatch(...)` | 异步批处理 |
| `stream(input, config=None, **kwargs)` | 同步流式输出 |
| `astream(...)` | 异步流式输出 |
| `astream_events(...)` | 流式事件，可观测中间步骤 |
| `astream_log(...)` | 输出 run log patch |
| `transform(input_iter)` / `atransform(...)` | 对输入迭代做转换 |
| `bind(**kwargs)` | 绑定额外参数（不修改原 Runnable） |
| `assign(**kwargs)` / `pick(keys)` | 派生 Runnable |
| `with_config(config)` | 注入 `RunnableConfig` |
| `with_fallbacks(fallbacks, *, exceptions, exception_key)` | 增加 fallback 链 |
| `with_retry(*, retry_if_exception_type, wait_exponential_jitter, stop_after_attempt)` | 增加重试 |
| `with_listeners(on_start, on_end, on_error)` | 监听生命周期事件 |
| `with_types(input_type, output_type)` | 类型标注 |
| `as_tool(*, name, description, arg_types, ...)` | 将任意 Runnable 包装为 Tool |

### 8.2 `BaseChatModel`（`langchain_core.language_models.chat_models`）

```python
class BaseChatModel(BaseLanguageModel, Runnable[LanguageModelInput, BaseMessage]):
```

**关键接口：**

- `_generate(messages, stop, run_manager, **kwargs) -> ChatResult`
- `_stream(messages, stop, run_manager, **kwargs) -> Iterator[ChatGenerationChunk]`
- `_agenerate`、`_astream` 异步对应方法
- `bind_tools(tools, **kwargs)`：把工具绑定到模型
- `with_structured_output(schema, **kwargs)`：结构化输出
- `profile`（`ModelProfile`）：模型能力元数据（context window、modalities、tool calling 等）

### 8.3 `BaseLLM`（`langchain_core.language_models.llms`）

字符串进/字符串出的旧式模型接口；继承自 `BaseLanguageModel`。`LLM` 是它的一个具体包装基类。

### 8.4 `Embeddings`（`langchain_core.embeddings.embeddings`）

```python
class Embeddings(ABC):
    def embed_documents(self, texts: list[str]) -> list[list[float]]
    def embed_query(self, text: str) -> list[float]
    async def aembed_documents(self, texts: list[str]) -> list[list[float]]
    async def aembed_query(self, text: str) -> list[float]
```

### 8.5 `BaseTool`（`langchain_core.tools.base`）

```python
class BaseTool(RunnableSerializable[..., Any]):
    name: str
    description: str
    args_schema: type[BaseModel] | None
    return_direct: bool
    handle_tool_error: bool | str | Callable[[ToolException], str] | None
```

**配套类型：**

- `ArgsSchema`、`InjectedToolArg`、`InjectedToolCallId`、`SchemaAnnotationError`、`ToolException`
- `StructuredTool`、`Tool`、`@tool` 装饰器（`tools/convert.py`）
- `render_text_description`、`render_text_description_and_args`（`tools/render.py`）
- `create_retriever_tool`（`tools/retriever.py`）

### 8.6 `BaseRetriever`（`langchain_core.retrievers.py`）

```python
class BaseRetriever(RunnableSerializable[RetrieverInput, RetrieverOutput], ABC):
    class Config: arbitrary_types_allowed = True
    tags: list[str] | None
    metadata: dict | None
```

抽象方法：`_get_relevant_documents(query, run_manager)` 与 `_aget_relevant_documents`。

### 8.7 `VectorStore`（`langchain_core.vectorstores.base`）

主要方法：

- `add_texts(texts, metadatas, ids, **kwargs)`
- `add_documents(documents, **kwargs)`
- `similarity_search(query, k, **kwargs)` / `asimilarity_search(...)`
- `similarity_search_with_score(...)`
- `similarity_search_by_vector(embedding, k)`
- `max_marginal_relevance_search(query, k, fetch_k, lambda_mult)`
- `from_texts(texts, embedding, metadatas, **kwargs)`
- `as_retriever(**kwargs)`：生成 `VectorStoreRetriever`

### 8.8 `BaseStore`（`langchain_core.stores.py`）

```python
class BaseStore(ABC, Generic[K, V]):
    def mget(self, keys: Sequence[K]) -> list[V | None]
    def mset(self, key_value_pairs: Sequence[tuple[K, V]]) -> None
    def mdelete(self, keys: Sequence[K]) -> None
    def yield_keys(self, prefix: str | None = None) -> Iterator[K]
    # async 异步对应方法 amget / amset / amdelete / ayield_keys
```

### 8.9 `create_agent`（`langchain.agents.factory`）

```python
def create_agent(
    model: str | BaseChatModel,
    tools: Sequence[BaseTool | Callable | dict],
    *,
    system_prompt: str | SystemMessage | None = None,
    middleware: Sequence[AgentMiddleware] = (),
    response_format: ResponseFormat | type[BaseModel] | None = None,
    state_schema: type[StateT] | None = None,
    context_schema: type[ContextT] | None = None,
    checkpointer: Checkpointer | None = None,
    store: BaseStore | None = None,
    cache: BaseCache | None = None,
    interrupt_before: list[str] | None = None,
    interrupt_after: list[str] | None = None,
    debug: bool = False,
    name: str | None = None,
    cache_policy: ... | None = None,
) -> CompiledStateGraph:
    ...
```

返回 `langgraph.graph.state.CompiledStateGraph`，因此可以直接 `.invoke(...)` / `.stream(...)` / `.astream(...)`，并接入 LangGraph 生态（checkpointer、store、interrupt 等）。

### 8.10 `init_chat_model`（`langchain.chat_models.base`）

```python
def init_chat_model(
    model: str | None = None,
    *,
    model_provider: str | None = None,
    configurable_fields: ... = None,
    **kwargs: Any,
) -> BaseChatModel:
    ...
```

- 支持 `init_chat_model("openai:gpt-5.5")` 简写；
- 也支持 `init_chat_model("gpt-5.5", model_provider="openai")`；
- 支持 LangGraph 的 `configurable_fields`，可在运行时切换模型或参数。

### 8.11 中间件协议（`langchain.agents.middleware.types`）

```python
class AgentMiddleware(Generic[ContextT, ResponseT]):
    """Base class for all agent middleware."""

    state_schema: type[StateT] | None = None
    tools: list[BaseTool] | None = None
    def before_agent(state, runtime) -> dict | None: ...
    def before_model(state, runtime) -> dict | None: ...
    def after_model(state, runtime) -> dict | None: ...
    def after_agent(state, runtime) -> dict | None: ...
    def wrap_model_call(request, handler) -> ModelResponse | AIMessage | ExtendedModelResponse: ...
    def wrap_tool_call(request, handler) -> ToolMessage | Command: ...
```

> 上述钩子全部支持异步（`a*` 版本）。

---

## 9. 依赖关系与运行机制

### 9.1 顶层依赖图

```text
┌─────────────────────────┐
│  langchain (1.3.14)     │ ─────────────┐
│  libs/langchain_v1      │              │
└──────────┬──────────────┘              │
           │                              │
           ▼                              │
┌─────────────────────────┐              │
│  langchain-core (1.5.1) │ ◀──── 共享抽象 │  所有 partner 包
│  libs/core              │              │
└──────────┬──────────────┘              │
           │                              │
           ▼                              │
┌─────────────────────────┐              │
│  langgraph              │              │
│  langsmith              │              │
│  pydantic               │              │
│  tenacity               │              │
└─────────────────────────┘              │
                                          │
┌────────────────────────────────────┐    │
│  partners/*                         │ ───┘
│  langchain-openai / -anthropic / …  │
└────────────────────────────────────┘
```

### 9.2 运行时数据流（典型 Agent 调用）

```text
用户输入
   │
   ▼
create_agent → CompiledStateGraph
   │
   ▼
StateGraph node: before_agent (middleware)
   │
   ▼
[循环] ┌─────────────────────────────────────┐
       ▼                                     │
 before_model (middleware)                    │
 dynamic_prompt (若声明)                       │
       │                                     │
       ▼                                     │
   wrap_model_call (middleware) ──▶ BaseChatModel
       │                                     │
       ▼                                     │
  AIMessage（含 tool_calls?）                 │
       │                                     │
       ▼                                     │
   after_model (middleware)                   │
       │                                     │
       ├── 有 tool_calls ─▶ ToolNode ─▶ wrap_tool_call (middleware) ─▶ 回到循环
       └── 无 tool_calls ─▶ after_agent ─▶ END
```

### 9.3 序列化与可观测

- `langchain_core.load.Serializable`（`langchain_core/load/serializable.py`）：所有公开类均通过 `Serializable` 提供 dump/load 协议，键以 `lc://...` 开头。
- `langsmith` SDK 提供追踪桥接（`libs/standard-tests/langchain_tests/_langsmith_plugin.py`），在 `GITHUB_ACTIONS=true` 时自动注入 `LANGSMITH_TAGS` / `LANGSMITH_METADATA`。
- 单元测试必须断开追踪：`libs/core/Makefile` 在 `make test` 之前 `env -u` 一系列 `LANGCHAIN_TRACING_V2`、`LANGCHAIN_API_KEY`、`LANGSMITH_*` 变量。

### 9.4 与 LangGraph 的协作

`langchain` v1 主包强依赖 `langgraph>=1.2.5,<1.3.0`，因此：

- `create_agent` 实际返回的是 `langgraph.graph.state.CompiledStateGraph`。
- 持久化、检查点、interrupt、subgraph 等能力均来自 LangGraph。
- 中间件节点实际上是 LangGraph 的 node，统一经过 LangGraph 调度。

---

## 10. 运行与开发指南

### 10.1 安装基础环境

仓库使用 `uv`，首次克隆后：

```bash
# 仓库根（也可进入具体包目录）
uv sync --all-groups
```

> `libs/langchain_v1/pyproject.toml` 中通过 `[tool.uv.sources]` 把 `langchain-core`、`langchain-tests`、`langchain-text-splitters`、`langchain-openai`、`langchain-anthropic` 映射到本地路径，实现 editable 安装。

### 10.2 运行单元测试

各包根目录的 `Makefile` 暴露 `test`、`test_watch`、`lint`、`format`、`type`、`benchmark` 等目标。

例如 `libs/core/Makefile`：

```bash
# 运行 core 包的单元测试
make test

# 运行某个测试文件
TEST_FILE=tests/unit_tests/test_tools.py make test

# 监视模式
make test_watch

# 仅 lint 改动文件
make lint_diff

# 性能基准
make benchmark
```

`libs/langchain_v1/Makefile`、`libs/partners/*/Makefile` 遵循相同约定。

跨包更新 lock：

```bash
# 在 libs/ 目录下
make lock        # 重新生成所有核心包的 uv.lock
make check-lock  # 检查所有 lockfile 是否最新
```

### 10.3 Lint / Format / Type

- **Ruff**：`lint` / `format`，配置统一启用 `select = ["ALL"]` 并在 `pyproject.toml` 中豁免少量规则。
- **Mypy**：`type` 目标，启用 `strict = true`、`enable_error_code = "deprecated"`，配合 `pydantic.mypy` 插件。
- 风格：Google 风格 docstring（`tool.ruff.lint.pydocstyle.convention = "google"`）。
- 所有公共函数必须有完整类型注解（`flake8-annotations.allow-star-arg-any = true`）。
- 在 `langchain_core` 中**禁止相对导入**（`tool.ruff.lint.flake8-tidy-imports.ban-relative-imports = "all"`）。

### 10.4 启动一个最小示例

```bash
uv add langchain
```

```python
from langchain.chat_models import init_chat_model

model = init_chat_model("openai:gpt-5.5")
print(model.invoke("Hello, world!"))
```

或构造 Agent：

```python
from langchain.agents import create_agent
from langchain.tools import tool

@tool
def get_word_length(word: str) -> int:
    """Return the length of a word."""
    return len(word)

agent = create_agent(
    model="openai:gpt-5.5",
    tools=[get_word_length],
    system_prompt="You are a helpful assistant.",
)

result = agent.invoke({"messages": [{"role": "user", "content": "How long is 'hello'?"}]})
print(result)
```

### 10.5 启用 LangSmith 追踪（可选）

```bash
export LANGSMITH_TRACING=true
export LANGSMITH_API_KEY=<your-key>
export LANGSMITH_PROJECT=<your-project>
```

---

## 11. CI/CD 与发布流程

### 11.1 工作流（`.github/workflows/`）

- `pr_lint.yml`：校验 PR 标题符合 Conventional Commits，**所有标题必须包含 scope**。
- `pr_labeler.yml` / `pr_labeler_backfill.yml`：按文件/PR 自动打标签。
- `auto-label-by-package.yml`：按包路径打标签。
- `integration_tests.yml`：集成测试（含 LangSmith 追踪）。
- `_release.yml`：发布工作流（每个 partner 包独立发布）。
- `check_agents_sync.yml`、`check_extras_sync.yml`、`check_release_deps.yml`、`check_versions.yml`、`check_diffs.yml`：一致性检查。
- `_lint.yml`、`_test.yml`、`_test_pydantic.yml`、`_test_vcr.yml`：通用 lint/test 任务。
- `_refresh_model_profiles.yml` / `refresh_model_profiles.yml`：定期刷新模型档案。
- `block_fork_main_prs.yml`：阻止来自 fork 的 main PR。
- `bump_uv_pin.yml`：自动升级 `uv` 版本。

### 11.2 发布流程

每个 partner 包独立发布。典型步骤：

1. **版本号 bump PR**：同时修改
   - `<partner>/_version.py`
   - `<partner>/pyproject.toml`
   - `uv.lock`（在该包目录运行 `uv lock`）
2. 合并到 master。
3. 触发 release 工作流：
   ```bash
   gh workflow run 63880841 --repo langchain-ai/langchain \
       -f working-directory=<partner> \
       -f release-version=<version>
   ```
4. 工作流自动完成构建 → 校验 → TestPyPI → PyPI → GitHub Release（`mark-release` job 通过 `ncipollo/release-action` 创建）。

### 11.3 提交与分支命名约定

- **Commit / PR 标题**：Conventional Commits，必须含 scope。
  - 范例：`feat(langchain): add new chat completion feature`
  - 范例：`fix(core): resolve type hinting issue in vector store`
  - 范例：`chore(anthropic): update infrastructure dependencies`
  - 范例：`release(openrouter): 0.2.6`
- **分支命名**：`<github-username>/<scope>/<short-description>`
  - 范例：`mdrxy/anthropic/normalize-tool-call-ids`

### 11.4 添加新 partner 包

需要在以下位置进行登记（参见 `.github/` 中文件）：

- `.github/ISSUE_TEMPLATE/*.yml` 的包下拉菜单
- `.github/dependabot.yml`
- `.github/scripts/pr-labeler-config.json`
- `.github/workflows/_release.yml`（API key）
- `.github/workflows/auto-label-by-package.yml`
- `.github/workflows/check_diffs.yml`
- `.github/workflows/integration_tests.yml`
- `.github/workflows/pr_lint.yml` 的允许 scope

---

## 附：阅读导航

- 想理解抽象接口 → 从 `libs/core/langchain_core/runnables/base.py`、`language_models/base.py`、`embeddings/embeddings.py`、`tools/base.py`、`retrievers.py`、`vectorstores/base.py`、`stores.py` 入手。
- 想理解 Agent 工厂与中间件 → `libs/langchain_v1/langchain/agents/factory.py` + `agents/middleware/`。
- 想了解具体 provider → 进入 `libs/partners/<provider>/`，阅读 `chat_models.py` 等主要导出文件，并参考 `unit_tests/` 与 `integration_tests/`。
- 想了解发布与 CI → 阅读 `.github/workflows/*.yml` 与仓库根 `AGENTS.md`。
- 想了解 v0.x 历史实现 → `libs/langchain/langchain_classic/`。