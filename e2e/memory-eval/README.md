# 长期记忆抽取评测（T10 / T15）

用于验证平台托管抽取的提示词与输入构造是否达标，不启动 Java 服务、不依赖 npm 包。

## 目录

| 文件 | 说明 |
|---|---|
| `cases.jsonl` | 离线标注用例：消息、只读上下文起点、必记事实、禁记事实、needs_context 期望 |
| `traffic-cases.jsonl` | 真实链路标注用例：**只含用户消息**（助手回复由模型真实生成），用于影子流量 |
| `run.mjs` | 读取 Java 中的 `PLATFORM_EXTRACT_PROMPT`，调用 OpenAI 兼容模型并计算指标 |
| `shadow-traffic.mjs` | 把真实链路用例跑成影子流量：建会话 → 发消息 → 轮末抽取留样，输出 labels |
| `shadow-gate.mjs` | 用影子样本 + labels 计算召回 / 污染 / PII / needs_context 门禁 |
| `README.md` | 本说明 |

## 用法

```bash
# 只打印每条用例的抽取输入，不调模型（CI 可用）
node e2e/memory-eval/run.mjs --dry-run

# 真实评测（以 DashScope 兼容模式为例）
BIZBUDDY_LLM_API_KEY=sk-xxx node e2e/memory-eval/run.mjs \
  --base-url https://dashscope.aliyuncs.com/compatible-mode/v1 \
  --model qwen-plus --gate

# 输出 Markdown 报告
node e2e/memory-eval/run.mjs ... --out memory-eval-report.md
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

## 真实流量影子门禁

`shadow-traffic.mjs` 跑流量、`shadow-gate.mjs` 判门禁（与 `run.mjs` 的离线提示词评测互补）：

```bash
# 1) 先快照「跑流量之前」的 MEMORY.md（门禁用它判定已知事实）
docker cp bizbuddy-admin:/ruoyi/server/data/agent-workspace/<entryCode>/<userId>/MEMORY.md memory-before.md

# 2) 跑标注流量（自动切 shadow、抽样率 1.0、每轮抽取；跑完恢复原配置并关闭会话）
node e2e/memory-eval/shadow-traffic.mjs --labels-out labels.jsonl [--limit 10] [--only id1,id2]

# 3) 导出影子样本
docker cp bizbuddy-admin:/ruoyi/server/data/agent-workspace/<entryCode>/<userId>/memory/.extract_shadow/YYYY-MM-DD.jsonl samples.jsonl

# 4) 判门禁（退出码 0 通过 / 1 未通过）
node e2e/memory-eval/shadow-gate.mjs \
  --samples samples.jsonl --labels labels.jsonl --memory memory-before.md --gate

# 5) 还原 MEMORY.md 并清理影子样本，避免测试偏好留在开发账号
docker cp memory-before.md bizbuddy-admin:/ruoyi/server/data/agent-workspace/<entryCode>/<userId>/MEMORY.md
```

指标：必记召回 ≥90%、污染 ≤2%、PII 出境 0、needs_context 缺失 0。
`--memory` 传**跑流量之前**的 `MEMORY.md`：平台会按「已有记忆」去重，已记住的事实不算召回缺失；
传跑完之后的文件会把框架合并进来的内容也算作已知，掩盖平台抽取的漏记。

最近一次：`traffic-cases.jsonl` 37 个会话，必记召回 23/23、污染 0/14、PII 0、needs_context 0/0 → PASS。

## 与真实流量的关系

`run.mjs` 是**离线提示词评测**。真实流量的影子样本由平台在 `shadow` 模式写入：

```text
memory/.extract_shadow/YYYY-MM-DD.jsonl
```

每条包含会话、消息范围、保留/丢弃计数与抽取正文，用于人工复核与阈值校准。影子模式只留样，不写正式流水，也不影响框架抽取。
