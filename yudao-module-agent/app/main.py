"""FastAPI 服务入口。

Spring 类比：
  @SpringBootApplication  →  FastAPI() + lifespan
  @ComponentScan          →  app.include_router(...)
  @ControllerAdvice       →  app.exception_handler(...)
"""
import logging
import sys
import os
import time
import traceback

from fastapi import FastAPI, Request, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager

from config.settings import settings
from app.routers import health, chat, rag, ws, file_up_content
from app.schemas.common import ApiResponse, StatusCode


def setup_logging():
    """配置结构化日志。"""
    logging.basicConfig(
        stream=sys.stdout,
        level=logging.INFO,
        format="%(asctime)s | %(name)s | %(levelname)s | %(message)s",
    )


def setup_langsmith():
    """启用 LangSmith 追踪（如果配置了 API Key）。"""
    if settings.langsmith_tracing and settings.langsmith_api_key:
        os.environ["LANGCHAIN_TRACING_V2"] = "true"
        os.environ["LANGCHAIN_API_KEY"] = settings.langsmith_api_key
        os.environ["LANGCHAIN_PROJECT"] = "my-agents"


async def verify_api_key(request: Request, call_next):
    """简单的 API Key 认证中间件（可选）。

    在生产环境建议用 OAuth2 / JWT 替代。
    """
    # 健康检查端点跳过认证
    if request.url.path in ("/health", "/ready", "/docs", "/openapi.json"):
        return await call_next(request)

    # 如果设置了 API_KEY 则要求认证
    api_key = getattr(settings, "api_key", "")
    if api_key:
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or auth.removeprefix("Bearer ") != api_key:
            raise HTTPException(status_code=401, detail="Invalid or missing API Key")
    return await call_next(request)


_request_logger = logging.getLogger("api.request")


async def request_logging_middleware(request: Request, call_next):
    """请求日志中间件：记录每个 FastAPI 接口的请求和响应。

    日志格式：
      → 请求: method path | client_ip | query_params
      ← 响应: status_code | duration_ms
    """
    start_time = time.time()

    # 请求日志
    _request_logger.info(
        "→ %s %s | client=%s | query=%s",
        request.method,
        request.url.path,
        request.client.host if request.client else "-",
        dict(request.query_params) or "-",
    )

    response = await call_next(request)

    # 响应日志
    duration_ms = (time.time() - start_time) * 1000
    _request_logger.info(
        "← %s %s | status=%s | duration=%.2fms",
        request.method,
        request.url.path,
        response.status_code,
        duration_ms,
    )

    return response


@asynccontextmanager
async def lifespan(app: FastAPI):
    setup_logging()
    setup_langsmith()
    logger = logging.getLogger(__name__)
    logger.info(
        "service starting | model=%s | memory=%s | langsmith=%s",
        settings.llm_model,
        settings.memory_type,
        settings.langsmith_tracing,
    )
    yield
    logger.info("service stopped")


app = FastAPI(
    title="My Agents API",
    version="0.2.0",
    lifespan=lifespan,
)

# ========== 中间件 ==========
# CORS（允许前端跨域访问）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# API Key 认证（可选）
app.middleware("http")(verify_api_key)

# 请求日志（记录所有 FastAPI 接口调用）
app.middleware("http")(request_logging_middleware)


# ========== 全局异常处理器（类比 Spring 的 @ControllerAdvice）==========

@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
    """将 HTTPException 包装为统一响应格式。"""
    return JSONResponse(
        status_code=exc.status_code,
        content=ApiResponse.error(
            code=exc.status_code,
            message=exc.detail if isinstance(exc.detail, str) else str(exc.detail),
        ).model_dump(),
    )


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    """将 Pydantic 参数校验错误包装为统一响应格式。"""
    errors = exc.errors()
    # 提取第一条校验错误信息
    detail = errors[0].get("msg", "参数校验失败") if errors else "参数校验失败"
    return JSONResponse(
        status_code=422,
        content=ApiResponse.error(
            code=StatusCode.UNPROCESSABLE,
            message=f"参数校验失败: {detail}",
        ).model_dump(),
    )


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """兜底异常处理器 —— 捕获所有未处理的异常。"""
    logger = logging.getLogger(__name__)
    logger.error(
        "未处理异常 | path=%s | type=%s | %s\n%s",
        request.url.path,
        type(exc).__name__,
        exc,
        traceback.format_exc(),
    )
    return JSONResponse(
        status_code=500,
        content=ApiResponse.internal_error(
            message="服务器内部错误" if not settings.app_debug else str(exc),
        ).model_dump(),
    )


# ========== 路由注册 ==========
app.include_router(health.router)
app.include_router(chat.router)
app.include_router(rag.router)
app.include_router(ws.router)
app.include_router(file_up_content.router)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.app_host,
        port=settings.app_port,
        reload=settings.app_debug,
    )