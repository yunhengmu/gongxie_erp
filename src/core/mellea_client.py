from __future__ import annotations

from functools import lru_cache

from core.settings import settings


@lru_cache
def get_mellea_backend():
    """进程内共享一个 OpenAIBackend（HTTP 客户端），指向 vLLM。"""
    from mellea.backends.openai import OpenAIBackend

    base_url = settings.VLLM_BASE_URL or settings.COMPATIBLE_BASE_URL
    model_id = settings.VLLM_MODEL or settings.COMPATIBLE_MODEL
    if not base_url or not model_id:
        raise ValueError("Mellea 需要 VLLM_BASE_URL/VLLM_MODEL（或 COMPATIBLE_*）配置")

    api_key = "EMPTY"
    if settings.VLLM_API_KEY:
        api_key = settings.VLLM_API_KEY.get_secret_value()
    elif settings.COMPATIBLE_API_KEY:
        api_key = settings.COMPATIBLE_API_KEY.get_secret_value()

    return OpenAIBackend(model_id=model_id, base_url=base_url, api_key=api_key)


def new_mellea_session():
    """每次业务调用新建一个无状态会话。

    IVR 重试循环发生在会话内部；调用结束即丢弃，避免上下文跨请求累积。
    """
    from mellea import MelleaSession
    from mellea.stdlib.context import ChatContext

    return MelleaSession(get_mellea_backend(), ctx=ChatContext())
