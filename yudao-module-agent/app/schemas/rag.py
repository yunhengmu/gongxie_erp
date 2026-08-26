"""RAG 专用数据模型。"""
from typing import Optional, List

from pydantic import BaseModel, Field


class RAGRequest(BaseModel):
    """RAG 问答请求。"""
    question: str = Field(..., min_length=1, description="用户问题")
    thread_id: str = Field("", description="会话 ID，空字符串表示自动生成")
    use_hybrid: bool = Field(True, description="是否启用混合检索（BM25+向量）")
    top_k: int = Field(5, ge=1, le=20, description="检索文档数量")
    stream: bool = Field(False, description="是否流式返回")


class RAGResponse(BaseModel):
    """RAG 问答响应。"""
    thread_id: str = Field(..., description="会话 ID")
    answer: str = Field(..., description="AI 回答")
    sources: List[dict] = Field(default_factory=list, description="检索到的文档来源")


class DocumentUploadResponse(BaseModel):
    """文档上传响应。"""
    success: bool
    file_name: str
    chunk_count: int = 0
    message: str = ""


class EvalRequest(BaseModel):
    """RAG 评估请求。"""
    samples: List[dict] = Field(..., min_length=1, description="评估样本列表")


class EvalResponse(BaseModel):
    """RAG 评估响应。"""
    context_precision: float = 0.0
    context_recall: float = 0.0
    faithfulness: float = 0.0
    answer_relevancy: float = 0.0
    answer_correctness: float = 0.0
    overall_score: float = 0.0
    report: str = ""