"""聊天/Agent 调用端点（Controller 层 — 类比 Spring 的 @RestController）。

路由设计（类似 Spring 的 @RestController + @PostMapping）：

    @RestController("/v1")        → router = APIRouter(prefix="/v1")
    @PostMapping("/chat")         → @router.post("/chat")
    @PostMapping("/research")     → @router.post("/research")
    @PostMapping("/review")       → @router.post("/review")

业务逻辑通过 Depends() 注入 AgentService（类比 @Autowired）。
"""
import logging

from fastapi import APIRouter, HTTPException, Depends

from app.services.agent_service import AgentService
from app.schemas.chat import ChatRequest, ChatResponse, ErrorResponse

logger = logging.getLogger(__name__)

# @RestController("/v1")
router = APIRouter(prefix="/v1", tags=["chat"])


# ==================== 通用 ==================== #

# @PostMapping("/chat")
@router.post("/chat", response_model=ChatResponse, responses={500: {"model": ErrorResponse}})
async def chat(
    request: ChatRequest,
    svc: AgentService = Depends(),  # ← @Autowired
):
    """通用聊天入口。智能路由: BERT 分类 → 策略选择 → Agent 分发。"""
    last_msg = request.messages[-1]["content"] if request.messages else ""
    agent, strategy = svc.select_agent(last_msg, request.tenant_id, request.user_id)
    logger.info("chat 路由: strategy=%s", strategy)

    if request.stream:
        return await svc.stream(agent, request.messages, request.thread_id)

    try:
        return await svc.invoke(agent, request.messages, request.thread_id)
    except Exception as e:
        logger.exception("chat error")
        raise HTTPException(status_code=500, detail=str(e))


# @PostMapping("/chat/stream")
@router.post("/chat/stream")
async def chat_stream(
    request: ChatRequest,
    svc: AgentService = Depends(),  # ← @Autowired
):
    """通用聊天流式入口。智能路由。"""
    last_msg = request.messages[-1]["content"] if request.messages else ""
    agent, strategy = svc.select_agent(last_msg, request.tenant_id, request.user_id)
    logger.info("chat_stream 路由: strategy=%s", strategy)
    return await svc.stream(agent, request.messages, request.thread_id)


# ==================== 指定 Agent ==================== #

# @PostMapping("/research")
@router.post("/research", response_model=ChatResponse)
async def research(
    request: ChatRequest,
    svc: AgentService = Depends(),  # ← @Autowired
):
    """指定 research_agent 调用。"""
    from core.agent import build_agent

    agent = build_agent("research", request.tenant_id, request.user_id)
    if request.stream:
        return await svc.stream(agent, request.messages, request.thread_id)
    try:
        return await svc.invoke(agent, request.messages, request.thread_id)
    except Exception as e:
        logger.exception("research error")
        raise HTTPException(status_code=500, detail=str(e))


# @PostMapping("/review")
@router.post("/review", response_model=ChatResponse)
async def review(
    request: ChatRequest,
    svc: AgentService = Depends(),  # ← @Autowired
):
    """指定 code_reviewer_agent 调用。"""
    from core.agent import build_agent

    agent = build_agent("code_reviewer", request.tenant_id, request.user_id)
    if request.stream:
        return await svc.stream(agent, request.messages, request.thread_id)
    try:
        return await svc.invoke(agent, request.messages, request.thread_id)
    except Exception as e:
        logger.exception("review error")
        raise HTTPException(status_code=500, detail=str(e))