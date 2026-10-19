"""Middleware shared by the prebuilt (`create_agent`) agents.

These agents used to hand-roll the model/tool loop with `StateGraph` + `wrap_model`
+ conditional edges. They now call `create_agent` and only describe the genuinely
custom behaviour through middleware, so the glue lives here once instead of being
copy-pasted into every agent module.

Conventions worth knowing:

* `ModelSelectionMiddleware` keeps the frontend's model selector working
  (`config["configurable"]["model"]`). Every agent in this package includes it, which
  is why that lookup is no longer repeated in each module's model node.
* Side calls made outside the agent loop (safety check, birthdate extraction, ...)
  should use `run_model()` so they honour the same selector.
* Async-only hooks are called out explicitly: the service always drives agents via
  `astream()`, so those agents are async-only.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from langchain.agents import AgentState
from langchain.agents.middleware import AgentMiddleware, ModelRequest, Runtime
from langchain_core.messages import AIMessage, AnyMessage
from langgraph.config import get_config

from agents.safeguard import Safeguard, SafeguardOutput, SafetyAssessment
from core import get_model, settings

# `create_agent`'s overloads want every middleware to share one state schema, which is
# no longer true once an agent mixes, say, `SafeguardMiddleware` (its own state) with
# `ModelSelectionMiddleware` (plain `AgentState`). Declaring the list with this alias
# keeps the `create_agent(...)` call type-clean without changing behaviour.
MiddlewareList = list[AgentMiddleware[Any, None]]


def requested_model_name() -> Any | None:
    """Model explicitly selected for this run, or `None` when the default applies.

    Returns `None` outside a runnable context (e.g. when an agent is imported by a
    script or a test) instead of raising like `get_config()` does.
    """
    try:
        config = get_config()
    except RuntimeError:
        return None
    return config.get("configurable", {}).get("model")


def run_model() -> Any:
    """Model to use for side calls made outside the agent loop."""
    return get_model(requested_model_name() or settings.DEFAULT_MODEL)


class ModelSelectionMiddleware(AgentMiddleware):
    """Swap in the model requested at runtime via `config["configurable"]["model"]`.

    `create_agent` binds one model at build time; this middleware re-binds the request
    to the model the user picked in the UI, so no agent needs its own model node.
    Unbound models are expected here: the agent's model node binds the tools itself.
    """

    def _resolve(self, request: ModelRequest) -> ModelRequest:
        model_name = requested_model_name()
        if model_name is None:
            return request
        return request.override(model=get_model(model_name))

    def wrap_model_call(self, request: ModelRequest, handler):
        return handler(self._resolve(request))

    async def awrap_model_call(self, request: ModelRequest, handler):
        return await handler(self._resolve(request))


def safety_refusal(safety: SafeguardOutput) -> AIMessage:
    """Message shown instead of a model answer when the input was flagged."""
    return AIMessage(
        content=(
            f"This conversation was flagged for unsafe content: "
            f"{', '.join(safety.unsafe_categories)}"
        )
    )


class SafeguardedState(AgentState):
    """Agent state plus the input safety assessment."""

    safety: SafeguardOutput | None


class SafeguardMiddleware(AgentMiddleware[SafeguardedState, None]):
    """Reject prompt-injection attempts before the agent starts.

    Two hooks, both previously written by hand in the research/RAG agents:

    * `before_agent` classifies the conversation once per run (`Safeguard` falls back
      to "safe" when no Groq key is configured, same as before).
    * `wrap_model_call` short-circuits with a refusal message when the input was
      unsafe. Skipping the model call also ends the loop, because no tool calls can
      be produced.
    """

    state_schema = SafeguardedState

    def __init__(self) -> None:
        super().__init__()
        self._safeguard: Safeguard | None = None

    @property
    def safeguard(self) -> Safeguard:
        # Built lazily so importing an agent doesn't print the "skipping Safeguard"
        # notice (or construct a model) until the agent is actually used.
        if self._safeguard is None:
            self._safeguard = Safeguard()
        return self._safeguard

    def _assess(self, messages: list[AnyMessage]) -> dict[str, Any]:
        return {"safety": self.safeguard.invoke(messages)}

    async def _aassess(self, messages: list[AnyMessage]) -> dict[str, Any]:
        return {"safety": await self.safeguard.ainvoke(messages)}

    def before_agent(self, state: SafeguardedState, runtime: Runtime) -> dict[str, Any] | None:
        if not state.get("messages"):
            return None
        return self._assess(state["messages"])

    async def abefore_agent(
        self, state: SafeguardedState, runtime: Runtime
    ) -> dict[str, Any] | None:
        if not state.get("messages"):
            return None
        return await self._aassess(state["messages"])

    @staticmethod
    def _refusal(state: Mapping[str, Any]) -> AIMessage | None:
        safety = state.get("safety")
        if safety is not None and safety.safety_assessment is SafetyAssessment.UNSAFE:
            return safety_refusal(safety)
        return None

    def wrap_model_call(self, request: ModelRequest, handler):
        if refusal := self._refusal(request.state):
            return refusal
        return handler(request)

    async def awrap_model_call(self, request: ModelRequest, handler):
        if refusal := self._refusal(request.state):
            return refusal
        return await handler(request)
