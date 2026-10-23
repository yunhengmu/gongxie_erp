"""Agno 本地可视化控制台后端。

- /os        AgentOS（开启 JWT 鉴权 + RBAC + 审计）
- /console    Vue 构建产物
- /console/api 控制台自身接口（配置、状态、本地管理员令牌）
"""

from __future__ import annotations

import hashlib
import importlib
import json
import os
import re
import secrets
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal, Optional

import jwt as pyjwt
import httpx
import uvicorn
from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

import agno
from agno.agent import Agent
from agno.db.sqlite import SqliteDb
from agno.models.openai import OpenAIChat, OpenAILike
from agno.os import AgentOS, Authorization, create_dev_token
from agno.team import Team
from agno.tools.function import Function
from agno.workflow import Step, Workflow

BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "console.config.json"
COMPONENTS_PATH = BASE_DIR / "console.components.json"
ACCOUNTS_PATH = BASE_DIR / "console.accounts.json"
AUX_PATH = BASE_DIR / "console.aux_models.json"
TOOLS_PATH = BASE_DIR / "console.tools.json"
DB_PATH = BASE_DIR / "agno-console.db"
DIST_DIR = BASE_DIR / "console-web" / "dist"

OS_ID = "agno-console"
ADMIN_SUBJECT = "local-admin"
AUTH_SECRET = os.getenv("CONSOLE_AUTH_SECRET", "agno-console-local-dev-secret-0123456789abcdef")

DEFAULT_CONFIG = {
    "agent_id": "assistant",
    "name": "assistant",
    "provider": "openai",
    "model_id": "gpt-4o-mini",
    "base_url": "",
    "host": "",
    "api_key": "",
    "temperature": 0.7,
    "max_tokens": None,
    "instructions": "你是一个用于本地调试的助手，回答简洁准确。",
    "markdown": True,
}


class AgentConfig(BaseModel):
    agent_id: str = "assistant"
    name: str = "assistant"
    provider: str = "openai"
    model_id: str = "gpt-4o-mini"
    base_url: str = ""
    host: str = ""
    api_key: Optional[str] = None
    temperature: float = 0.7
    max_tokens: Optional[int] = None
    instructions: str = ""
    markdown: bool = True


def load_config() -> dict:
    if CONFIG_PATH.exists():
        try:
            saved = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
            return {**DEFAULT_CONFIG, **saved}
        except (json.JSONDecodeError, OSError):
            pass
    return dict(DEFAULT_CONFIG)


def save_config(config: dict) -> None:
    tmp_path = CONFIG_PATH.with_suffix(".json.tmp")
    tmp_path.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp_path.replace(CONFIG_PATH)


PROVIDER_SPECS: dict[str, dict] = {
    "openai": {
        "label": "OpenAI 官方",
        "module": "agno.models.openai",
        "class": "OpenAIChat",
        "fields": ["model_id", "api_key"],
        "env": "OPENAI_API_KEY",
    },
    "openai-compatible": {
        "label": "OpenAI 兼容适配器",
        "module": "agno.models.openai",
        "class": "OpenAILike",
        "fields": ["model_id", "base_url", "api_key"],
        "env": "",
    },
    "deepseek": {
        "label": "DeepSeek",
        "module": "agno.models.deepseek",
        "class": "DeepSeek",
        "fields": ["model_id", "api_key"],
        "env": "DEEPSEEK_API_KEY",
    },
    "dashscope": {
        "label": "通义千问 DashScope",
        "module": "agno.models.dashscope",
        "class": "DashScope",
        "fields": ["model_id", "api_key"],
        "env": "DASHSCOPE_API_KEY",
    },
    "vllm": {
        "label": "vLLM",
        "module": "agno.models.vllm",
        "class": "VLLM",
        "fields": ["model_id", "base_url", "api_key"],
        "env": "",
    },
    "openrouter": {
        "label": "OpenRouter",
        "module": "agno.models.openrouter",
        "class": "OpenRouter",
        "fields": ["model_id", "api_key"],
        "env": "OPENROUTER_API_KEY",
    },
    "ollama": {
        "label": "Ollama（需安装 ollama 包）",
        "module": "agno.models.ollama",
        "class": "Ollama",
        "fields": ["model_id", "host"],
        "env": "",
    },
    "anthropic": {
        "label": "Anthropic Claude（需安装 anthropic 包）",
        "module": "agno.models.anthropic",
        "class": "Claude",
        "fields": ["model_id", "api_key"],
        "env": "ANTHROPIC_API_KEY",
    },
    "google": {
        "label": "Google Gemini（需安装 google-genai 包）",
        "module": "agno.models.google",
        "class": "Gemini",
        "fields": ["model_id", "api_key"],
        "env": "GOOGLE_API_KEY",
    },
}

_PROVIDER_CLASS_CACHE: dict[str, object] = {}


def load_provider_class(provider: str):
    spec = PROVIDER_SPECS.get(provider)
    if spec is None:
        raise ValueError(f"不支持的模型来源：{provider}")
    if provider not in _PROVIDER_CLASS_CACHE:
        module = importlib.import_module(spec["module"])
        _PROVIDER_CLASS_CACHE[provider] = getattr(module, spec["class"])
    return _PROVIDER_CLASS_CACHE[provider]


def build_model(config: dict):
    provider = config.get("provider") or "openai"
    spec = PROVIDER_SPECS.get(provider)
    if spec is None:
        raise ValueError(f"不支持的模型来源：{provider}")

    model_class = load_provider_class(provider)
    kwargs: dict = {"id": config["model_id"]}

    if "api_key" in spec["fields"]:
        env_key = os.getenv(spec["env"]) if spec.get("env") else ""
        kwargs["api_key"] = config.get("api_key") or env_key or "sk-test-placeholder"
    if "base_url" in spec["fields"]:
        kwargs["base_url"] = config.get("base_url") or None
    if "host" in spec["fields"]:
        kwargs["host"] = config.get("host") or None
    kwargs["temperature"] = float(config.get("temperature", 0.7))
    if config.get("max_tokens"):
        kwargs["max_tokens"] = int(config["max_tokens"])

    try:
        return model_class(**kwargs)
    except TypeError:
        kwargs.pop("temperature", None)
        kwargs.pop("max_tokens", None)
        return model_class(**kwargs)


def build_agent(config: dict, db: SqliteDb) -> Agent:
    model = build_model(config)
    aux_tools = build_aux_tools(config) + build_library_tools(config)

    kwargs: dict = {}
    if aux_tools:
        kwargs["tools"] = aux_tools

    return Agent(
        id=config.get("agent_id") or "assistant",
        name=config.get("name") or config.get("agent_id") or "assistant",
        model=model,
        instructions=compose_instructions(config),
        markdown=bool(config.get("markdown", True)),
        db=db,
        filesystem=True,
        **kwargs,
    )


def agent_summary(agent: Agent) -> dict:
    model = getattr(agent, "model", None)
    return {
        "id": agent.id,
        "name": agent.name,
        "model": getattr(model, "id", ""),
        "provider": getattr(model, "provider", ""),
        "temperature": getattr(model, "temperature", None),
    }


def load_component_store() -> dict:
    data: dict = {"agents": [], "teams": [], "workflows": [], "history": {}, "current": {}}
    if COMPONENTS_PATH.exists():
        try:
            saved = json.loads(COMPONENTS_PATH.read_text(encoding="utf-8"))
            for key in ("agents", "teams", "workflows"):
                data[key] = saved.get(key, [])
            data["history"] = saved.get("history", {})
            data["current"] = saved.get("current", {})
        except (json.JSONDecodeError, OSError):
            pass
    return data


def save_component_store(data: dict) -> None:
    tmp_path = COMPONENTS_PATH.with_suffix(".json.tmp")
    tmp_path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp_path.replace(COMPONENTS_PATH)


def agent_config_from_def(definition: dict) -> dict:
    return {
        "agent_id": definition["id"],
        "name": definition.get("name") or definition["id"],
        "provider": definition.get("provider", "openai"),
        "model_id": definition.get("model_id", "gpt-4o-mini"),
        "base_url": definition.get("base_url", ""),
        "host": definition.get("host", ""),
        "api_key": definition.get("api_key", ""),
        "temperature": definition.get("temperature", 0.7),
        "max_tokens": None,
        "instructions": definition.get("instructions", ""),
        "markdown": True,
        "aux_bindings": definition.get("aux_bindings") or [],
        "tool_ids": definition.get("tool_ids") or [],
    }


def build_team_from_def(definition: dict, pool: list[Agent]) -> Team:
    wanted = set(definition.get("member_ids") or [])
    members = [item for item in pool if item.id in wanted]
    if not members:
        raise ValueError("团队至少需要一个已存在的 Agent 成员")
    return Team(
        id=definition["id"],
        name=definition.get("name") or definition["id"],
        description=definition.get("description") or None,
        members=members,
        db=db,
    )


def build_workflow_from_def(definition: dict, pool: list[Agent]) -> Workflow:
    wanted = set(definition.get("step_ids") or [])
    step_agents = [item for item in pool if item.id in wanted]
    if not step_agents:
        raise ValueError("工作流至少需要一个已存在的 Agent 步骤")
    steps = [Step(name=f"step-{index + 1}", agent=item) for index, item in enumerate(step_agents)]
    return Workflow(
        id=definition["id"],
        name=definition.get("name") or definition["id"],
        description=definition.get("description") or None,
        steps=steps,
        db=db,
    )


def initialize_component(component: object) -> None:
    for method_name in ("initialize_agent", "initialize_team", "initialize_workflow"):
        method = getattr(component, method_name, None)
        if callable(method):
            method()


def component_key(kind: str, component_id: str) -> str:
    return f"{kind}:{component_id}"


def record_component_version(store: dict, kind: str, definition: dict) -> int:
    key = component_key(kind, definition["id"])
    history = store.setdefault("history", {}).setdefault(key, [])
    number = (history[-1]["version"] if history else 0) + 1
    history.append(
        {
            "version": number,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "definition": definition,
        }
    )
    store.setdefault("current", {})[key] = number
    return number


def load_accounts() -> list[dict]:
    if ACCOUNTS_PATH.exists():
        try:
            saved = json.loads(ACCOUNTS_PATH.read_text(encoding="utf-8"))
            return saved if isinstance(saved, list) else []
        except (json.JSONDecodeError, OSError):
            pass
    return []


def save_accounts(accounts: list[dict]) -> None:
    tmp_path = ACCOUNTS_PATH.with_suffix(".json.tmp")
    tmp_path.write_text(json.dumps(accounts, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp_path.replace(ACCOUNTS_PATH)


def hash_password(password: str, salt: Optional[str] = None) -> tuple[str, str]:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt), 200_000).hex()
    return salt, digest


def verify_password(password: str, salt: str, expected: str) -> bool:
    _, digest = hash_password(password, salt)
    return secrets.compare_digest(digest, expected)


def ensure_account(username: str, user_id: str, password: str) -> None:
    accounts = load_accounts()
    if any(item.get("username") == username for item in accounts):
        return
    salt, digest = hash_password(password)
    accounts.append({"username": username, "user_id": user_id, "salt": salt, "password_hash": digest})
    save_accounts(accounts)


def role_scopes(role_record: Optional[dict]) -> set[str]:
    if not role_record:
        return set()
    scopes: set[str] = set()
    for entry in role_record.get("scopes") or []:
        if isinstance(entry, str):
            scopes.add(entry)
        elif isinstance(entry, dict):
            raw = entry.get("raw") or entry.get("scope")
            effect = entry.get("effect", "allow")
            if raw and effect != "deny":
                scopes.add(raw)
    return scopes


def load_aux_models() -> list[dict]:
    if AUX_PATH.exists():
        try:
            data = json.loads(AUX_PATH.read_text(encoding="utf-8"))
            return data if isinstance(data, list) else []
        except (json.JSONDecodeError, OSError):
            pass
    return []


def save_aux_models(items: list[dict]) -> None:
    tmp_path = AUX_PATH.with_suffix(".json.tmp")
    tmp_path.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp_path.replace(AUX_PATH)


def load_library_tools() -> list[dict]:
    if TOOLS_PATH.exists():
        try:
            data = json.loads(TOOLS_PATH.read_text(encoding="utf-8"))
            return data if isinstance(data, list) else []
        except (json.JSONDecodeError, OSError):
            pass
    return []


def save_library_tools(items: list[dict]) -> None:
    tmp_path = TOOLS_PATH.with_suffix(".json.tmp")
    tmp_path.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp_path.replace(TOOLS_PATH)


def find_library_tool(tool_id: Optional[str]) -> Optional[dict]:
    if not tool_id:
        return None
    return next((item for item in load_library_tools() if item.get("id") == tool_id), None)


AUX_PROTOCOLS: dict[str, list[dict]] = {
    "embedding": [
        {"key": "openai-compatible", "label": "OpenAI 兼容 /embeddings"},
        {"key": "ollama", "label": "Ollama /api/embeddings"},
        {"key": "custom", "label": "仅保存配置（不测试）"},
    ],
    "rerank": [
        {"key": "cohere", "label": "Cohere /v1/rerank"},
        {"key": "custom", "label": "仅保存配置（不测试）"},
    ],
    "tts": [
        {"key": "openai-compatible", "label": "OpenAI 兼容 /audio/speech"},
        {"key": "custom", "label": "仅保存配置（不测试）"},
    ],
    "stt": [
        {"key": "openai-compatible", "label": "OpenAI 兼容 /audio/transcriptions"},
        {"key": "custom", "label": "仅保存配置（不测试）"},
    ],
}


def post_json(url: str, payload: dict, headers: dict, timeout: int = 25) -> tuple[int, dict]:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8", "ignore")
        try:
            return response.status, json.loads(raw)
        except json.JSONDecodeError:
            return response.status, {"raw": raw[:300]}


def test_aux_model(item: dict) -> dict:
    kind = item.get("kind")
    protocol = item.get("protocol")
    base = (item.get("base_url") or "").rstrip("/")
    model_id = item.get("model") or ""

    if protocol == "custom":
        return {"ok": True, "message": "已保存配置，未执行在线测试"}
    if not base:
        return {"ok": False, "message": "未配置 base_url"}

    headers = {"Content-Type": "application/json"}
    if item.get("api_key"):
        headers["Authorization"] = f"Bearer {item['api_key']}"

    try:
        if kind == "embedding" and protocol == "openai-compatible":
            status, data = post_json(f"{base}/embeddings", {"model": model_id, "input": "hello"}, headers)
            vector = ((data.get("data") or [{}])[0] or {}).get("embedding") or []
            return {"ok": True, "status": status, "message": f"向量维度 {len(vector)}"}

        if kind == "embedding" and protocol == "ollama":
            status, data = post_json(f"{base}/api/embeddings", {"model": model_id, "prompt": "hello"}, headers)
            vector = data.get("embedding") or []
            return {"ok": True, "status": status, "message": f"向量维度 {len(vector)}"}

        if kind == "rerank" and protocol == "cohere":
            status, data = post_json(
                f"{base}/v1/rerank",
                {"model": model_id, "query": "hello", "documents": ["a", "b"]},
                headers,
            )
            return {"ok": True, "status": status, "message": f"返回 {len(data.get('results') or [])} 条重排结果"}

        if kind == "tts" and protocol == "openai-compatible":
            payload = {
                "model": model_id or "tts-1",
                "voice": (item.get("extra") or {}).get("voice", "alloy"),
                "input": "你好",
            }
            request = urllib.request.Request(
                f"{base}/audio/speech", data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST"
            )
            with urllib.request.urlopen(request, timeout=30) as response:
                audio = response.read()
            return {"ok": True, "status": 200, "message": f"返回音频 {len(audio)} 字节"}

        if kind == "stt":
            return {"ok": False, "message": "STT 测试需要音频文件，请通过对话页或外部客户端上传"}

        return {"ok": False, "message": f"{kind} + {protocol} 暂不支持在线测试"}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "ignore")[:300]
        return {"ok": False, "status": exc.code, "message": detail}
    except Exception as exc:
        return {"ok": False, "message": f"{type(exc).__name__}: {exc}"}


def find_aux_model(model_id: Optional[str]) -> Optional[dict]:
    if not model_id:
        return None
    return next((item for item in load_aux_models() if item.get("id") == model_id), None)


def build_tts_tool(model_id: str, suffix: str = ""):
    def text_to_speech(text: str, voice: str = "") -> str:
        """把一段文字合成为语音文件并保存到服务器本地，返回可下载的文件路径。"""
        item = find_aux_model(model_id)
        if item is None:
            return "语音合成失败：绑定的 TTS 辅助模型已不存在"
        base = (item.get("base_url") or "").rstrip("/")
        if not base:
            return "语音合成失败：该 TTS 辅助模型未配置 base_url"
        payload = {
            "model": item.get("model") or "tts-1",
            "voice": voice or (item.get("extra") or {}).get("voice", "alloy"),
            "input": text,
        }
        headers = {"Content-Type": "application/json"}
        if item.get("api_key"):
            headers["Authorization"] = f"Bearer {item['api_key']}"
        try:
            response = httpx.post(f"{base}/audio/speech", json=payload, headers=headers, timeout=60)
            response.raise_for_status()
        except Exception as exc:
            return f"语音合成失败：{type(exc).__name__}: {exc}"
        audio_dir = BASE_DIR / "audio"
        audio_dir.mkdir(exist_ok=True)
        ext = ".mp3" if "mpeg" in (response.headers.get("content-type") or "") else ".wav"
        path = audio_dir / f"tts-{uuid.uuid4().hex[:8]}{ext}"
        path.write_bytes(response.content)
        return f"音频已生成：{path}（{len(response.content)} 字节）"

    text_to_speech.__name__ = f"text_to_speech{suffix}"
    return text_to_speech


def build_stt_tool(model_id: str, suffix: str = ""):
    def transcribe_audio(file_path: str) -> str:
        """把服务器上的本地音频文件转写为文字，file_path 必须是绝对路径。"""
        item = find_aux_model(model_id)
        if item is None:
            return "语音转写失败：绑定的 STT 辅助模型已不存在"
        base = (item.get("base_url") or "").rstrip("/")
        if not base:
            return "语音转写失败：该 STT 辅助模型未配置 base_url"
        path = Path(file_path)
        if not path.exists():
            return f"语音转写失败：文件不存在 {file_path}"
        headers = {}
        if item.get("api_key"):
            headers["Authorization"] = f"Bearer {item['api_key']}"
        files = {"file": (path.name, path.read_bytes(), "application/octet-stream")}
        try:
            response = httpx.post(
                f"{base}/audio/transcriptions",
                data={"model": item.get("model") or "whisper-1"},
                files=files,
                headers=headers,
                timeout=120,
            )
            response.raise_for_status()
            payload = response.json()
        except Exception as exc:
            return f"语音转写失败：{type(exc).__name__}: {exc}"
        return payload.get("text") or response.text[:300]

    transcribe_audio.__name__ = f"transcribe_audio{suffix}"
    return transcribe_audio


def build_rerank_tool(model_id: str, suffix: str = ""):
    def rerank_documents(query: str, documents: list[str]) -> str:
        """按与 query 的相关度重排候选文本，返回 JSON 数组，每项含 text 与 score。"""
        item = find_aux_model(model_id)
        if item is None:
            return "重排失败：绑定的 Rerank 辅助模型已不存在"
        protocol = item.get("protocol")
        if protocol != "cohere":
            return f"重排失败：协议 {protocol} 暂不支持重排调用"
        base = (item.get("base_url") or "").rstrip("/")
        if not base:
            return "重排失败：该 Rerank 辅助模型未配置 base_url"
        headers = {"Content-Type": "application/json"}
        if item.get("api_key"):
            headers["Authorization"] = f"Bearer {item['api_key']}"
        try:
            _, data = post_json(
                f"{base}/v1/rerank",
                {"model": item.get("model") or "", "query": query, "documents": documents},
                headers,
            )
        except Exception as exc:
            return f"重排失败：{type(exc).__name__}: {exc}"
        ranked = []
        for entry in (data.get("results") or [])[:10]:
            index = entry.get("index")
            if isinstance(index, int) and 0 <= index < len(documents):
                ranked.append({"text": documents[index], "score": entry.get("relevance_score")})
        return json.dumps(ranked, ensure_ascii=False)

    rerank_documents.__name__ = f"rerank_documents{suffix}"
    return rerank_documents


AUX_KIND_LABELS = {"embedding": "Embedding", "rerank": "Rerank", "tts": "TTS", "stt": "STT"}
AUX_TOOL_BUILDERS = {"tts": build_tts_tool, "stt": build_stt_tool, "rerank": build_rerank_tool}


def build_aux_tools(config: dict) -> list:
    """把 Agent 绑定的辅助模型包装成可调用工具，同类型多个时自动加序号。"""
    tools: list = []
    counts: dict[str, int] = {}
    for binding in config.get("aux_bindings") or []:
        item = find_aux_model(binding.get("id"))
        if item is None:
            continue
        kind = item.get("kind") or ""
        builder = AUX_TOOL_BUILDERS.get(kind)
        if builder is None:
            continue
        counts[kind] = counts.get(kind, 0) + 1
        suffix = "" if counts[kind] == 1 else f"_{counts[kind]}"
        tools.append(builder(item["id"], suffix))
    return tools


def compose_instructions(config: dict) -> Optional[str]:
    """把每条辅助模型绑定的小介绍拼进 Agent 的 instructions。"""
    base = (config.get("instructions") or "").strip()
    lines = []
    for binding in config.get("aux_bindings") or []:
        item = find_aux_model(binding.get("id"))
        intro = (binding.get("intro") or "").strip()
        if item is None or not intro:
            continue
        label = AUX_KIND_LABELS.get(item.get("kind") or "", item.get("kind") or "")
        lines.append(f"- {item.get('name')}（{label}）：{intro}")
    if not lines:
        return base or None
    block = "可用辅助模型：\n" + "\n".join(lines)
    return f"{base}\n\n{block}" if base else block


PLACEHOLDER_RE = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")


def fill_placeholders(template: str, values: dict) -> str:
    return PLACEHOLDER_RE.sub(lambda match: str(values.get(match.group(1), match.group(0))), template or "")


def call_http_tool(item: dict, arguments: dict) -> str:
    method = (item.get("method") or "GET").upper()
    url = fill_placeholders(item.get("url") or "", arguments)
    headers = {
        key: fill_placeholders(str(value), arguments) for key, value in (item.get("headers") or {}).items()
    }
    body = fill_placeholders(item.get("body") or "", arguments)
    response = httpx.request(method, url, headers=headers or None, content=body or None, timeout=60)
    response.raise_for_status()
    return response.text[:4000]


def build_library_tool(snapshot: dict) -> Function:
    properties: dict = {}
    required: list[str] = []
    for param in snapshot.get("parameters") or []:
        name = str(param.get("name") or "").strip()
        if not name.isidentifier():
            continue
        schema = {"type": param.get("type") or "string"}
        if param.get("description"):
            schema["description"] = param["description"]
        properties[name] = schema
        if param.get("required", True):
            required.append(name)

    def run_tool(**kwargs) -> str:
        item = find_library_tool(snapshot.get("id")) or snapshot
        try:
            return call_http_tool(item, kwargs)
        except httpx.HTTPStatusError as exc:
            return f"调用失败：HTTP {exc.response.status_code} {exc.response.text[:300]}"
        except Exception as exc:
            return f"调用失败：{type(exc).__name__}: {exc}"

    return Function(
        name=snapshot["name"],
        description=snapshot.get("description") or snapshot.get("label") or snapshot["name"],
        parameters={"type": "object", "properties": properties, "required": required},
        entrypoint=run_tool,
        skip_entrypoint_processing=True,
    )


def build_library_tools(config: dict) -> list:
    """把 Agent 绑定的工具库条目实例化成 Agno Function 工具。"""
    tools: list = []
    for tool_id in config.get("tool_ids") or []:
        item = find_library_tool(tool_id)
        if item is None or not item.get("enabled", True):
            continue
        tools.append(build_library_tool(item))
    return tools


def effective_scopes(subject: str) -> set[str]:
    try:
        roles = authz.roles_of(subject)
    except Exception:
        return set()
    scopes: set[str] = set()
    for role in roles:
        scopes |= role_scopes(authz.get_role(role))
    return scopes


def current_subject(authorization: Optional[str] = Header(None)) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="未登录")
    token = authorization.split(" ", 1)[1]
    try:
        payload = pyjwt.decode(
            token,
            AUTH_SECRET,
            algorithms=["HS256"],
            options={"verify_aud": False},
        )
    except Exception:
        raise HTTPException(status_code=401, detail="令牌无效或已过期")
    subject = payload.get("sub")
    if not subject:
        raise HTTPException(status_code=401, detail="令牌缺少主体")
    return str(subject)


def require_admin(subject: str = Depends(current_subject)) -> str:
    if "agent_os:admin" not in effective_scopes(subject):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    return subject


def apply_component_definition(kind: str, definition: dict):
    if kind == "agent":
        new_component = build_agent(agent_config_from_def(definition), db)
        initialize_component(new_component)
        for index, item in enumerate(agent_os.agents or []):
            if item.id == definition["id"]:
                agent_os.agents[index] = new_component
                return new_component
        agent_os.agents.append(new_component)
        return new_component

    if kind == "team":
        new_component = build_team_from_def(definition, list(agent_os.agents or []))
        initialize_component(new_component)
        for index, item in enumerate(agent_os.teams or []):
            if item.id == definition["id"]:
                agent_os.teams[index] = new_component
                return new_component
        agent_os.teams.append(new_component)
        return new_component

    new_component = build_workflow_from_def(definition, list(agent_os.agents or []))
    initialize_component(new_component)
    for index, item in enumerate(agent_os.workflows or []):
        if item.id == definition["id"]:
            agent_os.workflows[index] = new_component
            return new_component
    agent_os.workflows.append(new_component)
    return new_component


db = SqliteDb(db_file=str(DB_PATH))
agent = build_agent(load_config(), db)

team_agent = build_agent({**load_config(), "agent_id": "team-worker", "name": "team-worker"}, db)
workflow_agent = build_agent({**load_config(), "agent_id": "workflow-worker", "name": "workflow-worker"}, db)

demo_team = Team(
    id="demo-team",
    name="Demo Team",
    description="内置示例团队：一个成员，用于验证团队页面与团队运行接口",
    members=[team_agent],
    db=db,
)

demo_workflow = Workflow(
    id="demo-workflow",
    name="Demo Workflow",
    description="内置示例工作流：单步骤，用于验证工作流页面与运行接口",
    steps=[Step(name="draft", agent=workflow_agent)],
    db=db,
)

_component_store = load_component_store()

_extra_agents: list[Agent] = []
for _definition in _component_store["agents"]:
    try:
        _extra_agents.append(build_agent(agent_config_from_def(_definition), db))
    except Exception:
        pass

_agent_pool = [agent, *_extra_agents]

_extra_teams: list[Team] = []
for _definition in _component_store["teams"]:
    try:
        _extra_teams.append(build_team_from_def(_definition, _agent_pool))
    except Exception:
        pass

_extra_workflows: list[Workflow] = []
for _definition in _component_store["workflows"]:
    try:
        _extra_workflows.append(build_workflow_from_def(_definition, _agent_pool))
    except Exception:
        pass

authz = Authorization(db=db, audit=True, verification_keys=[AUTH_SECRET], algorithm="HS256")
authz.define_role("admin", ["agent_os:admin"], name="管理员", description="完全控制")
authz.define_role(
    "runner",
    ["config:read", "agents:read", "agents:*:run", "sessions:read", "sessions:write"],
    default=True,
    name="运行者",
    description="可查看并运行智能体",
)
authz.define_role(
    "viewer",
    ["config:read", "agents:read", "sessions:read"],
    name="只读",
    description="只读访问",
)
authz.seed(admin=ADMIN_SUBJECT)
ensure_account("admin", ADMIN_SUBJECT, os.getenv("CONSOLE_ADMIN_PASSWORD", "admin123"))

agent_os = AgentOS(
    id=OS_ID,
    name="Agno Console",
    db=db,
    agents=[agent, *_extra_agents],
    teams=[demo_team, *_extra_teams],
    workflows=[demo_workflow, *_extra_workflows],
    user_directory=True,
    authorization=authz,
)

app = FastAPI(title="Agno Console")
api = APIRouter(prefix="/console/api")


class LoginBody(BaseModel):
    username: str
    password: str


class AccountCreateBody(BaseModel):
    username: str
    user_id: str
    password: str


@api.post("/auth/login")
def login(payload: LoginBody) -> dict:
    account = next(
        (item for item in load_accounts() if item.get("username") == payload.username),
        None,
    )
    if account is None or not verify_password(
        payload.password, account.get("salt", ""), account.get("password_hash", "")
    ):
        raise HTTPException(status_code=401, detail="用户名或密码错误")

    token = create_dev_token(
        account["user_id"],
        secret=AUTH_SECRET,
        name=payload.username,
        expires_in=8 * 3600,
    )
    return {
        "token": token,
        "user_id": account["user_id"],
        "username": payload.username,
        "scopes": sorted(effective_scopes(account["user_id"])),
    }


@api.get("/me")
def me(subject: str = Depends(current_subject)) -> dict:
    return {
        "user_id": subject,
        "roles": authz.roles_of(subject),
        "scopes": sorted(effective_scopes(subject)),
    }


@api.get("/providers")
def list_providers(_: str = Depends(current_subject)) -> dict:
    items = []
    for key, spec in PROVIDER_SPECS.items():
        available = True
        error = ""
        try:
            load_provider_class(key)
        except Exception as exc:
            available = False
            error = type(exc).__name__
        items.append(
            {
                "key": key,
                "label": spec["label"],
                "fields": spec["fields"],
                "env": spec.get("env", ""),
                "available": available,
                "error": error,
            }
        )
    return {"data": items}


class AuxModelBody(BaseModel):
    name: str
    kind: Literal["embedding", "rerank", "tts", "stt"]
    protocol: str = "openai-compatible"
    base_url: str = ""
    api_key: Optional[str] = None
    model: str = ""
    extra: dict = {}
    enabled: bool = True


def mask_aux_model(item: dict) -> dict:
    masked = {key: value for key, value in item.items() if key != "api_key"}
    masked["api_key_set"] = bool(item.get("api_key"))
    return masked


def validate_aux_protocol(kind: str, protocol: str) -> None:
    allowed = {option["key"] for option in AUX_PROTOCOLS.get(kind, [])}
    if protocol not in allowed:
        raise HTTPException(status_code=400, detail=f"{kind} 不支持协议：{protocol}")


@api.get("/aux-models")
def list_aux_models(_: str = Depends(current_subject)) -> dict:
    return {"data": [mask_aux_model(item) for item in load_aux_models()], "protocols": AUX_PROTOCOLS}


@api.get("/aux-models/{model_id}")
def get_aux_model(model_id: str, _: str = Depends(current_subject)) -> dict:
    item = next((entry for entry in load_aux_models() if entry.get("id") == model_id), None)
    if item is None:
        raise HTTPException(status_code=404, detail="辅助模型不存在")
    return mask_aux_model(item)


@api.post("/aux-models")
def create_aux_model(payload: AuxModelBody, _: str = Depends(require_admin)) -> dict:
    validate_aux_protocol(payload.kind, payload.protocol)
    if not payload.name.strip():
        raise HTTPException(status_code=400, detail="名称不能为空")
    items = load_aux_models()
    item = payload.model_dump()
    item["id"] = uuid.uuid4().hex[:12]
    item["created_at"] = datetime.now(timezone.utc).isoformat()
    items.append(item)
    save_aux_models(items)
    return {"ok": True, "id": item["id"]}


@api.put("/aux-models/{model_id}")
def update_aux_model(model_id: str, payload: AuxModelBody, _: str = Depends(require_admin)) -> dict:
    validate_aux_protocol(payload.kind, payload.protocol)
    items = load_aux_models()
    for index, entry in enumerate(items):
        if entry.get("id") != model_id:
            continue
        updated = payload.model_dump()
        updated["id"] = model_id
        updated["created_at"] = entry.get("created_at")
        if payload.api_key is None:
            updated["api_key"] = entry.get("api_key")
        items[index] = updated
        save_aux_models(items)
        return {"ok": True, "id": model_id}
    raise HTTPException(status_code=404, detail="辅助模型不存在")


@api.delete("/aux-models/{model_id}")
def delete_aux_model(model_id: str, _: str = Depends(require_admin)) -> dict:
    items = load_aux_models()
    remaining = [entry for entry in items if entry.get("id") != model_id]
    if len(remaining) == len(items):
        raise HTTPException(status_code=404, detail="辅助模型不存在")
    save_aux_models(remaining)
    return {"ok": True}


@api.post("/aux-models/{model_id}/test")
def test_aux_model_endpoint(model_id: str, _: str = Depends(require_admin)) -> dict:
    item = next((entry for entry in load_aux_models() if entry.get("id") == model_id), None)
    if item is None:
        raise HTTPException(status_code=404, detail="辅助模型不存在")
    return test_aux_model(item)


class ToolParamBody(BaseModel):
    name: str
    type: Literal["string", "integer", "number", "boolean"] = "string"
    description: str = ""
    required: bool = True


class ToolBody(BaseModel):
    name: str
    label: str = ""
    description: str = ""
    method: Literal["GET", "POST", "PUT", "DELETE", "PATCH"] = "GET"
    url: str = ""
    headers: dict = {}
    body: str = ""
    parameters: list[ToolParamBody] = []
    enabled: bool = True


class ToolTestBody(BaseModel):
    arguments: dict = {}


def validate_tool_body(payload: ToolBody) -> None:
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", payload.name or ""):
        raise HTTPException(status_code=400, detail="工具名必须是合法函数名：字母或下划线开头，仅含字母、数字、下划线")
    if not payload.url.strip():
        raise HTTPException(status_code=400, detail="URL 不能为空")
    for param in payload.parameters:
        if not param.name.isidentifier():
            raise HTTPException(status_code=400, detail=f"参数名不合法：{param.name}")


@api.get("/tool-library")
def list_tool_library(_: str = Depends(require_admin)) -> dict:
    return {"data": load_library_tools()}


@api.post("/tool-library")
def create_tool_library_item(payload: ToolBody, _: str = Depends(require_admin)) -> dict:
    validate_tool_body(payload)
    items = load_library_tools()
    if any(item.get("name") == payload.name for item in items):
        raise HTTPException(status_code=409, detail="工具名已存在")
    item = payload.model_dump()
    item["id"] = uuid.uuid4().hex[:12]
    item["created_at"] = datetime.now(timezone.utc).isoformat()
    items.append(item)
    save_library_tools(items)
    return {"ok": True, "id": item["id"]}


@api.put("/tool-library/{tool_id}")
def update_tool_library_item(tool_id: str, payload: ToolBody, _: str = Depends(require_admin)) -> dict:
    validate_tool_body(payload)
    items = load_library_tools()
    for index, entry in enumerate(items):
        if entry.get("id") != tool_id:
            continue
        if any(item.get("name") == payload.name and item.get("id") != tool_id for item in items):
            raise HTTPException(status_code=409, detail="工具名已存在")
        updated = payload.model_dump()
        updated["id"] = tool_id
        updated["created_at"] = entry.get("created_at")
        items[index] = updated
        save_library_tools(items)
        return {"ok": True, "id": tool_id}
    raise HTTPException(status_code=404, detail="工具不存在")


@api.delete("/tool-library/{tool_id}")
def delete_tool_library_item(tool_id: str, _: str = Depends(require_admin)) -> dict:
    items = load_library_tools()
    remaining = [item for item in items if item.get("id") != tool_id]
    if len(remaining) == len(items):
        raise HTTPException(status_code=404, detail="工具不存在")
    save_library_tools(remaining)
    return {"ok": True}


@api.post("/tool-library/{tool_id}/test")
def test_tool_library_item(tool_id: str, payload: ToolTestBody, _: str = Depends(require_admin)) -> dict:
    item = find_library_tool(tool_id)
    if item is None:
        raise HTTPException(status_code=404, detail="工具不存在")
    try:
        output = call_http_tool(item, payload.arguments)
    except httpx.HTTPStatusError as exc:
        return {"ok": False, "status": exc.response.status_code, "message": exc.response.text[:300]}
    except Exception as exc:
        return {"ok": False, "message": f"{type(exc).__name__}: {exc}"}
    return {"ok": True, "output": output}


@api.get("/accounts")
def list_accounts(_: str = Depends(require_admin)) -> dict:
    return {"data": [{"username": item["username"], "user_id": item["user_id"]} for item in load_accounts()]}


@api.post("/accounts")
def create_account(payload: AccountCreateBody, _: str = Depends(require_admin)) -> dict:
    if not payload.username.strip() or not payload.password:
        raise HTTPException(status_code=400, detail="用户名和密码不能为空")
    accounts = load_accounts()
    if any(item.get("username") == payload.username for item in accounts):
        raise HTTPException(status_code=409, detail="用户名已存在")
    salt, digest = hash_password(payload.password)
    accounts.append(
        {
            "username": payload.username,
            "user_id": payload.user_id,
            "salt": salt,
            "password_hash": digest,
        }
    )
    save_accounts(accounts)
    return {"ok": True}


@api.get("/config")
def read_config(_: str = Depends(current_subject)) -> dict:
    config = load_config()
    return {
        **config,
        "api_key": None,
        "api_key_set": bool(config.get("api_key") or os.getenv("OPENAI_API_KEY")),
    }


@api.put("/config")
def update_config(payload: AgentConfig, _: str = Depends(require_admin)) -> dict:
    current = load_config()
    data = payload.model_dump()

    if data.get("api_key") is None:
        data["api_key"] = current.get("api_key", "")
    if data["provider"] == "openai-compatible" and not data.get("base_url"):
        raise HTTPException(status_code=400, detail="openai-compatible 模式必须填写 base_url")
    if not data.get("agent_id"):
        raise HTTPException(status_code=400, detail="agent_id 不能为空")

    save_config(data)

    new_agent = build_agent(data, db)
    new_agent.initialize_agent()

    replaced = False
    for index, existing in enumerate(agent_os.agents):
        if getattr(existing, "id", None) == data["agent_id"]:
            agent_os.agents[index] = new_agent
            replaced = True
            break
    if not replaced:
        agent_os.agents.append(new_agent)

    return {"ok": True, "agent": agent_summary(new_agent)}


@api.get("/status")
def read_status(_: str = Depends(current_subject)) -> dict:
    return {
        "status": "ok",
        "os_name": agent_os.name,
        "agno_version": agno.__version__,
        "db": DB_PATH.name,
        "auth_mode": "jwt+rbac",
        "agent": agent_summary(agent_os.agents[0]),
    }


class AuxBinding(BaseModel):
    id: str
    intro: str = ""


class ComponentCreateBody(BaseModel):
    kind: Literal["agent", "team", "workflow"]
    id: str
    name: Optional[str] = None
    description: str = ""
    provider: str = "openai"
    model_id: str = "gpt-4o-mini"
    base_url: str = ""
    host: str = ""
    api_key: Optional[str] = None
    temperature: float = 0.7
    instructions: str = ""
    member_ids: list[str] = []
    step_ids: list[str] = []
    aux_bindings: list[AuxBinding] = []
    tool_ids: list[str] = []


@api.get("/components")
def list_components(_: str = Depends(current_subject)) -> dict:
    return {
        "agents": [agent_summary(item) for item in (agent_os.agents or [])],
        "teams": [
            {
                "id": item.id,
                "name": item.name,
                "description": getattr(item, "description", ""),
                "member_ids": [member.id for member in (getattr(item, "members", None) or [])],
            }
            for item in (agent_os.teams or [])
        ],
        "workflows": [
            {
                "id": item.id,
                "name": item.name,
                "description": getattr(item, "description", ""),
                "step_count": len(getattr(item, "steps", None) or []),
            }
            for item in (agent_os.workflows or [])
        ],
    }


@api.post("/components")
def create_component(payload: ComponentCreateBody, _: str = Depends(require_admin)) -> dict:
    existing = {
        getattr(item, "id", None)
        for item in [*(agent_os.agents or []), *(agent_os.teams or []), *(agent_os.workflows or [])]
    }
    if payload.id in existing:
        raise HTTPException(status_code=409, detail=f"组件 ID 已存在：{payload.id}")

    definition = payload.model_dump()

    if payload.kind == "agent":
        component = build_agent(agent_config_from_def(definition), db)
        initialize_component(component)
        agent_os.agents.append(component)
    elif payload.kind == "team":
        component = build_team_from_def(definition, list(agent_os.agents or []))
        initialize_component(component)
        agent_os.teams.append(component)
    else:
        component = build_workflow_from_def(definition, list(agent_os.agents or []))
        initialize_component(component)
        agent_os.workflows.append(component)

    store = load_component_store()
    store[f"{payload.kind}s"].append(definition)
    record_component_version(store, payload.kind, definition)
    save_component_store(store)

    return {"ok": True, "kind": payload.kind, "id": payload.id}


@api.get("/components/{kind}/{component_id}")
def get_component(kind: str, component_id: str, _: str = Depends(current_subject)) -> dict:
    store = load_component_store()
    for definition in store.get(f"{kind}s", []):
        if definition.get("id") == component_id:
            return definition
    raise HTTPException(status_code=404, detail="组件不存在")


@api.put("/components/{kind}/{component_id}")
def update_component(kind: str, component_id: str, payload: ComponentCreateBody, _: str = Depends(require_admin)) -> dict:
    if payload.kind != kind or payload.id != component_id:
        raise HTTPException(status_code=400, detail="路径与请求体中的类型或 ID 不一致")

    definition = payload.model_dump()
    apply_component_definition(kind, definition)

    store = load_component_store()
    collection = store.get(f"{kind}s", [])
    for index, item in enumerate(collection):
        if item.get("id") == component_id:
            collection[index] = definition
            break
    else:
        raise HTTPException(status_code=404, detail="组件不存在")

    version = record_component_version(store, kind, definition)
    save_component_store(store)
    return {"ok": True, "kind": kind, "id": component_id, "version": version}


@api.get("/components/{kind}/{component_id}/versions")
def list_component_versions(kind: str, component_id: str, _: str = Depends(current_subject)) -> dict:
    store = load_component_store()
    key = component_key(kind, component_id)
    return {
        "kind": kind,
        "id": component_id,
        "current": store.get("current", {}).get(key),
        "versions": store.get("history", {}).get(key, []),
    }


@api.post("/components/{kind}/{component_id}/versions/{version}/publish")
def publish_component_version(kind: str, component_id: str, version: int, _: str = Depends(require_admin)) -> dict:
    store = load_component_store()
    key = component_key(kind, component_id)
    history = store.get("history", {}).get(key, [])
    entry = next((item for item in history if item.get("version") == version), None)
    if entry is None:
        raise HTTPException(status_code=404, detail="版本不存在")

    definition = entry["definition"]
    apply_component_definition(kind, definition)

    collection = store.get(f"{kind}s", [])
    for index, item in enumerate(collection):
        if item.get("id") == component_id:
            collection[index] = definition
            break

    store.setdefault("current", {})[key] = version
    save_component_store(store)
    return {"ok": True, "version": version}


@api.delete("/components/{kind}/{component_id}")
def delete_component(kind: str, component_id: str, _: str = Depends(require_admin)) -> dict:
    if kind == "agent" and component_id == (agent_os.agents[0].id if agent_os.agents else ""):
        raise HTTPException(status_code=400, detail="主 Agent 不能删除")

    target = {
        "agent": agent_os.agents,
        "team": agent_os.teams,
        "workflow": agent_os.workflows,
    }.get(kind)
    if target is None:
        raise HTTPException(status_code=400, detail="kind 必须是 agent / team / workflow")

    for index, item in enumerate(target):
        if getattr(item, "id", None) == component_id:
            target.pop(index)
            break
    else:
        raise HTTPException(status_code=404, detail="组件不存在")

    store = load_component_store()
    store[f"{kind}s"] = [item for item in store.get(f"{kind}s", []) if item.get("id") != component_id]
    key = component_key(kind, component_id)
    store.get("history", {}).pop(key, None)
    store.get("current", {}).pop(key, None)
    save_component_store(store)

    return {"ok": True}


app.include_router(api)
app.mount("/os", agent_os.get_app())

if DIST_DIR.exists():
    app.mount("/console", StaticFiles(directory=str(DIST_DIR), html=True))
else:

    @app.get("/console")
    def console_not_built() -> JSONResponse:
        return JSONResponse(
            status_code=503,
            content={"detail": "前端尚未构建，请先在 console-web 目录执行 npm install && npm run build"},
        )


@app.get("/")
def index() -> RedirectResponse:
    return RedirectResponse(url="/console/")


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=7788)
