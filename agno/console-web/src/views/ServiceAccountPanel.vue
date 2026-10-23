<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createServiceAccount,
  listServiceAccounts,
  revokeServiceAccount,
  type ServiceAccountRow,
} from '../api'

const rows = ref<ServiceAccountRow[]>([])
const loading = ref(false)

const createDialog = ref(false)
const saving = ref(false)
const form = reactive({
  name: '',
  scopesText: 'agents:read, agents:*:run',
  days: 30,
  never: false,
})

const tokenDialog = ref(false)
const newToken = ref('')

async function load() {
  loading.value = true
  try {
    const result = await listServiceAccounts()
    rows.value = result.data || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.name = ''
  form.scopesText = 'agents:read, agents:*:run'
  form.days = 30
  form.never = false
  createDialog.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  const scopes = form.scopesText
    .split(/[,\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .map((scope) => ({ scope, effect: 'allow' as const }))
  saving.value = true
  try {
    const result = await createServiceAccount({
      name: form.name.trim(),
      scopes,
      expires_in_days: form.never ? null : form.days,
      never_expires: form.never,
      allow_privileged_scopes: true,
    })
    newToken.value = result.token
    createDialog.value = false
    tokenDialog.value = true
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    saving.value = false
  }
}

async function copyToken() {
  try {
    await navigator.clipboard.writeText(newToken.value)
    ElMessage.success('令牌已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

async function revoke(row: ServiceAccountRow) {
  try {
    await ElMessageBox.confirm(`确认撤销 ${row.name || row.id}？撤销后无法恢复。`, '撤销服务账号', { type: 'warning' })
  } catch {
    return
  }
  try {
    await revokeServiceAccount(row.id)
    ElMessage.success('已撤销')
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

function formatTime(value?: number | null) {
  if (!value) {
    return '-'
  }
  const date = new Date(value * 1000)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

function statusOf(row: ServiceAccountRow) {
  if (row.revoked_at) {
    return { text: '已撤销', type: 'info' as const }
  }
  if (row.expires_at && row.expires_at * 1000 < Date.now()) {
    return { text: '已过期', type: 'danger' as const }
  }
  return { text: '有效', type: 'success' as const }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <div class="card-title">服务账号</div>
        <div class="card-desc">给程序或第三方系统发放的长期令牌，按 scope 授权，可随时撤销</div>
      </div>
      <el-button type="primary" size="small" @click="openCreate">新建服务账号</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" style="width: 100%">
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <div class="session-name">{{ row.name || '未命名' }}</div>
          <div class="session-id">{{ row.principal || row.id }}</div>
        </template>
      </el-table-column>
      <el-table-column label="Scope" min-width="280">
        <template #default="{ row }">
          <div class="scope-line">{{ (row.scopes || []).join(', ') || '（无）' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="令牌前缀" width="140">
        <template #default="{ row }">
          <span class="session-id">{{ row.token_prefix || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="过期时间" width="180">
        <template #default="{ row }">{{ formatTime(row.expires_at) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusOf(row).type" effect="light">{{ statusOf(row).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button size="small" link type="danger" :disabled="Boolean(row.revoked_at)" @click="revoke(row)">
            撤销
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createDialog" title="新建服务账号" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如 erp-sync" />
        </el-form-item>
        <el-form-item label="Scope">
          <el-input v-model="form.scopesText" type="textarea" :rows="3" />
          <div class="dialog-hint">逗号或换行分隔，例如 agents:read、agents:*:run</div>
        </el-form-item>
        <el-form-item label="有效期">
          <el-input-number v-model="form.days" :min="1" :max="3650" :disabled="form.never" />
          <span class="hint" style="margin-left: 8px">天</span>
          <el-checkbox v-model="form.never" style="margin-left: 16px">永不过期</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="tokenDialog" title="服务账号令牌" width="640px">
      <el-alert type="warning" :closable="false" show-icon title="该令牌只显示一次，请立即保存" />
      <pre class="audit-json" style="max-height: 120px; overflow: auto; margin-top: 12px">{{ newToken }}</pre>
      <template #footer>
        <el-button @click="tokenDialog = false">关闭</el-button>
        <el-button type="primary" @click="copyToken">复制令牌</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
