# app/dependencies.py
"""依赖装配层。

类比 Spring 的 @Configuration + @Bean:
  - 每个函数就是一个 Bean 定义
  - 通过 lru_cache 实现单例(Scope = singleton)
"""
from functools import lru_cache

from fastapi import Depends

from core.agent import research_agent, code_reviewer_agent, rag_agent
from app.services.qa_service import QAService
from app.services.rag_service import RAGService

# ---------- Agent Bean(singleton) ----------

@lru_cache
def get_research_agent():
    """单例 Agent Bean。"""
    return research_agent

@lru_cache
def get_code_reviewer_agent():
    return code_reviewer_agent

@lru_cache
def get_rag_agent():
    """RAG Agent Bean。"""
    return rag_agent

# ---------- Service Bean(prototype: 每次请求新建) ----------

def get_qa_service(
    agent = Depends(get_research_agent),  # ← 注入 Agent Bean
) -> QAService:
    """每次请求创建新的 Service 实例,但复用单例 Agent。"""
    return QAService(agent=agent, thread_id_prefix="qa")

def get_rag_service(
    agent = Depends(get_rag_agent),
) -> RAGService:
    """RAG Service Bean。"""
    return RAGService(agent=agent)

# 同步获取 RAG Service（供 WebSocket 等非依赖注入场景使用）
def get_rag_service_sync() -> RAGService:
    """同步获取 RAG Service 实例。"""
    return RAGService(agent=rag_agent)