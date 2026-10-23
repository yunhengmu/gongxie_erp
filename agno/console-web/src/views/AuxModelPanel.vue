<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createAuxModel,
  deleteAuxModel,
  listAuxModels,
  testAuxModel,
  updateAuxModel,
  type AuxModel,
  type AuxProtocols,
} from '../api'

const rows = ref<AuxModel[]>([])
const protocols = ref<AuxProtocols>({})
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const editId = ref('')
const testingId = ref('')
const testResults = ref<Record<string, { ok: boolean; message: string }>>({})

const kindOptions: { value: AuxModel['kind']; label: string }[] = [
  { value: 'embedding', label: '向量 Embedding' },
  { value: 'rerank', label: '重排 Rerank' },
  { value: 'tts', label: '语音合成 TTS' },
  { value: 'stt', label: '语音转写 STT' },
]

const form = reactive({
  name: '',
  kind: 'embedding' as AuxModel['kind'],
  protocol: 'openai-compatible',
  base_url: '',
  model: '',
  api_key: '',
  extra: '{}',
  enabled: true,
})

const protocolOptions = computed(() => protocols.value[form.kind] || [])

watch(
  () => form.kind,
  () => {
    const options = protocols.value[form.kind] || []
    if (!options.some((item) => item.key === form.protocol)) {
      form.protocol = options[0]?.key || 'custom'
    }
  },
)

function kindLabel(kind: AuxModel['kind']) {
  return kindOptions.find((item) => item.value === kind)?.label || kind
}

function kindTag(kind: AuxModel['kind']) {
  if (kind === 'embedding') {
    return 'info'
  }
  if (kind === 'rerank') {
    return 'warning'
  }
  if (kind === 'tts') {
    return 'success'
  }
  return 'primary'
}

async function load() {
  loading.value = true
  try {
    const result = await listAuxModels()
    rows.value = result.data || []
    protocols.value = result.protocols || {}
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editId.value = ''
  form.name = ''
  form.kind = 'embedding'
  form.protocol = protocols.value.embedding?.[0]?.key || 'openai-compatible'
  form.base_url = ''
  form.model = ''
  form.api_key = ''
  form.extra = '{}'
  form.enabled = true
  dialog.value = true
}

function openEdit(row: AuxModel) {
  editId.value = row.id
  form.name = row.name
  form.kind = row.kind
  form.protocol = row.protocol
  form.base_url = row.base_url || ''
  form.model = row.model || ''
  form.api_key = ''
  form.extra = JSON.stringify(row.extra || {}, null, 2)
  form.enabled = row.enabled
  dialog.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  let extra: Record<string, any>
  try {
    extra = form.extra.trim() ? JSON.parse(form.extra) : {}
  } catch {
    ElMessage.error('扩展参数必须是合法 JSON 对象')
    return
  }
  const payload: Record<string, any> = {
    name: form.name.trim(),
    kind: form.kind,
    protocol: form.protocol,
    base_url: form.base_url.trim(),
    model: form.model.trim(),
    extra,
    enabled: form.enabled,
  }
  if (form.api_key.trim()) {
    payload.api_key = form.api_key.trim()
  }
  saving.value = true
  try {
    if (editId.value) {
      await updateAuxModel(editId.value, payload)
      ElMessage.success('已保存')
    } else {
      await createAuxModel(payload)
      ElMessage.success('已新增')
    }
    dialog.value = false
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    saving.value = false
  }
}

async function runTest(row: AuxModel) {
  testingId.value = row.id
  try {
    const result = await testAuxModel(row.id)
    testResults.value = { ...testResults.value, [row.id]: result }
  } catch (err) {
    testResults.value = {
      ...testResults.value,
      [row.id]: { ok: false, message: err instanceof Error ? err.message : String(err) },
    }
  } finally {
    testingId.value = ''
  }
}

async function remove(row: AuxModel) {
  try {
    await ElMessageBox.confirm(`确认删除辅助模型 ${row.name}？`, '删除', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteAuxModel(row.id)
    ElMessage.success('已删除')
    await load()
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
        <div class="card-title">辅助模型</div>
        <div class="card-desc">自行配置 Embedding / Rerank / TTS / STT，不绑定固定厂商</div>
      </div>
      <div style="display: flex; gap: 8px">
        <el-button type="primary" size="small" @click="openCreate">新增辅助模型</el-button>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="每条记录由你指定类型、协议、地址、模型和 Key。协议选“仅保存配置”时只存储不测试。"
      style="margin-bottom: 14px"
    />

    <el-table :data="rows" v-loading="loading" style="width: 100%">
      <el-table-column label="名称" min-width="160">
        <template #default="{ row }">
          <div class="session-name">{{ row.name }}</div>
          <div class="session-id">{{ row.id }}</div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="150">
        <template #default="{ row }">
          <el-tag size="small" :type="kindTag(row.kind)" effect="light">{{ kindLabel(row.kind) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="协议" width="200">
        <template #default="{ row }">{{ row.protocol }}</template>
      </el-table-column>
      <el-table-column label="模型" width="180">
        <template #default="{ row }">{{ row.model || '-' }}</template>
      </el-table-column>
      <el-table-column label="地址" min-width="220">
        <template #default="{ row }">
          <span class="session-id">{{ row.base_url || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最近测试" min-width="220">
        <template #default="{ row }">
          <el-tag
            v-if="testResults[row.id]"
            size="small"
            :type="testResults[row.id].ok ? 'success' : 'danger'"
            effect="plain"
          >
            {{ testResults[row.id].message }}
          </el-tag>
          <span v-else class="session-id">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="light">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" :loading="testingId === row.id" @click="runTest(row)">测试</el-button>
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" description="还没有辅助模型，点击右上角新增" />

    <el-dialog v-model="dialog" :title="editId ? '编辑辅助模型' : '新增辅助模型'" width="640px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如 生产 Embedding / 客服语音合成" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.kind" style="width: 100%">
            <el-option v-for="item in kindOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="协议">
          <el-select v-model="form.protocol" style="width: 100%">
            <el-option v-for="item in protocolOptions" :key="item.key" :label="item.label" :value="item.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.base_url" placeholder="https://api.openai.com/v1 或 http://localhost:11434" />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="form.model" placeholder="text-embedding-3-small / bge-m3 / tts-1" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.api_key"
            type="password"
            show-password
            :placeholder="editId ? '留空则保持原 Key' : '可留空，用于本地服务'"
          />
        </el-form-item>
        <el-form-item label="扩展参数">
          <el-input
            v-model="form.extra"
            type="textarea"
            :rows="4"
            placeholder='例如 {"voice": "alloy"} 或 {"dimensions": 1024}'
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">
        Embedding 测试会请求 /embeddings；Rerank 请求 /v1/rerank；TTS 请求 /audio/speech；STT 需音频文件，暂不提供在线测试。
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
