# LangChain Vue SDK（@langchain/vue）开发文档

> 适用项目：`apps/web-antd`（yudao-admin 前端 / Vben Admin 5.7 / Vue 3 + Ant Design Vue）
> 本文档基于**本仓库已安装的 `@langchain/vue@1.0.32`** 及其类型定义编写，所有 API 均对照
> `node_modules/@langchain/vue/dist/*.d.ts` 核实。安装命令见第 2 节。

---

## 0. 先看这里：这个 SDK 是干什么的

### 0.1 它和 `@ai-sdk/vue` 完全不是一回事

你之前看过的 `@ai-sdk/vue` 是「**直连大模型聊天**」的封装：前端 `useChat` → 你的 Node 代理 → 模型厂商。
`@langchain/vue` 则是**面向 LangGraph 的 Agent 流式协议（v2 streaming protocol）**的前端绑定：

- 它**不直接调大模型**，而是连一个**正在运行的 LangGraph Agent Server**（见 1.1）。
- 它的核心不是「聊天消息」，而是 **Agent 运行**：线程（thread）、工具调用、中断（HITL 人工审批）、
  子代理（Deep Agents）、子图、多模态流。

> 一句话：`@ai-sdk/vue` 管「对话」，`@langchain/vue` 管「Agent」。没有 LangGraph Agent Server，
> 光装 `@langchain/vue` 是跑不起来的。

### 0.2 本地依赖现状

`apps/web-antd/package.json` 当前 `dependencies`（见 [package.json](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/package.json#L31)）：

| 包 | 版本 | 说明 |
| --- | --- | --- |
| `@langchain/vue` | **^1.0.32** | Vue 3 Composition API 绑定，本 SDK 主包 |
| `@langchain/core` | **peer 依赖 ^1.1.48，未直接安装** | 提供 `BaseMessage` 等类型；需要时手动 `pnpm add @langchain/core`（见第 2 节） |

内部依赖链（见 `pnpm-lock.yaml`）：`@langchain/vue@1.0.32` → `@langchain/langgraph-sdk@1.9.31`（v2 流式传输、线程协议客户端）。

**Peer 依赖**：`vue`（^3.0.0）、`@langchain/core`（^1.1.48）。

### 0.3 用它的前提

1. 有一个**可访问的 LangGraph Agent Server**（本地 `langgraph dev`、LangGraph 平台、或自建 `AgentServerAdapter` 服务）。
2. 前端通过 `useStream` 连上它，`assistantId` 指向一个已部署的 Agent。

---

## 1. 架构与核心概念

```
┌───────────────────────────┐   v2 streaming protocol (SSE/WS)   ┌────────────────────────────┐
│  Vue 前端（本仓库）          │ ─────────────────────────────────▶ │  LangGraph Agent Server      │
│  @langchain/vue             │ ◀───────────────────────────────── │  LangGraph/LangChain 运行图   │
│  useStream + 选择器 composable│       线程流 + 消息 + 工具 + 中断    │  Deep Agents / 子图 / 工具     │
└───────────────────────────┘                                    └────────────────────────────┘
```

### 1.1 核心概念

| 概念 | 说明 |
| --- | --- |
| **Agent Server** | 部署 LangGraph 图的独立服务（本地 `langgraph dev` / LangGraph 云 / 自建 HTTP 服务）。`useStream` 只连它，不直连模型。 |
| **`useStream`** | 唯一的入口 composable。返回一个「句柄」，里面是响应式 refs（消息/状态/工具/中断）+ 命令方法（提交/停止/响应）。 |
| **线程（thread）** | 一次对话/一个 Agent 运行的会话单元。`threadId` 决定接续哪段历史；首次 `submit` 自动建线程。 |
| **中断（interrupt）** | Agent 运行到 HITL（人在回路）节点暂停，前端 `respond()` 恢复。 |
| **选择器（selector）** | `useMessages`/`useToolCalls`/`useValues` 等 ref-counted 订阅器：组件挂载才订阅、卸载自动释放。 |
| **v2-native 传输** | 基于会话的流式传输，组件重挂载自动重连（**不再需要** 0.x 的 `reconnectOnMount` / `joinStream`）。 |

### 1.2 大版本：1.x vs 0.x

- 选项、返回值、传输类都变了，完整对照见包内 `docs/v1-migration.md`（本仓库 node_modules 只打包了 `dist/`，线上文档见第 10 节参考）。
- 1.x 亮点：`<Suspense>` 友好（`hydrationPromise`）、`useStream<typeof agent>()` 品牌类型推断、多模态媒体内置组装、选择器 ref-counted。

---

## 2. 安装与依赖

已装 `@langchain/vue`。建议补上 peer 依赖，以便使用 `BaseMessage` 等消息类型：

```bash
# 在 apps/web-antd 目录下
corepack pnpm --filter @vben/web-antd add @langchain/core
```

> 若不需要手写 `BaseMessage`/Agent 品牌类型，`@langchain/vue` 本身即可编译运行（类型经 pnpm 虚拟存储解析）。

---

## 3. 核心 API：`useStream`

```ts
import { useStream } from '@langchain/vue';

const stream = useStream({
  assistantId: 'agent',      // 必填：绑定的 Agent 标识
  apiUrl: 'http://localhost:2024', // Agent Server 地址；可用 ref/getter 响应式切换
  // apiKey: '...',          // 可选
  // threadId: '...',        // 可选：指定会话线程（响应式）
  // client: myClient,       // 可选：复用自建 @langchain/langgraph-sdk Client
});
```

### 3.1 Options（已核实字段）

| 选项 | 类型 | 说明 |
| --- | --- | --- |
| `assistantId` | `string` | **必填**。绑定的 Agent。 |
| `apiUrl` | `MaybeRefOrGetter<string>` | Agent Server 地址，支持 ref/getter（可响应式切换）。 |
| `apiKey` | `MaybeRefOrGetter<string>` | 鉴权 Key，支持 ref/getter。 |
| `client` | `Client` | 复用预先配置好的 langgraph-sdk `Client`。 |
| `threadId` | `MaybeRefOrGetter<string \| null \| undefined>` | 指定线程；不传首次 `submit` 自动创建。 |
| `transport` | `AgentServerOptions \| CustomAdapterOptions` | 传输方式。默认连 LangGraph 官方服务；对接自建后端需实现 `AgentServerAdapter` 走 `CustomAdapterOptions`。 |

> 完整选项列表以 `node_modules/@langchain/vue/dist/use-stream.d.ts` 中 `UseStreamOptions` 为准（该类型再导出自 `@langchain/langgraph-sdk/stream`）。

### 3.2 返回值（句柄）

**响应式数据**（`ShallowRef`/`ComputedRef`，模板里自动解包，`<script setup>` 里读 `.value`，只读快照勿改）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `messages` | `ShallowRef<BaseMessage[]>` | 根命名空间消息，**按 token 流式更新**（渲染用它）。 |
| `values` | `ShallowRef<StateType>` | 线程级状态快照（按 superstep 更新，非 token 级）。 |
| `toolCalls` | `ShallowRef<AssembledToolCall[]>` | 根命名空间工具调用（配合 `useStream<typeof agent>()` 可收窄类型）。 |
| `interrupts` | `ShallowRef<Interrupt[]>` | 所有未决中断。 |
| `interrupt` | `ComputedRef<Interrupt \| undefined>` | 主中断别名（`interrupts[0]`）。 |
| `isLoading` | `ComputedRef<boolean>` | 运行中（禁用发送按钮/转圈用）。 |
| `isThreadLoading` | `ComputedRef<boolean>` | 线程初始拉取中（与 `isLoading` 区分）。 |
| `error` | `ComputedRef<unknown>` | 最近错误。 |
| `threadId` | `ComputedRef<string \| null>` | 当前线程 id。 |
| `hydrationPromise` | `ComputedRef<Promise<void>>` | 线程初始水合完成信号，供 `async setup()` + `<Suspense>`。 |
| `subagents` / `subgraphs` / `subgraphsByNode` | `ShallowRef<Map<...>>` | 发现到的子代理 / 子图（Deep Agents / 嵌套图）。 |

**命令方法**（普通函数，非 ref）：

| 方法 | 说明 |
| --- | --- |
| `submit(input, options?)` | 发起一次运行，`input` 为 `Partial<StateType>`（消息等）。 |
| `stop(options?)` | 停止当前运行（默认服务端取消 + 断开；`{ cancel: false }` 只断开）。 |
| `disconnect()` | 断开但不取消服务端运行（`stop({ cancel: false })` 别名）。 |
| `respond(response, options?)` | 恢复中断（HITL 应答）；`options.update` 可同 superstep 写入状态。 |
| `respondAll(responsesById, options?)` | 一次恢复同一检查点上的多个中断（如并行工具审批）。 |
| `getThread()` | 拿到底层 `ThreadStream`（低层协议访问）。 |

### 3.3 最小可跑示例

```vue
<script setup lang="ts">
import { useStream } from '@langchain/vue';

const stream = useStream({
  assistantId: 'agent',
  apiUrl: 'http://localhost:2024',
});

function onSubmit() {
  void stream.submit({
    messages: [{ type: 'human', content: '你好！' }],
  });
}
</script>

<template>
  <div>
    <div v-for="(msg, i) in stream.messages" :key="msg.id ?? i">
      {{ typeof msg.content === 'string' ? msg.content : JSON.stringify(msg.content) }}
    </div>
    <button :disabled="stream.isLoading" @click="onSubmit">发送</button>
  </div>
</template>
```

> 模板里 `stream.messages` 自动解包；`<script setup>` 里要写 `stream.messages.value`。

---

## 4. 选择器 composables（按需订阅）

所有选择器第一个参数都是 `stream` 句柄，第二个参数 `target` 可指向子代理/子图/命名空间。

| 函数 | 返回 | 用途 |
| --- | --- | --- |
| `useMessages(stream, target?)` | `ShallowRef<BaseMessage[]>` | 根/命名空间消息流（根命名空间直接复用 `stream.messages`）。 |
| `useToolCalls(stream, target?)` | `ShallowRef<AssembledToolCall[]>` | 工具调用流。 |
| `useValues<T>(stream, target?)` | `ShallowRef<T>` | 最新状态快照（当前状态面板用）。 |
| `useExtension<T>(stream, name, target?)` | `ShallowRef<T \| undefined>` | 订阅 `custom:<name>` 自定义通道的最新值。 |
| `useChannel(stream, channels, target?)` | `ShallowRef<Event[]>` | 原始事件缓冲（事件日志/自定义通道历史）。 |
| `useChannelEffect(stream, channels, opts)` | `void` | 每事件回调（埋点/日志，fire-and-forget）。 |
| `useMessageMetadata(stream, messageId)` | `ComputedRef<MessageMetadata \| undefined>` | 某条消息的元数据（`parentCheckpointId`，供 fork/编辑）。 |
| `useSubmissionQueue(stream)` | `{ entries, size, cancel, clear }` | 服务端排队提交的队列（`multitaskStrategy: 'enqueue'` 时）。 |
| `useAudio` / `useImages` / `useVideo` / `useFiles` | `ShallowRef<Media[]>` | 多模态媒体流，配合 `useMediaURL` 渲染 `<img>/<audio>/<video>`。 |

> 选择器是 **ref-counted** 的：组件挂载才开订阅、卸载自动释放（`onScopeDispose`）。

---

## 5. 共享 stream（跨组件）

多组件拆分聊天界面时，父组件提供、子组件消费同一个 stream：

```ts
// 父组件
import { provideStream } from '@langchain/vue';
provideStream({ assistantId: 'agent', apiUrl: 'http://localhost:2024' });
```

```ts
// 任意子组件
import { useStreamContext } from '@langchain/vue';
const { messages, submit } = useStreamContext();
// 带 Agent 类型：useStreamContext<typeof agent>()
```

应用级默认配置（所有 `useStream` 自动继承）：

```ts
// main.ts / 入口
import { LangChainPlugin } from '@langchain/vue';
app.use(LangChainPlugin, { apiUrl: 'http://localhost:2024' });
// 之后组件里 useStream({ assistantId: 'agent' }) 即可，apiUrl 自动继承
```

---

## 6. 消息与类型

- `stream.messages` 里的元素是 **`@langchain/core` 的 `BaseMessage`**（`HumanMessage`/`AIMessage`/`ToolMessage`…）。
- 想手写这些类型（如构造消息给 `submit`），需要 `@langchain/core`：`import { HumanMessage } from '@langchain/core/messages'`。
- `submit` 的 `input` 可用普通对象 `{ type: 'human', content: '...' }`（序列化兼容），也可传 `BaseMessage` 实例。

---

## 7. 在本仓库（yudao / Vben）的集成方案

### 7.1 方案对比

| 方案 | 做法 | 适用场景 |
| --- | --- | --- |
| **A. 接入 LangGraph Agent Server（完整能力）** | 仓库内/外起一个 LangGraph 服务，前端 `useStream` 连它 | 想要 Agent：工具调用、HITL 中断、子代理、多模态 |
| **B. 沿用 yudao 现有 AI 接口（现状）** | 继续用 [message/index.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/src/api/ai/chat/message/index.ts) 的手写 SSE | Java 后端统一代理/计费/知识库，暂时不需要 Agent 能力 |

> 关键事实：**`@langchain/vue` 不连 yudao 的 `/ai/chat/message/send-stream`**。它连的是 LangGraph Agent Server（Node/Python 运行图）。想真正跑起来，必须额外起一个 LangGraph 服务，这与 yudao Java 后端是两套独立的系统。

### 7.2 方案 A：本地起 Agent Server

最简单：在任意目录用 LangGraph CLI 启动本地开发服务

```bash
npx langgraph dev   # 启动后默认 http://localhost:2024，导出 assistantId
```

或自建 HTTP 服务实现 `AgentServerAdapter`（包内 `docs/custom-transport.md` 有完整示例，可参考 `examples/ui-react-transport`）。

### 7.3 前端注册 + Vite 代理

1. 在 `apps/web-antd/vite.config.ts` 的 `vite.server.proxy` 增加（现有 proxy 见 [vite.config.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/vite.config.ts)）：

```ts
'/langgraph': { target: 'http://localhost:2024', changeOrigin: true },
```

2. 页面里 `apiUrl` 指到代理路径，避免跨域：

```ts
const stream = useStream({
  assistantId: 'agent',
  apiUrl: '/langgraph', // 经 Vite proxy 转发到 2024
});
```

### 7.4 页面示例（Vben + Ant Design Vue）

```vue
<script setup lang="ts">
import { useStream } from '@langchain/vue';
import { computed, ref } from 'vue';

const stream = useStream({ assistantId: 'agent', apiUrl: '/langgraph' });
const input = ref('');

const messages = computed(() =>
  stream.messages.value.map((m) => ({
    role: m.role,
    text: typeof m.content === 'string' ? m.content : JSON.stringify(m.content),
  })),
);

function send() {
  if (!input.value.trim() || stream.isLoading.value) return;
  const text = input.value;
  input.value = '';
  void stream.submit({ messages: [{ type: 'human', content: text }] });
}
</script>

<template>
  <a-card title="LangGraph Agent 对话">
    <div v-for="(m, i) in messages" :key="i" :class="m.role">
      <a-tag :color="m.role === 'human' ? 'blue' : 'purple'">{{ m.role }}</a-tag>
      <span>{{ m.text }}</span>
    </div>
    <div class="actions">
      <a-input v-model:value="input" placeholder="输入消息" @press-enter="send" />
      <a-button type="primary" :loading="stream.isLoading.value" @click="send">发送</a-button>
      <a-button v-if="stream.isLoading.value" @click="stream.stop()">停止</a-button>
    </div>
  </a-card>
</template>
```

> 对应 0.2 节手写 SSE 页面的功能（发送/流式显示/停止），但消息状态、流式累加、中止都由 SDK 接管；还自带中断应答（`respond`）、工具调用展示（`toolCalls`）、子代理等能力。

---

## 8. `@langchain/vue` vs `@ai-sdk/vue`（选择依据）

| 维度 | `@ai-sdk/vue` | `@langchain/vue` |
| --- | --- | --- |
| 定位 | 对话 / 补全 / 结构化输出 | Agent（LangGraph） |
| 后端形态 | 任意 HTTP 接口（`useChat` → Node 代理 → 模型） | LangGraph Agent Server |
| 核心返回 | `messages` + `status` + `sendMessage` | `useStream` 句柄：`messages/values/toolCalls/interrupts` + `submit/stop/respond` |
| 消息模型 | `UIMessage { role, parts[] }` | `@langchain/core` `BaseMessage` |
| 特性 | 流式对话、`reasoning`、文件 | 线程、工具调用、**HITL 中断**、子代理/子图、多模态 |
| 需要额外服务 | 需要一个 Node 代理 | 需要一个 LangGraph Agent Server |

> 简单聊天 → `@ai-sdk/vue`；要 Agent 编排/人工审批/多智能体 → `@langchain/vue`。

---

## 9. 常见问题

- **`useStream` 一直没消息**：Agent Server 没起 / `apiUrl` 不对 / `assistantId` 与部署不符。先 `curl http://<apiUrl>/ok` 或浏览器访问确认服务可达。
- **跨域 401/网络错误**：确认 Vite proxy 已配置、`apiKey`（如需）正确。
- **报 `@langchain/core` 找不到**：它是 peer 依赖，补装：`corepack pnpm --filter @vben/web-antd add @langchain/core`。
- **想读完整选项/传输/中断文档**：包内 `dist/use-stream.d.ts`、`dist/selectors.d.ts`、`dist/context.d.ts` 是权威源码；完整 guides 见官方仓库 `libs/sdk-vue/docs`。
- **0.x 迁移**：返回值、选项、传输类均变化，不再有 `reconnectOnMount`/`joinStream`，按官方 `docs/v1-migration.md` 对照改。

---

## 10. 参考

- LangChain Vue SDK 源码（含全部 docs）：https://github.com/langchain-ai/langgraphjs/tree/main/libs/sdk-vue
- LangGraph JS 文档：https://docs.langchain.com/oss/javascript
- Deep Agents 概览：https://docs.langchain.com/oss/javascript/deepagents/overview
- 本仓库相关文件：
  - 依赖声明：[apps/web-antd/package.json](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/package.json#L31)
  - 已装包类型定义：`apps/web-antd/node_modules/@langchain/vue/dist/`
  - 现有手写 SSE 聊天页：[apps/web-antd/src/views/ai/chat/index/index.vue](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/src/views/ai/chat/index/index.vue)
  - SSE 请求封装：[apps/web-antd/src/api/ai/chat/message/index.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/src/api/ai/chat/message/index.ts)
  - Vite 代理：[apps/web-antd/vite.config.ts](file:///d:/yudao/yudao-ui-admin-vben-master/apps/web-antd/vite.config.ts)
