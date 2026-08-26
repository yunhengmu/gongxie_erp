"""RAG（检索增强生成）核心模块。

提供:
  - 混合检索器（BM25 + 向量双路检索 + RRF 融合）
  - 中文递归文本分割器
  - 父子分块策略
  - 多模态文档加载器
  - OCR 文字识别
  - Redis 缓存层
  - BERT 问题分类器
  - 检索策略选择器
  - Ragas 评估工具

模块结构:
  retriever.py   - 混合检索器
  chunker.py     - 文本分割器 + 父子分块
  document/      - 文档加载器 + OCR
  cache.py       - Redis 缓存层
  classifier.py  - 问题分类器（BERT）
  strategy.py    - 检索策略选择器
  evaluate.py    - Ragas 评估
"""