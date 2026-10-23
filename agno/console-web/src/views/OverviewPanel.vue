<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getStatus, listAgents, listSessions, type ConsoleStatus, type SessionRow } from '../api'

const status = ref<ConsoleStatus | null>(null)
const agentCount = ref(0)
const sessionTotal = ref(0)
const recent = ref<SessionRow[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    status.value = await getStatus()
    const agentId = status.value.agent.id
    const [agents, sessions] = await Promise.all([listAgents(), listSessions(agentId, 1, 5)])
    agentCount.value = agents.length
    recent.value = sessions.data || []
    sessionTotal.value = sessions.meta?.total_count ?? sessions.meta?.total ?? recent.value.length
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}

function formatTime(value?: string) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="metric-grid">
      <div class="metric-card">
        <div class="metric-label">当前模型</div>
        <div class="metric-value">{{ status?.agent.model || '-' }}</div>
        <div class="metric-sub">{{ status?.agent.provider || '-' }}</div>
      </div>
      <div class="metric-card">
        <div class="metric-label">智能体</div>
        <div class="metric-value">{{ agentCount }}</div>
        <div class="metric-sub">已注册到 AgentOS</div>
      </div>
      <div class="metric-card">
        <div class="metric-label">会话总数</div>
        <div class="metric-value">{{ sessionTotal }}</div>
        <div class="metric-sub">SQLite 持久化</div>
      </div>
      <div class="metric-card">
        <div class="metric-label">版本</div>
        <div class="metric-value">v{{ status?.agno_version || '-' }}</div>
        <div class="metric-sub">{{ status?.auth_mode || '-' }}</div>
      </div>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <div>
          <div class="card-title">最近会话</div>
          <div class="card-desc">最新 5 条运行记录</div>
        </div>
        <el-button size="small" @click="load">刷新</el-button>
      </div>

      <el-table :data="recent" style="width: 100%">
        <el-table-column label="会话" min-width="260">
          <template #default="{ row }">
            <div class="session-name">{{ row.session_name || '未命名会话' }}</div>
            <div class="session-id">{{ row.session_id }}</div>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="Token" width="100">
          <template #default="{ row }">{{ row.total_tokens ?? '-' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
