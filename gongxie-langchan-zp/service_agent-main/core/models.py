"""统一管理所有模型的初始化。

所有模型从这里输出，切换模型只需改 .env 中的 LLM_MODEL：
  - deepseek:deepseek-chat
  - openai:gpt-4o
  - anthropic:claude-sonnet-4-5
"""
import os

from langchain.chat_models import init_chat_model

from config.settings import settings

# 将 API Key 注入环境变量（init_chat_model 从环境变量读取）
if settings.deepseek_api_key:
    os.environ.setdefault("DEEPSEEK_API_KEY", settings.deepseek_api_key)

deepseek_model = init_chat_model(
    settings.llm_model,
    temperature=settings.llm_temperature,
    timeout=settings.llm_timeout,
)