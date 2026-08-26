"""RAG 系统评估模块 — 基于 Ragas 框架。

评估维度:
  1. Context Precision    — 检索上下文是否精确（噪音比例）
  2. Context Recall       — 检索是否覆盖了必要信息（召回率）
  3. Faithfulness         — 生成答案是否忠于检索上下文（幻觉检测）
  4. Answer Relevancy     — 答案是否与问题相关
  5. Answer Correctness   — 答案是否正确

依赖: ragas, datasets (pip install ragas datasets)
"""
import json
import logging
from pathlib import Path
from typing import List, Dict, Optional, Any
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class EvalSample:
    """单条评估样本。"""
    question: str
    ground_truth: str
    answer: str = ""
    contexts: List[str] = field(default_factory=list)


@dataclass
class EvalResult:
    """评估结果汇总。"""
    context_precision: float = 0.0
    context_recall: float = 0.0
    faithfulness: float = 0.0
    answer_relevancy: float = 0.0
    answer_correctness: float = 0.0
    overall_score: float = 0.0
    per_sample: List[dict] = field(default_factory=list)


class RagasEvaluator:
    """Ragas 评估器。

    用法:
        evaluator = RagasEvaluator()
        samples = [
            EvalSample(question="...", ground_truth="...", answer="...", contexts=[...]),
        ]
        result = evaluator.evaluate(samples)
    """

    def __init__(self):
        self._available = self._check_ragas()

    @staticmethod
    def _check_ragas() -> bool:
        try:
            import ragas  # noqa: F401
            return True
        except ImportError:
            logger.warning("ragas 未安装，评估功能不可用。安装: pip install ragas datasets")
            return False

    def evaluate(self, samples: List[EvalSample]) -> EvalResult:
        """执行 Ragas 评估。

        Args:
            samples: 评估样本列表

        Returns:
            EvalResult 包含各维度得分
        """
        if not self._available:
            return EvalResult()

        try:
            from datasets import Dataset
            from ragas import evaluate as ragas_evaluate
            from ragas.metrics import (
                context_precision,
                context_recall,
                faithfulness,
                answer_relevancy,
                answer_correctness,
            )

            # 构建 HuggingFace Dataset
            dataset_dict = {
                "question": [s.question for s in samples],
                "ground_truth": [s.ground_truth for s in samples],
                "answer": [s.answer for s in samples],
                "contexts": [s.contexts for s in samples],
            }
            dataset = Dataset.from_dict(dataset_dict)

            # 执行评估
            metrics = [
                context_precision,
                context_recall,
                faithfulness,
                answer_relevancy,
                answer_correctness,
            ]
            result = ragas_evaluate(dataset, metrics=metrics)

            # 提取得分
            scores = {}
            for metric_name in [
                "context_precision", "context_recall",
                "faithfulness", "answer_relevancy", "answer_correctness",
            ]:
                if metric_name in result:
                    scores[metric_name] = float(result[metric_name])

            overall = sum(scores.values()) / len(scores) if scores else 0.0

            return EvalResult(
                context_precision=scores.get("context_precision", 0.0),
                context_recall=scores.get("context_recall", 0.0),
                faithfulness=scores.get("faithfulness", 0.0),
                answer_relevancy=scores.get("answer_relevancy", 0.0),
                answer_correctness=scores.get("answer_correctness", 0.0),
                overall_score=overall,
            )
        except Exception as e:
            logger.exception("Ragas 评估失败: %s", e)
            return EvalResult()

    def evaluate_from_file(self, file_path: str) -> EvalResult:
        """从 JSON 文件加载评估数据并执行评估。

        文件格式:
        [
            {
                "question": "...",
                "ground_truth": "...",
                "answer": "...",
                "contexts": ["...", "..."]
            }
        ]
        """
        samples = load_eval_samples(file_path)
        return self.evaluate(samples)


def load_eval_samples(file_path: str) -> List[EvalSample]:
    """从 JSON 文件加载评估样本。

    Args:
        file_path: JSON 文件路径

    Returns:
        EvalSample 列表
    """
    with open(file_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    samples = []
    for item in data:
        samples.append(EvalSample(
            question=item.get("question", ""),
            ground_truth=item.get("ground_truth", ""),
            answer=item.get("answer", ""),
            contexts=item.get("contexts", []),
        ))
    return samples


def save_eval_samples(samples: List[EvalSample], file_path: str) -> None:
    """保存评估样本到 JSON 文件。"""
    data = [
        {
            "question": s.question,
            "ground_truth": s.ground_truth,
            "answer": s.answer,
            "contexts": s.contexts,
        }
        for s in samples
    ]
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def generate_eval_report(result: EvalResult) -> str:
    """生成可读的评估报告。

    Returns:
        Markdown 格式的评估报告
    """
    lines = [
        "# RAG 系统评估报告",
        "",
        "| 指标 | 得分 | 说明 |",
        "|------|------|------|",
        f"| Context Precision | {result.context_precision:.3f} | 检索上下文精确度 |",
        f"| Context Recall | {result.context_recall:.3f} | 检索上下文召回率 |",
        f"| Faithfulness | {result.faithfulness:.3f} | 答案忠实度（幻觉检测） |",
        f"| Answer Relevancy | {result.answer_relevancy:.3f} | 答案相关性 |",
        f"| Answer Correctness | {result.answer_correctness:.3f} | 答案正确性 |",
        f"| **Overall** | **{result.overall_score:.3f}** | 综合得分 |",
        "",
        "> 得分范围: 0.0 ~ 1.0，越高越好。",
    ]
    return "\n".join(lines)