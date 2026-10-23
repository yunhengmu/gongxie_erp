<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createLibraryTool,
  deleteLibraryTool,
  listLibraryTools,
  testLibraryTool,
  updateLibraryTool,
  type LibraryTool,
  type LibraryToolParam,
} from '../api'

const rows = ref<LibraryTool[]>([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const editId = ref('')

const testDialog = ref(false)
const testRow = ref<LibraryTool | null>(null)
const testArgs = ref('{}')
const testOutput = ref('')
const testOk = ref(true)
const testing = ref(false)

const methodOptions = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const typeOptions = [
  { value: 'string', label: '文本' },
  { value: 'integer', label: '整数' },
  { value: 'number', label: '小数' },
  { value: 'boolean', label: '布尔' },
]

const form = reactive({
  name: '',
  label: '',
  description: '',
  method: 'GET',
  url: '',
  headers: '{}',
  body: '',
  parameters: [] as LibraryToolParam[],
  enabled: true,
})

async function load() {
  loading.value = true
  try {
    rows.value = (await listLibraryTools()).data || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.name = ''
  form.label = ''
  form.description = ''
  form.method = 'GET'
  form.url = ''
  form.headers = '{}'
  form.body = ''
  form.parameters = []
  form.enabled = true
}

function openCreate() {
  editId.value = ''
  resetForm()
  dialog.value = true
}

function openEdit(row: LibraryTool) {
  editId.value = row.id
  form.name = row.name
  form.label = row.label || ''
  form.description = row.description || ''
  form.method = row.method || 'GET'
  form.url = row.url || ''
  form.headers = JSON.stringify(row.headers || {}, null, 2)
  form.body = row.body || ''
  form.parameters = (row.parameters || []).map((item) => ({ ...item }))
  form.enabled = row.enabled
  dialog.value = true
}

function addParam() {
  form.parameters.push({ name: '', type: 'string', description: '', required: true })
}

async function submit() {
  const name = form.name.trim()
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
    ElMessage.warning('工具名必须是合法函数名：字母或下划线开头，仅含字母、数字、下划线')
    return
  }
  if (!form.url.trim()) {
    ElMessage.warning('请填写 URL')
    return
  }
  let headers: Record<string, string>
  try {
    headers = form.headers.trim() ? JSON.parse(form.headers) : {}
  } catch {
    ElMessage.error('Headers 必须是合法 JSON 对象')
    return
  }
  const invalid = form.parameters.find((item) => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(item.name.trim()))
  if (invalid) {
    ElMessage.warning(`参数名不合法：${invalid.name || '空'}`)
    return
  }

  const payload = {
    name,
    label: form.label.trim(),
    description: form.description.trim(),
    method: form.method,
    url: form.url.trim(),
    headers,
    body: form.body,
    parameters: form.parameters.map((item) => ({
      name: item.name.trim(),
      type: item.type,
      description: item.description.trim(),
      required: item.required,
    })),
    enabled: form.enabled,
  }

  saving.value = true
  try {
    if (editId.value) {
      await updateLibraryTool(editId.value, payload)
      ElMessage.success('已保存')
    } else {
      await createLibraryTool(payload)
      ElMessage.success('工具已创建')
    }
    dialog.value = false
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    saving.value = false
  }
}

async function remove(row: LibraryTool) {
  try {
    await ElMessageBox.confirm(`确认删除工具 ${row.name}？已挂载该工具的 Agent 需重新发布。`, '删除工具', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteLibraryTool(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

function openTest(row: LibraryTool) {
  testRow.value = row
  testArgs.value = '{}'
  testOutput.value = ''
  testOk.value = true
  testDialog.value = true
}

async function runTest() {
  if (!testRow.value) {
    return
  }
  let args: Record<string, any>
  try {
    args = testArgs.value.trim() ? JSON.parse(testArgs.value) : {}
  } catch {
    ElMessage.error('参数必须是合法 JSON 对象')
    return
  }
  testing.value = true
  try {
    const result = await testLibraryTool(testRow.value.id, args)
    testOk.value = result.ok
    testOutput.value = result.ok
      ? result.output || '（空响应）'
      : `${result.message || '调用失败'}${result.status ? `（HTTP ${result.status}）` : ''}`
  } catch (err) {
    testOk.value = false
    testOutput.value = err instanceof Error ? err.message : String(err)
  } finally {
    testing.value = false
  }
}

function paramNames(row: LibraryTool) {
  return (row.parameters || []).map((item) => item.name).filter(Boolean).join(', ') || '-'
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <div class="card-title">工具库</div>
        <div class="card-desc">配置 HTTP 工具，供 Agent 挂载；URL、Headers、Body 支持 {参数名} 占位符</div>
      </div>
      <div style="display: flex; gap: 8px">
        <el-button type="primary" size="small" @click="openCreate">新增工具</el-button>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="工具名会成为模型看到的函数名；描述决定模型何时调用，请写清用途和参数含义。"
      style="margin-bottom: 14px"
    />

    <el-table :data="rows" v-loading="loading" style="width: 100%">
      <el-table-column label="工具" min-width="180">
        <template #default="{ row }">
          <div class="session-name">{{ row.label || row.name }}</div>
          <div class="session-id">{{ row.name }}</div>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="220">
        <template #default="{ row }">
          <span class="binding-intro">{{ row.description || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="请求" min-width="240">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.method }}</el-tag>
          <span class="session-id" style="margin-left: 6px">{{ row.url }}</span>
        </template>
      </el-table-column>
      <el-table-column label="参数" width="170">
        <template #default="{ row }">
          <span class="session-id">{{ paramNames(row) }}</span>
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
          <el-button size="small" link type="primary" @click="openTest(row)">测试</el-button>
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" description="还没有工具，点击右上角新增" />

    <el-dialog v-model="dialog" :title="editId ? '编辑工具' : '新增工具'" width="680px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="工具名">
          <el-input v-model="form.name" placeholder="例如 query_weather，模型看到的函数名" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="form.label" placeholder="例如 天气查询" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="告诉模型这个工具做什么、何时调用，例如：查询指定城市的实时天气"
          />
        </el-form-item>
        <el-form-item label="请求方法">
          <el-select v-model="form.method" style="width: 160px">
            <el-option v-for="item in methodOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="URL">
          <el-input v-model="form.url" placeholder="https://api.example.com/weather?city={city}" />
        </el-form-item>
        <el-form-item label="Headers">
          <el-input v-model="form.headers" type="textarea" :rows="3" placeholder='{"Authorization": "Bearer sk-..."}' />
        </el-form-item>
        <el-form-item label="Body">
          <el-input v-model="form.body" type="textarea" :rows="3" placeholder='{"city": "{city}"}；GET 请求可留空' />
        </el-form-item>
        <el-form-item label="参数">
          <div style="width: 100%">
            <div v-for="(param, index) in form.parameters" :key="index" class="param-row">
              <el-input v-model="param.name" placeholder="参数名" style="width: 130px" />
              <el-select v-model="param.type" style="width: 100px">
                <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <el-input v-model="param.description" placeholder="参数说明" style="flex: 1" />
              <el-checkbox v-model="param.required">必填</el-checkbox>
              <el-button link type="danger" @click="form.parameters.splice(index, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="addParam">添加参数</el-button>
          </div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">URL / Body / Header 中的 {参数名} 会在调用时替换成模型传入的值</div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testDialog" :title="`测试 ${testRow?.name || ''}`" width="560px">
      <el-form label-width="80px">
        <el-form-item label="参数 JSON">
          <el-input v-model="testArgs" type="textarea" :rows="4" placeholder='{"city": "北京"}' />
        </el-form-item>
      </el-form>
      <pre v-if="testOutput" class="tool-output" :class="{ failed: !testOk }">{{ testOutput }}</pre>
      <div class="dialog-hint">测试直接请求目标地址，不经过模型</div>
      <template #footer>
        <el-button @click="testDialog = false">关闭</el-button>
        <el-button type="primary" :loading="testing" @click="runTest">运行</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
