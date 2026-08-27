"""Prompt 模块统一出口。

对外保持与原 prompts.py 兼容:
    from core.prompts import RESEARCH_PROMPT, CODE_REVIEW_PROMPT, RAG_PROMPT

内部已改为"模板文件 + 加载器 + 构建器"的工程化结构,
便于后续扩展多版本 / A-B 测试。
"""
from core.prompts.loader import load_template

# 对外暴露模板原始文本(进程级缓存)。
# 注意: 这里直接返回 str, 因为 langchain 的 create_agent 要求
# system_prompt 为 str / SystemMessage, 传 ChatPromptTemplate 会报错。
RESEARCH_PROMPT: str = load_template("research")
CODE_REVIEW_PROMPT: str = load_template("code_review")
RAG_PROMPT: str = load_template("rag")

__all__ = ["RESEARCH_PROMPT", "CODE_REVIEW_PROMPT", "RAG_PROMPT"]
