"""RAG 路由 — 知识库问答、文档上传、评估。"""
import logging
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from fastapi.responses import StreamingResponse

from app.dependencies import get_rag_service
from app.schemas.rag import RAGRequest, RAGResponse, EvalRequest, EvalResponse, DocumentUploadResponse
from app.schemas.common import ApiResponse
from app.services.rag_service import RAGService

logger = logging.getLogger(__name__)

router = APIRouter(tags=["rag"])


@router.post("/v1/rag/ask", response_model=ApiResponse[RAGResponse])
async def rag_ask(
    req: RAGRequest,
    service: RAGService = Depends(get_rag_service),
):
    """RAG 知识库问答（非流式）。

    自动分类问题类型，选择最优检索策略。
    """
    result = await service.ask(
        question=req.question,
        thread_id=req.thread_id,
        use_hybrid=req.use_hybrid,
        top_k=req.top_k,
    )
    return ApiResponse.success(data=RAGResponse(**result))


@router.post("/v1/rag/ask/stream")
async def rag_ask_stream(
    req: RAGRequest,
    service: RAGService = Depends(get_rag_service),
):
    """RAG 知识库问答（流式 SSE）。"""

    # async def + yield = 异步生成器，既能 await 又能 yield
    # 写成闭包是为了直接访问外层的 req 和 service，不需要传参
    async def event_generator():
        """SSE 事件生成器 — 把 Service 的 token 流包装成 SSE 格式"""
        try:
            # async for: 每等 LLM 吐出一个 token 就循环一次，不等全部生成完
            # service.ask_stream() 返回 AsyncGenerator[str, None]，内部通过 agent.astream_events() 逐 token 获取
            async for token in service.ask_stream(
                question=req.question,       # 用户问题
                thread_id=req.thread_id,     # 会话ID，"" 表示自动生成，用于 LangGraph 多轮对话记忆
                use_hybrid=req.use_hybrid,   # True=BM25+向量混合检索，False=仅向量检索
                top_k=req.top_k,             # 检索文档数量，范围 1~20
            ):
                # SSE 协议格式: "data: <内容>\n\n"
                # \n\n 是双换行，SSE 靠它判断一条消息的边界（收到 \n\n 就知道当前消息完整了）
                yield f"data: {token}\n\n"

            # 流结束信号，模仿 OpenAI 的 SSE 设计
            # 客户端收到 "[DONE]" 后就知道没有更多 token 了，可以 break
            # 如果不发这个信号，客户端只能靠连接关闭来判断结束，无法区分"正常结束"还是"异常断开"
            yield "data: [DONE]\n\n"

        except Exception as e:
            # 必须在生成器内部捕获异常，不能用 FastAPI 默认的异常处理
            # 原因：此时 HTTP 响应头已发出，Body 已发了一部分，无法再返回 JSON 错误
            # logger.exception() 会自动附带完整调用栈，方便排查
            logger.exception("RAG 流式问答失败")
            yield f"data: [ERROR] {e}\n\n"

    # StreamingResponse: FastAPI 的流式响应，接收一个可迭代对象（生成器）
    # FastAPI 内部会逐个迭代生成器，每拿到一个 chunk 就作为 HTTP 响应体的一块发送（Transfer-Encoding: chunked）
    return StreamingResponse(
        event_generator(),  # 注意：调用函数，传进去的是生成器对象，不是函数引用

        # 设置 HTTP 响应头 Content-Type: text/event-stream
        # text/event-stream 是 SSE 协议的官方 MIME 类型，浏览器 EventSource API 只认这个
        media_type="text/event-stream",

        headers={
            # 告诉浏览器和中间代理不要缓存这个响应
            # SSE 是实时生成的，每次请求内容不同，缓存了就没意义
            "Cache-Control": "no-cache",

            # 告诉客户端和代理保持 TCP 连接不关闭
            # SSE 是长连接，LLM 可能生成几十秒甚至几分钟
            "Connection": "keep-alive",

            # 专门针对 Nginx 反向代理：禁用 proxy_buffering
            # Nginx 默认会缓冲上游响应再一次性发给客户端，这对 SSE 是致命的
            # 不加这个头的话，客户端等了 30 秒，然后一瞬间收到全部内容，完全不是流式
            "X-Accel-Buffering": "no",
        },
    )


# @router.post("/v1/rag/documents/upload", response_model=ApiResponse[DocumentUploadResponse])
# async def upload_documents(
#     files: list[UploadFile] = File(...),
#     service: RAGService = Depends(get_rag_service),
# ):
#     """上传文档到知识库（支持 PDF/Word/PPT/TXT）。"""
#     from core.rag.document.loader import load_document
#     import tempfile
#     import os

#     total_chunks = 0
#     results = []

#     for file in files:
#         try:
#             # 保存临时文件
#             suffix = os.path.splitext(file.filename)[1] if file.filename else ".txt"
#             with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
#                 content = await file.read()
#                 tmp.write(content)
#                 tmp_path = tmp.name

#             # 加载文档
#             doc = load_document(tmp_path)
#             os.unlink(tmp_path)

#             if doc.content.strip():
#                 from core.rag.chunker import ChineseRecursiveTextSplitter, clean_text

#                 splitter = ChineseRecursiveTextSplitter(chunk_size=500, chunk_overlap=80)
#                 chunks = splitter.split_text(clean_text(doc.content))
#                 service.add_documents(chunks)
#                 total_chunks += len(chunks)
#                 results.append(f"{file.filename}: {len(chunks)} chunks")
#             else:
#                 results.append(f"{file.filename}: 空文档")

#         except Exception as e:
#             logger.warning("文档上传失败 %s: %s", file.filename, e)
#             results.append(f"{file.filename}: 失败 - {e}")

#     return ApiResponse.success(
#         data=DocumentUploadResponse(
#             success=True,
#             file_name=", ".join(r.split(":")[0] for r in results),
#             chunk_count=total_chunks,
#             message="; ".join(results),
#         )
#     )


@router.post("/v1/rag/evaluate", response_model=ApiResponse[EvalResponse])
async def evaluate_rag(
    req: EvalRequest,
    service: RAGService = Depends(get_rag_service),
):
    """评估 RAG 系统性能（Ragas）。"""
    result = service.evaluate(req.samples)
    return ApiResponse.success(data=EvalResponse(**result))