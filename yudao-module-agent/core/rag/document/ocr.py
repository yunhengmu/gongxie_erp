"""OCR 文字识别模块。

支持:
  - PaddleOCR（推荐，中文识别精度高，离线可用）
  - Tesseract（需安装系统级 tesseract-ocr）
  - EasyOCR（备选，支持 80+ 语言）

当前为骨架实现，实际 OCR 引擎按需安装。
"""
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


def ocr_image(
    image_path: str,
    engine: str = "auto",
    lang: str = "ch",
) -> str:
    """对图片执行 OCR 文字识别。

    自动检测可用的 OCR 引擎，优先级: PaddleOCR > Tesseract > EasyOCR。

    Args:
        image_path: 图片文件路径
        engine: OCR 引擎，可选 "paddle"/"tesseract"/"easyocr"/"auto"
        lang: 识别语言，"ch" 中文，"en" 英文

    Returns:
        识别出的文字内容

    Raises:
        ImportError: 无可用 OCR 引擎
    """
    if engine == "auto":
        engines = [_ocr_paddle, _ocr_tesseract, _ocr_easyocr]
    elif engine == "paddle":
        engines = [_ocr_paddle]
    elif engine == "tesseract":
        engines = [_ocr_tesseract]
    elif engine == "easyocr":
        engines = [_ocr_easyocr]
    else:
        raise ValueError(f"不支持的 OCR 引擎: {engine}")

    for fn in engines:
        try:
            text = fn(image_path, lang)
            if text:
                return text
        except ImportError:
            continue
        except Exception as e:
            logger.warning("%s 失败: %s", fn.__name__, e)
            continue

    raise ImportError(
        "没有可用的 OCR 引擎。请安装以下之一:\n"
        "  pip install paddleocr paddlepaddle     # 推荐，中文精度高\n"
        "  pip install pytesseract                 # 需安装 tesseract-ocr\n"
        "  pip install easyocr                     # 支持多语言"
    )


def _ocr_paddle(image_path: str, lang: str = "ch") -> str:
    """PaddleOCR 识别（推荐）。"""
    from paddleocr import PaddleOCR

    ocr = PaddleOCR(use_angle_cls=True, lang=lang, show_log=False)
    result = ocr.ocr(image_path, cls=True)

    if not result or not result[0]:
        return ""

    lines = []
    for line in result[0]:
        text = line[1][0] if len(line) > 1 else ""
        if text:
            lines.append(text)
    return "\n".join(lines)


def _ocr_tesseract(image_path: str, lang: str = "chi_sim") -> str:
    """Tesseract OCR 识别。"""
    import pytesseract
    from PIL import Image

    img = Image.open(image_path)
    text = pytesseract.image_to_string(img, lang=lang)
    return text.strip()


def _ocr_easyocr(image_path: str, lang: str = "ch") -> str:
    """EasyOCR 识别。"""
    import easyocr

    reader = easyocr.Reader(["ch_sim", "en"] if lang == "ch" else ["en"])
    result = reader.readtext(image_path)

    lines = [text for _, text, _ in result]
    return "\n".join(lines)


def ocr_images_batch(
    image_paths: list[str],
    engine: str = "auto",
    lang: str = "ch",
) -> dict[str, str]:
    """批量 OCR 识别。

    Args:
        image_paths: 图片文件路径列表
        engine: OCR 引擎
        lang: 识别语言

    Returns:
        {文件路径: 识别文字} 字典
    """
    results = {}
    for path in image_paths:
        try:
            text = ocr_image(path, engine, lang)
            results[path] = text
        except Exception as e:
            logger.warning("OCR 失败 %s: %s", path, e)
            results[path] = ""
    return results