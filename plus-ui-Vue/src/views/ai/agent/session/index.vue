<template>
  <div class="p-2 app-container">
    <div class="search-wrap">
      <el-card shadow="hover" class="search-panel" :class="{ 'is-collapsed': !showSearch }">
        <template #header>
          <div class="panel-heading search-panel-toggle" @click.stop="showSearch = !showSearch">
            <div><h3>筛选条件</h3></div>
          </div>
        </template>
        <el-form ref="queryFormRef" :model="queryParams" :inline="true" class="query-form">
          <el-form-item label="任务标题" prop="sessionTitle">
            <el-input
              v-model="queryParams.sessionTitle"
              placeholder="请输入任务标题"
              clearable
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="智能体" prop="agentId">
            <el-select v-model="queryParams.agentId" placeholder="全部" clearable style="width: 200px">
              <el-option v-for="item in agentOptions" :key="item.id" :label="item.agentName" :value="item.id">
                <span>{{ item.agentName }}</span>
                <span v-if="item.status === '1'" class="text-muted">（已停用）</span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="归属用户" prop="userId">
            <el-input
              v-model="userLabel"
              placeholder="全部"
              readonly
              clearable
              style="width: 200px"
              @click="handleSelectUser"
              @clear="handleClearUser"
            />
          </el-form-item>
          <el-form-item label="来源" prop="source">
            <el-select v-model="queryParams.source" placeholder="全部" clearable style="width: 140px">
              <el-option label="手动对话" value="chat" />
              <el-option label="定时任务" value="task" />
              <el-option label="渠道接入" value="channel" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 140px">
              <el-option label="进行中" value="0" />
              <el-option label="已结束" value="1" />
            </el-select>
          </el-form-item>
          <el-form-item label="最后活跃">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="-"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 260px"
            />
          </el-form-item>
          <el-form-item label="只看我的">
            <el-switch v-model="queryParams.mine" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
            <el-button icon="Refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>

    <el-card shadow="hover" class="table-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>任务管理</h3>
            <p>检索全部用户的任务，查看消息记录与工具调用明细，必要时终止、删除或导出留档。</p>
          </div>
          <div class="toolbar-actions">
            <el-button
              v-hasPermi="['ai:session:edit']"
              type="warning"
              plain
              icon="VideoPause"
              :disabled="multiple"
              @click="handleClose()"
            >
              终止
            </el-button>
            <el-button
              v-hasPermi="['ai:session:remove']"
              type="danger"
              plain
              icon="Delete"
              :disabled="multiple"
              @click="handleDelete()"
            >
              删除
            </el-button>
            <el-button v-hasPermi="['ai:session:export']" type="primary" plain icon="Download" @click="handleExport">
              导出
            </el-button>
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        border
        class="data-table"
        :data="sessionList"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="任务标题" align="left" :show-overflow-tooltip="true">
          <template #default="scope">
            <el-tag v-if="scope.row.topFlag === '1'" type="warning" size="small" effect="plain" class="pin-tag">
              置顶
            </el-tag>
            <span>{{ scope.row.sessionTitle }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" align="center" width="110">
          <template #default="scope">
            <el-tag :type="sourceTagType(scope.row.source)" size="small" effect="plain">
              {{ sourceLabel(scope.row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="智能体" align="center" prop="agentName" :show-overflow-tooltip="true" width="150" />
        <el-table-column label="归属用户" align="center" width="120">
          <template #default="scope">
            <span v-if="scope.row.userName">{{ scope.row.userName }}</span>
            <span v-else class="text-muted">{{ scope.row.userId ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="部门" align="center" prop="deptName" :show-overflow-tooltip="true" width="140" />
        <el-table-column label="消息数" align="center" prop="messageCount" width="90" />
        <el-table-column label="状态" align="center" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'info'">
              {{ scope.row.status === '0' ? '进行中' : '已结束' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后活跃时间" align="center" prop="lastActiveTime" width="170" />
        <el-table-column label="创建时间" align="center" prop="createTime" width="170" />
        <el-table-column label="操作" align="center" width="200" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="消息明细" placement="top">
              <el-button
                v-hasPermi="['ai:session:query']"
                link
                type="primary"
                icon="View"
                @click="handleDetail(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="终止任务" placement="top">
              <el-button
                v-hasPermi="['ai:session:edit']"
                link
                type="warning"
                icon="VideoPause"
                :disabled="scope.row.status === '1'"
                @click="handleClose(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除任务" placement="top">
              <el-button
                v-hasPermi="['ai:session:remove']"
                link
                type="danger"
                icon="Delete"
                @click="handleDelete(scope.row)"
              ></el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <pagination
        v-show="total > 0"
        v-model:page="queryParams.pageNum"
        v-model:limit="queryParams.pageSize"
        :total="total"
        @pagination="getList"
      />
    </el-card>

    <el-dialog v-model="detail.visible" title="任务明细" width="900px" append-to-body>
      <el-descriptions v-if="detail.session" :column="3" border size="small" class="detail-summary">
        <el-descriptions-item label="任务标题">{{ detail.session.sessionTitle }}</el-descriptions-item>
        <el-descriptions-item label="智能体">
          {{ detail.session.agentName || detail.session.agentCode }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detail.session.status === '0' ? 'success' : 'info'">
            {{ detail.session.status === '0' ? '进行中' : '已结束' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="归属用户">
          {{ detail.session.userName || detail.session.userId }}
        </el-descriptions-item>
        <el-descriptions-item label="归属部门">{{ detail.session.deptName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源">
          <el-tag :type="sourceTagType(detail.session.source)" size="small" effect="plain">
            {{ sourceLabel(detail.session.source) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="消息条数">{{ detail.session.messageCount ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail.session.createTime }}</el-descriptions-item>
        <el-descriptions-item label="最后活跃时间">{{ detail.session.lastActiveTime }}</el-descriptions-item>
        <el-descriptions-item label="任务标识">{{ detail.session.id }}</el-descriptions-item>
      </el-descriptions>

      <div v-loading="detail.loading" class="detail-body">
        <el-empty v-if="!detail.loading && !detail.messages.length" description="该任务暂无消息记录" />
        <div v-for="item in detail.messages" :key="item.id" class="detail-message">
          <div class="detail-message-head">
            <el-tag :type="roleTagType(item.role)" effect="plain" size="small">{{ roleLabel(item.role) }}</el-tag>
            <span class="text-muted detail-message-time">{{ item.createTime }}</span>
            <el-tag v-if="item.status === '1'" type="danger" size="small" effect="plain">失败</el-tag>
            <span v-if="item.durationMs" class="text-muted">耗时 {{ item.durationMs }} ms</span>
            <span v-if="item.totalTokens" class="text-muted">Token {{ item.totalTokens }}</span>
          </div>
          <div v-if="item.errorMsg" class="detail-message-error">{{ item.errorMsg }}</div>
          <pre v-if="item.content" class="detail-message-content">{{ item.content }}</pre>
          <el-collapse v-if="toolCallsOf(item.id).length" class="detail-tools">
            <el-collapse-item :title="`工具调用（${toolCallsOf(item.id).length}）`" :name="String(item.id)">
              <div v-for="tool in toolCallsOf(item.id)" :key="tool.id" class="detail-tool-item">
                <div class="detail-tool-head">
                  <el-tag size="small" effect="plain">{{ tool.toolName }}</el-tag>
                  <el-tag :type="tool.state === 'SUCCESS' ? 'success' : 'danger'" size="small" effect="plain">
                    {{ tool.state || '未知' }}
                  </el-tag>
                  <span v-if="tool.durationMs !== null && tool.durationMs !== undefined" class="text-muted">
                    {{ tool.durationMs }} ms
                  </span>
                </div>
                <div class="detail-tool-block">
                  <div class="detail-tool-label">调用入参</div>
                  <pre class="detail-tool-pre">{{ tool.toolInput || '-' }}</pre>
                </div>
                <div class="detail-tool-block">
                  <div class="detail-tool-label">结果摘要</div>
                  <pre class="detail-tool-pre">{{ tool.toolOutput || '-' }}</pre>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>

      <template #footer>
        <el-button @click="detail.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <UserSelect ref="userSelectRef" :multiple="false" @confirm-call-back="handleUserSelected"></UserSelect>
  </div>
</template>

<script setup name="AiAgentSession" lang="ts">
import type { AgentVO } from '@/api/ai/agent/types';
import type { SessionMessageVO, SessionQuery, SessionVO, ToolCallLogVO } from '@/api/ai/session/types';
import {
  closeChatSessionById,
  delChatSession,
  exportSessionUrl,
  listChatSessions,
  listSessionMessage,
  listSessionToolCall,
  optionSessionAgent
} from '@/api/ai/session';
import type { UserVO } from '@/api/system/user/types';
import UserSelect from '@/components/UserSelect/index.vue';
import modal from '@/plugins/modal';
import { download } from '@/utils/request';

const router = useRouter();
const route = useRoute();

const sessionList = ref<SessionVO[]>([]);
const agentOptions = ref<AgentVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const dateRange = ref<string[]>([]);
const userLabel = ref('');
const userSelectRef = ref<InstanceType<typeof UserSelect>>();
const queryFormRef = ref<ElFormInstance>();
/** 从定时任务执行记录跳转而来时待展开的会话主键 */
const pendingSessionId = ref<string | undefined>();

const queryParams = ref<SessionQuery>({
  pageNum: 1,
  pageSize: 10,
  sessionTitle: '',
  agentId: undefined,
  userId: undefined,
  source: undefined,
  status: '',
  mine: false
});

const detail = reactive<{
  visible: boolean;
  loading: boolean;
  session?: SessionVO;
  messages: SessionMessageVO[];
  tools: ToolCallLogVO[];
}>({
  visible: false,
  loading: false,
  session: undefined,
  messages: [],
  tools: []
});

const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);

/** 查询会话列表 */
const getList = async () => {
  loading.value = true;
  try {
    const params: SessionQuery = { ...queryParams.value };
    if (dateRange.value && dateRange.value.length === 2) {
      params.beginTime = `${dateRange.value[0]}T00:00:00`;
      params.endTime = `${dateRange.value[1]}T23:59:59`;
    }
    const res = await listChatSessions(params);
    sessionList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
    openPendingSession();
  } finally {
    loading.value = false;
  }
};

/** 从定时任务执行记录跳转而来时，定位并直接展开该会话明细（后台任务结果） */
const openPendingSession = () => {
  const target = pendingSessionId.value;
  if (!target) {
    return;
  }
  const row = sessionList.value.find((item) => String(item.id) === String(target));
  if (row) {
    pendingSessionId.value = undefined;
    handleDetail(row);
  }
};

/** 应用地址栏带入的筛选（来源 / 指定会话），供定时任务执行记录直达 */
const applyRouteQuery = () => {
  const source = route.query.source;
  if (typeof source === 'string' && source) {
    queryParams.value.source = source;
  }
  const sessionId = route.query.sessionId;
  if (typeof sessionId === 'string' && sessionId) {
    queryParams.value.sessionId = sessionId;
    pendingSessionId.value = sessionId;
  }
};

/** 查询出现的智能体选项（含已停用） */
const getAgentOptions = async () => {
  const res = await optionSessionAgent();
  agentOptions.value = res.data ?? [];
};

const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryFormRef.value?.resetFields();
  dateRange.value = [];
  userLabel.value = '';
  queryParams.value.userId = undefined;
  queryParams.value.mine = false;
  queryParams.value.source = undefined;
  queryParams.value.sessionId = undefined;
  queryParams.value.pageNum = 1;
  pendingSessionId.value = undefined;
  getList();
};

const handleSelectionChange = (selection: SessionVO[]) => {
  ids.value = selection.map((item) => item.id);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};

/** 选择归属用户 */
const handleSelectUser = () => {
  userSelectRef.value?.open();
};

const handleUserSelected = (users: UserVO[]) => {
  const user = users?.[0];
  if (!user) {
    modal.msgWarning('请选择用户');
    return;
  }
  queryParams.value.userId = user.userId;
  userLabel.value = user.nickName || user.userName;
};

const handleClearUser = () => {
  queryParams.value.userId = undefined;
  userLabel.value = '';
};

/** 终止会话 */
const handleClose = async (row?: Partial<SessionVO>) => {
  const targetIds = row ? [row.id as string | number] : ids.value;
  if (!targetIds.length) {
    return;
  }
  await modal.confirm(`是否确认终止选中的 ${targetIds.length} 个任务？终止后不能再继续提问。`);
  await closeChatSessionById(targetIds.join(','));
  modal.msgSuccess('已终止');
  await getList();
};

/** 删除会话 */
const handleDelete = async (row?: Partial<SessionVO>) => {
  const targetIds = row ? [row.id as string | number] : ids.value;
  if (!targetIds.length) {
    return;
  }
  await modal.confirm(`是否确认删除选中的 ${targetIds.length} 个任务？删除后消息记录不再展示。`);
  await delChatSession(targetIds.join(','));
  modal.msgSuccess('删除成功');
  await getList();
};

/** 导出留档 */
const handleExport = () => {
  const params: SessionQuery = { ...queryParams.value };
  if (dateRange.value && dateRange.value.length === 2) {
    params.beginTime = `${dateRange.value[0]}T00:00:00`;
    params.endTime = `${dateRange.value[1]}T23:59:59`;
  }
  delete params.pageNum;
  delete params.pageSize;
  download(exportSessionUrl, params, `任务数据_${new Date().getTime()}.xlsx`);
};

/** 查看会话明细 */
const handleDetail = async (row: Partial<SessionVO>) => {
  if (row.id === undefined || row.id === null) {
    return;
  }
  detail.visible = true;
  detail.session = row as SessionVO;
  detail.messages = [];
  detail.tools = [];
  detail.loading = true;
  try {
    const [messageRes, toolRes] = await Promise.all([listSessionMessage(row.id), listSessionToolCall(row.id)]);
    detail.messages = messageRes.data ?? [];
    detail.tools = toolRes.data ?? [];
  } finally {
    detail.loading = false;
  }
};

/** 某条消息关联的工具调用 */
const toolCallsOf = (messageId: string | number) => {
  return detail.tools.filter((item) => String(item.messageId) === String(messageId));
};

const roleLabel = (role?: string) => {
  if (role === 'user') return '用户';
  if (role === 'assistant') return '助手';
  return role || '系统';
};

const roleTagType = (role?: string) => {
  if (role === 'user') return 'primary';
  if (role === 'assistant') return 'success';
  return 'info';
};

/** 会话来源展示名（chat 手动对话 / task 定时任务 / channel 渠道接入） */
const sourceLabel = (source?: string) => {
  if (source === 'task') return '定时任务';
  if (source === 'channel') return '渠道接入';
  return '手动对话';
};

/** 会话来源标签样式 */
const sourceTagType = (source?: string) => {
  if (source === 'task') return 'warning';
  if (source === 'channel') return 'success';
  return 'primary';
};

onMounted(() => {
  applyRouteQuery();
  getAgentOptions();
  getList();
});
</script>

<style scoped lang="scss">
/* 详情弹窗标签列给足宽度（≥5 汉字），避免短标签挤压与长标签换行 */
:deep(.el-descriptions__label) {
  min-width: 110px;
}
.text-muted {
  color: var(--app-text-muted, #909399);
}

.pin-tag {
  margin-right: 4px;
}

.detail-summary {
  margin-bottom: 12px;
}

.detail-body {
  max-height: 55vh;
  overflow-y: auto;
  padding-right: 4px;
}

.detail-message {
  padding: 10px 12px;
  margin-bottom: 10px;
  border: 1px solid var(--app-surface-border);
  border-radius: var(--app-radius-base, 8px);
  background: var(--app-surface-bg);
}

.detail-message-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.detail-message-time {
  font-size: 12px;
}

.detail-message-content {
  margin: 0;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-message-error {
  margin-bottom: 6px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.detail-tools {
  margin-top: 8px;
}

.detail-tool-item {
  padding: 6px 0;
  border-bottom: 1px dashed var(--app-surface-border);

  &:last-child {
    border-bottom: none;
  }
}

.detail-tool-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.detail-tool-block {
  margin-bottom: 6px;
}

.detail-tool-label {
  margin-bottom: 2px;
  font-size: 12px;
  color: var(--app-text-muted, #909399);
}

.detail-tool-pre {
  max-height: 160px;
  margin: 0;
  padding: 6px 8px;
  overflow: auto;
  background: var(--el-fill-color-light);
  border-radius: var(--app-radius-base, 6px);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
