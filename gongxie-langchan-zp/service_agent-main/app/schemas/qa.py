from pydantic import BaseModel, Field
from typing import Literal

class QARequest(BaseModel):
    question: str = Field(..., min_length=1, description="用户问题")
    thread_id: str = Field("", description="会话ID")
    mode: Literal['normal', 'stream'] = Field('normal', description='返回模式')

class QAResponse(BaseModel):
    response: str = Field(..., description="答案")
    thread_id: str = Field("", description="会话ID")