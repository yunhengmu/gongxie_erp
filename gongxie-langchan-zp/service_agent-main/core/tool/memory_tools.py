"""长期记忆工具 — 跨会话持久化用户信息。

工具列表:
  - save_user_info:   保存用户信息到长期记忆
  - get_user_info:    读取当前用户的长期记忆
  - forget_user_info: 删除用户信息（用户要求"忘记"时使用）
  - list_user_memories: 列出所有已存储的记忆条目

使用 LangGraph 的 InjectedStore 机制，Agent 调用时自动注入 Store 实例。
"""
from langgraph.prebuilt import InjectedStore
from langchain.tools import tool


@tool
def save_user_info(
    key: str,
    info: str,
    store: InjectedStore,
) -> str:
    """将用户信息保存到长期记忆（跨会话保留）。

    Args:
        key: 记忆的键名，如 "name", "preferences", "tech_stack", "role"
        info: 要保存的用户信息详情
    """
    store.put(("users",), key, {"info": info})
    return f"已记住: {key} = {info}"


@tool
def get_user_info(
    key: str,
    store: InjectedStore,
) -> str:
    """从长期记忆中读取用户信息。

    Args:
        key: 记忆的键名，如 "name", "preferences", "tech_stack"
    """
    item = store.get(("users",), key)
    if item is None:
        return f"未找到关于 {key} 的记忆。"
    return str(item.value.get("info", ""))


@tool
def forget_user_info(
    key: str,
    store: InjectedStore,
) -> str:
    """删除用户要求遗忘的长期记忆。

    Args:
        key: 要遗忘的记忆键名，如 "name", "preferences"
    """
    store.delete(("users",), key)
    return f"已遗忘: {key}"


@tool
def list_user_memories(
    store: InjectedStore,
) -> str:
    """列出当前用户所有的长期记忆条目。"""
    items = store.search(("users",))
    if not items:
        return "暂无任何长期记忆。"

    lines = []
    for item in items:
        lines.append(f"- {item.key}: {item.value.get('info', '')}")
    return "\n".join(lines) if lines else "暂无任何长期记忆。"


# 工具列表，供 agent.py 导入
memory_tools = [save_user_info, get_user_info, forget_user_info, list_user_memories]