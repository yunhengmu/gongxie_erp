<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getTrace, listTraces, type TraceRow } from '../api'

const props = defineProps<{ agentId: string }>()

const rows = ref<TraceRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)
const drawer = ref(false)
const detailLoading = ref(false)
const detail = ref<Record<string, any> | null>(null)

async function load() {
  loading.value = true
  try {
    const result = await listTraces(props.agentId, page.value, pageSize)
    rows.value = result.data || []
    total.value = result.meta?.total_count ?? result.meta?.total ?? rows.value.length
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
    rows.value = []
    total.value = 0
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

function formatDuration(value?: number) {
  if (value === undefined || value === null) {
    return '-'
  }
  return value > 1000 ? `${(value / 1000).toFixed(2)}s` : `${Math.round(value)}ms`
}

function statusType(status?: string) {
  if (!status) {
    return 'info'
  }
  const normalized = status.toLowerCase()
  if (normalized === 'error' || normalized === 'failed' || normalized === 'failure') {
    return 'danger'
  }
  if (normalized === 'ok' || normalized === 'success' || normalized === 'completed') {
    return 'success'
  }
  return 'info'
}

async function open(row: TraceRow) {
  drawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getTrace(row.trace_id)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
    drawer.value = false
  } finally {
    detailLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <div class="card-title">运行追踪</div>
        <div class="card-desc">共 {{ total }} 条 Trace，点击行查看完整调用链</div>
      </div>
      <el-button size="small" @click="load">刷新</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" style="width: 100%" @row-click="open">
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)" effect="light">{{ row.status || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Trace" min-width="240">
        <template #default="{ row }">
          <div class="session-name">{{ row.name || 'run' }}</div>
          <div class="session-id">{{ row.trace_id }}</div>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">{{ formatDuration(row.duration) }}</template>
      </el-table-column>
      <el-table-column label="Span" width="80">
        <template #default="{ row }">{{ row.total_spans ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="开始时间" width="180">
        <template #default="{ row }">{{ formatTime(row.start_time) }}</template>
      </el-table-column>
      <el-table-column label="Session" width="160">
        <template #default="{ row }">
          <span class="session-id">{{ row.session_id || '-' }}</span>
        </template>
      </el-table-column>
    </el-table>

    <div style="display: flex; justify-content: flex-end; margin-top: 16px">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>

    <el-drawer v-model="drawer" title="Trace 详情" size="720px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
            <el-descriptions-item label="Trace ID">{{ detail.trace_id }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ detail.status || '-' }}</el-descriptions-item>
            <el-descriptions-item label="耗时">{{ formatDuration(detail.duration) }}</el-descriptions-item>
            <el-descriptions-item label="Span 数">{{ detail.total_spans ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="Run ID">{{ detail.run_id || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Session">{{ detail.session_id || '-' }}</el-descriptions-item>
          </el-descriptions>
          <pre class="audit-json" style="max-height: 440px; overflow: auto">{{ JSON.stringify(detail, null, 2) }}</pre>
        </template>
      </div>
    </el-drawer>
  </el-card>
</template>
