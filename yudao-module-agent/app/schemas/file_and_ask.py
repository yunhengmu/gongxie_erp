from pydantic import BaseModel
from typing import List

# 1. 先定义列表中每个元素的模型
class Tag(BaseModel):
    content: str
    tag: str

# 2. 在主模型中使用 List[Tag]
class ResponseFileAsk(BaseModel):
    response: str
    tags: List[Tag] 