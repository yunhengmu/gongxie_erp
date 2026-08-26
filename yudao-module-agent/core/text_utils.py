"""中文文本处理工具。

基于 jieba 分词，提供：
  - 精确分词
  - 搜索引擎模式分词
  - 关键词提取（TF-IDF）
  - 自定义词典加载

依赖: jieba >= 0.42.1
"""
import logging
from functools import lru_cache
from pathlib import Path
from typing import Optional

import jieba
import jieba.analyse

logger = logging.getLogger(__name__)

# 自定义词典目录（相对于本模块）
_USER_DICT_DIR = Path(__file__).parent / "dicts"


def load_user_dict(dict_path: Optional[str] = None) -> None:
    """加载自定义词典，提升专业词汇分词准确率。

    词典格式: 每行一个词，格式为 `词语 词频 词性`（词频和词性可选）。

    Args:
        dict_path: 词典文件路径，默认为 core/dicts/user_dict.txt
    """
    if dict_path is None:
        dict_path = str(_USER_DICT_DIR / "user_dict.txt")

    try:
        jieba.load_userdict(dict_path)
        logger.info("自定义词典已加载: %s", dict_path)
    except FileNotFoundError:
        logger.debug("自定义词典不存在，跳过: %s", dict_path)
    except Exception as e:
        logger.warning("加载自定义词典失败: %s", e)


def jieba_tokenize(text: str, cut_all: bool = False) -> list[str]:
    """精确模式分词（默认）。

    精确模式试图将句子最精确地切开，适合文本分析。

    Args:
        text: 待分词文本
        cut_all: True 使用全模式（把所有可能的词都切出来，速度快但可能有冗余）

    Returns:
        分词结果列表

    Examples:
        >>> jieba_tokenize("我来到北京清华大学")
        ['我', '来到', '北京', '清华大学']
    """
    if cut_all:
        return list(jieba.cut(text, cut_all=True))
    return list(jieba.cut(text, cut_all=False))


def jieba_cut_for_search(text: str) -> list[str]:
    """搜索引擎模式分词。

    在精确模式基础上，对长词再次切分，提高召回率。
    适合用于构建倒排索引、BM25 检索等场景。

    Args:
        text: 待分词文本

    Returns:
        分词结果列表

    Examples:
        >>> jieba_cut_for_search("小明硕士毕业于中国科学院计算所")
        ['小明', '硕士', '毕业', '于', '中国', '科学', '学院', '科学院', '中国科学院', '计算', '计算所']
    """
    return list(jieba.cut_for_search(text))


@lru_cache(maxsize=512)
def jieba_tokenize_cached(text: str) -> tuple[str, ...]:
    """带缓存的分词，适合高频重复查询场景。

    返回 tuple 以支持 lru_cache（list 不可哈希）。
    """
    return tuple(jieba.cut(text, cut_all=False))


def jieba_extract_keywords(
    text: str,
    top_k: int = 10,
    with_weight: bool = False,
) -> list[str] | list[tuple[str, float]]:
    """TF-IDF 关键词提取。

    自动过滤停用词，提取最能代表文本主题的关键词。

    Args:
        text: 待提取关键词的文本
        top_k: 返回关键词数量
        with_weight: True 返回 (关键词, 权重) 列表，False 仅返回关键词列表

    Returns:
        关键词列表或 (关键词, 权重) 列表

    Examples:
        >>> jieba_extract_keywords("自然语言处理是人工智能的一个重要方向")
        ['自然语言', '处理', '人工智能', '重要', '方向']
    """
    if with_weight:
        return jieba.analyse.extract_tags(text, topK=top_k, withWeight=True)
    return jieba.analyse.extract_tags(text, topK=top_k)


def jieba_textrank_keywords(
    text: str,
    top_k: int = 10,
    with_weight: bool = False,
) -> list[str] | list[tuple[str, float]]:
    """TextRank 关键词提取。

    基于图模型，适合提取更抽象的主题词。

    Args:
        text: 待提取关键词的文本
        top_k: 返回关键词数量
        with_weight: True 返回 (关键词, 权重) 列表

    Returns:
        关键词列表或 (关键词, 权重) 列表
    """
    if with_weight:
        return jieba.analyse.textrank(text, topK=top_k, withWeight=True)
    return jieba.analyse.textrank(text, topK=top_k)


# ========== 模块初始化 ==========

# 尝试加载自定义词典
try:
    load_user_dict()
except Exception:
    pass