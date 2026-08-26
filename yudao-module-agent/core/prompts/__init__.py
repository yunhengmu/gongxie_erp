"""Prompt 模块统一出口。

对外保持与原 prompts.py 兼容:
    from core.prompts import RESEARCH_PROMPT, CODE_REVIEW_PROMPT, RAG_PROMPT

内部已改为"模板文件 + 加载器 + 构建器"的工程化结构,
便于后续扩展多版本 / A-B 测试。
"""
from langchain_core.prompts import ChatPromptTemplate

from core.prompts.builders import build_research_prompt, build_code_review_prompt, build_rag_prompt

# 对外暴露构建好的 Prompt Bean(singleton,进程级缓存)
RESEARCH_PROMPT: ChatPromptTemplate = build_research_prompt()
CODE_REVIEW_PROMPT: ChatPromptTemplate = build_code_review_prompt()
RAG_PROMPT: ChatPromptTemplate = build_rag_prompt()

__all__ = ["RESEARCH_PROMPT", "CODE_REVIEW_PROMPT", "RAG_PROMPT"]
