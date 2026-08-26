"""记忆系统配置。

短时记忆（对话记忆） — Checkpointer：同一 thread_id 内的对话历史
长期记忆（跨会话记忆） — Store：      跨 thread_id 的用户信息持久化
"""
from config.settings import settings

# ==================== 短时记忆：Checkpointer ====================

if settings.memory_type == "postgres":
    from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

    checkpointer = AsyncPostgresSaver.from_conn_string(settings.postgres_uri)
else:
    from langgraph.checkpoint.memory import InMemorySaver

    checkpointer = InMemorySaver()

# ==================== 长期记忆：Store ====================

if settings.store_type == "postgres":
    try:
        from langgraph.store.postgres import AsyncPostgresStore

        store = AsyncPostgresStore.from_conn_string(settings.postgres_uri)
    except ImportError:
        from langgraph.store.memory import InMemoryStore

        store = InMemoryStore()
else:
    from langgraph.store.memory import InMemoryStore

    store = InMemoryStore()