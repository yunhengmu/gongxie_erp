"""Research assistant built on the prebuilt `create_agent` ReAct loop.

`create_agent` wires the model <-> tools loop (bind tools, run tools, loop back,
stop on a plain answer), so this module only declares *what* the agent is: model,
tools, system prompt. The two behaviours that are not part of the loop live in
`agents.middleware`: runtime model selection and the input safeguard.
"""

from datetime import datetime

from langchain.agents import create_agent
from langchain.agents.middleware import ModelCallLimitMiddleware
from langchain_community.tools import DuckDuckGoSearchResults, OpenWeatherMapQueryRun
from langchain_community.utilities import OpenWeatherMapAPIWrapper
from langchain_core.tools import BaseTool

from agents.middleware import MiddlewareList, ModelSelectionMiddleware, SafeguardMiddleware
from agents.tools import calculator
from core import get_model, settings

web_search = DuckDuckGoSearchResults(name="WebSearch")
tools: list[BaseTool] = [web_search, calculator]

# Add weather tool if API key is set
# Register for an API key at https://openweathermap.org/api/
if settings.OPENWEATHERMAP_API_KEY:
    wrapper = OpenWeatherMapAPIWrapper(
        openweathermap_api_key=settings.OPENWEATHERMAP_API_KEY.get_secret_value()
    )
    tools.append(OpenWeatherMapQueryRun(name="Weather", api_wrapper=wrapper))

current_date = datetime.now().strftime("%B %d, %Y")
instructions = f"""
    You are a helpful research assistant with the ability to search the web and use other tools.
    Today's date is {current_date}.

    NOTE: THE USER CAN'T SEE THE TOOL RESPONSE.

    A few things to remember:
    - Please include markdown-formatted links to any citations used in your response. Only include one
    or two citations per response unless more are needed. ONLY USE LINKS RETURNED BY THE TOOLS.
    - Use calculator tool with numexpr to answer math questions. The user does not understand numexpr,
      so for the final response, use human readable format - e.g. "300 * 200", not "(300 \\times 200)".
    """

middleware: MiddlewareList = [
    ModelSelectionMiddleware(),
    SafeguardMiddleware(),
    # Replaces the hand-written `remaining_steps` guard: stop the loop with a
    # message instead of letting it run into LangGraph's recursion limit.
    ModelCallLimitMiddleware(run_limit=10, exit_behavior="end"),
]

research_assistant = create_agent(
    model=get_model(settings.DEFAULT_MODEL),  # fallback; middleware overrides per call
    tools=tools,
    system_prompt=instructions,
    middleware=middleware,
    name="research-assistant",
)
