<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createComponent,
  deleteComponent,
  getComponentDefinition,
  getComponents,
  getComponentVersions,
  getProviders,
  listAuxModels,
  listLibraryTools,
  publishComponentVersion,
  updateComponent,
  type AuxModel,
  type ComponentVersion,
  type ConsoleComponents,
  type LibraryTool,
  type ProviderSpec,
} from '../api'

type Kind = 'agent' | 'team' | 'workflow'

const tab = ref<'agents' | 'teams' | 'workflows'>('agents')
const data = ref<ConsoleComponents>({ agents: [], teams: [], workflows: [] })
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const editMode = ref(false)
const editKind = ref<Kind>('agent')
const editId = ref('')

const versionDrawer = ref(false)
const versionLoading = ref(false)
const versions = ref<ComponentVersion[]>([])
const currentVersion = ref<number | undefined>(undefined)
const versionKind = ref<Kind>('agent')
const versionId = ref('')

const providers = ref<ProviderSpec[]>([])
const auxModels = ref<AuxModel[]>([])
const libraryTools = ref<LibraryTool[]>([])
const enabledAux = computed(() => auxModels.value.filter((item) => item.enabled))
const enabledTools = computed(() => libraryTools.value.filter((item) => item.enabled))
const currentFields = computed(
  () => providers.value.find((item) => item.key === form.provider)?.fields || ['model_id', 'api_key'],
)

const bindingDialog = ref(false)
const bindingIndex = ref(-1)
const bindingForm = reactive({ id: '', intro: '' })

const kindLabels: Record<AuxModel['kind'], string> = {
  embedding: 'Embedding',
  rerank: 'Rerank',
  tts: 'TTS',
  stt: 'STT',
}

function auxModelOf(id: string) {
  return auxModels.value.find((item) => item.id === id)
}

function auxLabel(id: string) {
  const item = auxModelOf(id)
  return item ? `${kindLabels[item.kind]} · ${item.name}` : id
}

function openBindingCreate() {
  bindingIndex.value = -1
  bindingForm.id = ''
  bindingForm.intro = ''
  bindingDialog.value = true
}

function openBindingEdit(index: number) {
  const binding = form.aux_bindings[index]
  bindingIndex.value = index
  bindingForm.id = binding.id
  bindingForm.intro = binding.intro
  bindingDialog.value = true
}

function saveBinding() {
  if (!bindingForm.id) {
    ElMessage.warning('请选择辅助模型')
    return
  }
  if (!bindingForm.intro.trim()) {
    ElMessage.warning('请填写介绍')
    return
  }
  const entry = { id: bindingForm.id, intro: bindingForm.intro.trim() }
  if (bindingIndex.value >= 0) {
    form.aux_bindings[bindingIndex.value] = entry
  } else if (form.aux_bindings.some((item) => item.id === entry.id)) {
    ElMessage.warning('该辅助模型已添加')
    return
  } else {
    form.aux_bindings.push(entry)
  }
  bindingDialog.value = false
}

function removeBinding(index: number) {
  form.aux_bindings.splice(index, 1)
}

const form = reactive({
  id: '',
  name: '',
  description: '',
  provider: 'openai',
  model_id: 'gpt-4o-mini',
  base_url: '',
  host: '',
  api_key: '',
  temperature: 0.7,
  instructions: '',
  member_ids: [] as string[],
  step_ids: [] as string[],
  aux_bindings: [] as { id: string; intro: string }[],
  tool_ids: [] as string[],
})

function kindOfTab(): Kind {
  return tab.value === 'agents' ? 'agent' : tab.value === 'teams' ? 'team' : 'workflow'
}

function labelOfKind(kind: Kind) {
  return kind === 'agent' ? 'Agent' : kind === 'team' ? '团队' : '工作流'
}

async function load() {
  loading.value = true
  try {
    data.value = await getComponents()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.id = ''
  form.name = ''
  form.description = ''
  form.provider = 'openai'
  form.model_id = 'gpt-4o-mini'
  form.base_url = ''
  form.host = ''
  form.api_key = ''
  form.temperature = 0.7
  form.instructions = ''
  form.member_ids = []
  form.step_ids = []
  form.aux_bindings = []
  form.tool_ids = []
}

function openCreate() {
  editMode.value = false
  resetForm()
  dialog.value = true
}

async function openEdit(kind: Kind, id: string) {
  try {
    const definition = await getComponentDefinition(kind, id)
    editMode.value = true
    editKind.value = kind
    editId.value = id
    form.id = definition.id || id
    form.name = definition.name || ''
    form.description = definition.description || ''
    form.provider = definition.provider || 'openai'
    form.model_id = definition.model_id || 'gpt-4o-mini'
    form.base_url = definition.base_url || ''
    form.host = definition.host || ''
    form.api_key = definition.api_key || ''
    form.temperature = definition.temperature ?? 0.7
    form.instructions = definition.instructions || ''
    form.member_ids = definition.member_ids || []
    form.step_ids = definition.step_ids || []
    form.aux_bindings = (definition.aux_bindings || []).map((item: { id: string; intro: string }) => ({
      id: item.id,
      intro: item.intro || '',
    }))
    form.tool_ids = definition.tool_ids || []
    dialog.value = true
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

async function submit() {
  const kind = editMode.value ? editKind.value : kindOfTab()
  if (!form.id.trim()) {
    ElMessage.warning('请填写组件 ID')
    return
  }
  if (kind === 'team' && !form.member_ids.length) {
    ElMessage.warning('团队至少选择一个 Agent 成员')
    return
  }
  if (kind === 'workflow' && !form.step_ids.length) {
    ElMessage.warning('工作流至少选择一个 Agent 步骤')
    return
  }

  const payload = {
    kind,
    id: form.id.trim(),
    name: form.name.trim() || undefined,
    description: form.description.trim() || undefined,
    provider: form.provider,
    model_id: form.model_id.trim() || undefined,
    base_url: form.base_url.trim(),
    host: form.host.trim(),
    api_key: form.api_key.trim() || undefined,
    temperature: form.temperature,
    instructions: form.instructions,
    member_ids: form.member_ids,
    step_ids: form.step_ids,
    aux_bindings: form.aux_bindings,
    tool_ids: form.tool_ids,
  }

  saving.value = true
  try {
    if (editMode.value) {
      await updateComponent(kind, editId.value, payload)
      ElMessage.success('已保存为新版本并发布')
    } else {
      await createComponent(payload)
      ElMessage.success('组件已创建并注册到 AgentOS')
    }
    dialog.value = false
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    saving.value = false
  }
}

async function openVersions(kind: Kind, id: string) {
  versionKind.value = kind
  versionId.value = id
  versionDrawer.value = true
  versionLoading.value = true
  versions.value = []
  try {
    const result = await getComponentVersions(kind, id)
    versions.value = result.versions || []
    currentVersion.value = result.current
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    versionLoading.value = false
  }
}

async function publish(version: number) {
  try {
    await ElMessageBox.confirm(`确认回滚到版本 v${version} 并立即生效？`, '发布版本', { type: 'warning' })
  } catch {
    return
  }
  try {
    await publishComponentVersion(versionKind.value, versionId.value, version)
    ElMessage.success(`已回滚到 v${version}`)
    await openVersions(versionKind.value, versionId.value)
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

async function remove(kind: Kind, id: string) {
  try {
    await ElMessageBox.confirm(`确认删除 ${id}？`, '删除组件', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteComponent(kind, id)
    ElMessage.success('已删除')
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

onMounted(async () => {
  try {
    providers.value = (await getProviders()).data || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
  try {
    auxModels.value = (await listAuxModels()).data || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
  try {
    libraryTools.value = (await listLibraryTools()).data || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
  await load()
})
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <div class="card-title">组件管理</div>
        <div class="card-desc">创建或编辑 Agent / Team / Workflow，保存即发布为新版本，可回滚</div>
      </div>
      <el-button type="primary" size="small" @click="openCreate">
        新建{{ labelOfKind(kindOfTab()) }}
      </el-button>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="智能体" name="agents">
        <el-table :data="data.agents" v-loading="loading" style="width: 100%">
          <el-table-column label="Agent" min-width="200">
            <template #default="{ row }">
              <div class="session-name">{{ row.name || row.id }}</div>
              <div class="session-id">{{ row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column label="模型" width="160">
            <template #default="{ row }">{{ row.model || '-' }}</template>
          </el-table-column>
          <el-table-column label="适配器" width="150">
            <template #default="{ row }">{{ row.provider || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <template v-if="row.id === 'assistant'">
                <el-tag size="small" type="info" effect="plain">主 Agent</el-tag>
                <span class="hint" style="margin-left: 6px">在参数调节中修改</span>
              </template>
              <template v-else>
                <el-button size="small" link type="primary" @click="openEdit('agent', row.id)">编辑</el-button>
                <el-button size="small" link type="primary" @click="openVersions('agent', row.id)">版本</el-button>
                <el-button size="small" link type="danger" @click="remove('agent', row.id)">删除</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="团队" name="teams">
        <el-table :data="data.teams" v-loading="loading" style="width: 100%">
          <el-table-column label="团队" min-width="200">
            <template #default="{ row }">
              <div class="session-name">{{ row.name || row.id }}</div>
              <div class="session-id">{{ row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column label="成员" min-width="220">
            <template #default="{ row }">
              <span class="scope-line">{{ (row.member_ids || []).join(', ') || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openEdit('team', row.id)">编辑</el-button>
              <el-button size="small" link type="primary" @click="openVersions('team', row.id)">版本</el-button>
              <el-button size="small" link type="danger" @click="remove('team', row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="工作流" name="workflows">
        <el-table :data="data.workflows" v-loading="loading" style="width: 100%">
          <el-table-column label="工作流" min-width="200">
            <template #default="{ row }">
              <div class="session-name">{{ row.name || row.id }}</div>
              <div class="session-id">{{ row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column label="步骤数" width="100">
            <template #default="{ row }">{{ row.step_count }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openEdit('workflow', row.id)">编辑</el-button>
              <el-button size="small" link type="primary" @click="openVersions('workflow', row.id)">版本</el-button>
              <el-button size="small" link type="danger" @click="remove('workflow', row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="dialog"
      :title="editMode ? '编辑 ' + labelOfKind(editKind) : '新建' + labelOfKind(kindOfTab())"
      width="620px"
    >
      <el-form :model="form" label-width="110px">
        <el-form-item label="ID">
          <el-input v-model="form.id" :disabled="editMode" placeholder="唯一标识，例如 support-agent" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="显示名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>

        <template v-if="(editMode ? editKind : kindOfTab()) === 'agent'">
          <el-form-item label="模型来源">
            <el-select v-model="form.provider" style="width: 100%">
              <el-option
                v-for="item in providers"
                :key="item.key"
                :label="item.available ? item.label : item.label + '（不可用）'"
                :value="item.key"
                :disabled="!item.available"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="模型 ID">
            <el-input v-model="form.model_id" placeholder="gpt-4o-mini" />
          </el-form-item>
          <el-form-item v-if="currentFields.includes('base_url')" label="Base URL">
            <el-input v-model="form.base_url" placeholder="https://your-gateway.example.com/v1" />
          </el-form-item>
          <el-form-item v-if="currentFields.includes('host')" label="Host">
            <el-input v-model="form.host" placeholder="http://localhost:11434" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="form.api_key" type="password" show-password placeholder="留空则用全局 Key" />
          </el-form-item>
          <el-form-item label="Temperature">
            <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" show-input style="max-width: 420px" />
          </el-form-item>
          <el-form-item label="Instructions">
            <el-input v-model="form.instructions" type="textarea" :rows="5" />
          </el-form-item>
          <el-form-item label="辅助模型">
            <div class="binding-list">
              <div v-for="(binding, index) in form.aux_bindings" :key="binding.id" class="binding-card">
                <div class="binding-head">
                  <el-tag size="small" effect="light">{{ auxLabel(binding.id) }}</el-tag>
                  <div class="binding-actions">
                    <el-button size="small" link type="primary" @click="openBindingEdit(index)">编辑</el-button>
                    <el-button size="small" link type="danger" @click="removeBinding(index)">移除</el-button>
                  </div>
                </div>
                <div class="binding-intro">{{ binding.intro }}</div>
              </div>
              <el-button size="small" @click="openBindingCreate">新建辅助模型</el-button>
              <div class="dialog-hint">可绑定任意类型的辅助模型；填写的介绍会写入该 Agent 的 instructions，模型据此判断何时调用</div>
            </div>
          </el-form-item>
          <el-form-item label="工具">
            <el-select v-model="form.tool_ids" multiple style="width: 100%" placeholder="从工具库选择工具，可多选">
              <el-option
                v-for="item in enabledTools"
                :key="item.id"
                :label="`${item.name} · ${item.label || item.description || ''}`"
                :value="item.id"
              />
            </el-select>
            <div class="dialog-hint">工具由「资源 → 工具库」维护，保存后自动挂载到该 Agent</div>
          </el-form-item>
        </template>

        <el-form-item v-else-if="(editMode ? editKind : kindOfTab()) === 'team'" label="成员">
          <el-select v-model="form.member_ids" multiple style="width: 100%" placeholder="选择已存在的 Agent">
            <el-option v-for="agent in data.agents" :key="agent.id" :label="agent.name || agent.id" :value="agent.id" />
          </el-select>
        </el-form-item>

        <el-form-item v-else label="步骤">
          <el-select v-model="form.step_ids" multiple style="width: 100%" placeholder="按顺序选择 Agent，每个 Agent 一步">
            <el-option v-for="agent in data.agents" :key="agent.id" :label="agent.name || agent.id" :value="agent.id" />
          </el-select>
        </el-form-item>
      </el-form>

      <div class="dialog-hint">
        {{ editMode ? '保存后会生成新版本并立即发布，可在“版本”里回滚。' : '创建后立即注册到 AgentOS，可在权限页按资源授权。' }}
      </div>

      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ editMode ? '保存并发布' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="bindingDialog"
      :title="bindingIndex >= 0 ? '编辑辅助模型介绍' : '新建辅助模型'"
      width="520px"
      append-to-body
    >
      <el-form :model="bindingForm" label-width="90px">
        <el-form-item label="辅助模型">
          <el-select
            v-model="bindingForm.id"
            :disabled="bindingIndex >= 0"
            style="width: 100%"
            placeholder="选择已启用的辅助模型"
          >
            <el-option
              v-for="item in enabledAux"
              :key="item.id"
              :label="`${kindLabels[item.kind]} · ${item.name}`"
              :value="item.id"
            />
          </el-select>
          <div v-if="!enabledAux.length" class="dialog-hint">暂无启用的辅助模型，可先到「辅助模型」页新增</div>
        </el-form-item>
        <el-form-item label="介绍">
          <el-input
            v-model="bindingForm.intro"
            type="textarea"
            :rows="4"
            placeholder="说明用途与触发时机，例如：用户要求朗读或配音时，用它把回复合成语音"
          />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">介绍会随绑定一起保存，并在构建 Agent 时拼进 instructions</div>
      <template #footer>
        <el-button @click="bindingDialog = false">取消</el-button>
        <el-button type="primary" @click="saveBinding">确定</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="versionDrawer" :title="versionId + ' 的版本历史'" size="620px">
      <div v-loading="versionLoading">
        <el-table :data="versions" style="width: 100%">
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="200">
            <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.version === currentVersion" size="small" type="success" effect="light">当前</el-tag>
              <span v-else class="hint">历史</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button
                size="small"
                link
                type="primary"
                :disabled="row.version === currentVersion"
                @click="publish(row.version)"
              >
                回滚到此版本
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!versions.length" description="暂无版本记录" />
      </div>
    </el-drawer>
  </el-card>
</template>
