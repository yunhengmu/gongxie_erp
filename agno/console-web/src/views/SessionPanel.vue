<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelAgentRun,
  compactSession,
  deleteSession,
  forkSession,
  getSession,
  listSessionRuns,
  listSessions,
  renameSession,
  type SessionRow,
} from '../api'

const props = defineProps<{ agentId: string }>()

const rows = ref<SessionRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)
const drawer = ref(false)
const detailLoading = ref(false)
const detail = ref<Record<string, any> | null>(null)
const runs = ref<any[]>([])
const activeSession = ref<SessionRow | null>(null)

async function load() {
  loading.value = true
  try {
    const result = await listSessions(props.agentId, page.value, pageSize)
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

function formatContent(value: unknown) {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

async function open(row: SessionRow) {
  activeSession.value = row
  drawer.value = true
  detailLoading.value = true
  detail.value = null
  runs.value = []
  try {
    const [sessionDetail, runList] = await Promise.all([
      getSession(row.session_id),
      listSessionRuns(row.session_id, 20),
    ])
    detail.value = sessionDetail
    runs.value = runList.data || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
    drawer.value = false
  } finally {
    detailLoading.value = false
  }
}

async function cancelRun(row: any) {
  const agentId = row.agent_id || props.agentId
  if (!agentId || !row.run_id) {
    ElMessage.warning('缺少 agent_id 或 run_id，无法取消')
    return
  }
  try {
    await cancelAgentRun(agentId, row.run_id)
    ElMessage.success('已发送取消请求')
    if (activeSession.value) {
      const runList = await listSessionRuns(activeSession.value.session_id, 20)
      runs.value = runList.data || []
    }
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

async function rename(row: SessionRow) {
  try {
    const { value } = await ElMessageBox.prompt('输入新的会话名称', '重命名会话', {
      inputValue: row.session_name || '',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    if (!value || !value.trim()) {
      return
    }
    await renameSession(row.session_id, value.trim())
    ElMessage.success('已重命名')
    await load()
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') {
      ElMessage.error(err instanceof Error ? err.message : String(err))
    }
  }
}

async function remove(row: SessionRow) {
  try {
    await ElMessageBox.confirm(`确认删除会话 ${row.session_name || row.session_id}？`, '删除会话', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteSession(row.session_id)
    ElMessage.success('已删除')
    drawer.value = false
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

async function fork() {
  if (!activeSession.value) {
    return
  }
  try {
    await forkSession(props.agentId, activeSession.value.session_id)
    ElMessage.success('已复制为新会话')
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

async function compact() {
  if (!activeSession.value) {
    return
  }
  try {
    await compactSession(props.agentId, activeSession.value.session_id)
    ElMessage.success('会话已压缩')
    detail.value = await getSession(activeSession.value.session_id)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <div class="card-title">会话记录</div>
        <div class="card-desc">共 {{ total }} 条，点击行查看历史与运行记录</div>
      </div>
      <el-button size="small" @click="load">刷新</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" style="width: 100%" @row-click="open">
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
      <el-table-column label="用户" width="140">
        <template #default="{ row }">{{ row.user_id || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click.stop="rename(row)">重命名</el-button>
          <el-button size="small" link type="danger" @click.stop="remove(row)">删除</el-button>
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

    <el-drawer v-model="drawer" title="会话详情" size="680px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div style="display: flex; gap: 8px; margin-bottom: 16px">
            <el-button size="small" @click="fork">分叉</el-button>
            <el-button size="small" @click="compact">压缩</el-button>
            <el-button v-if="activeSession" size="small" type="danger" plain @click="remove(activeSession)">
              删除
            </el-button>
          </div>

          <div class="detail-block">
            <h4>概览</h4>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="Session ID">{{ detail.session_id }}</el-descriptions-item>
              <el-descriptions-item label="Agent">{{ detail.agent_id || props.agentId }}</el-descriptions-item>
              <el-descriptions-item label="总 Token">{{ detail.total_tokens ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ formatTime(detail.created_at) }}</el-descriptions-item>
            </el-descriptions>
          </div>

          <div class="detail-block">
            <h4>运行记录</h4>
            <el-table :data="runs" size="small" style="width: 100%">
              <el-table-column label="Run" min-width="220">
                <template #default="{ row }">
                  <span class="session-id">{{ row.run_id || row.id || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">{{ row.status || '-' }}</template>
              </el-table-column>
              <el-table-column label="时间" width="170">
                <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button
                    v-if="['RUNNING', 'PENDING', 'IN_PROGRESS'].includes(row.status)"
                    size="small"
                    link
                    type="danger"
                    @click="cancelRun(row)"
                  >
                    取消
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!runs.length" description="暂无运行记录" />
          </div>

          <div class="detail-block">
            <h4>对话历史</h4>
            <div v-for="(item, index) in detail.chat_history || []" :key="index" class="history-item">
              <div class="history-role">{{ item.role }}</div>
              <div class="history-content">{{ formatContent(item.content) }}</div>
            </div>
            <el-empty v-if="!(detail.chat_history || []).length" description="暂无对话历史" />
          </div>
        </template>
      </div>
    </el-drawer>
  </el-card>
</template>
