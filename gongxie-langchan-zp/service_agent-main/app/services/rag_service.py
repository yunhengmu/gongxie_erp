"""RAG 业务服务。

编排 RAG 问答流程: 分类 → 检索 → 构建 Prompt → LLM 生成 → 缓存。
"""
import logging
import uuid
from typing import List, Optional, AsyncGenerator

from langgraph.graph.state import CompiledStateGraph

from config.settings import settings
from core.rag.classifier import classify_query, ClassificationResult, CATEGORY_GREETING
from core.rag.strategy import select_strategy, RetrievalStrategy
from core.rag.retriever import HybridRetriever
from core.rag.cache import get_cache, cache_result
from core.rag.evaluate import RagasEvaluator, EvalSample, generate_eval_report

logger = logging.getLogger(__name__)


class RAGService:
    """RAG 问答服务。

    完整的 RAG 流程:
      1. 问题分类（BERT / 规则）
      2. 策略选择（直接 LLM / 混合检索）
      3. 检索上下文（BM25 + 向量 + RRF）
      4. 构建 Prompt + 调用 Agent
      5. 缓存结果
    """

    def __init__(self, agent: CompiledStateGraph):
        self._agent = agent
        self._retriever = HybridRetriever()
        self._cache = get_cache()

    async def ask(
        self,
        question: str,
        thread_id: str = "",
        use_hybrid: bool = True,
        top_k: int = 5,
    ) -> dict:
        """RAG 问答（非流式）。

        Returns:
            {"thread_id": str, "answer": str, "sources": list}
        """
        thread_id = thread_id or f"rag-{uuid.uuid4().hex[:8]}"

        # 1. 问题分类
        classification = classify_query(question)
        logger.info("问题分类: %s (confidence=%.2f)", classification.category, classification.confidence)

        # 2. 策略选择
        strategy, strategy_note = select_strategy(classification, question)
        logger.info("检索策略: %s (%s)", strategy.value, strategy_note)

        # 3. 检索上下文（仅专业知识需要）
        sources = []
        context = ""

        if strategy == RetrievalStrategy.HYBRID_SEARCH:
            context = self._retriever.search_as_context(question, top_k, use_hybrid)
            search_results = self._retriever.search(question, top_k, use_hybrid)
            sources = [
                {"content": r.content[:200], "score": r.score, "source": r.source}
                for r in search_results
            ]

        elif strategy == RetrievalStrategy.GREETING:
            return {
                "thread_id": thread_id,
                "answer": "你好！我是知识助手，有什么可以帮你的？",
                "sources": [],
            }

        # 4. 构建消息并调用 Agent
        config = {"configurable": {"thread_id": thread_id}}
        messages = [{"role": "user", "content": question}]

        # 如果有上下文，拼接到消息中
        if context:
            messages = [
                {"role": "system", "content": f"参考以下知识库内容回答问题:\n\n{context}"},
                {"role": "user", "content": question},
            ]

        result = await self._agent.ainvoke({"messages": messages}, config=config)
        answer = result["messages"][-1].content if result.get("messages") else ""

        # 5. 缓存热点查询
        if len(question) < 200:
            self._cache.set(f"rag:answer:{question}", answer, ttl=3600)

        return {
            "thread_id": thread_id,
            "answer": answer,
            "sources": sources,
        }

    async def ask_stream(
        self,
        question: str,
        thread_id: str = "",
        use_hybrid: bool = True,
        top_k: int = 5,
    ) -> AsyncGenerator[str, None]:
        """RAG 问答（流式）。"""
        thread_id = thread_id or f"rag-{uuid.uuid4().hex[:8]}"

        # 1. 分类 + 策略
        classification = classify_query(question)
        strategy, _ = select_strategy(classification, question)

        # 2. 检索
        context = ""
        if strategy == RetrievalStrategy.HYBRID_SEARCH:
            context = self._retriever.search_as_context(question, top_k, use_hybrid)

        if strategy == RetrievalStrategy.GREETING:
            yield "你好！我是知识助手，有什么可以帮你的？"
            return

        # 3. 流式调用 Agent
        config = {"configurable": {"thread_id": thread_id}}
        messages = [{"role": "user", "content": question}]
        if context:
            messages = [
                {"role": "system", "content": f"参考以下知识库内容回答问题:\n\n{context}"},
                {"role": "user", "content": question},
            ]

        async for event in self._agent.astream_events(
            {"messages": messages}, config=config, version="v2"
        ):
            if event["event"] == "on_chat_model_stream":
                content = event["data"]["chunk"].content
                if content:
                    yield content

    def evaluate(self, samples: List[dict]) -> dict:
        """评估 RAG 系统性能。

        Args:
            samples: 评估样本列表 [{"question": ..., "ground_truth": ..., "answer": ..., "contexts": [...]}]

        Returns:
            评估结果
        """
        evaluator = RagasEvaluator()
        eval_samples = [
            EvalSample(
                question=s["question"],
                ground_truth=s["ground_truth"],
                answer=s.get("answer", ""),
                contexts=s.get("contexts", []),
            )
            for s in samples
        ]
        result = evaluator.evaluate(eval_samples)
        report = generate_eval_report(result)

        return {
            "context_precision": result.context_precision,
            "context_recall": result.context_recall,
            "faithfulness": result.faithfulness,
            "answer_relevancy": result.answer_relevancy,
            "answer_correctness": result.answer_correctness,
            "overall_score": result.overall_score,
            "report": report,
        }

    def add_documents(self, documents: List[str]) -> int:
        """向检索器添加文档。

        Returns:
            添加的文档数量
        """
        self._retriever.add_documents(documents)
        return len(documents)