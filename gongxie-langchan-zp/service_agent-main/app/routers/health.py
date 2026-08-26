"""健康检查端点。"""
from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/health")
async def health():
    return {"status": "ok"}


@router.get("/ready")
async def ready():
    """就绪检查（供容器编排用）。"""
    return {"status": "ready"}