<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { ColumnDef, Paginated } from '../api'

const props = defineProps<{
  title: string
  desc?: string
  loader: (page: number, limit: number) => Promise<Paginated<any>>
  columns: ColumnDef[]
  actions?: { label: string; type?: 'primary' | 'success' | 'danger' | 'warning' | 'info'; handler: (row: any) => Promise<void> }[]
  emptyText?: string
}>()

const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const limit = 20
const loading = ref(false)
const drawer = ref(false)
const activeRow = ref<any>(null)
const errorText = ref('')

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    const result = await props.loader(page.value, limit)
    rows.value = result.data || []
    total.value = result.meta?.total_count ?? result.meta?.total ?? rows.value.length
  } catch (err) {
    errorText.value = err instanceof Error ? err.message : String(err)
    ElMessage.error(errorText.value)
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function formatTime(value: any) {
  if (value === undefined || value === null || value === '') {
    return '-'
  }
  const number = typeof value === 'number' ? (value > 1e12 ? value : value * 1000) : Date.parse(String(value))
  return Number.isNaN(number) ? String(value) : new Date(number).toLocaleString()
}

function cell(row: any, column: ColumnDef) {
  const value = row?.[column.key]
  if (value === undefined || value === null || value === '') {
    return '-'
  }
  if (column.type === 'time') {
    return formatTime(value)
  }
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  return String(value)
}

function tagType(value: any) {
  const text = String(value ?? '').toLowerCase()
  if (['error', 'failed', 'rejected', 'denied', 'revoked', 'expired'].includes(text)) {
    return 'danger'
  }
  if (['ok', 'success', 'completed', 'active', 'approved', 'enabled'].includes(text)) {
    return 'success'
  }
  if (['pending', 'paused', 'waiting'].includes(text)) {
    return 'warning'
  }
  return 'info'
}

function openRow(row: any) {
  activeRow.value = row
  drawer.value = true
}

async function runAction(action: { label: string; handler: (row: any) => Promise<void> }, row: any) {
  try {
    await action.handler(row)
    ElMessage.success(`${action.label}成功`)
    await load()
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    if (message === 'cancel' || message === 'close') {
      return
    }
    ElMessage.error(message)
  }
}

defineExpose({ reload: load })

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <div class="card-title">{{ title }}</div>
        <div class="card-desc">{{ desc || '共 ' + total + ' 条数据' }}</div>
      </div>
      <div style="display: flex; gap: 8px; align-items: center">
        <slot name="toolbar" />
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="errorText"
      type="warning"
      :closable="false"
      show-icon
      :title="'接口不可用：' + errorText"
      style="margin-bottom: 12px"
    />

    <el-table
      :data="rows"
      v-loading="loading"
      style="width: 100%"
      :empty-text="emptyText || '暂无数据'"
      @row-click="openRow"
    >
      <el-table-column
        v-for="column in columns"
        :key="column.key"
        :label="column.label"
        :width="column.width"
        min-width="140"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <el-tag v-if="column.type === 'tag'" size="small" :type="tagType(row[column.key])" effect="light">
            {{ cell(row, column) }}
          </el-tag>
          <span v-else :class="column.type === 'mono' ? 'session-id' : ''">{{ cell(row, column) }}</span>
        </template>
      </el-table-column>

      <el-table-column v-if="actions && actions.length" label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            v-for="action in actions"
            :key="action.label"
            size="small"
            link
            :type="action.type || 'primary'"
            @click.stop="runAction(action, row)"
          >
            {{ action.label }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="total > limit" style="display: flex; justify-content: flex-end; margin-top: 16px">
      <el-pagination
        v-model:current-page="page"
        :page-size="limit"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>

    <el-drawer v-model="drawer" :title="title + ' 详情'" size="640px">
      <pre class="audit-json" style="max-height: 560px; overflow: auto">{{ JSON.stringify(activeRow, null, 2) }}</pre>
    </el-drawer>
  </el-card>
</template>
