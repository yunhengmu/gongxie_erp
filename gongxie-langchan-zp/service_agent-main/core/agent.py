"""所有 Agent 的工厂定义。

基于 LangGraph StateGraph 构建（langchain.agents.create_agent 在 V1.0+ 已
迁移到 LangGraph 的 create_react_agent 实现）。
"""
from langchain.agents import create_agent

from core.models import deepseek_model
from core.tool.tools import web_search, code_review, make_retriever_tool
from core.tool.memory_tools import memory_tools
from core.tool.api_tools import api_tools
from core.prompts import RESEARCH_PROMPT, CODE_REVIEW_PROMPT, RAG_PROMPT
from core.memory import checkpointer, store

# 基础工具列表（含长期记忆 + 微服务调用）
tools = [web_search] + memory_tools + api_tools

# 尝试加载向量库检索工具
kb_tool = make_retriever_tool()
if kb_tool:
    tools.append(kb_tool)

research_agent = create_agent(
    model=deepseek_model,
    tools=[web_search] + memory_tools + api_tools,
    system_prompt=RESEARCH_PROMPT,
    checkpointer=checkpointer,
    store=store,
)

code_reviewer_agent = create_agent(
    model=deepseek_model,
    tools=[code_review] + memory_tools,
    system_prompt=CODE_REVIEW_PROMPT,
    checkpointer=checkpointer,
    store=store,
)

# RAG Agent: 使用 RAG 专用提示词，挂载知识库检索工具
rag_tools = [web_search] + memory_tools + api_tools
if kb_tool:
    rag_tools = [kb_tool, web_search] + memory_tools + api_tools  # kb_search 优先

rag_agent = create_agent(
    model=deepseek_model,
    tools=rag_tools,
    system_prompt=RAG_PROMPT,
    checkpointer=checkpointer,
    store=store,
)