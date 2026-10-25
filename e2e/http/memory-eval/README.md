# 长期记忆抽取评测（T10）

用于验证平台托管抽取的提示词与输入构造是否达标，不启动 Java 服务、不依赖 npm 包。

## 目录

| 文件 | 说明 |
|---|---|
| `cases.jsonl` | 离线标注用例：消息、只读上下文起点、必记事实、禁记事实、needs_context 期望 |
| `run.mjs` | 读取 Java 中的 `PLATFORM_EXTRACT_PROMPT`，调用 OpenAI 兼容模型并计算指标 |
| `README.md` | 本说明 |

> 长期记忆已固定为平台托管，原 `shadow` 模式及其流量/门禁脚本已移除。
> 运行期抽取质量由 API e2e L16~L19 与记忆页「运行健康」覆盖。

## 用法

```bash
# 只打印每条用例的抽取输入，不调模型（CI 可用）
node e2e/http/memory-eval/run.mjs --dry-run

# 真实评测（以 DashScope 兼容模式为例）
BIZBUDDY_LLM_API_KEY=sk-xxx node e2e/http/memory-eval/run.mjs \
  --base-url https://dashscope.aliyuncs.com/compatible-mode/v1 \
  --model qwen-plus --gate

# 输出 Markdown 报告
node e2e/http/memory-eval/run.mjs ... --out memory-eval-report.md
```

参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--cases` | `cases.jsonl` | 用例文件 |
| `--prompt` | Java `AiMemoryPrompts.java` | 默认从 Java 常量读取，避免评测与线上提示词漂移 |
| `--base-url` | `BIZBUDDY_LLM_BASE_URL` | OpenAI 兼容根地址 |
| `--model` | `BIZBUDDY_LLM_MODEL` | 模型标识 |
| `--api-key` | `BIZBUDDY_LLM_API_KEY` | 密钥；`--dry-run` 时不需要 |
| `--out` | 无 | 输出 Markdown 报告路径 |
| `--gate` | 关闭 | 按门槛判定：必记召回 ≥90%、污染 ≤2%、needs_context 缺失 0 |

## 用例格式

```json
{
  "id": "coreference-table",
  "slice": "coreference",
  "pendingFrom": 1,
  "messages": [
    {"role": "assistant", "content": "建议用表格展示统计结果"},
    {"role": "user", "content": "好的，以后都这样"}
  ],
  "mustRemember": ["表格"],
  "mustNotRemember": []
}
```

- `pendingFrom`：从第几条消息开始算“待抽取消息”，之前的消息只作为只读上下文；
- `mustRemember`：输出中必须出现的稳定事实关键词；
- `mustNotRemember`：不得出现的项目 / 工具 / 一次性任务 / PII 关键词；
- `expectNeedsContext`：期望输出 `needs_context` 或空内容。

## 指标与门槛

- 必记召回：`mustHit / mustTotal`，门槛 ≥90%；
- 污染率：`pollution / mustNotTotal`，门槛 ≤2%；
- 指代 / 纠正分桶需单独看，门槛分别 ≥85% / ≥90%；
- `needs_context` 用例不得输出猜测性事实。
