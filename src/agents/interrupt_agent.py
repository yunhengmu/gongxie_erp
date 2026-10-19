"""Interrupt + long-term memory demo built on the prebuilt `create_agent`.

The hand-written graph was `background -> determine_birthdate -> generate_response`
with a `wrap_model` preprocessor. Those three nodes are not part of the model/tool
loop, so they become three small middleware instead:

1. `BackgroundNoteMiddleware` - one extra model turn before the loop. It stays a
   separate `before_agent` node, so its message is committed and streamed *before*
   the interrupt fires (an interrupt rolls back the node it happens in).
2. `BirthdateMiddleware` - reuses the birthdate from the store, asks the user through
   `interrupt()` when it is missing, then persists the answer.
3. `BirthdateResponseMiddleware` - builds the final system prompt from the birthdate.

Async-only: the service drives agents through `astream()`.
"""

import logging
from collections.abc import Mapping
from datetime import datetime
from typing import Any

from langchain.agents import AgentState, create_agent
from langchain.agents.middleware import AgentMiddleware, ModelRequest, Runtime
from langchain_core.messages import AIMessage, AnyMessage, HumanMessage, SystemMessage
from langgraph.config import get_config
from langgraph.store.base import BaseStore
from langgraph.types import interrupt
from pydantic import BaseModel, Field

from agents.middleware import MiddlewareList, ModelSelectionMiddleware, run_model
from core import get_model, settings

logger = logging.getLogger(__name__)

BIRTHDATE_KEY = "birthdate"

BACKGROUND_PROMPT = """
You are a helpful assistant that tells users there zodiac sign.
Provide a one sentence summary of the origin of zodiac signs.
Don't tell the user what their sign is, you are just demonstrating your knowledge on the topic.
"""

EXTRACTION_PROMPT = """
You are an expert at extracting birthdates from conversational text.

Rules for extraction:
- Look for user messages that mention birthdates
- Consider various date formats (MM/DD/YYYY, YYYY-MM-DD, Month Day, Year)
- Validate that the date is reasonable (not in the future)
- If no clear birthdate was provided by the user, return None
"""

RESPONSE_PROMPT = """
You are a helpful assistant.

Known information:
- The user's birthdate is {birthdate_str}

User's latest message: "{last_user_message}"

Based on the known information and the user's message, provide a helpful and relevant response.
If the user asked for their birthdate, confirm it.
If the user asked for their zodiac sign, calculate it and tell them.
Otherwise, respond conversationally based on their message.
"""


class BirthdateExtraction(BaseModel):
    birthdate: str | None = Field(
        description=(
            "The extracted birthdate in YYYY-MM-DD format. "
            "If no birthdate is found, this should be None."
        )
    )
    reasoning: str = Field(
        description="Explanation of how the birthdate was extracted or why no birthdate was found"
    )


class InterruptAgentState(AgentState):
    """Agent state plus the (long-term) birthdate resolved for this user."""

    birthdate: datetime | None


def _config_value(key: str) -> Any | None:
    try:
        config = get_config()
    except RuntimeError:
        return None
    return config.get("configurable", {}).get(key)


async def _extract_birthdate(messages: list[AnyMessage]) -> tuple[datetime | None, str]:
    """One extraction attempt; returns the parsed date (if any) and the reasoning."""
    model = (
        run_model().with_structured_output(BirthdateExtraction).with_config(tags=["skip_stream"])
    )
    result: BirthdateExtraction = await model.ainvoke(
        [SystemMessage(content=EXTRACTION_PROMPT), *messages]
    )

    if not result.birthdate:
        return None, result.reasoning
    try:
        return datetime.fromisoformat(result.birthdate), result.reasoning
    except ValueError:
        return None, "I couldn't understand the date format."


class BackgroundNoteMiddleware(AgentMiddleware[InterruptAgentState, None]):
    """Run one model turn before the agent loop, as the old `background` node did."""

    async def abefore_agent(
        self, state: InterruptAgentState, runtime: Runtime
    ) -> dict[str, Any] | None:
        response = await run_model().ainvoke(
            [SystemMessage(content=BACKGROUND_PROMPT), *state["messages"]]
        )
        return {"messages": [AIMessage(content=response.content)]}


class BirthdateMiddleware(AgentMiddleware[InterruptAgentState, None]):
    """Resolve the user's birthdate from the store, the conversation, or the user."""

    state_schema = InterruptAgentState

    @staticmethod
    async def _from_store(store: BaseStore, user_id: str) -> datetime | None:
        try:
            item = await store.aget((user_id,), BIRTHDATE_KEY)
            # Some store implementations return a list for a single key lookup.
            if isinstance(item, list):
                item = item[0] if item else None
            raw = item.value.get("birthdate") if item else None
            return datetime.fromisoformat(raw) if raw else None
        except Exception as e:
            # A missing/broken store should fall back to extraction, not fail the run.
            logger.error(f"Error reading birthdate for user {user_id}: {e}")
            return None

    async def abefore_agent(
        self, state: InterruptAgentState, runtime: Runtime
    ) -> dict[str, Any] | None:
        if state.get("birthdate"):
            return None

        user_id = _config_value("user_id")
        store: BaseStore | None = runtime.store
        if user_id and store is not None:
            if stored := await self._from_store(store, user_id):
                logger.info(f"Found birthdate in store for user {user_id}: {stored}")
                return {"birthdate": stored}

        birthdate: datetime | None = None
        messages = list(state["messages"])
        for _ in range(2):
            birthdate, reasoning = await _extract_birthdate(messages)
            if birthdate is not None:
                break
            answer = interrupt(f"{reasoning}\nPlease tell me your birthdate?")
            messages.append(HumanMessage(content=str(answer)))

        if birthdate is not None and user_id and store is not None:
            try:
                await store.aput((user_id,), BIRTHDATE_KEY, {"birthdate": birthdate.isoformat()})
            except Exception as e:
                logger.error(f"Error writing birthdate for user {user_id}: {e}")

        logger.info(f"Returning birthdate {birthdate} for user {user_id}")
        return {"birthdate": birthdate}


class BirthdateResponseMiddleware(AgentMiddleware[InterruptAgentState, None]):
    """Answer with a prompt tailored to the resolved birthdate."""

    state_schema = InterruptAgentState

    @staticmethod
    def _last_user_message(state: Mapping[str, Any]) -> str:
        for message in reversed(state["messages"]):
            if isinstance(message, HumanMessage):
                return str(message.content)
        return ""

    def _prompt(self, state: Mapping[str, Any]) -> str:
        birthdate: datetime | None = state.get("birthdate")
        return RESPONSE_PROMPT.format(
            birthdate_str=birthdate.strftime("%B %d, %Y") if birthdate else "unknown",
            last_user_message=self._last_user_message(state),
        )

    @staticmethod
    def _missing_birthdate(state: Mapping[str, Any]) -> AIMessage | None:
        if state.get("birthdate") is None:
            return AIMessage(
                content="I couldn't determine your birthdate. Could you please provide it?"
            )
        return None

    def wrap_model_call(self, request: ModelRequest, handler):
        if fallback := self._missing_birthdate(request.state):
            return fallback
        return handler(
            request.override(system_message=SystemMessage(content=self._prompt(request.state)))
        )

    async def awrap_model_call(self, request: ModelRequest, handler):
        if fallback := self._missing_birthdate(request.state):
            return fallback
        return await handler(
            request.override(system_message=SystemMessage(content=self._prompt(request.state)))
        )


middleware: MiddlewareList = [
    ModelSelectionMiddleware(),
    # Order matters for the `before_agent` hooks: the background note has to be its own
    # node so its message is committed before `BirthdateMiddleware` interrupts.
    BackgroundNoteMiddleware(),
    BirthdateMiddleware(),
    BirthdateResponseMiddleware(),
]

interrupt_agent = create_agent(
    model=get_model(settings.DEFAULT_MODEL),  # fallback; middleware overrides per call
    tools=[],
    middleware=middleware,
    name="interrupt-agent",
)
