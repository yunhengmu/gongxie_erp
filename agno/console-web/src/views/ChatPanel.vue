<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { streamRun } from '../api'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  error?: boolean
  pending?: boolean
}

const props = defineProps<{ agentId: string }>()

const messages = ref<ChatMessage[]>([])
const input = ref('')
const sending = ref(false)
const sessionId = ref(crypto.randomUUID())
const listRef = ref<HTMLElement | null>(null)
let controller: AbortController | null = null

async function scrollToBottom() {
  await nextTick()
  const element = listRef.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) {
    return
  }

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  const index = messages.value.length
  messages.value.push({ role: 'assistant', content: '', pending: true })
  sending.value = true
  controller = new AbortController()
  await scrollToBottom()

  try {
    await streamRun({
      agentId: props.agentId,
      message: text,
      sessionId: sessionId.value,
      signal: controller.signal,
      onEvent: (event, data) => {
        const message = messages.value[index]
        if (!message) {
          return
        }
        if (event === 'RunContent' && typeof data.content === 'string') {
          message.content += data.content
          message.pending = false
          scrollToBottom()
        } else if (event === 'RunError') {
          message.error = true
          message.pending = false
          message.content = data.content || '运行失败'
        }
      },
    })
  } catch (err) {
    const message = messages.value[index]
    if (message) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        message.pending = false
        message.content += '\n\n[已停止]'
      } else {
        message.error = true
        message.pending = false
        message.content = message.content || (err instanceof Error ? err.message : String(err))
      }
    }
  } finally {
    const message = messages.value[index]
    if (message) {
      message.pending = false
      if (!message.content && !message.error) {
        message.content = '(无输出)'
      }
    }
    sending.value = false
    controller = null
    scrollToBottom()
  }
}

function stop() {
  controller?.abort()
}

function newSession() {
  if (sending.value) {
    return
  }
  messages.value = []
  sessionId.value = crypto.randomUUID()
}
</script>

<template>
  <div class="chat-shell">
    <div class="chat-head">
      <div class="chat-session">session: {{ sessionId }}</div>
      <div>
        <el-button size="small" :disabled="sending" @click="newSession">新会话</el-button>
        <el-button v-if="sending" size="small" type="danger" plain @click="stop">停止</el-button>
      </div>
    </div>

    <div ref="listRef" class="chat-list">
      <div v-if="!messages.length" class="chat-empty">
        <div>发送一条消息，验证当前模型配置</div>
        <div>没有有效 API Key 时会直接显示 RunError</div>
      </div>

      <div v-for="(message, index) in messages" :key="index" class="bubble-row" :class="message.role">
        <div
          class="bubble"
          :class="[message.role, { error: message.error, pending: message.pending && !message.content }]"
        >
          {{ message.content || (message.pending ? '思考中…' : '') }}
        </div>
      </div>
    </div>

    <div class="chat-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        :disabled="sending"
        placeholder="输入消息，Enter 发送，Shift+Enter 换行"
        @keydown.enter.exact.prevent="send"
      />
      <el-button type="primary" :loading="sending" @click="send">发送</el-button>
    </div>
  </div>
</template>
