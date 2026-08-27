"""所有工具定义。

工具列表:
  - web_search:   DuckDuckGo / Tavily 网络搜索（真实实现）
  - code_review:  LLM 代码审查（真实实现）
  - kb_search:    向量库检索（可选，依赖 Chroma + BGE-M3）
"""
import logging

from langchain.tools import tool
from langchain_core.tools.retriever import create_retriever_tool

from config.settings import settings

logger = logging.getLogger(__name__)


# ========== 网络搜索 ==========

def _search_duckduckgo(query: str, max_results: int = 5) -> str:
    """DuckDuckGo 搜索（免费，无需 API Key）。"""
    try:
        from duckduckgo_search import DDGS

        with DDGS() as ddgs:
            results = list(ddgs.text(query, max_results=max_results))
        if not results:
            return f"[DuckDuckGo] 未找到与 '{query}' 相关的结果。"

        lines = []
        for i, r in enumerate(results, 1):
            title = r.get("title", "无标题")
            href = r.get("href", "")
            body = r.get("body", "")
            lines.append(f"{i}. {title}\n   URL: {href}\n   {body}")
        return "\n\n".join(lines)
    except Exception as e:
        logger.warning("DuckDuckGo search failed: %s", e)
        return f"[搜索失败] DuckDuckGo: {e}"


def _search_tavily(query: str, max_results: int = 5) -> str:
    """Tavily 搜索（需 API Key，质量更高）。"""
    try:
        from tavily import TavilyClient

        client = TavilyClient(api_key=settings.tavily_api_key)
        response = client.search(query, max_results=max_results, search_depth="basic")
        results = response.get("results", [])
        if not results:
            return f"[Tavily] 未找到与 '{query}' 相关的结果。"

        lines = []
        for i, r in enumerate(results, 1):
            title = r.get("title", "无标题")
            url = r.get("url", "")
            content = r.get("content", "")
            lines.append(f"{i}. {title}\n   URL: {url}\n   {content}")
        return "\n\n".join(lines)
    except Exception as e:
        logger.warning("Tavily search failed, falling back to DuckDuckGo: %s", e)
        return _search_duckduckgo(query, max_results)


@tool
def web_search(query: str) -> str:
    """Search the web for information. Returns formatted search results with titles, URLs, and snippets.

    Use this tool when you need to find current information, facts, or data from the internet.
    """
    max_results = settings.search_max_results
    provider = settings.search_provider

    if provider == "tavily" and settings.tavily_api_key:
        return _search_tavily(query, max_results)
    else:
        return _search_duckduckgo(query, max_results)


# ========== 代码审查 ==========

@tool
async def code_review(code: str) -> str:
    """Review a code snippet for bugs, security issues, and suggest improvements.

    Provide the full code snippet to get a detailed review.
    """
    try:
        from core.models import deepseek_model

        review_prompt = (
            "你是一位资深代码审查专家。请审查以下代码，从以下几个方面给出反馈：\n"
            "1. 🐛 Bug 和逻辑错误\n"
            "2. 🔒 安全漏洞\n"
            "3. ⚡ 性能问题\n"
            "4. 📝 代码风格和可读性\n"
            "5. 💡 改进建议\n\n"
            "请用中文输出，简洁明了。\n\n"
            f"```\n{code}\n```"
        )
        response = await deepseek_model.ainvoke(review_prompt)
        return response.content
    except Exception as e:
        logger.exception("code_review failed")
        return f"[代码审查失败] {e}"


# ========== RAG 工具（可选，依赖向量库） ==========

def make_retriever_tool():
    """创建向量库检索工具。需要先安装 fastembed + langchain-chroma。"""
    try:
        from langchain_chroma import Chroma
        from langchain_community.embeddings.fastembed import FastEmbedEmbeddings

        embeddings = FastEmbedEmbeddings(model_name=settings.embedding_model)
        vectorstore = Chroma(
            collection_name="kb",
            embedding_function=embeddings,
            persist_directory=settings.chroma_persist_dir,
        )
        return create_retriever_tool(
            vectorstore.as_retriever(search_kwargs={"k": 4}),
            name="kb_search",
            description="从本地知识库检索相关内容",
        )
    except Exception as e:
        logger.warning("向量库检索工具初始化失败，跳过 kb_search: %s", e)
        return None
