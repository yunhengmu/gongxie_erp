<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createSchedule,
  deleteSchedule,
  disableSchedule,
  enableSchedule,
  listSchedules,
  triggerSchedule,
  type ScheduleRow,
} from '../api'

const rows = ref<ScheduleRow[]>([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)

const form = reactive({
  name: '',
  cron_expr: '0 9 * * *',
  endpoint: '/agents/assistant/runs',
  method: 'POST',
  timezone: 'Asia/Shanghai',
  description: '',
})

async function load() {
  loading.value = true
  try {
    const result = await listSchedules()
    rows.value = result.data || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.name = ''
  form.cron_expr = '0 9 * * *'
  form.endpoint = '/agents/assistant/runs'
  form.method = 'POST'
  form.timezone = 'Asia/Shanghai'
  form.description = ''
  dialog.value = true
}

async function submit() {
  if (!form.name.trim() || !form.cron_expr.trim() || !form.endpoint.trim()) {
    ElMessage.warning('名称、Cron、Endpoint 都不能为空')
    return
  }
  saving.value = true
  try {
    await createSchedule({
      name: form.name.trim(),
      cron_expr: form.cron_expr.trim(),
      endpoint: form.endpoint.trim(),
      method: form.method,
      timezone: form.timezone,
      description: form.description.trim() || undefined,
    })
    ElMessage.success('定时任务已创建')
    dialog.value = false
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    saving.value = false
  }
}

async function toggle(row: ScheduleRow) {
  try {
    if (row.enabled) {
      await disableSchedule(row.id)
      ElMessage.success('已停用')
    } else {
      await enableSchedule(row.id)
      ElMessage.success('已启用')
    }
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

async function trigger(row: ScheduleRow) {
  try {
    await triggerSchedule(row.id)
    ElMessage.success('已触发执行')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

async function remove(row: ScheduleRow) {
  try {
    await ElMessageBox.confirm(`确认删除 ${row.name || row.id}？`, '删除定时任务', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteSchedule(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

function formatTime(value?: string | number | null) {
  if (value === undefined || value === null || value === '') {
    return '-'
  }
  const number = typeof value === 'number' ? (value > 1e12 ? value : value * 1000) : Date.parse(String(value))
  return Number.isNaN(number) ? String(value) : new Date(number).toLocaleString()
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <div class="card-title">定时任务</div>
        <div class="card-desc">用 Cron 表达式定时调用 AgentOS 接口</div>
      </div>
      <el-button type="primary" size="small" @click="openCreate">新建任务</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" style="width: 100%">
      <el-table-column label="任务" min-width="200">
        <template #default="{ row }">
          <div class="session-name">{{ row.name || '未命名' }}</div>
          <div class="session-id">{{ row.id }}</div>
        </template>
      </el-table-column>
      <el-table-column label="Cron" width="140">
        <template #default="{ row }">
          <span class="scope-line">{{ row.cron_expr || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="调用" min-width="260">
        <template #default="{ row }">
          <span class="scope-line">{{ row.method || 'POST' }} {{ row.endpoint || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="下次执行" width="180">
        <template #default="{ row }">{{ formatTime(row.next_run_at) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="light">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="toggle(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button size="small" link type="success" @click="trigger(row)">执行</el-button>
          <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="新建定时任务" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如 每日报表" />
        </el-form-item>
        <el-form-item label="Cron">
          <el-input v-model="form.cron_expr" placeholder="0 9 * * *" />
          <div class="dialog-hint">五段式：分 时 日 月 周，例如 0 9 * * * 表示每天 9 点</div>
        </el-form-item>
        <el-form-item label="Endpoint">
          <el-input v-model="form.endpoint" placeholder="/agents/assistant/runs" />
        </el-form-item>
        <el-form-item label="Method">
          <el-select v-model="form.method" style="width: 160px">
            <el-option label="POST" value="POST" />
            <el-option label="GET" value="GET" />
          </el-select>
        </el-form-item>
        <el-form-item label="时区">
          <el-input v-model="form.timezone" placeholder="Asia/Shanghai" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">创建</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
