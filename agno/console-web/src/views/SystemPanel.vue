<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getMetrics, getQueue, type MetricsSummary } from '../api'

const metrics = ref<MetricsSummary[]>([])
const loading = ref(false)
const queue = ref<any>(null)
const queueError = ref('')

const latest = computed(() => metrics.value[0])
const totalTokens = computed(() =>
  metrics.value.reduce((sum, item) => sum + (item.token_metrics?.total_tokens || 0), 0),
)
const totalRuns = computed(() =>
  metrics.value.reduce(
    (sum, item) => sum + (item.agent_runs_count || 0) + (item.team_runs_count || 0) + (item.workflow_runs_count || 0),
    0,
  ),
)

function formatNumber(value: number | undefined | null) {
  return (value ?? 0).toLocaleString()
}

function formatTime(value?: string) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

async function loadMetrics() {
  loading.value = true
  try {
    const result = await getMetrics()
    metrics.value = result.metrics || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}

async function loadQueue() {
  queueError.value = ''
  try {
    queue.value = await getQueue()
  } catch (err) {
    queue.value = null
    queueError.value = err instanceof Error ? err.message : String(err)
  }
}

function refresh() {
  loadMetrics()
  loadQueue()
}

onMounted(refresh)
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <div class="card-title">运行监控</div>
        <div class="card-desc">按天的运行量、Token 消耗与任务队列状态</div>
      </div>
      <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
    </div>

    <el-row :gutter="12" style="margin-bottom: 16px">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">累计运行</div>
          <div class="stat-value">{{ formatNumber(totalRuns) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">今日 Agent 运行</div>
          <div class="stat-value">{{ formatNumber(latest?.agent_runs_count) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">今日会话</div>
          <div class="stat-value">{{ formatNumber(latest?.agent_sessions_count) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">累计 Tokens</div>
          <div class="stat-value">{{ formatNumber(totalTokens) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-table :data="metrics" v-loading="loading" style="width: 100%">
      <el-table-column label="日期" width="130">
        <template #default="{ row }">{{ (row.date || '').slice(0, 10) }}</template>
      </el-table-column>
      <el-table-column label="Agent" width="90">
        <template #default="{ row }">{{ formatNumber(row.agent_runs_count) }}</template>
      </el-table-column>
      <el-table-column label="Team" width="90">
        <template #default="{ row }">{{ formatNumber(row.team_runs_count) }}</template>
      </el-table-column>
      <el-table-column label="Workflow" width="100">
        <template #default="{ row }">{{ formatNumber(row.workflow_runs_count) }}</template>
      </el-table-column>
      <el-table-column label="会话" width="90">
        <template #default="{ row }">
          {{ formatNumber((row.agent_sessions_count || 0) + (row.team_sessions_count || 0) + (row.workflow_sessions_count || 0)) }}
        </template>
      </el-table-column>
      <el-table-column label="用户" width="90">
        <template #default="{ row }">{{ formatNumber(row.users_count) }}</template>
      </el-table-column>
      <el-table-column label="Tokens" width="120">
        <template #default="{ row }">{{ formatNumber(row.token_metrics?.total_tokens) }}</template>
      </el-table-column>
      <el-table-column label="模型" min-width="220">
        <template #default="{ row }">
          <el-tag
            v-for="model in row.model_metrics || []"
            :key="model.model_id + model.model_provider"
            size="small"
            effect="plain"
            style="margin-right: 6px"
          >
            {{ model.model_provider }} / {{ model.model_id }} · {{ model.count }}
          </el-tag>
          <span v-if="!(row.model_metrics || []).length" class="hint">-</span>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!metrics.length && !loading" description="暂无指标数据，发起一次对话后会出现" />

    <div class="detail-block" style="margin-top: 20px">
      <h4>任务队列</h4>
      <el-alert
        v-if="queueError"
        type="warning"
        :closable="false"
        show-icon
        :title="'队列未启用：' + queueError"
      />
      <template v-else-if="queue">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="待处理">{{ queue.pending ?? queue.metrics?.pending ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="运行中">{{ queue.running ?? queue.metrics?.running ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="已完成">{{ queue.completed ?? queue.metrics?.completed ?? 0 }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <div v-else class="hint">加载中…</div>
    </div>
  </el-card>
</template>
