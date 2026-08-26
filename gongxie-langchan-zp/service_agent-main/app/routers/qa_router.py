# app/routers/qa.py
import logging

from fastapi import APIRouter, HTTPException, Depends

from app.schemas.qa import QARequest, QAResponse
from app.services.qa_service import QAService
from app.dependencies import get_qa_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", tags=["qa"])

@router.post("/qa", response_model=QAResponse)
async def ask(
    req: QARequest,
    svc: QAService = Depends(get_qa_service),  # ← 注入 Service
) -> QAResponse:
    try:
        tid, answer = await svc.ask(req.question, req.thread_id)
        return QAResponse(thread_id=tid, response=answer)
    except Exception as e:
        logger.exception("qa error")
        raise HTTPException(status_code=500, detail=str(e))