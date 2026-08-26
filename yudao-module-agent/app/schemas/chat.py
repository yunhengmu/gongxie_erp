"""请求/响应模型。"""
from pydantic import BaseModel


class ChatRequest(BaseModel):
    """聊天请求。"""
    messages: list[dict]  # [{"role": "user", "content": "..."}]
    thread_id: str = ""
    stream: bool = False


class ChatResponse(BaseModel):
    """聊天响应。"""
    thread_id: str
    response: str


class ErrorResponse(BaseModel):
    """错误响应。"""
    detail: str