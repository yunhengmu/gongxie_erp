"""数据模型包 —— 统一导出所有 Schema。"""

from app.schemas.common import ApiResponse, PageResponse, PageInfo, StatusCode
from app.schemas.chat import ChatRequest, ChatResponse, ErrorResponse
from app.schemas.rag import (
    RAGRequest,
    RAGResponse,
    DocumentUploadResponse,
    EvalRequest,
    EvalResponse,
)
from app.schemas.qa import QARequest, QAResponse

__all__ = [
    # 统一响应
    "ApiResponse",
    "PageResponse",
    "PageInfo",
    "StatusCode",
    # 聊天
    "ChatRequest",
    "ChatResponse",
    "ErrorResponse",
    # RAG
    "RAGRequest",
    "RAGResponse",
    "DocumentUploadResponse",
    "EvalRequest",
    "EvalResponse",
    # QA
    "QARequest",
    "QAResponse",
]