<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  assignRole,
  createAccount,
  createRole,
  createUser,
  deleteRole,
  deleteUser,
  listAgents,
  listAudit,
  listRoles,
  listTeams,
  listUsers,
  listWorkflows,
  setRoleScopes,
  unassignRole,
  updateUser,
  type RoleRow,
  type UserRow,
} from '../api'

const active = ref('roles')
const roles = ref<RoleRow[]>([])
const users = ref<UserRow[]>([])
const audit = ref<Record<string, any>[]>([])
const loading = ref(false)

const roleDialog = ref(false)
const roleForm = reactive({ slug: '', name: '', description: '' })
const roleSaving = ref(false)

const scopeDialog = ref(false)
const scopeTarget = ref<RoleRow | null>(null)
const scopeChecked = ref<Record<string, boolean>>({})
const extraScopes = ref('')
const scopeSaving = ref(false)
const resourceLoading = ref(false)
const agents = ref<{ id: string; name?: string }[]>([])
const teams = ref<any[]>([])
const workflows = ref<any[]>([])

const GLOBAL_SCOPES = [
  { value: 'config:read', label: '读取配置' },
  { value: 'sessions:read', label: '查看会话' },
  { value: 'sessions:write', label: '写入会话' },
  { value: 'metrics:read', label: '查看指标' },
  { value: 'traces:read', label: '查看追踪' },
  { value: 'knowledge:read', label: '查看知识库' },
  { value: 'knowledge:write', label: '管理知识库' },
  { value: 'service_accounts:read', label: '查看服务账号' },
  { value: 'service_accounts:write', label: '创建服务账号' },
  { value: 'agent_os:admin', label: '超级管理员（全部权限）' },
]

const assignDialog = ref(false)
const assignTarget = ref<UserRow | null>(null)
const assignSlug = ref('')
const assignSaving = ref(false)

const userDialog = ref(false)
const userForm = reactive({ id: '', name: '', email: '', password: '' })
const userSaving = ref(false)

async function loadRoles() {
  const result = await listRoles()
  roles.value = result.data || []
}

async function loadUsers() {
  const result = await listUsers()
  users.value = result.data || []
}

async function loadAudit() {
  const result = await listAudit()
  audit.value = result.data || []
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadRoles(), loadUsers(), loadAudit()])
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}

function openCreateRole() {
  roleForm.slug = ''
  roleForm.name = ''
  roleForm.description = ''
  roleDialog.value = true
}

async function submitRole() {
  if (!roleForm.slug.trim()) {
    ElMessage.warning('请填写角色标识')
    return
  }
  roleSaving.value = true
  try {
    await createRole({
      slug: roleForm.slug.trim(),
      name: roleForm.name.trim() || undefined,
      description: roleForm.description.trim() || undefined,
    })
    ElMessage.success('角色已创建')
    roleDialog.value = false
    await loadRoles()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    roleSaving.value = false
  }
}

async function loadResources() {
  if (resourceLoading.value) {
    return
  }
  resourceLoading.value = true
  try {
    const [agentList, teamList, workflowList] = await Promise.all([listAgents(), listTeams(), listWorkflows()])
    agents.value = (agentList as any[]) || []
    teams.value = teamList.data || []
    workflows.value = workflowList.data || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    resourceLoading.value = false
  }
}

function scopeKey(type: 'agents' | 'teams' | 'workflows', id: string, action: 'read' | 'run') {
  return `${type}:${id}:${action}`
}

const agentRows = computed(() => [{ id: '*', name: '全部 Agent（含未来新建）' }, ...agents.value])
const teamRows = computed(() => [{ id: '*', name: '全部团队（含未来新建）' }, ...teams.value])
const workflowRows = computed(() => [{ id: '*', name: '全部工作流（含未来新建）' }, ...workflows.value])

function openScopes(row: RoleRow) {
  scopeTarget.value = row
  const existing = (row.scopes || []).map((item) => item.raw)
  const checked: Record<string, boolean> = {}
  for (const scope of existing) {
    checked[scope] = true
  }
  scopeChecked.value = checked
  const known = new Set(GLOBAL_SCOPES.map((item) => item.value))
  const resources = existing.filter(
    (scope) => !known.has(scope) && !/^(agents|teams|workflows):/.test(scope),
  )
  extraScopes.value = resources.join(', ')
  scopeDialog.value = true
  loadResources()
}

async function submitScopes() {
  if (!scopeTarget.value) {
    return
  }
  const selected = Object.entries(scopeChecked.value)
    .filter(([, value]) => value)
    .map(([key]) => key)
  const extras = extraScopes.value
    .split(/[,\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
  const scopes = Array.from(new Set([...selected, ...extras]))

  const invalid: string[] = []
  for (const scope of scopes) {
    const match = scope.match(/^(agents|teams|workflows):([^:]+):(read|run)$/)
    if (!match) {
      continue
    }
    const [, type, resourceId] = match
    if (resourceId === '*') {
      continue
    }
    const pool: any[] =
      type === 'agents' ? agents.value : type === 'teams' ? teams.value : workflows.value
    if (!pool.some((item) => item.id === resourceId)) {
      invalid.push(scope)
    }
  }
  if (invalid.length) {
    ElMessage.error(`以下资源还不存在，请先创建：${invalid.join('、')}`)
    return
  }

  scopeSaving.value = true
  try {
    await setRoleScopes(scopeTarget.value.slug, scopes)
    ElMessage.success('权限已更新')
    scopeDialog.value = false
    await loadRoles()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    scopeSaving.value = false
  }
}

async function removeRole(row: RoleRow) {
  try {
    await ElMessageBox.confirm(`确认删除角色 ${row.name || row.slug}？`, '删除角色', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteRole(row.slug)
    ElMessage.success('角色已删除')
    await loadRoles()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

function openAssign(row: UserRow) {
  assignTarget.value = row
  assignSlug.value = row.role_slug || ''
  assignDialog.value = true
}

async function submitAssign() {
  if (!assignTarget.value || !assignSlug.value) {
    ElMessage.warning('请选择角色')
    return
  }
  assignSaving.value = true
  try {
    await assignRole(assignTarget.value.id, assignSlug.value)
    ElMessage.success('角色已分配')
    assignDialog.value = false
    await loadUsers()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    assignSaving.value = false
  }
}

function openCreateUser() {
  userForm.id = ''
  userForm.name = ''
  userForm.email = ''
  userForm.password = ''
  userDialog.value = true
}

async function submitUser() {
  if (!userForm.id.trim()) {
    ElMessage.warning('请填写用户 ID（必须与 JWT 的 sub 一致）')
    return
  }
  if (userForm.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  userSaving.value = true
  try {
    await createUser({
      id: userForm.id.trim(),
      name: userForm.name.trim() || undefined,
      email: userForm.email.trim() || undefined,
    })
    await createAccount({
      username: userForm.id.trim(),
      user_id: userForm.id.trim(),
      password: userForm.password,
    })
    ElMessage.success('用户与登录账号已创建')
    userDialog.value = false
    await loadUsers()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    userSaving.value = false
  }
}

async function toggleUser(row: UserRow) {
  try {
    await updateUser(row.id, { disabled: row.status === 'active' })
    ElMessage.success(row.status === 'active' ? '已禁用' : '已启用')
    await loadUsers()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

async function unassignUser(row: UserRow) {
  if (!row.role_slug) {
    return
  }
  try {
    await ElMessageBox.confirm(`确认撤销 ${row.id} 的角色 ${row.role_slug}？`, '撤销角色', { type: 'warning' })
  } catch {
    return
  }
  try {
    await unassignRole(row.id, row.role_slug)
    ElMessage.success('角色已撤销')
    await loadUsers()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

async function removeUser(row: UserRow) {
  try {
    await ElMessageBox.confirm(`确认删除用户 ${row.name || row.id}？`, '删除用户', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteUser(row.id)
    ElMessage.success('用户已删除')
    await loadUsers()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

function formatTime(value: any) {
  if (value === undefined || value === null) {
    return '-'
  }
  const number = typeof value === 'number' ? (value > 1e12 ? value : value * 1000) : Date.parse(String(value))
  return Number.isNaN(number) ? String(value) : new Date(number).toLocaleString()
}

function auditTime(row: Record<string, any>) {
  return formatTime(row.created_at ?? row.timestamp ?? row.at ?? row.time)
}

function auditText(row: Record<string, any>) {
  return JSON.stringify(row)
}

onMounted(loadAll)
</script>

<template>
  <el-card shadow="never" v-loading="loading">
    <div class="toolbar">
      <div>
        <div class="card-title">权限管理</div>
        <div class="card-desc">基于 AgentOS RBAC：角色定义 scope，用户按角色获得权限，变更写入审计</div>
      </div>
      <el-button size="small" @click="loadAll">刷新</el-button>
    </div>

    <el-tabs v-model="active">
      <el-tab-pane label="角色" name="roles">
        <div class="toolbar">
          <span class="hint">共 {{ roles.length }} 个角色</span>
          <el-button type="primary" size="small" @click="openCreateRole">新建角色</el-button>
        </div>
        <el-table :data="roles" style="width: 100%">
          <el-table-column label="角色" width="180">
            <template #default="{ row }">
              <div class="session-name">{{ row.name || row.slug }}</div>
              <div class="session-id">{{ row.slug }}</div>
            </template>
          </el-table-column>
          <el-table-column label="Scope" min-width="300">
            <template #default="{ row }">
              <div class="scope-line">{{ (row.scopes || []).map((item: any) => item.raw).join(', ') || '（无）' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="默认" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.is_default" size="small" type="success" effect="plain">默认</el-tag>
              <span v-else class="hint">-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openScopes(row)">编辑权限</el-button>
              <el-button size="small" link type="danger" @click="removeRole(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="用户" name="users">
        <div class="toolbar">
          <span class="hint">共 {{ users.length }} 个用户</span>
          <el-button type="primary" size="small" @click="openCreateUser">新建用户</el-button>
        </div>
        <el-table :data="users" style="width: 100%">
          <el-table-column label="用户" min-width="220">
            <template #default="{ row }">
              <div class="session-name">{{ row.name || row.id }}</div>
              <div class="session-id">{{ row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column label="邮箱" width="200">
            <template #default="{ row }">{{ row.email || '-' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.role_slug" size="small" effect="plain">{{ row.role_name || row.role_slug }}</el-tag>
              <span v-else class="hint">未分配</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'" effect="light">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openAssign(row)">分配角色</el-button>
              <el-button v-if="row.role_slug" size="small" link type="info" @click="unassignUser(row)">
                撤销角色
              </el-button>
              <el-button size="small" link :type="row.status === 'active' ? 'warning' : 'success'" @click="toggleUser(row)">
                {{ row.status === 'active' ? '禁用' : '启用' }}
              </el-button>
              <el-button size="small" link type="danger" @click="removeUser(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="审计" name="audit">
        <div class="toolbar">
          <span class="hint">最近 {{ audit.length }} 条权限变更与访问决策</span>
        </div>
        <el-table :data="audit" style="width: 100%">
          <el-table-column label="时间" width="190">
            <template #default="{ row }">{{ auditTime(row) }}</template>
          </el-table-column>
          <el-table-column label="事件" min-width="420">
            <template #default="{ row }">
              <el-tooltip :content="auditText(row)" placement="top" :show-after="300">
                <pre class="audit-json">{{ auditText(row) }}</pre>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="roleDialog" title="新建角色" width="480px">
      <el-form :model="roleForm" label-width="90px">
        <el-form-item label="标识">
          <el-input v-model="roleForm.slug" placeholder="例如 operator" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="roleForm.name" placeholder="例如 操作员" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="roleForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">创建后通过“编辑权限”为其分配 scope 列表。</div>
      <template #footer>
        <el-button @click="roleDialog = false">取消</el-button>
        <el-button type="primary" :loading="roleSaving" @click="submitRole">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scopeDialog" title="编辑角色权限" width="780px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="正确顺序：先创建 Agent/Team/Workflow（含适配器与工具），再给角色授权，最后把角色分配给用户。"
        style="margin-bottom: 8px"
      />
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="带“全部”通配的 scope 会同时授权未来新建的资源；只想授权现有资源，请逐个勾选。"
        style="margin-bottom: 16px"
      />

      <div v-loading="resourceLoading">
        <h4 class="section-title">全局权限</h4>
        <div class="scope-grid">
          <el-checkbox v-for="scope in GLOBAL_SCOPES" :key="scope.value" v-model="scopeChecked[scope.value]">
            {{ scope.label }}
            <span class="hint">{{ scope.value }}</span>
          </el-checkbox>
        </div>

        <h4 class="section-title">智能体</h4>
        <el-table :data="agentRows" size="small" style="width: 100%">
          <el-table-column label="资源" min-width="220">
            <template #default="{ row }">
              {{ row.name || row.id }} <span class="hint">/ {{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="读取" width="90">
            <template #default="{ row }">
              <el-checkbox v-model="scopeChecked[scopeKey('agents', row.id, 'read')]" />
            </template>
          </el-table-column>
          <el-table-column label="运行" width="90">
            <template #default="{ row }">
              <el-checkbox v-model="scopeChecked[scopeKey('agents', row.id, 'run')]" />
            </template>
          </el-table-column>
        </el-table>

        <h4 class="section-title">团队</h4>
        <el-table :data="teamRows" size="small" style="width: 100%">
          <el-table-column label="资源" min-width="220">
            <template #default="{ row }">
              {{ row.name || row.id }} <span class="hint">/ {{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="读取" width="90">
            <template #default="{ row }">
              <el-checkbox v-model="scopeChecked[scopeKey('teams', row.id, 'read')]" />
            </template>
          </el-table-column>
          <el-table-column label="运行" width="90">
            <template #default="{ row }">
              <el-checkbox v-model="scopeChecked[scopeKey('teams', row.id, 'run')]" />
            </template>
          </el-table-column>
        </el-table>

        <h4 class="section-title">工作流</h4>
        <el-table :data="workflowRows" size="small" style="width: 100%">
          <el-table-column label="资源" min-width="220">
            <template #default="{ row }">
              {{ row.name || row.id }} <span class="hint">/ {{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="读取" width="90">
            <template #default="{ row }">
              <el-checkbox v-model="scopeChecked[scopeKey('workflows', row.id, 'read')]" />
            </template>
          </el-table-column>
          <el-table-column label="运行" width="90">
            <template #default="{ row }">
              <el-checkbox v-model="scopeChecked[scopeKey('workflows', row.id, 'run')]" />
            </template>
          </el-table-column>
        </el-table>

        <h4 class="section-title">其他 scope</h4>
        <el-input
          v-model="extraScopes"
          type="textarea"
          :rows="2"
          placeholder="例如 knowledge:read, metrics:write；多个用逗号分隔"
        />
      </div>

      <template #footer>
        <el-button @click="scopeDialog = false">取消</el-button>
        <el-button type="primary" :loading="scopeSaving" @click="submitScopes">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="userDialog" title="新建用户" width="480px">
      <el-form :model="userForm" label-width="90px">
        <el-form-item label="用户 ID">
          <el-input v-model="userForm.id" placeholder="必须与 JWT 的 sub 一致，例如 alice" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="userForm.name" placeholder="显示名称" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="userForm.email" placeholder="可选" />
        </el-form-item>
        <el-form-item label="登录密码">
          <el-input v-model="userForm.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">
        用户名与用户 ID 相同；创建后会同时生成登录账号，用户可用该账号登录控制台，页面按角色权限显示。
      </div>
      <template #footer>
        <el-button @click="userDialog = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="submitUser">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="assignDialog" title="分配角色" width="420px">
      <div class="hint" style="margin-bottom: 8px">用户：{{ assignTarget?.name || assignTarget?.id }}</div>
      <el-select v-model="assignSlug" placeholder="选择角色" style="width: 100%">
        <el-option v-for="role in roles" :key="role.slug" :label="role.name || role.slug" :value="role.slug" />
      </el-select>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button type="primary" :loading="assignSaving" @click="submitAssign">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
