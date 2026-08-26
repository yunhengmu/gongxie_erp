"""Prompt 模板加载器。

类比 Spring 的 MessageSource:
  - 从 classpath 读取资源文件(templates/*.md)
  - 缓存已加载的模板,避免重复 IO
"""
from functools import lru_cache
from pathlib import Path

# 模板根目录: core/prompts/templates/
TEMPLATES_DIR = Path(__file__).parent / "templates"


@lru_cache
def load_template(name: str) -> str:
    """按名称加载模板(不带扩展名)。

    Args:
        name: 模板名称,如 "research" → templates/research.md

    Returns:
        模板原始文本。

    Raises:
        FileNotFoundError: 模板文件不存在。
    """
    file = TEMPLATES_DIR / f"{name}.md"
    if not file.exists():
        raise FileNotFoundError(f"Prompt template not found: {file}")
    return file.read_text(encoding="utf-8")
