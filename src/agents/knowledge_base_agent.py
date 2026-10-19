"""Bedrock Knowledge Base RAG agent built on the prebuilt `create_agent`.

The old version was three hand-wired nodes (`retrieve_documents` ->
`prepare_augmented_prompt` -> `model`) plus a `wrap_model` preprocessor. Retrieval and
prompt augmentation are now one middleware: it fetches documents before the agent
starts and injects them into the system prompt for the (single) model call. The agent
has no tools, so there is nothing else to wire.
"""

import logging
import os
from typing import Any, cast

from langchain.agents import AgentState, create_agent
from langchain.agents.middleware import AgentMiddleware, ModelRequest, Runtime
from langchain_aws import AmazonKnowledgeBasesRetriever
from langchain_core.messages import HumanMessage, SystemMessage

from agents.middleware import MiddlewareList, ModelSelectionMiddleware
from core import get_model, settings

logger = logging.getLogger(__name__)

BASE_PROMPT = """You are a helpful assistant that provides accurate information based on retrieved documents.

You will receive a query along with relevant documents retrieved from a knowledge base. Use these documents to inform your response.

Follow these guidelines:
1. Base your answer primarily on the retrieved documents
2. If the documents contain the answer, provide it clearly and concisely
3. If the documents are insufficient, state that you don't have enough information
4. Never make up facts or information not present in the documents
5. Always cite the source documents when referring to specific information
6. If the documents contradict each other, acknowledge this and explain the different perspectives

Format your response in a clear, conversational manner. Use markdown formatting when appropriate.
"""

WITH_DOCUMENTS_PROMPT = (
    "\n\nI've retrieved the following documents that may be relevant to the query:\n\n"
    "{documents}\n\nPlease use these documents to inform your response to the user's query. "
    "Only use information from these documents and clearly indicate when you are unsure."
)

NO_DOCUMENTS_PROMPT = "\n\nNo relevant documents were found in the knowledge base for this query."


class KnowledgeBaseState(AgentState):
    """Agent state plus the documents retrieved for this turn."""

    retrieved_documents: list[dict[str, Any]]
    kb_documents: str


def get_kb_retriever() -> AmazonKnowledgeBasesRetriever:
    """Create and return a Knowledge Base retriever instance."""
    kb_id = os.environ.get("AWS_KB_ID", "")
    if not kb_id:
        raise ValueError("AWS_KB_ID environment variable must be set")

    return AmazonKnowledgeBasesRetriever(
        knowledge_base_id=kb_id,
        retrieval_config={"vectorSearchConfiguration": {"numberOfResults": 3}},
    )


def _summarize(documents: list[Any]) -> list[dict[str, Any]]:
    return [
        {
            "id": doc.metadata.get("id", f"doc-{i}"),
            "source": doc.metadata.get("source", "Unknown"),
            "title": doc.metadata.get("title", f"Document {i}"),
            "content": doc.page_content,
            "relevance_score": doc.metadata.get("score", 0),
        }
        for i, doc in enumerate(documents, 1)
    ]


def _format(documents: list[dict[str, Any]]) -> str:
    return "\n\n".join(
        f"--- Document {i} ---\n"
        f"Source: {doc.get('source', 'Unknown')}\n"
        f"Title: {doc.get('title', 'Unknown')}\n\n"
        f"{doc.get('content', '')}"
        for i, doc in enumerate(documents, 1)
    )


class KnowledgeBaseMiddleware(AgentMiddleware[KnowledgeBaseState, None]):
    """Retrieve documents before the run, then put them in the system prompt.

    Async-only: the service drives agents through `astream()`, and the retriever call
    is the only reason this hook needs I/O at all.
    """

    state_schema = KnowledgeBaseState

    async def abefore_agent(
        self, state: KnowledgeBaseState, runtime: Runtime
    ) -> dict[str, Any] | None:
        human_messages = [msg for msg in state["messages"] if isinstance(msg, HumanMessage)]
        if not human_messages:
            return {"retrieved_documents": [], "kb_documents": ""}

        query = cast(str, human_messages[-1].content)
        try:
            documents = await get_kb_retriever().ainvoke(query)
        except Exception as e:
            logger.error(f"Error retrieving documents: {e}")
            return {"retrieved_documents": [], "kb_documents": ""}

        summaries = _summarize(documents)
        logger.info(f"Retrieved {len(summaries)} documents for query: {query[:50]}...")
        return {"retrieved_documents": summaries, "kb_documents": _format(summaries)}

    def _augment(self, request: ModelRequest) -> ModelRequest:
        documents = request.state.get("kb_documents")
        prompt = BASE_PROMPT + (
            WITH_DOCUMENTS_PROMPT.format(documents=documents) if documents else NO_DOCUMENTS_PROMPT
        )
        return request.override(system_message=SystemMessage(content=prompt))

    def wrap_model_call(self, request: ModelRequest, handler):
        return handler(self._augment(request))

    async def awrap_model_call(self, request: ModelRequest, handler):
        return await handler(self._augment(request))


middleware: MiddlewareList = [ModelSelectionMiddleware(), KnowledgeBaseMiddleware()]

kb_agent = create_agent(
    model=get_model(settings.DEFAULT_MODEL),  # fallback; middleware overrides per call
    tools=[],
    middleware=middleware,
    name="knowledge-base-agent",
)
