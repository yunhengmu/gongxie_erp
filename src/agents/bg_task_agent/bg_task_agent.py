"""Background-task demo built on the prebuilt `create_agent`.

The graph used to be `bg_task -> model -> END` with a hand-written model node. The
task reporting is not part of the agent loop, so it becomes a `before_agent` hook that
pushes updates through `runtime.stream_writer`; the reply itself is the prebuilt model
call.

Async-only: the task sequence sleeps with `asyncio`, and the service drives agents
through `astream()`.
"""

import asyncio
from typing import Any

from langchain.agents import create_agent
from langchain.agents.middleware import AgentMiddleware, AgentState, Runtime

from agents.bg_task_agent.task import Task
from agents.middleware import ModelSelectionMiddleware
from core import get_model, settings


class BackgroundTaskMiddleware(AgentMiddleware[AgentState, None]):
    """Stream custom task-progress data while the agent "works"."""

    async def abefore_agent(self, state: AgentState, runtime: Runtime) -> dict[str, Any] | None:
        writer = runtime.stream_writer
        task1 = Task("Simple task 1...", writer)
        task2 = Task("Simple task 2...", writer)

        task1.start()
        await asyncio.sleep(2)
        task2.start()
        await asyncio.sleep(2)
        task1.write_data(data={"status": "Still running..."})
        await asyncio.sleep(2)
        task2.finish(result="error", data={"output": 42})
        await asyncio.sleep(2)
        task1.finish(result="success", data={"output": 42})
        return None


bg_task_agent = create_agent(
    model=get_model(settings.DEFAULT_MODEL),  # fallback; middleware overrides per call
    tools=[],
    middleware=[ModelSelectionMiddleware(), BackgroundTaskMiddleware()],
    name="bg-task-agent",
)
