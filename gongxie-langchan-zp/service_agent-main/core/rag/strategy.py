"""检索策略选择器。

根据问题分类结果，选择最优的检索策略。

策略路由:
  ┌─────────────────────────────────────────────────────┐
  │                  用户问题                            │
  │                    ↓                                 │
  │              BERT 问题分类器                          │
  │         ┌──────┼──────┬──────────┐                   │
  │         ↓      ↓      ↓          ↓                   │
  │    通用知识  专业知识  代码审查   问候                  │
  │         │      │      │          │                   │
  │         ↓      ↓      ↓          ↓                   │
  │    直接LLM  混合检索  直接LLM   直接回复               │
  │         │      │      │          │                   │
  │         │      ├─MySQL精确匹配（可选）                 │
  │         │      ├─BM25关键词检索                       │
  │         │      ├─向量语义检索                         │
  │         │      └─RRF融合                             │
  │         │                                            │
  │         └──────┬──────┘                              │
  │                ↓                                     │
  │         构建 Prompt                                  │
  │         (上下文 + 历史 + 问题)                         │
  │                ↓                                     │
  │         调用 LLM 生成                                 │
  └─────────────────────────────────────────────────────┘
"""
import logging
from enum import Enum
from typing import Optional, List

from core.rag.classifier import (
    ClassificationResult,
    CATEGORY_GENERAL,
    CATEGORY_SPECIALIZED,
    CATEGORY_CODE_REVIEW,
    CATEGORY_GREETING,
)

logger = logging.getLogger(__name__)


class RetrievalStrategy(Enum):
    """检索策略枚举。"""
    DIRECT_LLM = "direct_llm"           # 直接调用 LLM（通用知识）
    HYBRID_SEARCH = "hybrid_search"     # 混合检索（BM25 + 向量）
    MYSQL_EXACT = "mysql_exact"         # MySQL 精确匹配（知识图谱）
    GREETING = "greeting"               # 问候/闲聊直接回复
    CODE_REVIEW = "code_review"         # 代码审查


class StrategySelector:
    """检索策略选择器。

    根据分类结果和可用资源自动选择最优策略。
    """

    def __init__(self):
        self._mysql_available = self._check_mysql()
        self._vector_available = self._check_vector_store()

    @staticmethod
    def _check_mysql() -> bool:
        """检查 MySQL 是否可用。"""
        try:
            from config.settings import settings
            return bool(settings.mysql_password)
        except Exception:
            return False

    @staticmethod
    def _check_vector_store() -> bool:
        """检查向量库是否可用。"""
        try:
            from core.rag.retriever import VectorRetriever
            retriever = VectorRetriever()
            return retriever._engine != "none"
        except Exception:
            return False

    def select(
        self,
        classification: ClassificationResult,
        query: str = "",
    ) -> tuple[RetrievalStrategy, Optional[str]]:
        """根据分类结果选择检索策略。

        Args:
            classification: 问题分类结果
            query: 原始查询文本

        Returns:
            (策略, 附加说明)
        """
        category = classification.category

        if category == CATEGORY_GREETING:
            return RetrievalStrategy.GREETING, "问候/闲聊，直接回复"

        if category == CATEGORY_CODE_REVIEW:
            return RetrievalStrategy.CODE_REVIEW, "代码审查模式"

        if category == CATEGORY_SPECIALIZED:
            # 专业知识：优先 MySQL 精确匹配，再混合检索
            if self._mysql_available:
                return RetrievalStrategy.MYSQL_EXACT, "先 MySQL 精确匹配，再混合检索"
            if self._vector_available:
                return RetrievalStrategy.HYBRID_SEARCH, "混合检索（BM25 + 向量）"
            return RetrievalStrategy.DIRECT_LLM, "向量库不可用，降级为直接 LLM"

        # 通用知识：直接 LLM
        return RetrievalStrategy.DIRECT_LLM, "通用知识，直接调用 LLM"


# ========== 全局实例 ==========

_selector: Optional[StrategySelector] = None


def get_selector() -> StrategySelector:
    """获取全局策略选择器。"""
    global _selector
    if _selector is None:
        _selector = StrategySelector()
    return _selector


def select_strategy(
    classification: ClassificationResult,
    query: str = "",
) -> tuple[RetrievalStrategy, Optional[str]]:
    """便捷函数：选择检索策略。"""
    return get_selector().select(classification, query)