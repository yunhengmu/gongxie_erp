"""文件工具 — 保存上传文件到指定目录并返回路径。"""
import os
import uuid
import logging
from pathlib import Path

logger = logging.getLogger(__name__)


def save_file(
    file_bytes: bytes,
    filename: str,
    save_dir: str,
    keep_original_name: bool = False,
) -> str:
    """保存文件到指定目录，返回完整路径。

    Args:
        file_bytes: 文件字节内容
        filename: 原始文件名（用于提取扩展名）
        save_dir: 保存目录
        keep_original_name: True 保留原始文件名，False 用 UUID 重命名

    Returns:
        保存后的完整文件路径

    Raises:
        OSError: 目录创建失败或写入失败
    """
    os.makedirs(save_dir, exist_ok=True)

    ext = Path(filename).suffix
    safe_name = filename if keep_original_name else f"{uuid.uuid4().hex}{ext}"
    file_path = os.path.join(save_dir, safe_name)

    with open(file_path, "wb") as f:
        f.write(file_bytes)

    logger.info("文件已保存: %s (%d bytes)", file_path, len(file_bytes))
    return file_path


def delete_file(file_path: str) -> bool:
    """删除文件，不存在时不报错。

    Returns:
        True 删除成功，False 文件不存在
    """
    try:
        os.remove(file_path)
        logger.info("文件已删除: %s", file_path)
        return True
    except FileNotFoundError:
        return False