"""Prompt 构建工厂。

把原始模板字符串封装成 LangChain 的 ChatPromptTemplate,
供 Agent 使用(类比 @Bean 工厂方法)。
"""
from langchain_core.prompts import ChatPromptTemplate

from core.prompts.loader import load_template


def build_research_prompt() -> ChatPromptTemplate:
    """构建 research agent 的 prompt。"""
    template = load_template("research")
    return ChatPromptTemplate.from_template(template)


def build_code_review_prompt() -> ChatPromptTemplate:
    """构建 code reviewer 的 prompt。"""
    template = load_template("code_review")
    return ChatPromptTemplate.from_template(template)


def build_rag_prompt() -> ChatPromptTemplate:
    """构建 RAG agent 的 prompt，包含 {context} 和 {chat_history} 占位符。"""
    template = load_template("rag")
    return ChatPromptTemplate.from_template(template)
