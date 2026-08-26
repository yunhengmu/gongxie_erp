# app/services/qa_service.py
import uuid
import logging
from typing import Any, Protocol

logger = logging.getLogger(__name__)

# ---------- Protocol(接口抽象,类比 Java Interface) ----------

class RunnableAgent(Protocol):
    """Agent 必须实现的方法契约。"""
    async def ainvoke(self, input: dict, config: dict) -> dict: ...

# ---------- Service(业务 Bean) ----------

class QAService:
    """问答服务。
    
    通过构造函数注入依赖,而不是在内部 import,
    这样:
      1. 单元测试可传入 Mock Agent
      2. 切换 Agent 实现不需要改 Service
      3. 符合依赖倒置原则(DIP)
    """

    def __init__(
        self,
        agent: RunnableAgent,          # 主 Agent
        thread_id_prefix: str = "qa",  # 可选配置
    ):
        self._agent = agent
        self._prefix = thread_id_prefix

    async def ask(self, question: str, thread_id: str = "") -> tuple[str, str]:
        """非流式问答。返回 (thread_id, answer)。"""
        # 1. 生成或复用 thread_id
        tid = thread_id or f"{self._prefix}-{uuid.uuid4()}"

        # 2. 构造 messages(OpenAI 格式)
        messages = [{"role": "user", "content": question}]

        # 3. 调用注入进来的 Agent
        config = {"configurable": {"thread_id": tid}}
        result = await self._agent.ainvoke({"messages": messages}, config=config)

        # 4. 取最后一条 AI 消息
        answer = result["messages"][-1].content
        return tid, answer