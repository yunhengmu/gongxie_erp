"""Minimal `create_agent` template - copy this file to start a new agent.

Everything the agent needs is declared, nothing is wired by hand:

    model + tools + system_prompt  ->  the ReAct loop
    ModelSelectionMiddleware       ->  the UI's model selector keeps working

The other agents in this package follow the same shape; add middleware only for
behaviour that is genuinely outside the model <-> tools loop (see `agents.middleware`
and `interrupt_agent.py`).
"""

from datetime import datetime

from langchain.agents import create_agent
from langchain_community.tools import DuckDuckGoSearchResults

from agents.middleware import ModelSelectionMiddleware
from agents.tools import calculator
from core import get_model, settings

web_search = DuckDuckGoSearchResults(name="WebSearch")
tools = [web_search, calculator]

current_date = datetime.now().strftime("%B %d, %Y")
system_prompt = f"""
    You are a helpful research assistant with the ability to search the web and use other tools.
    Today's date is {current_date}.

    NOTE: THE USER CAN'T SEE THE TOOL RESPONSE.

    A few things to remember:
    - Please include markdown-formatted links to any citations used in your response. Only include one
    or two citations per response unless more are needed. ONLY USE LINKS RETURNED BY THE TOOLS.
    - Use calculator tool with numexpr to answer math questions. The user does not understand numexpr,
    so for the final response, use human readable format - e.g. "300 * 200", not "(300 \\times 200)".
    """

prebuilt_agent = create_agent(
    model=get_model(settings.DEFAULT_MODEL),  # fallback; middleware overrides per call
    tools=tools,
    system_prompt=system_prompt,
    middleware=[ModelSelectionMiddleware()],
    name="prebuilt-agent",
)
