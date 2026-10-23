<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listFilesystemEntries } from '../api'

const props = defineProps<{ agentId: string }>()

const directory = ref('')
const entries = ref<any[]>([])
const usage = ref<Record<string, any> | null>(null)
const namespace = ref('')
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  try {
    const result = await listFilesystemEntries(props.agentId, directory.value.trim())
    entries.value = result.entries || []
    usage.value = result.usage || null
    namespace.value = result.namespace || props.agentId
    error.value = ''
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
    entries.value = []
    usage.value = null
  } finally {
    loading.value = false
  }
}

function formatSize(value: any) {
  if (value === undefined || value === null) {
    return '-'
  }
  const size = Number(value)
  if (Number.isNaN(size)) {
    return String(value)
  }
  if (size < 1024) {
    return `${size} B`
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(value: any) {
  if (!value) {
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
        <div class="card-title">文件系统</div>
        <div class="card-desc">
          Agent {{ props.agentId }} 的沙箱文件
          <span v-if="usage">· {{ usage.file_count }} 个文件 / {{ formatSize(usage.total_bytes) }}</span>
        </div>
      </div>
      <div style="display: flex; gap: 8px">
        <el-input v-model="directory" size="small" placeholder="目录，留空为根" style="width: 200px" />
        <el-button size="small" @click="load">加载</el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      type="warning"
      :closable="false"
      show-icon
      :title="'读取失败：' + error"
      style="margin-bottom: 12px"
    />

    <el-table :data="entries" v-loading="loading" empty-text="目录为空，Agent 还没有写入文件" style="width: 100%">
      <el-table-column label="路径" min-width="300">
        <template #default="{ row }">
          <span class="scope-line">{{ row.path }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.type || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="110">
        <template #default="{ row }">{{ formatSize(row.size_bytes) }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
      </el-table-column>
    </el-table>

    <div class="hint" style="margin-top: 12px">
      namespace: {{ namespace || props.agentId }}。路径为相对路径，例如 notes/todo.md；Agent 通过文件工具写入后才会出现在这里。
    </div>
  </el-card>
</template>
