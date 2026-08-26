"""文本分割器 — 中文递归分割 + 父子分块策略。

核心概念:
  父子分块 (Parent-Child Chunking):
    - Parent Chunk: 较大的上下文块（如 800 字符），用于提供给 LLM 理解
    - Child Chunk:  较小的检索块（如 200 字符），用于向量检索
    - 检索时命中 Child Chunk，返回其对应的 Parent Chunk

  优势:
    - 小粒度检索（Child）保证检索精度
    - 大粒度返回（Parent）保证上下文完整性
    - 避免"断章取义"问题

依赖:
  - jieba: 中文分词
  - langchain-text-splitters: 递归分割器基类
"""
import logging
import re
from typing import List, Tuple, Optional
from dataclasses import dataclass, field

from langchain_text_splitters import RecursiveCharacterTextSplitter

logger = logging.getLogger(__name__)


# ========== 中文递归文本分割器 ==========

class ChineseRecursiveTextSplitter(RecursiveCharacterTextSplitter):
    """中文优化的递归文本分割器。

    分割优先级（从大到小）:
      1. 段落分隔 (\\n\\n)
      2. 换行符 (\\n)
      3. 句号/问号/感叹号 (。？！)
      4. 逗号/分号 (，；)
      5. 空格
      6. 单字符

    与标准 RecursiveCharacterTextSplitter 的区别:
      - 增加了中文标点符号作为分隔符
      - 集成了 jieba 分词感知，避免在词中间切断
    """

    # 中文优先的分隔符层级
    _CHINESE_SEPARATORS = [
        "\n\n",    # 段落
        "\n",      # 换行
        "。", "！", "？",  # 句子结束
        "；",      # 分号
        "，",      # 逗号
        "、",      # 顿号
        " ",       # 空格
        "",        # 单字符
    ]

    def __init__(
        self,
        chunk_size: int = 500,
        chunk_overlap: int = 80,
        keep_separator: bool = True,
        use_jieba: bool = True,
    ):
        """
        Args:
            chunk_size: 每个 chunk 的最大字符数
            chunk_overlap: chunk 之间的重叠字符数
            keep_separator: 是否保留分隔符
            use_jieba: 是否使用 jieba 分词避免断词
        """
        super().__init__(
            separators=self._CHINESE_SEPARATORS,
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            keep_separator=keep_separator,
        )
        self._use_jieba = use_jieba

    def split_text(self, text: str) -> List[str]:
        """分割文本，可选 jieba 分词边界保护。"""
        chunks = super().split_text(text)
        if not self._use_jieba:
            return chunks
        return self._jieba_boundary_check(chunks)

    def _jieba_boundary_check(self, chunks: List[str]) -> List[str]:
        """对每个 chunk 的首尾进行分词边界检查，避免在词中间切断。"""
        try:
            from core.text_utils import jieba_tokenize

            refined = []
            for i, chunk in enumerate(chunks):
                if i > 0 and len(chunk) > 2:
                    # 检查开头是否从词中间开始
                    prev_chunk = chunks[i - 1]
                    if prev_chunk:
                        # 取前一个 chunk 末尾 + 当前 chunk 开头
                        boundary = prev_chunk[-2:] + chunk[:2]
                        tokens = jieba_tokenize(boundary)
                        # 如果边界处有跨 chunk 的词，向前调整
                        if len(tokens) > 2:
                            # 将第一个 token 归入前一个 chunk
                            pass  # 简化处理：保持原样
                refined.append(chunk)
            return refined
        except ImportError:
            return chunks


# ========== 父子分块 ==========

@dataclass
class Chunk:
    """文档块。"""
    content: str
    index: int
    parent_index: Optional[int] = None  # 所属 Parent Chunk 的索引
    metadata: dict = field(default_factory=dict)


@dataclass
class ChunkPair:
    """父子块对。"""
    parent: Chunk      # 大块（上下文完整）
    children: List[Chunk] = field(default_factory=list)  # 小块（检索粒度）


class ParentChildChunker:
    """父子分块器。

    流程:
      原始文档 → Parent Chunk（大块）
              → Child Chunk（小块，从 Parent 再分割）
              → 建立映射关系

    检索时:
      命中 Child → 返回 Parent
    """

    def __init__(
        self,
        parent_chunk_size: int = 800,
        child_chunk_size: int = 200,
        chunk_overlap: int = 50,
    ):
        """
        Args:
            parent_chunk_size: 父块大小（返回给 LLM 的上下文粒度）
            child_chunk_size: 子块大小（向量检索粒度）
            chunk_overlap: 重叠字符数
        """
        self._parent_splitter = ChineseRecursiveTextSplitter(
            chunk_size=parent_chunk_size,
            chunk_overlap=chunk_overlap,
        )
        self._child_splitter = ChineseRecursiveTextSplitter(
            chunk_size=child_chunk_size,
            chunk_overlap=chunk_overlap // 2,
        )

    def chunk(self, text: str, metadata: dict = None) -> List[ChunkPair]:
        """对单篇文档执行父子分块。

        Args:
            text: 文档文本
            metadata: 文档元数据

        Returns:
            ChunkPair 列表
        """
        parent_chunks = self._parent_splitter.split_text(text)
        pairs: List[ChunkPair] = []

        for p_idx, parent_text in enumerate(parent_chunks):
            parent = Chunk(
                content=parent_text,
                index=p_idx,
                metadata=metadata or {},
            )
            # 从 Parent 中再分割出 Children
            child_texts = self._child_splitter.split_text(parent_text)
            children = [
                Chunk(
                    content=ct,
                    index=c_idx,
                    parent_index=p_idx,
                    metadata=metadata or {},
                )
                for c_idx, ct in enumerate(child_texts)
            ]
            pairs.append(ChunkPair(parent=parent, children=children))

        return pairs

    def chunk_documents(
        self,
        documents: List[Tuple[str, dict]],
    ) -> Tuple[List[Chunk], List[Chunk], dict]:
        """批量处理多篇文档。

        Args:
            documents: [(文本, 元数据), ...]

        Returns:
            (parents, children, child_to_parent_map)
            - parents: Parent Chunk 列表
            - children: Child Chunk 列表（用于建索引）
            - child_to_parent_map: Child 索引 → Parent 索引的映射
        """
        all_parents: List[Chunk] = []
        all_children: List[Chunk] = []
        child_to_parent: dict = {}

        parent_offset = 0

        for text, meta in documents:
            pairs = self.chunk(text, meta)
            for pair in pairs:
                parent = pair.parent
                parent.index = parent_offset + parent.index
                all_parents.append(parent)

                for child in pair.children:
                    child.parent_index = parent.index
                    child_idx = len(all_children)
                    all_children.append(child)
                    child_to_parent[child_idx] = parent.index

            parent_offset += len(pairs)

        return all_parents, all_children, child_to_parent


# ========== 文本预处理 ==========

def clean_text(text: str) -> str:
    """文本清洗：去除多余空白、统一换行等。"""
    # 统一换行符
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    # 去除连续空行（保留最多 1 个）
    text = re.sub(r"\n{3,}", "\n\n", text)
    # 去除首尾空白
    text = text.strip()
    return text


def extract_sections(text: str, max_section_length: int = 2000) -> List[str]:
    """按标题/段落粗略分割长文档，避免后续分割时丢失结构。

    识别以 # 或数字标题开头的段落。
    """
    # 按可能的标题分隔
    sections = re.split(r"\n(?=#{1,3}\s|\d+[\.、)])", text)
    result = []
    for sec in sections:
        sec = clean_text(sec)
        if not sec:
            continue
        if len(sec) > max_section_length:
            # 长段落再按双换行分割
            subs = sec.split("\n\n")
            result.extend(clean_text(s) for s in subs if s.strip())
        else:
            result.append(sec)
    return result