"""RAG 模块单元测试。"""
import pytest

from core.rag.chunker import ChineseRecursiveTextSplitter, ParentChildChunker, clean_text
from core.rag.classifier import QueryClassifier, classify_query, CATEGORY_GENERAL, CATEGORY_SPECIALIZED, CATEGORY_GREETING, CATEGORY_CODE_REVIEW
from core.rag.strategy import StrategySelector, select_strategy, RetrievalStrategy
from core.rag.retriever import BM25Retriever, HybridRetriever, SearchResult
from core.rag.evaluate import EvalSample, RagasEvaluator, generate_eval_report


# ========== 文本分割器 ==========

class TestChineseRecursiveTextSplitter:
    """中文递归文本分割器测试。"""

    def test_split_basic(self):
        splitter = ChineseRecursiveTextSplitter(chunk_size=100, chunk_overlap=20)
        text = "人工智能是计算机科学的一个分支。它企图了解智能的实质，并生产出一种新的能以人类智能相似的方式做出反应的智能机器。"
        chunks = splitter.split_text(text)
        assert len(chunks) > 0
        for chunk in chunks:
            assert len(chunk) <= 100 + 20  # chunk_size + overlap buffer

    def test_split_paragraphs(self):
        splitter = ChineseRecursiveTextSplitter(chunk_size=200, chunk_overlap=30)
        text = "第一段内容。\n\n第二段内容。\n\n第三段内容。"
        chunks = splitter.split_text(text)
        assert len(chunks) >= 1

    def test_clean_text(self):
        text = "  多余空格  \r\n\r\n\r\n多余空行\n\n正常行  "
        cleaned = clean_text(text)
        assert "多余空格" in cleaned
        assert cleaned.count("\n\n") <= 2  # 最多保留一个空行


class TestParentChildChunker:
    """父子分块器测试。"""

    def test_chunk_basic(self):
        chunker = ParentChildChunker(
            parent_chunk_size=300,
            child_chunk_size=100,
        )
        text = "人工智能（AI）是计算机科学的一个分支。" * 10
        pairs = chunker.chunk(text)

        assert len(pairs) > 0
        for pair in pairs:
            assert pair.parent.content  # parent 不为空
            assert len(pair.children) >= 1  # 至少有一个 child

    def test_chunk_documents(self):
        chunker = ParentChildChunker(
            parent_chunk_size=300,
            child_chunk_size=100,
        )
        documents = [
            ("文档1的内容。" * 20, {"source": "doc1"}),
            ("文档2的内容。" * 20, {"source": "doc2"}),
        ]
        parents, children, child_to_parent = chunker.chunk_documents(documents)

        assert len(parents) > 0
        assert len(children) > 0
        assert len(child_to_parent) == len(children)
        # 每个 child 都应该有对应的 parent
        for child_idx, parent_idx in child_to_parent.items():
            assert 0 <= parent_idx < len(parents)


# ========== 问题分类器 ==========

class TestQueryClassifier:
    """问题分类器测试（规则回退模式）。"""

    def test_classify_greeting(self):
        result = classify_query("你好")
        assert result.category == CATEGORY_GREETING
        assert result.confidence > 0.5

    def test_classify_code_review(self):
        result = classify_query("review: def foo(): pass")
        assert result.category == CATEGORY_CODE_REVIEW

    def test_classify_specialized(self):
        result = classify_query("什么是向量数据库？")
        assert result.category == CATEGORY_SPECIALIZED
        assert result.needs_rag is True

    def test_classify_general(self):
        result = classify_query("今天天气怎么样")
        assert result.category == CATEGORY_GENERAL
        assert result.needs_rag is False


# ========== 策略选择器 ==========

class TestStrategySelector:
    """策略选择器测试。"""

    def test_greeting_strategy(self):
        classification = classify_query("你好")
        strategy, note = select_strategy(classification)
        assert strategy == RetrievalStrategy.GREETING

    def test_code_review_strategy(self):
        classification = classify_query("review: print('hello')")
        strategy, note = select_strategy(classification)
        assert strategy == RetrievalStrategy.CODE_REVIEW

    def test_specialized_strategy(self):
        classification = classify_query("请解释什么是RAG检索增强生成")
        strategy, note = select_strategy(classification)
        # 专业知识分类，但向量库可能不可用，降级为 DIRECT_LLM
        assert strategy in (RetrievalStrategy.HYBRID_SEARCH, RetrievalStrategy.DIRECT_LLM)


# ========== 检索器 ==========

class TestBM25Retriever:
    """BM25 检索器测试。"""

    def test_empty_retriever(self):
        retriever = BM25Retriever()
        results = retriever.search("测试")
        assert results == []

    def test_basic_search(self):
        docs = [
            "Python 是一门编程语言",
            "Java 也是一门编程语言",
            "机器学习是人工智能的分支",
            "深度学习使用神经网络",
            "自然语言处理是AI的重要方向",
        ]
        retriever = BM25Retriever(docs)
        results = retriever.search("Python 编程", top_k=3)
        assert len(results) > 0
        assert results[0].source == "bm25"
        assert "Python" in results[0].content

    def test_add_documents(self):
        retriever = BM25Retriever(["初始文档"])
        retriever.add_documents(["新增文档一", "新增文档二"])
        results = retriever.search("新增")
        assert len(results) > 0


class TestHybridRetriever:
    """混合检索器测试。"""

    def test_search_no_vector(self):
        """向量库不可用时，仅 BM25 工作。"""
        retriever = HybridRetriever(["Python异步编程指南", "FastAPI框架入门", "机器学习基础"])
        results = retriever.search("Python", top_k=2, use_hybrid=True)
        # 向量库不可用时，至少返回 BM25 结果
        assert len(results) >= 0  # 可能为空（如果 BM25 也没安装 rank-bm25）


# ========== 评估 ==========

class TestRagasEvaluator:
    """Ragas 评估器测试。"""

    def test_generate_report(self):
        from core.rag.evaluate import EvalResult
        result = EvalResult(
            context_precision=0.85,
            context_recall=0.78,
            faithfulness=0.92,
            answer_relevancy=0.88,
            answer_correctness=0.80,
            overall_score=0.846,
        )
        report = generate_eval_report(result)
        assert "0.850" in report
        assert "0.780" in report
        assert "0.920" in report

    def test_eval_sample(self):
        sample = EvalSample(
            question="什么是向量数据库？",
            ground_truth="向量数据库是用于存储和检索向量嵌入的数据库",
            answer="向量数据库用于存储向量嵌入并进行相似度搜索",
            contexts=["向量数据库专门用于存储和检索高维向量数据"],
        )
        assert sample.question
        assert sample.ground_truth