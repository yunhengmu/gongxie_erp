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
          <el-form-item label="关键词" prop="keyword">
            <el-input
              v-model="queryParams.keyword"
              placeholder="请输入工具名 / 入参 / 出参"
              clearable
              style="width: 200px"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="工具名" prop="toolName">
            <el-input
              v-model="queryParams.toolName"
              placeholder="请输入工具名称"
              clearable
              style="width: 180px"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="状态" prop="state">
            <el-select v-model="queryParams.state" placeholder="全部" clearable style="width: 140px">
              <el-option label="成功" value="SUCCESS" />
              <el-option label="失败" value="ERROR" />
              <el-option label="中断" value="INTERRUPTED" />
              <el-option label="被拒绝" value="DENIED" />
            </el-select>
          </el-form-item>
          <el-form-item label="发起智能体" prop="agentId">
            <el-select v-model="queryParams.agentId" placeholder="全部" clearable filterable style="width: 200px">
              <el-option v-for="item in agentOptions" :key="item.id" :label="item.agentName" :value="item.id">
                <span>{{ item.agentName }}</span>
                <span v-if="item.status === '1'" class="text-muted">（已停用）</span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="任务ID" prop="sessionId">
            <el-select
              v-model="queryParams.sessionId"
              placeholder="输入或选择任务"
              clearable
              filterable
              allow-create
              style="width: 220px"
            >
              <el-option
                v-for="item in sessionOptions"
                :key="String(item.id)"
                :label="`${item.sessionTitle || '未命名任务'}（${item.id}）`"
                :value="String(item.id)"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="触发用户" prop="userId">
            <el-select
              v-model="queryParams.userId"
              placeholder="输入或选择用户"
              clearable
              filterable
              allow-create
              style="width: 200px"
            >
              <el-option
                v-for="item in userOptions"
                :key="String(item.userId)"
                :label="`${item.nickName || item.userName}（${item.userName}）`"
                :value="item.userId"
              />
            </el-select>
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
            <h3>工具调用明细</h3>
            <p>逐个记录智能体对业务工具的调用过程，可按工具维度统计失败率，辅助判断工具定义是否合理。</p>
          </div>
          <div class="toolbar-actions">
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <div class="section-block">
        <div class="section-title">按工具统计</div>
        <el-table v-loading="statLoading" border class="data-table" :data="statList">
          <el-table-column label="工具名" align="left" prop="toolName" :show-overflow-tooltip="true" />
          <el-table-column label="调用次数" align="center" prop="totalCount" width="110" />
          <el-table-column label="成功" align="center" prop="successCount" width="100" />
          <el-table-column label="失败" align="center" prop="failCount" width="100" />
          <el-table-column label="失败率(%)" align="center" width="120">
            <template #default="scope">
              <span :class="{ 'text-danger': (scope.row.failRate ?? 0) > 0 }">
                {{ formatRate(scope.row.failRate) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="平均耗时(ms)" align="center" width="130">
            <template #default="scope">{{ scope.row.avgDurationMs ?? '-' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!statLoading && !statList.length" description="暂无统计数据" :image-size="60" />
      </div>

      <div class="section-block">
        <div class="section-title">调用明细</div>
        <el-table v-loading="loading" border class="data-table" :data="toolCallList">
          <el-table-column label="时间" align="center" prop="createTime" width="170" />
          <el-table-column label="工具名" align="left" prop="toolName" :show-overflow-tooltip="true" />
          <el-table-column label="状态" align="center" width="100">
            <template #default="scope">
              <el-tag :type="stateTagType(scope.row.state)" effect="plain">{{ stateLabel(scope.row.state) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="任务标题" align="left" prop="sessionTitle" :show-overflow-tooltip="true" />
          <el-table-column label="发起智能体" align="center" prop="agentName" width="140" :show-overflow-tooltip="true" />
          <el-table-column label="触发用户" align="center" width="120">
            <template #default="scope">
              <span v-if="scope.row.userName">{{ scope.row.userName }}</span>
              <span v-else class="text-muted">{{ scope.row.userId ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时(ms)" align="center" prop="durationMs" width="100" />
          <el-table-column label="操作" align="center" width="120" class-name="small-padding fixed-width">
            <template #default="scope">
              <el-button
                v-hasPermi="['ai:audit:toolcall']"
                link
                type="primary"
                icon="View"
                @click="handleDetail(scope.row)"
              >
                查看入参/出参
              </el-button>
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
      </div>
    </el-card>

    <el-dialog v-model="detail.visible" title="工具调用详情" width="760px" append-to-body>
      <el-descriptions v-if="detail.record" :column="2" border size="small" class="detail-summary">
        <el-descriptions-item label="工具名">{{ detail.record.toolName }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="stateTagType(detail.record.state)" effect="plain">
            {{ stateLabel(detail.record.state) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="任务标题">{{ detail.record.sessionTitle || '-' }}</el-descriptions-item>
        <el-descriptions-item label="发起智能体">{{ detail.record.agentName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="触发用户">
          {{ detail.record.userName || detail.record.userId || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="耗时">{{ detail.record.durationMs ?? '-' }} ms</el-descriptions-item>
        <el-descriptions-item label="调用时间" :span="2">{{ detail.record.createTime }}</el-descriptions-item>
      </el-descriptions>

      <div class="detail-block">
        <div class="detail-label">调用入参</div>
        <pre class="detail-pre">{{ detail.record?.toolInput || '-' }}</pre>
      </div>
      <div class="detail-block">
        <div class="detail-label">结果摘要</div>
        <pre class="detail-pre">{{ detail.record?.toolOutput || '-' }}</pre>
      </div>

      <template #footer>
        <el-button @click="detail.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="AiAuditToolCall" lang="ts">
import type { AgentVO } from '@/api/ai/agent/types';
import { listToolCallAudit, optionAuditAgents, toolCallStats } from '@/api/ai/audit';
import type { ToolCallAuditQuery, ToolCallAuditVO, ToolCallStatVO } from '@/api/ai/audit/types';
import { listChatSessions } from '@/api/ai/session';
import type { SessionVO } from '@/api/ai/session/types';
import { listUser } from '@/api/system/user';
import type { UserVO } from '@/api/system/user/types';

const toolCallList = ref<ToolCallAuditVO[]>([]);
const statList = ref<ToolCallStatVO[]>([]);
const agentOptions = ref<AgentVO[]>([]);
const sessionOptions = ref<SessionVO[]>([]);
const userOptions = ref<UserVO[]>([]);
const loading = ref(true);
const statLoading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const dateRange = ref<string[]>([]);
const queryFormRef = ref<ElFormInstance>();

const queryParams = ref<ToolCallAuditQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  toolName: '',
  state: undefined,
  agentId: undefined,
  sessionId: '',
  userId: ''
});

const detail = reactive<{
  visible: boolean;
  record?: ToolCallAuditVO;
}>({
  visible: false,
  record: undefined
});

/** 组装含时间范围的查询参数 */
const buildParams = () => {
  const params: ToolCallAuditQuery = { ...queryParams.value };
  if (dateRange.value && dateRange.value.length === 2) {
    params.beginTime = `${dateRange.value[0]}T00:00:00`;
    params.endTime = `${dateRange.value[1]}T23:59:59`;
  }
  return params;
};

/** 查询明细与统计（同一份筛选条件） */
const getList = async () => {
  const params = buildParams();
  const statParams: ToolCallAuditQuery = { ...params };
  delete statParams.pageNum;
  delete statParams.pageSize;
  loading.value = true;
  statLoading.value = true;
  try {
    const [statRes, listRes] = await Promise.all([toolCallStats(statParams), listToolCallAudit(params)]);
    statList.value = statRes.data ?? [];
    toolCallList.value = listRes.data?.rows ?? [];
    total.value = listRes.data?.total ?? 0;
  } finally {
    loading.value = false;
    statLoading.value = false;
  }
};

const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryFormRef.value?.resetFields();
  dateRange.value = [];
  queryParams.value.pageNum = 1;
  getList();
};

/** 状态标签颜色 */
const stateTagType = (state?: string): ElTagType => {
  if (state === 'SUCCESS') return 'success';
  if (state === 'ERROR') return 'danger';
  if (state === 'INTERRUPTED') return 'warning';
  if (state === 'DENIED') return 'info';
  return 'info';
};

/** 状态文案 */
const stateLabel = (state?: string) => {
  if (state === 'SUCCESS') return '成功';
  if (state === 'ERROR') return '失败';
  if (state === 'INTERRUPTED') return '中断';
  if (state === 'DENIED') return '被拒绝';
  return state || '未知';
};

/** 失败率展示（为空显示 -） */
const formatRate = (rate?: number) => (rate === undefined || rate === null ? '-' : `${rate}%`);

/** 查看入参 / 出参 */
const handleDetail = (row: Partial<ToolCallAuditVO>) => {
  detail.record = row as ToolCallAuditVO;
  detail.visible = true;
};

/** 查询发起智能体选项（含已停用：历史数据的专家可能已被停用） */
const getAgentOptions = async () => {
  const res = await optionAuditAgents();
  agentOptions.value = res.data ?? [];
};

/** 加载任务与用户选项；无相关权限时静默，筛选框仍支持直接输入 ID */
const getFilterOptions = async () => {
  const [sessionRes, userRes] = await Promise.allSettled([
    listChatSessions({ pageNum: 1, pageSize: 200 } as any),
    listUser({ pageNum: 1, pageSize: 500 } as any)
  ]);
  if (sessionRes.status === 'fulfilled') {
    sessionOptions.value = sessionRes.value.data?.rows || [];
  }
  if (userRes.status === 'fulfilled') {
    userOptions.value = (userRes.value as unknown as { data?: { rows?: UserVO[] } }).data?.rows || [];
  }
};

getAgentOptions();
getFilterOptions();
getList();
</script>

<style scoped lang="scss">
/* 详情弹窗标签列给足宽度（≥5 汉字），避免短标签挤压与长标签换行 */
:deep(.el-descriptions__label) {
  min-width: 110px;
}
.table-heading p {
  margin: 6px 0 0;
  color: var(--app-text-muted);
  font-size: 13px;
}

.text-muted {
  color: var(--app-text-muted, #909399);
}

.text-danger {
  color: var(--el-color-danger);
}

.section-block {
  margin-bottom: 20px;

  &:last-child {
    margin-bottom: 0;
  }
}

.section-title {
  margin-bottom: 10px;
  color: var(--app-text-title);
  font-size: 15px;
  font-weight: 600;
}

.detail-summary {
  margin-bottom: 12px;
}

.detail-block {
  margin-bottom: 10px;
}

.detail-label {
  margin-bottom: 4px;
  color: var(--app-text-muted, #909399);
  font-size: 12px;
}

.detail-pre {
  max-height: 240px;
  margin: 0;
  padding: 8px 10px;
  overflow: auto;
  border-radius: var(--app-radius-base, 6px);
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
