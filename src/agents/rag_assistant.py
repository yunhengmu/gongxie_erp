"""RAG assistant (AcmeTech handbook) built on the prebuilt `create_agent`.

Same story as `research_assistant`: the tool loop comes from `create_agent`, and the
cross-cutting concerns (runtime model selection, input safeguard, loop limit) come
from `agents.middleware`.
"""

from datetime import datetime

from langchain.agents import create_agent
from langchain.agents.middleware import ModelCallLimitMiddleware

from agents.middleware import MiddlewareList, ModelSelectionMiddleware, SafeguardMiddleware
from agents.tools import database_search
from core import get_model, settings

tools = [database_search]

current_date = datetime.now().strftime("%B %d, %Y")
instructions = f"""
    You are AcmeBot, a helpful and knowledgeable virtual assistant designed to support employees by retrieving
    and answering questions based on AcmeTech's official Employee Handbook. Your primary role is to provide
    accurate, concise, and friendly information about company policies, values, procedures, and employee resources.
    Today's date is {current_date}.

    NOTE: THE USER CAN'T SEE THE TOOL RESPONSE.

    A few things to remember:
    - If you have access to multiple databases, gather information from a diverse range of sources before crafting your response.
    - Please include markdown-formatted links to any citations used in your response. Only include one
    or two citations per response unless more are needed. ONLY USE LINKS RETURNED BY THE TOOLS.
    - Only use information from the database. Do not use information from outside sources.
    """

middleware: MiddlewareList = [
    ModelSelectionMiddleware(),
    SafeguardMiddleware(),
    ModelCallLimitMiddleware(run_limit=10, exit_behavior="end"),
]

rag_assistant = create_agent(
    model=get_model(settings.DEFAULT_MODEL),  # fallback; middleware overrides per call
    tools=tools,
    system_prompt=instructions,
    middleware=middleware,
    name="rag-assistant",
)
