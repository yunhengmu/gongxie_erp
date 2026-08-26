# Vercel AI SDK 开发文档（结合本仓库代码）

> 适用项目：`apps/web-antd`（yudao-admin 前端 / Vben Admin 5.7 / Vue 3 + Ant Design Vue）
> 本文档**基于本仓库已安装的依赖与本地源码**编写，所有「本地代码」章节均标注了实际文件路径，可直接跳转查看。

---

## 0. 本地代码现状（先看这里）

### 0.1 依赖已装但未使用

`apps/web-antd/package.json` 中已经安装了 AI SDK 相关依赖（`dependencies`）：

| 包 | 版本（由 `pnpm-lock.yaml` 锁定） | 用途 |
| --- | --- | --- |
| `ai` | **7.0.76** | AI SDK 核心包（**服务端**运行时：`generateText`/`streamText`/`tool`/`Output` 等） |
| `@ai-sdk/vue` | **4.0.76** | Vue 3 集成（前端 composable：`useChat`/`useCompletion`/`useObject`） |
| `zod` | **3.25.76**（`catalog:` 仓库统一版本） | 结构化输出 / 工具入参的 schema 校验 |

锁定版本链（见 `pnpm-lock.yaml`）：`ai@7.0.76` 内部依赖 `@ai-sdk/gateway@4.0.61`、`@ai-sdk/provider@4.0.7`、`@ai-sdk/provider-utils@5.0.28`；`@ai-sdk/vue@4.0.76(vue@3.5.39)(zod@3.25.76)`。

> 结论：**`ai` / `@ai-sdk/vue` 已经装好，但 `apps/web-antd/src` 下还没有任何业务代码 import 它们**。当前 AI 聊天功能走的是下面这套「手写 SSE 直连 yudao 后端」的旧方案。

### 0.2 当前 AI 聊天是怎么实现的（对照基准）

本地现有的 AI 聊天页面没有用 AI SDK，而是**手写 SSE + fetch**：

- 页面：[apps/web-antd/src/views/ai/chat/index/index.vue](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/src/views/ai/chat/index/index.vue)
  - `doSendMessageStream()`（约 L331）：创建 `AbortController`，先 push 两条「假消息」（用户消息 + `思考中...` 占位），然后发流，收到首个 chunk 后弹出假数据、换成 `data.send`/`data.receive` 真实消息，再逐 chunk 累加 `reasoningContent`（深度思考）和 `content`，每次都要手动 `scrollToBottom()`。
  - `stopStream()`（约 L435）：手动 `controller.abort()` 停止流。
- 请求封装：[apps/web-antd/src/api/ai/chat/message/index.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/src/api/ai/chat/message/index.ts)
  - `sendChatMessageStream()`（约 L59）：用 `@vben/request` 导出的 `fetchEventSource` POST 到 `${apiURL}/ai/chat/message/send-stream`，手动塞 `Authorization: Bearer <token>` 头，回调里 `JSON.parse(res.data)` 解析每个 chunk，判断 `code !== 0` 等。

**这段代码的问题**：消息结构、占位、流式累加、推理内容、中止、错误处理全都要自己写；消息模型也是 yudao 后端自定义的 `ChatMessage`。而 `useChat` 把这些都封装好了，还自带 `UIMessage` 协议、`status` 状态机、`stop()`、`regenerate()`。

> 这份文档的目的：介绍 **Vercel AI SDK** 的完整用法，并给出**迁移到 useChat 的路径**，让你从「手写 SSE」升级到官方封装的流式对话。

---

## 1. 架构与核心概念

```
┌─────────────┐  fetch(UIMessageStream)  ┌─────────────────┐  直连    ┌─────────────┐
│  Vue 前端     │ ─────────────────────▶ │  Node 后端代理     │ ──────▶ │  模型厂商     │
│ @ai-sdk/vue  │ ◀───────────────────── │  ai 核心包         │ ◀────── │ OpenAI/...  │
│ useChat 等    │       SSE 流            │  streamText       │         └─────────────┘
└─────────────┘                          └─────────────────┘
```

**分层职责（务必分清，这是 AI SDK 最容易被用错的地方）**：

| 层 | 包 | 运行环境 | 职责 |
| --- | --- | --- | --- |
| 前端 | `@ai-sdk/vue` | **浏览器** | `useChat`/`useCompletion`/`useObject`，管理消息状态、发请求、解析流 |
| 服务端 | `ai` | **Node 服务端** | `generateText`/`streamText`/`tool`，持有 API Key，直连模型 |

> **安全红线**：`ai` 核心包（`generateText`/`streamText`）**只能在 Node 服务端运行，绝不能 import 进前端页面**——否则 API Key 会打进浏览器 bundle 泄露。前端只通过 `@ai-sdk/vue` 的 composable 请求「自己的后端接口」。

### 1.1 大版本差异（v7 / @ai-sdk/vue@4 与旧版对比）

`ai@7` 是全新大版本，核心变化：

1. **Gateway 优先**：默认走 Vercel AI Gateway，`model` 直接传 `'厂商/模型'` 字符串即可，无需 provider 包。
2. **`generateObject`/`streamObject` 已弃用**：结构化输出改用 `generateText`/`streamText` 的 `output` 选项（`Output.object()` 等）。
3. **统一消息模型 `UIMessage`**：消息体从旧的 `{role, content}` 升级为 `{role, parts[]}`，可以同时承载文本/推理/工具/文件等多种片段。
4. **`useChat` 重写**：移除 `input`/`handleSubmit`/`isLoading`，改为 `sendMessage` + `status`。

---

## 2. 模型接入方式

### 2.1 方式 A：Vercel AI Gateway（开箱即用，零安装）

`ai` 内置 `gateway` provider（由 `@ai-sdk/gateway@4.0.61` 提供并重新导出），模型 ID 用 `厂商/模型` 格式：

```ts
import { generateText, gateway } from 'ai';

const { text } = await generateText({
  model: gateway('openai/gpt-4o'), // 或直接传字符串 'openai/gpt-4o'
  prompt: '你好',
});
```

配置（服务端环境变量）：

```bash
AI_GATEWAY_API_KEY=xxx          # Vercel AI Gateway API Key / Vercel access token
# AI_GATEWAY_BASE_URL=...       # 默认 https://ai-gateway.vercel.sh/v4/ai
# VERCEL_TEAM_ID=...            # 多团队时按需配置
```

`gateway` 还提供多模态入口：`gateway.embedding(...)`、`gateway.image(...)`、`gateway.video(...)`、`gateway.speech(...)`、`gateway.transcription(...)` 等。

### 2.2 方式 B：官方 provider 包直连（推荐用于国内厂商/自建网关）

```bash
pnpm add @ai-sdk/openai              # OpenAI / 兼容协议
pnpm add @ai-sdk/deepseek            # DeepSeek
pnpm add @ai-sdk/openai-compatible   # 任意 OpenAI 兼容接口（通义/月之暗面/豆包/自建 vLLM 等）
pnpm add @ai-sdk/anthropic @ai-sdk/google
```

```ts
import { createOpenAI } from '@ai-sdk/openai';

const openai = createOpenAI({
  apiKey: process.env.OPENAI_API_KEY,
  baseURL: process.env.OPENAI_BASE_URL, // 可指向兼容网关 / 国内中转
});

const { text } = await generateText({
  model: openai('gpt-4o'),
  prompt: '你好',
});
```

---

## 3. 服务端核心 API（`import ... from 'ai'`）

> 以下 API **仅在 Node 运行时可用**（服务端代理 / Serverless / Vite 中间件）。

### 3.1 `generateText` — 一次性生成

```ts
import { generateText } from 'ai';

const result = await generateText({
  model: 'openai/gpt-4o',
  system: '你是资深 Java 架构师，回答要简洁。',
  prompt: '什么是 DDD？',
  // 或 messages: [{ role: 'user', content: '...' }]
  temperature: 0.7,
  maxOutputTokens: 1024,
});

console.log(result.text);         // 生成的文本
console.log(result.usage);        // { promptTokens, completionTokens, totalTokens }
console.log(result.finishReason); // 'stop' | 'length' | 'tool-calls' | ...
console.log(result.reasoning);    // 推理内容（o1/deepseek-r1 等推理模型）
```

### 3.2 `streamText` — 流式生成（聊天场景核心）

```ts
import { streamText } from 'ai';

const result = streamText({
  model: 'openai/gpt-4o',
  system: '你是智能助手。',
  prompt: '写一首关于春天的诗',
  onChunk: ({ chunk }) => { /* 每个 chunk 回调，可 await 暂停消费 */ },
  onError: (error) => console.error(error),
});

// 多种消费方式：
const text = await result.text;                        // 完整文本
for await (const chunk of result.textStream) { ... }   // 逐块迭代

// 输出为 HTTP Response（三种协议）：
const uiResponse = result.toUIMessageStreamResponse(); // 供前端 useChat 消费（推荐）
const sseResponse = result.toDataStreamResponse();     // 兼容旧版数据流协议
const textResponse = result.toTextStreamResponse();    // 纯文本流
```

### 3.3 结构化输出 — `Output`（取代 generateObject）

```ts
import { generateText, Output } from 'ai';
import { z } from 'zod';

const { output } = await generateText({
  model: 'openai/gpt-4o',
  output: Output.object({
    schema: z.object({
      sentiment: z.enum(['positive', 'neutral', 'negative']),
      summary: z.string(),
      keywords: z.array(z.string()),
    }),
  }),
  prompt: '分析这段文本：...',
});

console.log(output?.sentiment, output?.summary);
```

`Output` 工厂方法（均从 `ai` 导出）：`Output.text()`、`Output.object({schema})`、`Output.array({schema, ...})`、`Output.json({schema})`、`Output.choice(...)`。

> 流式版本：把 `generateText` 换成 `streamText`，通过 `result.output`（Promise）取最终对象，`result.partialOutputStream` 可拿中间态。

### 3.4 工具调用（Function Calling）

```ts
import { generateText, tool } from 'ai';
import { z } from 'zod';

const result = await generateText({
  model: 'openai/gpt-4o',
  tools: {
    getWeather: tool({
      description: '查询城市天气',
      inputSchema: z.object({ city: z.string() }),
      execute: async ({ city }) => ({ city, temp: 26 }),
    }),
  },
  prompt: '北京天气如何？',
});

console.log(result.toolCalls);   // 模型发起的调用
console.log(result.toolResults); // 工具执行结果
```

### 3.5 向量嵌入（RAG 场景）

```ts
import { embed, embedMany } from 'ai';

const { embedding } = await embed({
  model: 'openai/text-embedding-3-small', // 或 gateway.embedding('...')
  value: '这是一段文本',
});

const { embeddings } = await embedMany({
  model: 'openai/text-embedding-3-small',
  values: ['文本1', '文本2'],
});
```

### 3.6 已弃用 API（避免使用）

- `generateObject` / `streamObject` —— 改用 `generateText` / `streamText` + `output`。

---

## 4. Vue 前端 API（`import ... from '@ai-sdk/vue'`）

### 4.1 `useChat`（推荐，新版 API）

这是替代 0.2 节「手写 SSE」的核心。在 Vue 组件里：

```ts
import { useChat } from '@ai-sdk/vue';

const { messages, sendMessage, status, error, stop, regenerate } = useChat({
  api: '/api/chat', // 指向你自己的后端（Node 代理，见第 6 节）
  onError: (e) => console.error(e),
});
```

**返回值**：

| 属性/方法 | 类型 | 说明 |
| --- | --- | --- |
| `messages` | `ShallowRef<UIMessage[]>` | 消息列表，直接可写（增删改都会反映到界面） |
| `status` | `ShallowRef<'submitted'\|'streaming'\|'ready'\|'error'>` | 请求状态机 |
| `error` | `ShallowRef<Error \| undefined>` | 错误对象 |
| `id` | `ComputedRef<string>` | 会话 id（未传自动生成） |
| `sendMessage(msg, opts?)` | `(msg) => Promise<void>` | **发送消息并触发请求**（替代旧版 handleSubmit） |
| `regenerate(opts?)` | `() => Promise<void>` | 重新生成最后一条 |
| `stop()` | `() => Promise<void>` | 中止当前请求，保留已生成内容（替代手写 abort） |
| `resumeStream(opts?)` | `() => Promise<void>` | 恢复被中断的流 |
| `clearError()` | `() => void` | 清除错误状态 |

**发送消息**（支持纯文本 / 携带文件 / 重新发送指定消息）：

```ts
await sendMessage({ text: '你好' });
await sendMessage({ text: '看下这张图', files }); // files: FileList | FileUIPart[]
await sendMessage({ messageId: 'xxx' });          // 替换已有消息后重新发送
```

**`UIMessage` 结构（渲染依赖它，取代旧版 `{role, content}`）**：

```ts
type UIMessage = {
  id: string;
  role: 'system' | 'user' | 'assistant';
  metadata?: unknown;
  parts: Array<
    | { type: 'text'; text: string; state?: 'streaming' | 'done' }
    | { type: 'reasoning'; text: string; state?: 'streaming' | 'done' } // 深度思考
    | { type: 'tool'; ... }       // 工具调用
    | { type: 'file'; ... }       // 附件
    | { type: 'source-url' | 'source-document'; ... }
  >;
};
```

> 对比 0.2 节：手写版里 `reasoningContent` 要自己逐 chunk 累加，`useChat` 直接提供 `type: 'reasoning'` 的 part 并自动流式更新。

### 4.2 `useCompletion`（单次补全，无需消息列表）

```ts
const {
  completion,      // Ref<string>
  complete,        // (prompt) => Promise<string>
  input,           // Ref<string>
  handleSubmit,    // 表单提交
  isLoading,       // Ref<boolean>
  stop, setCompletion, error,
} = useCompletion({ api: '/api/completion' });
```

### 4.3 `useObject`（前端流式结构化输出，原 experimental_useObject）

```ts
import { useObject } from '@ai-sdk/vue';
import { z } from 'zod';

const { object, submit, isLoading, error, stop, clear } = useObject({
  api: '/api/object',
  schema: z.object({ title: z.string(), points: z.array(z.string()) }),
});

submit({ topic: 'Vue 3 响应式' }); // 触发请求，object 随流逐步更新
```

---

## 5. 前后端通信协议（UIMessageStream）

`useChat` 默认通过 `HttpChatTransport` 向 `api`（默认 `/api/chat`）发 **POST**，请求体为 `{ messages, id }`，并期望返回 **UIMessageStream** 格式的流。

后端（Node）一行代码即可生成该格式：

```ts
const result = streamText({ model, messages });
return result.toUIMessageStreamResponse();
```

> 即：**前端 `useChat` 与后端 `streamText().toUIMessageStreamResponse()` 天生配对**，无需手写 SSE 解析、无需 `JSON.parse(res.data)`、无需自己拼 Authorization 之外的协议细节。

---

## 6. 本项目（yudao / Vben）集成方案

### 6.1 方案对比

| 方案 | 做法 | 适用场景 |
| --- | --- | --- |
| **A. Node 代理服务（完整 AI SDK）** | 在仓库内新增一个 Node 服务跑 `streamText`，前端 `useChat` 指向它 | 想用 AI SDK 的流式对话/工具调用/流式结构化，模型 Key 自己管 |
| **B. 沿用 yudao 后端 AI 接口（现状）** | 继续用 0.2 节的手写 SSE + `@vben/request` | 由 Java 后端统一代理模型、计费、知识库；`@ai-sdk/vue` 暂不需要 |

> 关键事实：**yudao 后端是 Java（Spring Boot），无法运行 `ai`（TypeScript SDK）**。所以 AI SDK 的 `generateText`/`streamText` 只能在仓库内新增的 Node 服务里跑，前端 `useChat` 通过代理/反向代理访问它。若不想新增 Node 服务，就继续用方案 B。

### 6.2 方案 A：Node 代理服务示例（`apps/web-antd/server/chat.ts`）

新建 `apps/web-antd/server/chat.ts`：

```ts
import { createServer } from 'node:http';
import { streamText, convertToModelMessages } from 'ai';
import { createOpenAI } from '@ai-sdk/openai';

const openai = createOpenAI({
  apiKey: process.env.OPENAI_API_KEY,
  baseURL: process.env.OPENAI_BASE_URL,
});

const server = createServer(async (req, res) => {
  if (req.url === '/api/chat' && req.method === 'POST') {
    // 1. 读取请求体
    let body = '';
    for await (const chunk of req) body += chunk;
    const { messages } = JSON.parse(body);

    // 2. 用 AI SDK 流式生成（UIMessage -> ModelMessage）
    const result = streamText({
      model: openai('gpt-4o'),
      system: '你是智能助手。',
      messages: await convertToModelMessages(messages),
    });

    // 3. 输出 UIMessageStream，useChat 直接消费
    const response = result.toUIMessageStreamResponse();
    res.writeHead(response.status, Object.fromEntries(response.headers));
    const reader = response.body!.getReader();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      res.write(Buffer.from(value));
    }
    res.end();
    return;
  }
  res.writeHead(404).end();
});

server.listen(8787, () => console.log('AI proxy on :8787'));
```

在 `apps/web-antd/package.json` 增加脚本：

```json
"scripts": {
  "ai:server": "tsx server/chat.ts"
}
```

开发环境在 `apps/web-antd/vite.config.ts` 增加 proxy（现有 proxy 见 [vite.config.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/vite.config.ts#L9-L17)），把 `/api/chat` 转发到 8787，避免跨域：

```ts
vite: {
  server: {
    proxy: {
      '/admin-api': { /* 现有配置保持不变 */ },
      '/api/chat': { target: 'http://localhost:8787', changeOrigin: true },
    },
  },
},
```

### 6.3 前端聊天页面示例（Vben + Ant Design Vue）

下面示例直接对应 0.2 节的 `index/index.vue` 功能（发送、流式显示、停止、重新生成、错误提示），但代码量大幅减少：

```vue
<script setup lang="ts">
import { useChat } from '@ai-sdk/vue';
import { computed } from 'vue';

const { messages, sendMessage, status, error, stop, regenerate } = useChat({
  api: '/api/chat', // 指向 Node 代理
  onError: (e) => console.error(e),
});

// 把 UIMessage 里的 text part 拼成可展示文本
const assistantText = computed(() =>
  messages.value
    .filter((m) => m.role === 'assistant')
    .flatMap((m) => m.parts)
    .filter((p) => p.type === 'text')
    .map((p) => p.text)
    .join(''),
);

// status 替代手写的 conversationInProgress
const isLoading = computed(
  () => status.value === 'streaming' || status.value === 'submitted',
);
</script>

<template>
  <div class="chat">
    <div v-for="m in messages" :key="m.id" class="msg" :class="m.role">
      <!-- 按 part 类型渲染：文本/推理/工具/文件 -->
      <template v-for="(p, i) in m.parts" :key="i">
        <div v-if="p.type === 'text'">{{ p.text }}</div>
        <div v-else-if="p.type === 'reasoning'" class="reasoning">
          {{ p.text }}
        </div>
      </template>
    </div>

    <div v-if="error" class="err">{{ error.message }}</div>

    <div class="actions">
      <a-button :loading="isLoading" @click="sendMessage({ text: '你好' })">
        发送
      </a-button>
      <!-- 替代手写 stopStream() -->
      <a-button v-if="isLoading" @click="stop()">停止</a-button>
      <a-button @click="regenerate()">重新生成</a-button>
    </div>
  </div>
</template>
```

### 6.4 从手写 SSE 迁移到 `useChat`（对照表）

| 手写 SSE（本地现状） | `useChat` |
| --- | --- |
| [message/index.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/src/api/ai/chat/message/index.ts) 里 `fetchEventSource` + 手动 `JSON.parse` | 内置 `HttpChatTransport` 解析 UIMessageStream |
| push 两条假消息 + 首 chunk 后替换 | `messages` 自动维护，`sendMessage` 即插即插 |
| `receiveMessageFullText += ...` 手动累加 | `parts[]` 中 `type: 'text'` 自动流式更新 |
| `reasoningContent` 手动累加 | `type: 'reasoning'` part 自动流式更新 |
| `AbortController` + `conversationInProgress` | `stop()` + `status` |
| 每次 `scrollToBottom()` | 监听 `messages`/`status` 变化后自行滚动 |
| yudao 自定义 `ChatMessage` 结构 | 标准 `UIMessage { role, parts[] }` |

> 注意：方案 A 下消息不再落库到 yudao（除非你在 Node 代理里自行同步）。需要保留历史记录/计费时，建议继续走方案 B 或让代理回调 yudao 的存储接口。

### 6.5 环境变量与安全

- API Key 只存在于**服务端**环境变量（`.env` 不要提交到仓库），前端 bundle 中绝不出现。
- `useChat` 的 `api` 必须指向你自己的后端，由后端持有厂商密钥。
- 正式环境给代理服务加鉴权（如校验 yudao 登录态：参考 [message/index.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/src/api/ai/chat/message/index.ts#L70-L76) 里 `Authorization: Bearer <token>` 的取法，代理端用同样的 token 校验后再转发）。

---

## 7. 从旧版本迁移速查

| 旧用法（v4/v5） | 新用法（v7 / @ai-sdk/vue@4） |
| --- | --- |
| `useChat({ api })` 返回 `input`/`handleSubmit`/`isLoading` | 返回 `sendMessage({ text })`/`status`/`error` |
| `generateObject({ schema })` | `generateText({ output: Output.object({ schema }) })` |
| `experimental_useObject` | `useObject` |
| `new Chat({...})`（类） | `useChat({...})`（composable） |
| 消息 `{role, content}` | `UIMessage {role, parts[]}` |
| 需单独安装 `@ai-sdk/openai` 等 | 也可直接 `model: 'openai/gpt-4o'` 走内置 Gateway |

---

## 8. 常见问题

- **Node 版本过低**：`ai@7` 要求 Node >= 22（仓库 `.node-version` 已满足），请保持升级。
- **zod 版本不匹配**：`ai` 要求 `zod ^3.25.76 || ^4.1.8`，本仓库 catalog 为 3.25.76，满足要求。
- **跨域 401/网络错误**：确认 `useChat` 的 `api` 已被 Vite proxy 或后端 CORS 放行。
- **`status` 一直 `streaming`**：后端未返回 UIMessageStream 格式，请使用 `toUIMessageStreamResponse()`。
- **生产构建后聊天不可用**：Node 代理需单独部署并配置域名/代理，前端 `api` 指向线上地址。
- **把 `ai` import 进前端组件导致构建报错**：说明用法错了，`generateText`/`streamText` 只能在 Node 服务端使用，前端只 import `@ai-sdk/vue`。

---

## 9. 参考

- AI SDK 官方文档：https://ai-sdk.dev/docs
- 模型厂商接入：https://ai-sdk.dev/providers/ai-sdk-providers
- 本仓库相关文件：
  - 依赖声明：[apps/web-antd/package.json](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/package.json#L28-L75)
  - 锁定版本：`pnpm-lock.yaml`（`ai@7.0.76`、`@ai-sdk/vue@4.0.76`）
  - 现有聊天页（手写 SSE）：[apps/web-antd/src/views/ai/chat/index/index.vue](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/src/views/ai/chat/index/index.vue)
  - SSE 请求封装：[apps/web-antd/src/api/ai/chat/message/index.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/src/api/ai/chat/message/index.ts)
  - Vite 代理：[apps/web-antd/vite.config.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/vite.config.ts)
