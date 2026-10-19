import json
import re

from mellea.core import Context, ValidationResult
from mellea.stdlib.requirements import simple_validate

# ── 类型1：simple_validate —— 只看输出字符串的确定性校验 ──
# 返回 bool 或 (bool, 失败原因)；失败原因会作为"修复指令"发回模型

validate_order_id_format = simple_validate(
    lambda x: (
        bool(re.fullmatch(r"ORD-\d{4}-\d{3,6}", x.strip())),
        f"'{x.strip()}' 不是合法订单号，格式必须为 ORD-YYYY-NNNNN。",
    )
)

validate_no_placeholder = simple_validate(
    lambda x: ("TODO" not in x and "..." not in x, "回复中残留 TODO/省略号占位符，请给出完整内容。")
)

# ── 类型2：完整校验函数 —— 需要复杂逻辑/报错细节时 ──


def validate_json_schema(ctx: Context) -> ValidationResult:
    """校验输出是合法 JSON，失败时把解析错误喂回模型修复。"""
    text = ctx.last_output().value if ctx.last_output() else ""
    try:
        json.loads(text)
        return ValidationResult(True)
    except json.JSONDecodeError as exc:
        return ValidationResult(
            False,
            reason=f"输出不是合法 JSON（位置 {exc.pos}: {exc.msg}），"
            "请只输出 JSON，不要附加任何说明文字。",
        )


# ── 类型3：LLM-as-judge —— 主观约束，用自然语言描述（无需写代码）──
# 在 instruct() 的 requirements 里直接写字符串或 check("...")
# 注意：小模型当裁判不可靠，只用于"语气礼貌"这类无法编码的软约束
