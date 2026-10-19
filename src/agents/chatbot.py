"""Plain chatbot: one model call per turn, no tools.

Previously an `@entrypoint` function that hand-managed conversation history through
`entrypoint.final(save=...)`. `create_agent` keeps the message list in state for us
(the service attaches the checkpointer), so the only thing left to declare is the
model - plus the shared middleware that honours the UI's model selector.
"""

from langchain.agents import create_agent

from agents.middleware import ModelSelectionMiddleware
from core import get_model, settings

chatbot = create_agent(
    model=get_model(settings.DEFAULT_MODEL),  # fallback; middleware overrides per call
    tools=[],
    middleware=[ModelSelectionMiddleware()],
    name="chatbot",
)
