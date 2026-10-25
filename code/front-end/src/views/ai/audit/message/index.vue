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
          <el-form-item label="正文关键词" prop="keyword">
            <el-input
              v-model="queryParams.keyword"
              placeholder="请输入消息正文关键词"
              clearable
              style="width: 200px"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="角色" prop="role">
            <el-select v-model="queryParams.role" placeholder="全部" clearable style="width: 130px">
              <el-option label="用户" value="user" />
              <el-option label="助手" value="assistant" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 130px">
              <el-option label="正常" value="0" />
              <el-option label="失败" value="1" />
            </el-select>
          </el-form-item>
          <el-form-item label="任务标题" prop="sessionTitle">
            <el-input
              v-model="queryParams.sessionTitle"
              placeholder="请输入任务标题"
              clearable
              style="width: 200px"
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
          <el-form-item label="时间范围">
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
            <h3>任务与消息审计</h3>
            <p>留存人与智能体的完整交互记录，可按用户、智能体、时间范围检索消息明细，用于合规留档与争议溯源。</p>
          </div>
          <div class="toolbar-actions">
            <el-button
              v-hasPermi="['ai:audit:message:export']"
              type="primary"
              plain
              icon="Download"
              @click="handleExport"
            >
              导出
            </el-button>
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border class="data-table" :data="messageList">
        <el-table-column label="时间" align="center" prop="createTime" width="170" />
        <el-table-column label="任务标题" align="left" prop="sessionTitle" :show-overflow-tooltip="true" />
        <el-table-column label="智能体" align="center" prop="agentName" width="150" :show-overflow-tooltip="true" />
        <el-table-column label="归属用户" align="center" width="120">
          <template #default="scope">
            <span v-if="scope.row.userName">{{ scope.row.userName }}</span>
            <span v-else class="text-muted">{{ scope.row.userId ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" align="center" width="90">
          <template #default="scope">
            <el-tag :type="roleTagType(scope.row.role)" effect="plain">{{ roleLabel(scope.row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="正文" align="left" prop="content" :show-overflow-tooltip="true" />
        <el-table-column label="状态" align="center" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.status === '1'" type="danger">失败</el-tag>
            <el-tag v-else type="success">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="工具调用次数" align="center" prop="toolCallCount" width="110" />
        <el-table-column label="Token" align="center" width="90">
          <template #default="scope">{{ scope.row.totalTokens ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="耗时(ms)" align="center" prop="durationMs" width="100" />
        <el-table-column label="操作" align="center" width="100" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看详情" placement="top">
              <el-button
                v-hasPermi="['ai:audit:message']"
                link
                type="primary"
                icon="View"
                @click="handleDetail(scope.row)"
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

    <el-dialog v-model="detail.visible" title="消息详情" width="820px" append-to-body>
      <el-descriptions v-if="detail.record" :column="3" border size="small" class="detail-summary">
        <el-descriptions-item label="任务标题">{{ detail.record.sessionTitle || '-' }}</el-descriptions-item>
        <el-descriptions-item label="智能体">{{ detail.record.agentName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="归属用户">
          {{ detail.record.userName || detail.record.userId || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="角色">{{ roleLabel(detail.record.role) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detail.record.status === '1' ? 'danger' : 'success'">
            {{ detail.record.status === '1' ? '失败' : '正常' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="耗时">{{ detail.record.durationMs ?? '-' }} ms</el-descriptions-item>
        <el-descriptions-item label="Prompt Token">{{ detail.record.promptTokens ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="Completion Token">
          {{ detail.record.completionTokens ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="Total Token">{{ detail.record.totalTokens ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="工具调用次数">{{ detail.record.toolCallCount ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="时间" :span="2">{{ detail.record.createTime }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.record.errorMsg" label="失败原因" :span="3">
          <span class="detail-error">{{ detail.record.errorMsg }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="detail-body">
        <div class="detail-label">消息正文</div>
        <template v-if="detail.record?.role === 'assistant'">
          <AiMarkdown v-if="detail.record.content" :content="detail.record.content" />
          <el-empty v-else description="无正文内容" :image-size="60" />
        </template>
        <pre v-else-if="detail.record?.content" class="detail-plain">{{ detail.record.content }}</pre>
        <el-empty v-else description="无正文内容" :image-size="60" />
      </div>

      <template #footer>
        <el-button @click="detail.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <UserSelect ref="userSelectRef" :multiple="false" @confirm-call-back="handleUserSelected"></UserSelect>
  </div>
</template>

<script setup name="AiAuditMessage" lang="ts">
import type { AgentVO } from '@/api/ai/agent/types';
import { exportMessageAuditUrl, listMessageAudit } from '@/api/ai/audit';
import type { MessageAuditQuery, MessageAuditVO } from '@/api/ai/audit/types';
import { optionSessionAgent } from '@/api/ai/session';
import type { UserVO } from '@/api/system/user/types';
import UserSelect from '@/components/UserSelect/index.vue';
import modal from '@/plugins/modal';
import { download } from '@/utils/request';
import AiMarkdown from '@/views/ai/components/AiMarkdown.vue';

const messageList = ref<MessageAuditVO[]>([]);
const agentOptions = ref<AgentVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const dateRange = ref<string[]>([]);
const userLabel = ref('');
const userSelectRef = ref<InstanceType<typeof UserSelect>>();
const queryFormRef = ref<ElFormInstance>();

const queryParams = ref<MessageAuditQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  role: undefined,
  status: undefined,
  sessionTitle: '',
  agentId: undefined,
  userId: undefined
});

const detail = reactive<{
  visible: boolean;
  record?: MessageAuditVO;
}>({
  visible: false,
  record: undefined
});

/** 查询消息审计列表 */
const getList = async () => {
  loading.value = true;
  try {
    const params: MessageAuditQuery = { ...queryParams.value };
    if (dateRange.value && dateRange.value.length === 2) {
      params.beginTime = `${dateRange.value[0]}T00:00:00`;
      params.endTime = `${dateRange.value[1]}T23:59:59`;
    }
    const res = await listMessageAudit(params);
    messageList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  } finally {
    loading.value = false;
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
  queryParams.value.pageNum = 1;
  getList();
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

const roleLabel = (role?: string) => {
  if (role === 'user') return '用户';
  if (role === 'assistant') return '助手';
  return role || '系统';
};

const roleTagType = (role?: string): ElTagType => {
  if (role === 'user') return 'primary';
  if (role === 'assistant') return 'success';
  return 'info';
};

/** 查看消息详情 */
const handleDetail = (row: Partial<MessageAuditVO>) => {
  detail.record = row as MessageAuditVO;
  detail.visible = true;
};

/** 导出留档 */
const handleExport = () => {
  const params: MessageAuditQuery = { ...queryParams.value };
  if (dateRange.value && dateRange.value.length === 2) {
    params.beginTime = `${dateRange.value[0]}T00:00:00`;
    params.endTime = `${dateRange.value[1]}T23:59:59`;
  }
  delete params.pageNum;
  delete params.pageSize;
  download(exportMessageAuditUrl, params, `消息审计_${new Date().getTime()}.xlsx`);
};

onMounted(() => {
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

.detail-summary {
  margin-bottom: 12px;
}

.detail-body {
  max-height: 50vh;
  overflow-y: auto;
}

.detail-label {
  margin-bottom: 6px;
  color: var(--app-text-muted, #909399);
  font-size: 12px;
}

.detail-plain {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--app-radius-base, 8px);
  background: var(--el-fill-color-light);
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-error {
  color: var(--el-color-danger);
}
</style>
