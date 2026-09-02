"""Agent 服务层 — 类似 Spring 的 @Service。

职责：
  - Agent 调用（invoke / stream）
  - Agent 智能选择逻辑（BERT 分类器 + 策略选择）
  - 线程 ID 管理

路由层通过 Depends() 注入本服务，实现 Controller/Service 分离。
"""
import uuid
import json
import logging

from fastapi.responses import StreamingResponse

from core.agent import build_agent
from core.rag.classifier import classify_query, CATEGORY_CODE_REVIEW, CATEGORY_SPECIALIZED, CATEGORY_GREETING
from core.rag.strategy import select_strategy, RetrievalStrategy
from app.schemas.chat import ChatResponse

logger = logging.getLogger(__name__)


class AgentService:
    """Agent 服务，无状态，可通过 Depends() 注入。"""

    # ==================== 公开方法 ====================

    async def invoke(self, agent, messages: list, thread_id: str = "") -> ChatResponse:
        """调用 agent 并返回完整响应（非流式）。"""
        tid = thread_id or str(uuid.uuid4())
        config = {"configurable": {"thread_id": tid}}
        result = await agent.ainvoke({"messages": messages}, config=config)
        return ChatResponse(thread_id=tid, response=result["messages"][-1].content)

    async def stream(self, agent, messages: list, thread_id: str = ""):
        """调用 agent 并以 SSE 格式流式返回。"""
        tid = thread_id or str(uuid.uuid4())
        config = {"configurable": {"thread_id": tid}}

        async def _event_generator():
            # 先发送元信息
            yield f"data: {json.dumps({'type': 'meta', 'thread_id': tid})}\n\n"

            # 流式 event 迭代
            async for event in agent.astream_events(
                {"messages": messages},
                config=config,
                version="v2",
            ):
                if event.get("event") == "on_chat_model_stream":
                    chunk = event["data"]["chunk"]
                    if chunk.content:
                        yield f"data: {json.dumps({'type': 'token', 'content': chunk.content})}\n\n"

            yield "data: [DONE]\n\n"

        return StreamingResponse(
            _event_generator(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no",
            },
        )

    def select_agent(self, message: str, tenant_id: str = "", user_id: str = ""):
        """智能 Agent 选择 — 基于 BERT 分类器 + 策略选择器。

        替代原有的简单前缀匹配 ("review:" → code_reviewer)。

        路由逻辑:
          1. BERT 分类器分析问题类型
          2. 策略选择器决定检索策略
          3. 返回按 (tenant_id, user_id) 组装的最优 Agent:
             - 专业知识 + 向量库可用 → rag (混合检索)
             - 代码审查 → code_reviewer
             - 通用知识 → research
             - 问候 → research (直接回复)

        长期记忆工具按 (tenant_id, user_id) 闭包生成，namespace 固化，
        因此 agent 无法在启动时静态构建，只能按请求组装（build_agent）。
        """
        # 1. 使用 BERT 分类器分析问题
        classification = classify_query(message)
        strategy, strategy_note = select_strategy(classification, message)

        logger.info(
            "Agent 路由: category=%s strategy=%s note=%s",
            classification.category, strategy.value, strategy_note,
        )

        # 2. 根据策略选择 Agent（按租户/用户上下文组装，含长期记忆工具）
        if strategy == RetrievalStrategy.HYBRID_SEARCH:
            return build_agent("rag", tenant_id, user_id), strategy.value

        if strategy == RetrievalStrategy.CODE_REVIEW:
            return build_agent("code_reviewer", tenant_id, user_id), strategy.value

        # 通用知识 / 问候 → research
        return build_agent("research", tenant_id, user_id), strategy.value