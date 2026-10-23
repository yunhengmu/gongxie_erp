<script setup lang="ts">
import { computed, markRaw, onMounted, ref, watch, type Component } from 'vue'
import {
  Avatar,
  Bell,
  ChatDotRound,
  Clock,
  Collection,
  DataBoard,
  Document,
  FolderOpened,
  Grid,
  Key,
  MagicStick,
  Monitor,
  Odometer,
  Refresh,
  Setting,
  SwitchButton,
  Timer,
  Tools,
  TrendCharts,
} from '@element-plus/icons-vue'
import { bootstrapAuth, getAuthToken, getMe, getStatus, logout, type ConsoleStatus, type MeInfo } from './api'
import ApprovalPanel from './views/ApprovalPanel.vue'
import AuxModelPanel from './views/AuxModelPanel.vue'
import ChatPanel from './views/ChatPanel.vue'
import ComponentsPanel from './views/ComponentsPanel.vue'
import ConfigPanel from './views/ConfigPanel.vue'
import EvalPanel from './views/EvalPanel.vue'
import FilesPanel from './views/FilesPanel.vue'
import KnowledgePanel from './views/KnowledgePanel.vue'
import LoginPanel from './views/LoginPanel.vue'
import MemoryPanel from './views/MemoryPanel.vue'
import OverviewPanel from './views/OverviewPanel.vue'
import PermissionPanel from './views/PermissionPanel.vue'
import SchedulePanel from './views/SchedulePanel.vue'
import ServiceAccountPanel from './views/ServiceAccountPanel.vue'
import SessionPanel from './views/SessionPanel.vue'
import SystemPanel from './views/SystemPanel.vue'
import TeamWorkflowPanel from './views/TeamWorkflowPanel.vue'
import ToolPanel from './views/ToolPanel.vue'
import TracePanel from './views/TracePanel.vue'

const views: Record<string, Component> = {
  overview: OverviewPanel,
  chat: ChatPanel,
  sessions: SessionPanel,
  trace: TracePanel,
  system: SystemPanel,
  config: ConfigPanel,
  auxModels: AuxModelPanel,
  components: ComponentsPanel,
  schedules: SchedulePanel,
  accounts: ServiceAccountPanel,
  permissions: PermissionPanel,
  knowledge: KnowledgePanel,
  memory: MemoryPanel,
  approvals: ApprovalPanel,
  evals: EvalPanel,
  teams: TeamWorkflowPanel,
  files: FilesPanel,
  tools: ToolPanel,
}

interface NavItem {
  key: string
  label: string
  icon: Component
  scope: string
}

const NAV: { group: string; items: NavItem[] }[] = [
  {
    group: '运行',
    items: [
      { key: 'overview', label: '概览', icon: markRaw(DataBoard), scope: 'config:read' },
      { key: 'chat', label: '对话测试', icon: markRaw(ChatDotRound), scope: 'agents:*:run' },
      { key: 'sessions', label: '会话记录', icon: markRaw(Clock), scope: 'sessions:read' },
      { key: 'trace', label: '运行追踪', icon: markRaw(Monitor), scope: 'traces:read' },
      { key: 'system', label: '运行监控', icon: markRaw(Odometer), scope: 'metrics:read' },
    ],
  },
  {
    group: '配置',
    items: [
      { key: 'config', label: '参数调节', icon: markRaw(Setting), scope: 'agent_os:admin' },
      { key: 'auxModels', label: '辅助模型', icon: markRaw(MagicStick), scope: 'agent_os:admin' },
      { key: 'components', label: '组件管理', icon: markRaw(Grid), scope: 'agent_os:admin' },
      { key: 'schedules', label: '定时任务', icon: markRaw(Timer), scope: 'agent_os:admin' },
      { key: 'accounts', label: '服务账号', icon: markRaw(Avatar), scope: 'service_accounts:read' },
      { key: 'permissions', label: '权限管理', icon: markRaw(Key), scope: 'agent_os:admin' },
    ],
  },
  {
    group: '资源',
    items: [
      { key: 'tools', label: '工具库', icon: markRaw(Tools), scope: 'agent_os:admin' },
      { key: 'knowledge', label: '知识库', icon: markRaw(Collection), scope: 'knowledge:read' },
      { key: 'memory', label: '记忆与学习', icon: markRaw(Document), scope: 'config:read' },
      { key: 'approvals', label: '审批', icon: markRaw(Bell), scope: 'config:read' },
      { key: 'evals', label: '评测', icon: markRaw(TrendCharts), scope: 'evals:read' },
      { key: 'teams', label: '团队与工作流', icon: markRaw(Avatar), scope: 'teams:read' },
      { key: 'files', label: '文件系统', icon: markRaw(FolderOpened), scope: 'agents:read' },
    ],
  },
]

const active = ref('overview')
const status = ref<ConsoleStatus | null>(null)
const me = ref<MeInfo | null>(null)
const ready = ref(false)
const error = ref('')

const currentView = computed(() => views[active.value] || OverviewPanel)
const agentId = computed(() => status.value?.agent.id || 'assistant')

function hasScope(required: string): boolean {
  const scopes = me.value?.scopes || []
  if (scopes.includes('agent_os:admin')) {
    return true
  }
  if (scopes.includes(required)) {
    return true
  }
  const parts = required.split(':')
  if (parts.length === 3 && parts[1] === '*') {
    return scopes.some((scope) => scope.startsWith(`${parts[0]}:`) && scope.endsWith(`:${parts[2]}`))
  }
  return false
}

const visibleNav = computed(() =>
  NAV.map((group) => ({ ...group, items: group.items.filter((item) => hasScope(item.scope)) })).filter(
    (group) => group.items.length > 0,
  ),
)

watch(
  visibleNav,
  (groups) => {
    const keys = groups.flatMap((group) => group.items.map((item) => item.key))
    if (keys.length && !keys.includes(active.value)) {
      active.value = keys[0]
    }
  },
  { immediate: true },
)

async function refresh() {
  try {
    status.value = await getStatus()
    error.value = ''
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
}

async function init() {
  const token = await bootstrapAuth()
  me.value = token ? getMe() : null
  ready.value = true
  if (me.value) {
    await refresh()
  }
}

async function handleLogin() {
  me.value = getMe()
  await refresh()
}

function doLogout() {
  logout()
  me.value = null
  status.value = null
  active.value = 'overview'
}

function select(index: string) {
  active.value = index
}

onMounted(init)
</script>

<template>
  <div v-if="!ready" class="login-shell">
    <el-icon class="is-loading" size="28"><Refresh /></el-icon>
  </div>

  <LoginPanel v-else-if="!me || !getAuthToken()" @success="handleLogin" />

  <div v-else class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="dot" />
        <div>
          <div class="brand-title">Agno Console</div>
          <div class="brand-sub">本地智能体控制台 · RBAC</div>
        </div>
      </div>
      <div class="status">
        <el-tag v-if="error" type="danger" effect="light">连接失败</el-tag>
        <template v-else-if="status">
          <el-tag type="success" effect="light">运行中</el-tag>
          <el-tag effect="plain">{{ status.agent.model }}</el-tag>
        </template>
        <el-tag effect="plain" type="info">{{ me.user_id }}</el-tag>
        <el-tag v-if="me.roles.length" effect="plain" type="success">{{ me.roles.join(',') }}</el-tag>
        <el-button circle :icon="Refresh" title="刷新状态" @click="refresh" />
        <el-button circle :icon="SwitchButton" title="退出登录" @click="doLogout" />
      </div>
    </header>

    <div class="layout">
      <aside class="sidebar">
        <el-menu :default-active="active" class="side-menu" @select="select">
          <el-menu-item-group v-for="group in visibleNav" :key="group.group" :title="group.group">
            <el-menu-item v-for="item in group.items" :key="item.key" :index="item.key">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </aside>

      <main class="main-content">
        <el-alert
          v-if="error"
          :title="'后端未连接：' + error"
          type="error"
          :closable="false"
          show-icon
          class="alert"
        />
        <el-empty v-if="!visibleNav.length" description="当前账号没有可访问的页面，请联系管理员分配角色权限" />
        <component :is="currentView" v-else :agent-id="agentId" @saved="refresh" />
      </main>
    </div>
  </div>
</template>
