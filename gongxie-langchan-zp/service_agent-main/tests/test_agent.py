"""Agent 单元测试。"""
import pytest

from core.models import deepseek_model
from core.tool.tools import web_search


def test_web_search_tool():
    """DuckDuckGo 搜索应返回格式化的结果字符串。"""
    result = web_search.invoke({"query": "Python"})
    assert isinstance(result, str)
    assert len(result) > 0
    # 应该有标题和 URL
    assert "URL:" in result or "http" in result.lower()


def test_model_initialized():
    """模型应成功初始化。"""
    assert deepseek_model is not None


# ========== jieba 分词测试 ==========

def test_jieba_tokenize():
    """精确模式分词。"""
    from core.text_utils import jieba_tokenize

    tokens = jieba_tokenize("我来到北京清华大学")
    assert "北京" in tokens
    assert "清华大学" in tokens
    assert len(tokens) > 1


def test_jieba_cut_for_search():
    """搜索引擎模式分词。"""
    from core.text_utils import jieba_cut_for_search

    tokens = jieba_cut_for_search("小明硕士毕业于中国科学院计算所")
    assert "中国科学院" in tokens
    assert "计算" in tokens
    assert len(tokens) > 0


def test_jieba_extract_keywords():
    """TF-IDF 关键词提取。"""
    from core.text_utils import jieba_extract_keywords

    keywords = jieba_extract_keywords("自然语言处理是人工智能的一个重要方向")
    assert len(keywords) > 0
    assert isinstance(keywords[0], str)


def test_jieba_tokenize_cached():
    """缓存分词结果一致。"""
    from core.text_utils import jieba_tokenize_cached

    t1 = jieba_tokenize_cached("测试文本")
    t2 = jieba_tokenize_cached("测试文本")
    assert t1 == t2


@pytest.mark.asyncio
async def test_code_review_tool():
    """LLM 代码审查应返回非空结果。"""
    from core.tool.tools import code_review

    result = await code_review.ainvoke({"code": "print('hello')"})
    assert isinstance(result, str)
    assert len(result) > 0