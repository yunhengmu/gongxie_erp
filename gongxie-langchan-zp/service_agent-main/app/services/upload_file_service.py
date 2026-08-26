from fastapi import UploadFile
from core.rag.document.loader import load_document
from core.rag.chunker import ChineseRecursiveTextSplitter, ParentChildChunker, clean_text
from utils.file_util import save_file
from datetime import datetime
from app.services.rag_service import RAGService
from app.schemas.file_and_ask import ResponseFileAsk
from core.rag.classifier import classify_query
from core.rag.strategy import select_strategy, RetrievalStrategy
from core.agent import research_agent, code_reviewer_agent, rag_agent
from core.rag.retriever import HybridRetriever, VectorRetriever
from app.schemas.file_and_ask import Tag

class UploadFileService:

    def __init__(self, rag_service: RAGService):
        self._rag_service = rag_service

    async def load_document(self, files: list[UploadFile]) -> int:
        trunkNum = -1
        if files:
            trunkNum = 0
            for file in files:
                content = await file.read()
                timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
                filename = f"agent_{timestamp}_{file.filename}"
                path = save_file(content, filename, "./uploads")
                doc = load_document(path)
                if doc:
                    text = clean_text(doc.content)
                    if text and len(text) > 1500:
                        chunker = ParentChildChunker()
                        pairs = chunker.chunk(text)
                        children_texts = [c.content for p in pairs for c in p.children]
                        self._rag_service.add_documents(children_texts)
                        trunkNum += len(children_texts)
                    else:
                        splitter = ChineseRecursiveTextSplitter()
                        chunks = splitter.split_text(text)
                        self._rag_service.add_documents(chunks) 
                        trunkNum += len(chunks)
        return trunkNum

    async def load_document_ask(self, files: list[UploadFile], question: str) -> ResponseFileAsk:
            # ========== 1. 文件入库 ==========
            if files:
                for file in files:
                    content = await file.read()
                    timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
                    filename = f"{timestamp}_{file.filename}"
                    path = save_file(content, filename, "./uploads")
                    doc = load_document(path)
                    if doc:
                        text = clean_text(doc.content)
                        chunker = ParentChildChunker()
                        pairs = chunker.chunk(text)
                        children_texts = [child.content for pair in pairs for child in pair.children]
                        self._retriever.add_documents(children_texts)   # ← 入自己的检索器

            # ========== 2. 分类 + 策略 ==========
            classification = classify_query(question)
            strategy, _ = select_strategy(classification, question)

            tags: list[Tag] = []

            # ========== 3. 按策略构造 messages ==========
            if strategy == RetrievalStrategy.HYBRID_SEARCH:
                # 检索
                results = self._retriever.search(question, top_k=5)

                # 标签 1：策略标记
                tags.append(Tag(content="hybrid_search", tag="strategy"))

                if results:
                    context_parts = []
                    for r in results:
                        context_parts.append(f"[文档] {r.content}")
                        # 标签 2：每条结果的来源（bm25 / vector / fusion）
                        tags.append(Tag(content=r.content[:100], tag=r.source))
                    context = "\n\n".join(context_parts)
                    messages = [
                        {"role": "system", "content": f"参考以下知识库内容回答问题:\n\n{context}"},
                        {"role": "user", "content": question},
                    ]
                else:
                    tags.append(Tag(content="no_results", tag="search_status"))
                    messages = [{"role": "user", "content": question}]

                agent = rag_agent

            else:
                # 标签：非检索策略
                tags.append(Tag(content=strategy.value, tag="strategy"))
                messages = [{"role": "user", "content": question}]
                agent = research_agent

            # ========== 4. 调用 Agent ==========
            config = {"configurable": {"thread_id": f"file-ask-{uuid.uuid4().hex[:8]}"}}
            result = await agent.ainvoke({"messages": messages}, config=config)
            answer = result["messages"][-1].content if result.get("messages") else ""

            return ResponseFileAsk(response=answer, tags=tags)