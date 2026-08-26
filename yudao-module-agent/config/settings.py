"""
集中配置管理。

职责：
  - 所有配置的"唯一权威定义"（Single Source of Truth）
  - 提供类型校验、默认值、IDE 自动补全
  - 兼容 .env 文件（本地开发）、环境变量（K8s/Docker）、系统 env

与 .env 的关系：
  - settings.py 是"定义"：标注所有配置项的名称、类型、默认值
  - .env 是"覆盖"：只写与默认值不同的字段（比如 API Key）
  - 生产环境（K8s/Docker）没有 .env 文件，config 通过环境变量注入
"""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ========== LLM ==========
    deepseek_api_key: str = ""          # 无默认值，必须通过 .env 或环境变量提供
    llm_model: str = "deepseek-chat"    # 有默认值，可省略不写
    llm_temperature: float = 0.5
    llm_timeout: int = 300

    # ========== LangSmith ==========
    langsmith_tracing: bool = False
    langsmith_api_key: str = ""

    # ========== Server ==========
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    app_debug: bool = False
    api_prefix: str = "/v1"

    # ========== Memory ==========
    memory_type: str = "in_memory"      # in_memory | postgres（短时记忆/对话记忆）
    store_type: str = "in_memory"       # in_memory | postgres（长期记忆/跨会话记忆）
    postgres_uri: str = ""

    # ========== Vector Store ==========
    chroma_persist_dir: str = "./.chroma"
    embedding_model: str = "BAAI/bge-m3"
    milvus_host: str = "localhost"
    milvus_port: int = 19530

    # ========== Redis ==========
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_db: int = 0
    redis_password: str = ""

    # ========== MySQL ==========
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = ""
    mysql_database: str = "rag"

    # ========== Microservices ==========
    order_service_url: str = ""         # 订单微服务地址
    inventory_service_url: str = ""     # 库存微服务地址
    user_service_url: str = ""          # 用户微服务地址
    notification_service_url: str = ""  # 通知微服务地址
    internal_api_token: str = ""        # 微服务间鉴权 Token（LLM 不可见）

    # ========== Security ==========
    api_key: str = ""                   # 可选，设置后 API 请求需 Bearer 认证

    # ========== Tools ==========
    search_provider: str = "duckduckgo"  # duckduckgo | tavily
    tavily_api_key: str = ""
    duckduckgo_enabled: bool = True
    search_max_results: int = 5

    # ========== RAG ==========
    rag_hybrid_search: bool = True      # 是否启用混合检索（BM25+向量）
    rag_top_k: int = 5                  # 默认返回 Top-K 文档
    rag_rerank_enabled: bool = False    # 是否启用重排序
    bert_model_name: str = "bert-base-chinese"  # BERT 分类模型


settings = Settings()