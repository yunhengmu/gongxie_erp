"""问题分类器 — 基于 BERT 模型判断问题类型。

分类:
  - general_knowledge: 通用知识（直接 LLM 即可回答）
  - specialized_knowledge: 专业知识（需要 RAG 检索）
  - code_review: 代码审查
  - greeting: 问候/闲聊

工作原理:
  1. 加载预训练 BERT 分类模型（bert-base-chinese）
  2. 对用户问题编码 → 分类
  3. 返回类别标签和置信度

依赖:
  - transformers: BERT 模型加载
  - torch: 推理框架

当前为骨架实现：定义了分类逻辑，实际模型推理在运行时按需加载。
"""
import logging
from typing import Tuple, Optional
from dataclasses import dataclass

from config.settings import settings

logger = logging.getLogger(__name__)


# 分类标签
CATEGORY_GENERAL = "general_knowledge"
CATEGORY_SPECIALIZED = "specialized_knowledge"
CATEGORY_CODE_REVIEW = "code_review"
CATEGORY_GREETING = "greeting"
CATEGORY_VECTOR_SEARCH = "vector_search"


@dataclass
class ClassificationResult:
    """分类结果。"""
    category: str
    confidence: float
    needs_rag: bool  # 是否需要 RAG 检索


class QueryClassifier:
    """BERT 问题分类器。

    用法:
        classifier = QueryClassifier()
        result = classifier.classify("什么是向量数据库？")
        # ClassificationResult(category="specialized_knowledge", confidence=0.92, needs_rag=True)
    """

    def __init__(self, model_name: Optional[str] = None):
        """
        Args:
            model_name: BERT 模型名称，默认使用 settings.bert_model_name
        """
        self._model_name = model_name or settings.bert_model_name
        self._model = None
        self._tokenizer = None
        self._labels = [
            CATEGORY_GENERAL,
            CATEGORY_SPECIALIZED,
            CATEGORY_CODE_REVIEW,
            CATEGORY_GREETING,
            CATEGORY_VECTOR_SEARCH,
        ]
        self._initialized = False

    def _init_model(self) -> None:
        """延迟加载 BERT 模型。"""
        if self._initialized:
            return
        try:
            from transformers import AutoTokenizer, AutoModelForSequenceClassification
            import torch

            self._tokenizer = AutoTokenizer.from_pretrained(self._model_name)
            self._model = AutoModelForSequenceClassification.from_pretrained(
                self._model_name,
                num_labels=len(self._labels),
            )
            self._model.eval()
            self._device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            self._model.to(self._device)
            self._initialized = True
            logger.info(
                "BERT 分类器已加载 (model=%s, device=%s)",
                self._model_name, self._device,
            )
        except ImportError as e:
            logger.warning("transformers/torch 未安装，使用规则分类器: %s", e)
            self._initialized = True  # 标记为已初始化，使用规则回退
        except Exception as e:
            logger.warning("BERT 模型加载失败，使用规则分类器: %s", e)
            self._initialized = True

    def classify(self, query: str) -> ClassificationResult:
        """对用户问题进行分类。

        Args:
            query: 用户问题文本

        Returns:
            ClassificationResult 包含分类标签和置信度
        """
        self._init_model()

        # 如果 BERT 模型可用，使用模型推理
        if self._model is not None:
            return self._classify_with_model(query)

        # 回退：基于规则的分类
        return self._classify_with_rules(query)

    def _classify_with_model(self, query: str) -> ClassificationResult:
        """使用 BERT 模型进行分类。"""
        import torch
        import torch.nn.functional as F

        inputs = self._tokenizer(
            query,
            return_tensors="pt",
            truncation=True,
            max_length=128,
            padding=True,
        ).to(self._device)

        with torch.no_grad():
            outputs = self._model(**inputs)
            probs = F.softmax(outputs.logits, dim=-1)
            pred_idx = torch.argmax(probs, dim=-1).item()
            confidence = probs[0][pred_idx].item()

        category = self._labels[pred_idx]
        return ClassificationResult(
            category=category,
            confidence=confidence,
            needs_rag=category == CATEGORY_SPECIALIZED,
        )

    def _classify_with_rules(self, query: str) -> ClassificationResult:
        """基于规则的分类（BERT 不可用时的回退方案）。"""
        query_lower = query.lower().strip()

        # 代码审查
        if query_lower.startswith("review:") or query_lower.startswith("审查"):
            return ClassificationResult(
                category=CATEGORY_CODE_REVIEW,
                confidence=0.9,
                needs_rag=False,
            )

        # 问候
        greetings = ["你好", "hi", "hello", "早上好", "下午好", "晚上好", "谢谢", "再见"]
        if any(g in query_lower for g in greetings):
            return ClassificationResult(
                category=CATEGORY_GREETING,
                confidence=0.85,
                needs_rag=False,
            )

        # 专业知识关键词（指示需要 RAG 检索）
        specialized_keywords = [
            "课程", "教材", "知识点", "教学", "考试", "知识点",
            "什么是", "如何", "怎么", "定义", "概念", "原理",
            "算法", "架构", "数据库", "向量", "模型", "训练",
            "代码", "函数", "类", "接口", "API",
        ]
        if any(kw in query_lower for kw in specialized_keywords):
            return ClassificationResult(
                category=CATEGORY_SPECIALIZED,
                confidence=0.7,
                needs_rag=True,
            )

        # 默认：通用知识
        return ClassificationResult(
            category=CATEGORY_GENERAL,
            confidence=0.6,
            needs_rag=False,
        )


# ========== 全局实例 ==========

_classifier: Optional[QueryClassifier] = None


def get_classifier() -> QueryClassifier:
    """获取全局分类器单例。"""
    global _classifier
    if _classifier is None:
        _classifier = QueryClassifier()
    return _classifier


def classify_query(query: str) -> ClassificationResult:
    """便捷函数：对问题分类。"""
    return get_classifier().classify(query)