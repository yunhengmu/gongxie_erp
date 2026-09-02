"""所有 Agent 的工厂定义。

基于 LangGraph StateGraph 构建（langchain.agents.create_agent 在 V1.0+ 已
迁移到 LangGraph 的 create_react_agent 实现）。
"""
from langchain.agents import create_agent

from core.models import deepseek_model
from core.tool.tools import web_search, code_review, make_retriever_tool
from core.tool.api_tools import api_tools
from core.tool.memory_tools_tenant import make_memory_tools, MEMORY_TOOLS_PROMPT
from core.prompts import RESEARCH_PROMPT, CODE_REVIEW_PROMPT, RAG_PROMPT
from core.memory import checkpointer, store

# 基础工具列表（微服务调用）
tools = [web_search] + api_tools

# 尝试加载向量库检索工具
kb_tool = make_retriever_tool()
if kb_tool:
    tools.append(kb_tool)

research_agent = create_agent(
    model=deepseek_model,
    tools=[web_search] + api_tools,
    system_prompt=RESEARCH_PROMPT,
    checkpointer=checkpointer,
    store=store,
)

code_reviewer_agent = create_agent(
    model=deepseek_model,
    tools=[code_review],
    system_prompt=CODE_REVIEW_PROMPT,
    checkpointer=checkpointer,
    store=store,
)

# RAG Agent: 使用 RAG 专用提示词，挂载知识库检索工具
rag_tools = [web_search] + api_tools
if kb_tool:
    rag_tools = [kb_tool, web_search] + api_tools  # kb_search 优先

rag_agent = create_agent(
    model=deepseek_model,
    tools=rag_tools,
    system_prompt=RAG_PROMPT,
    checkpointer=checkpointer,
    store=store,
)


def build_agent(kind: str, tenant_id: str, user_id: str):
    """按租户上下文组装 agent：静态工具集 + 租户化长期记忆工具。

    静态 agent（上方三个）不携带记忆工具——记忆工具必须按 (tenant, user)
    闭包生成，无法在启动时静态构建。create_agent 只是构建图定义，开销很小；
    checkpointer / store / model 仍是进程级单例，不重复创建。

    Args:
        kind: "research" | "code_reviewer" | "rag"
        tenant_id: 租户 ID（长期记忆 namespace 第一段）
        user_id: 用户 ID（长期记忆 namespace 第二段）
    """
    memory = make_memory_tools(tenant_id, user_id)

    if kind == "code_reviewer":
        return create_agent(
            model=deepseek_model,
            tools=[code_review] + memory,
            system_prompt=CODE_REVIEW_PROMPT + MEMORY_TOOLS_PROMPT,
            checkpointer=checkpointer,
            store=store,
        )

    if kind == "rag":
        return create_agent(
            model=deepseek_model,
            tools=rag_tools + memory,
            system_prompt=RAG_PROMPT + MEMORY_TOOLS_PROMPT,
            checkpointer=checkpointer,
            store=store,
        )

    # 默认 research
    return create_agent(
        model=deepseek_model,
        tools=[web_search] + api_tools + memory,
        system_prompt=RESEARCH_PROMPT + MEMORY_TOOLS_PROMPT,
        checkpointer=checkpointer,
        store=store,
    )
