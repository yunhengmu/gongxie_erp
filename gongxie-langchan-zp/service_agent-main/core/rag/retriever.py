"""混合检索器 — BM25 关键词检索 + 向量语义检索 + RRF 融合。

检索流程:
  用户查询
    ├── BM25 关键词检索（稀疏表示，精确匹配）
    ├── 向量语义检索（稠密表示，语义匹配）
    └── RRF (Reciprocal Rank Fusion) 融合排序
         └── 返回 Top-K 文档

依赖:
  - jieba: 中文分词（BM25 索引构建）
  - rank-bm25: BM25 算法实现
  - langchain-chroma / pymilvus: 向量库
  - fastembed / FlagEmbedding: 向量嵌入
"""
import logging
from typing import List, Optional, Tuple
from dataclasses import dataclass

from config.settings import settings

logger = logging.getLogger(__name__)


@dataclass
class SearchResult:
    """单条检索结果。"""
    content: str
    metadata: dict
    score: float          # 0-1 归一化得分
    source: str           # "bm25" | "vector" | "fusion"


# ========== BM25 检索 ==========

class BM25Retriever:
    """基于 BM25 的关键词检索器。

    使用 jieba 分词构建倒排索引，适合精确关键词匹配。
    """

    def __init__(self, documents: Optional[List[str]] = None):
        """
        Args:
            documents: 初始文档列表，用于构建索引
        """
        self._documents: List[str] = list(documents) if documents else []
        self._bm25 = None
        if self._documents:
            self._build_index()

    def _tokenize(self, text: str) -> List[str]:
        """jieba 中文分词。"""
        try:
            from core.text_utils import jieba_cut_for_search
            return jieba_cut_for_search(text)
        except ImportError:
            return text.split()

    def _build_index(self) -> None:
        """构建 BM25 索引。"""
        try:
            from rank_bm25 import BM25Okapi

            tokenized_docs = [self._tokenize(doc) for doc in self._documents]
            self._bm25 = BM25Okapi(tokenized_docs)
        except ImportError:
            logger.warning(
                "rank-bm25 未安装，BM25 检索不可用。"
                "安装: pip install rank-bm25"
            )
            self._bm25 = None

    def add_documents(self, documents: List[str]) -> None:
        """追加文档并重建索引。"""
        self._documents.extend(documents)
        self._build_index()

    def search(self, query: str, top_k: int = 5) -> List[SearchResult]:
        """BM25 关键词检索。

        Args:
            query: 查询文本
            top_k: 返回数量

        Returns:
            SearchResult 列表，按得分降序
        """
        if not self._bm25 or not self._documents:
            return []

        tokenized_query = self._tokenize(query)
        scores = self._bm25.get_scores(tokenized_query)
        # 按得分排序取 top_k
        indexed = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)[:top_k]
        max_score = indexed[0][1] if indexed else 1.0

        results = []
        for idx, score in indexed:
            if score <= 0:
                continue
            results.append(SearchResult(
                content=self._documents[idx],
                metadata={"index": idx, "raw_score": score},
                score=score / max_score if max_score > 0 else 0,  # 归一化
                source="bm25",
            ))
        return results


# ========== 向量检索 ==========

class VectorRetriever:
    """向量语义检索器。

    支持 Chroma（本地）和 Milvus（分布式）两种后端。
    """

    def __init__(self):
        self._vectorstore = None
        self._engine = self._detect_engine()

    @staticmethod
    def _detect_engine() -> str:
        """检测可用的向量库引擎。"""
        try:
            import pymilvus  # noqa: F401
            return "milvus"
        except ImportError:
            pass
        try:
            import chromadb  # noqa: F401
            return "chroma"
        except ImportError:
            return "none"

    def _init_chroma(self):
        """初始化 Chroma 向量库。"""
        try:
            from langchain_chroma import Chroma
            from langchain_community.embeddings.fastembed import FastEmbedEmbeddings

            embeddings = FastEmbedEmbeddings(model_name=settings.embedding_model)
            self._vectorstore = Chroma(
                collection_name="kb",
                embedding_function=embeddings,
                persist_directory=settings.chroma_persist_dir,
            )
            logger.info("Chroma 向量库已初始化 (collection=kb)")
        except Exception as e:
            logger.warning("Chroma 初始化失败: %s", e)

    def _init_milvus(self):
        """初始化 Milvus 向量库。"""
        try:
            from langchain_milvus import Milvus

            self._vectorstore = Milvus(
                embedding_function=None,  # 使用默认，由外部设置
                collection_name="kb",
                connection_args={"host": settings.milvus_host, "port": settings.milvus_port},
            )
            logger.info(
                "Milvus 向量库已初始化 (host=%s, port=%s)",
                settings.milvus_host, settings.milvus_port,
            )
        except Exception as e:
            logger.warning("Milvus 初始化失败: %s", e)

    @property
    def vectorstore(self):
        """延迟初始化向量库。"""
        if self._vectorstore is None:
            if self._engine == "chroma":
                self._init_chroma()
            elif self._engine == "milvus":
                self._init_milvus()
        return self._vectorstore

    def add_documents(self, documents: List[str]) -> None:
        """向向量库添加文档。"""
        if self.vectorstore is None:
            logger.warning("向量库不可用，跳过添加")
            return
        try:
            self.vectorstore.add_texts(documents)
            logger.info("已向向量库添加 %d 条文档", len(documents))
        except Exception as e:
            logger.warning("向量库添加文档失败: %s", e)

    def search(self, query: str, top_k: int = 5) -> List[SearchResult]:
        """向量语义检索。

        Args:
            query: 查询文本
            top_k: 返回数量

        Returns:
            SearchResult 列表
        """
        if self.vectorstore is None:
            return []

        try:
            docs = self.vectorstore.similarity_search_with_score(query, k=top_k)
            results = []
            for doc, score in docs:
                # Chroma 返回 L2 距离，Milvus 返回余弦相似度，统一归一化
                normalized_score = max(0.0, min(1.0, 1.0 - score / 2.0)) if score > 1 else score
                results.append(SearchResult(
                    content=doc.page_content,
                    metadata=doc.metadata,
                    score=normalized_score,
                    source="vector",
                ))
            return results
        except Exception as e:
            logger.warning("向量检索失败: %s", e)
            return []


# ========== RRF 融合 ==========

def _rrf_fusion(
    bm25_results: List[SearchResult],
    vector_results: List[SearchResult],
    k: int = 60,
    top_k: int = 5,
) -> List[SearchResult]:
    """Reciprocal Rank Fusion 算法融合两路检索结果。

    RRF 公式: score(d) = Σ 1 / (k + rank_i(d))
    其中 k 是平滑参数（默认 60）。

    Args:
        bm25_results: BM25 检索结果
        vector_results: 向量检索结果
        k: RRF 平滑参数
        top_k: 最终返回数量

    Returns:
        融合后的 SearchResult 列表
    """
    # 为每个文档计算 RRF 得分
    scores: dict[str, tuple[float, SearchResult]] = {}

    for rank, result in enumerate(bm25_results, 1):
        key = result.content[:100]  # 用内容前 100 字符做 key
        rrf_score = 1.0 / (k + rank)
        if key in scores:
            old_score, _ = scores[key]
            scores[key] = (old_score + rrf_score, result)
        else:
            scores[key] = (rrf_score, result)

    for rank, result in enumerate(vector_results, 1):
        key = result.content[:100]
        rrf_score = 1.0 / (k + rank)
        if key in scores:
            old_score, _ = scores[key]
            scores[key] = (old_score + rrf_score, result)
        else:
            scores[key] = (rrf_score, result)

    # 按 RRF 得分降序
    sorted_results = sorted(scores.values(), key=lambda x: x[0], reverse=True)
    return [
        SearchResult(
            content=result.content,
            metadata=result.metadata,
            score=score,
            source="fusion",
        )
        for score, result in sorted_results[:top_k]
    ]

# ========== 混合检索器 ==========

class HybridRetriever:
    """混合检索器 — BM25 + 向量 + RRF 融合。

    使用方式:
        retriever = HybridRetriever(documents=["文档1", "文档2", ...])
        results = retriever.search("查询文本", top_k=5)
    """

    def __init__(self, documents: Optional[List[str]] = None):
        self._bm25 = BM25Retriever(documents)
        self._vector = VectorRetriever()

    def add_documents(self, documents: List[str]) -> None:
        """追加文档到 BM25 索引和向量库。"""
        self._bm25.add_documents(documents)
        self._vector.add_documents(documents)

    def search(
        self,
        query: str,
        top_k: int = 5,
        use_hybrid: bool = True,
    ) -> List[SearchResult]:
        """执行混合检索。

        Args:
            query: 查询文本
            top_k: 返回数量
            use_hybrid: True 执行 BM25+向量融合，False 仅向量检索

        Returns:
            SearchResult 列表
        """
        if not use_hybrid:
            return self._vector.search(query, top_k)

        bm25_results = self._bm25.search(query, top_k)
        vector_results = self._vector.search(query, top_k)

        # 如果某一路检索为空，直接返回另一路
        if not bm25_results:
            return vector_results[:top_k]
        if not vector_results:
            return bm25_results[:top_k]

        return _rrf_fusion(bm25_results, vector_results, top_k=top_k)

    def search_as_context(
        self,
        query: str,
        top_k: int = 5,
        use_hybrid: bool = True,
    ) -> str:
        """以纯文本格式返回检索结果，可直接拼接至 Prompt。

        Returns:
            格式化的上下文字符串
        """
        results = self.search(query, top_k, use_hybrid)
        if not results:
            return ""

        lines = []
        for i, r in enumerate(results, 1):
            lines.append(f"[文档{i}] (score={r.score:.2f}, source={r.source})\n{r.content}")
        return "\n\n".join(lines)