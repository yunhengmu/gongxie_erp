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
          <el-form-item label="专家" prop="expertAgentId">
            <el-select v-model="queryParams.expertAgentId" placeholder="全部" clearable filterable style="width: 200px">
              <el-option v-for="item in agentOptions" :key="item.id" :label="item.agentName" :value="item.id">
                <span>{{ item.agentName }}</span>
                <span v-if="item.status === '1'" class="text-muted">（已停用）</span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="动作" prop="action">
            <el-select v-model="queryParams.action" placeholder="全部" clearable style="width: 140px">
              <el-option label="自己回答" value="self" />
              <el-option label="路由专家" value="route" />
              <el-option label="指定专家" value="pinned" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 150px">
              <el-option label="执行中" value="running" />
              <el-option label="待人工确认" value="pending" />
              <el-option label="成功" value="success" />
              <el-option label="降级" value="degraded" />
              <el-option label="中止" value="aborted" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间范围">
            <el-date-picker
              v-model="dateRange"
              type="datetimerange"
              value-format="YYYY-MM-DD HH:mm:ss"
              range-separator="-"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              style="width: 360px"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
            <el-button icon="Refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>

    <el-card shadow="hover" class="stat-panel">
      <div v-loading="statLoading" class="stat-grid">
        <div class="stat-item">
          <div class="stat-label">编排总数</div>
          <div class="stat-value">{{ stat.totalCount ?? 0 }}</div>
          <div class="stat-sub">自己回答 {{ stat.selfCount ?? 0 }} · 路由专家 {{ stat.routeCount ?? 0 }}（其中用户指定 {{ stat.pinnedCount ?? 0 }}）</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">路由命中率</div>
          <div class="stat-value">{{ formatRate(stat.routeHitRate) }}</div>
          <div class="stat-sub">路由数 ÷ 编排总数</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">降级率</div>
          <div class="stat-value">{{ formatRate(stat.degradeRate) }}</div>
          <div class="stat-sub">降级 ÷ 已终结（成功 {{ stat.successCount ?? 0 }} / 降级 {{ stat.degradedCount ?? 0 }} / 中止 {{ stat.abortedCount ?? 0 }}）</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">待人工确认</div>
          <div class="stat-value">{{ stat.pendingCount ?? 0 }}</div>
          <div class="stat-sub">执行中 {{ stat.runningCount ?? 0 }} · 专家调用 {{ stat.expertCallTotal ?? 0 }} 次</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">平均耗时</div>
          <div class="stat-value">{{ formatDuration(stat.avgDurationMs) }}</div>
          <div class="stat-sub">合计 {{ formatDuration(stat.totalDurationMs) }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">Token 合计</div>
          <div class="stat-value">{{ formatNumber(stat.totalTokens) }}</div>
          <div class="stat-sub">
            专家轮 {{ formatNumber(sumTokens(stat.expertPromptTokens, stat.expertCompletionTokens)) }} ·
            入口轮 {{ formatNumber(stat.entryTokens) }}
          </div>
        </div>
      </div>
      <div class="stat-tip">统计与下方列表使用同一筛选条件；入口轮 Token 取自助手消息（含人工确认后的整合轮）。</div>
    </el-card>

    <el-card shadow="hover" class="table-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>编排运行</h3>
            <p>
              入口智能体每轮对话落一行：记录路由决策、任务片段与每次专家调用的契约、结论原文、Token 与耗时，用于复盘降级率与专家表现。
            </p>
          </div>
          <div class="toolbar-actions">
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border class="data-table" :data="runList">
        <template #empty>
          <el-empty description="暂无编排运行数据" :image-size="80" />
        </template>
        <el-table-column label="创建时间" align="center" prop="createTime" width="170" />
        <el-table-column label="会话标题" align="left" prop="sessionTitle" :show-overflow-tooltip="true" />
        <el-table-column label="入口智能体" align="center" prop="entryAgentName" width="140" :show-overflow-tooltip="true" />
        <el-table-column label="提问用户" align="center" width="110">
          <template #default="scope">
            <span v-if="scope.row.userName">{{ scope.row.userName }}</span>
            <span v-else class="text-muted">{{ scope.row.userId ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="动作" align="center" width="110">
          <template #default="scope">
            <el-tag :type="actionTagType(scope.row.action)" effect="plain">{{ actionLabel(scope.row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="150">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row.status)" effect="plain">{{ statusLabel(scope.row.status) }}</el-tag>
            <el-tooltip v-if="scope.row.degradeReason" :content="scope.row.degradeReason" placement="top">
              <el-icon class="status-tip-icon"><WarningFilled /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="专家" align="center" width="180" :show-overflow-tooltip="true">
          <template #default="scope">{{ scope.row.expertAgentNames || '-' }}</template>
        </el-table-column>
        <el-table-column label="专家次数" align="center" width="100">
          <template #default="scope">{{ scope.row.expertCallCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="耗时" align="center" width="100">
          <template #default="scope">{{ formatDuration(scope.row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="Token" align="center" width="110">
          <template #default="scope">{{ formatNumber(scope.row.totalTokens) }}</template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="100" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-button
              v-hasPermi="['ai:audit:route:query']"
              link
              type="primary"
              icon="View"
              @click="handleDetail(scope.row)"
            >
              详情
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
    </el-card>

    <el-drawer v-model="detail.visible" title="编排运行详情" size="65%" append-to-body>
      <div v-loading="detail.loading" class="run-detail">
        <template v-if="detail.record">
          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">基本信息</span></template>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="会话标题">{{ detail.record.sessionTitle || '-' }}</el-descriptions-item>
              <el-descriptions-item label="入口智能体">{{ detail.record.entryAgentName || '-' }}</el-descriptions-item>
              <el-descriptions-item label="提问用户">
                {{ detail.record.userName || detail.record.userId || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="动作">
                <el-tag :type="actionTagType(detail.record.action)" effect="plain">
                  {{ actionLabel(detail.record.action) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag :type="statusTagType(detail.record.status)" effect="plain">
                  {{ statusLabel(detail.record.status) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="专家调用次数">{{ detail.record.expertCallCount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="耗时">{{ formatDuration(detail.record.durationMs) }}</el-descriptions-item>
              <el-descriptions-item label="入口轮 Token">
                {{ formatNumber(detail.record.entryTokens) }}
                <span class="form-tip">
                  输入 {{ formatNumber(detail.record.entryPromptTokens) }} / 输出
                  {{ formatNumber(detail.record.entryCompletionTokens) }}
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="专家轮 Token">{{ formatNumber(detail.record.expertTokens) }}</el-descriptions-item>
              <el-descriptions-item label="创建时间" :span="3">{{ detail.record.createTime || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="detail.record.degradeReason" label="降级 / 中止原因" :span="3">
                <span class="detail-error">{{ detail.record.degradeReason }}</span>
              </el-descriptions-item>
              <el-descriptions-item v-if="detail.record.warningText" label="编排告警" :span="3">
                <span class="detail-warning">{{ detail.record.warningText }}</span>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">路由决策</span></template>
            <el-descriptions v-if="decisionFields.length" :column="2" border size="small">
              <el-descriptions-item v-for="item in decisionFields" :key="item.label" :label="item.label">
                {{ item.value }}
              </el-descriptions-item>
            </el-descriptions>
            <div class="context-block">
              <div class="context-title">决策原文</div>
              <pre class="detail-pre">{{ detail.record.decisionJson || '-' }}</pre>
            </div>
          </el-card>

          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">任务片段（下发给专家的上下文）</span></template>
            <pre class="detail-pre">{{ detail.record.taskContext || '-' }}</pre>
          </el-card>

          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">专家调用明细</span></template>
            <el-table v-if="expertCalls.length" :data="expertCalls" border size="small">
              <el-table-column type="expand">
                <template #default="scope">
                  <div class="expand-block">
                    <div class="context-title">专家输出（原文留证）</div>
                    <pre class="detail-pre">{{ scope.row.rawOutput || '-' }}</pre>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="第 N 次" align="center" prop="attemptNo" width="80" />
              <el-table-column label="类型" align="center" width="110">
                <template #default="scope">{{ callKindLabel(scope.row.callKind) }}</template>
              </el-table-column>
              <el-table-column label="专家" align="left" prop="expertAgentName" width="140" :show-overflow-tooltip="true" />
              <el-table-column label="状态" align="center" width="130">
                <template #default="scope">
                  <el-tag :type="callStatusTagType(scope.row.status)" effect="plain">
                    {{ callStatusLabel(scope.row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="失败原因" align="left" prop="validationError" :show-overflow-tooltip="true" />
              <el-table-column label="Token" align="center" width="110">
                <template #default="scope">
                  {{ formatNumber(sumTokens(scope.row.promptTokens, scope.row.completionTokens)) }}
                </template>
              </el-table-column>
              <el-table-column label="耗时" align="center" width="100">
                <template #default="scope">{{ formatDuration(scope.row.durationMs) }}</template>
              </el-table-column>
            </el-table>
            <span v-else class="text-muted">本轮未调用专家（自己回答）</span>
          </el-card>
        </template>
        <el-empty v-else-if="!detail.loading" description="暂无编排详情" :image-size="80" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup name="AiAuditRouteRun" lang="ts">
import type { AgentVO } from '@/api/ai/agent/types';
import { optionAuditAgents } from '@/api/ai/audit';
import { getRouteRun, listRouteRuns, routeRunStats } from '@/api/ai/route';
import type { RouteExpertCallVO, RouteRunQuery, RouteRunStatsVO, RouteRunVO } from '@/api/ai/route/types';

const { proxy } = getCurrentInstance() as any;

const runList = ref<RouteRunVO[]>([]);
const agentOptions = ref<AgentVO[]>([]);
const stat = ref<RouteRunStatsVO>({});
const loading = ref(true);
const statLoading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const dateRange = ref<string[]>([]);
const queryFormRef = ref<ElFormInstance>();

/**
 * 默认统计「近 7 天」：统计为内存聚合（与工具调用明细同款范式），
 * 无时间范围时会把全部编排拉回内存；用户可清空时间范围查看全量。
 */
const defaultDateRange = (): string[] => {
  const pad = (num: number) => String(num).padStart(2, '0');
  const fmt = (date: Date) =>
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
      date.getMinutes()
    )}:${pad(date.getSeconds())}`;
  const end = new Date();
  const begin = new Date(end.getTime() - 6 * 24 * 60 * 60 * 1000);
  begin.setHours(0, 0, 0, 0);
  return [fmt(begin), fmt(end)];
};

const queryParams = ref<RouteRunQuery>({
  pageNum: 1,
  pageSize: 10,
  sessionId: undefined,
  expertAgentId: undefined,
  action: undefined,
  status: undefined
});

const detail = reactive<{
  visible: boolean;
  loading: boolean;
  record?: RouteRunVO;
}>({
  visible: false,
  loading: false,
  record: undefined
});

/** 专家调用明细（缺失时兜底为空数组） */
const expertCalls = computed<RouteExpertCallVO[]>(() => detail.record?.expertCalls ?? []);

/** 路由决策原文解析出的关键字段（非法 JSON 时为空，仅展示原文） */
const decisionFields = computed<Array<{ label: string; value: string }>>(() => {
  const raw = detail.record?.decisionJson;
  if (!raw) {
    return [];
  }
  try {
    const parsed = JSON.parse(raw) as Record<string, any>;
    const fields: Array<{ label: string; value: string }> = [];
    const push = (label: string, value: any) => {
      if (value !== undefined && value !== null && value !== '') {
        fields.push({ label, value: String(value) });
      }
    };
    push('动作', parsed.action === 'pinned' ? '指定专家' : parsed.action === 'route' ? '路由专家' : parsed.action === 'self' ? '自己回答' : parsed.action);
    push('目标专家', parsed.expert_agent_key);
    push('置信度', parsed.confidence);
    push('决策理由', parsed.reason);
    return fields;
  } catch {
    return [];
  }
});

/** 耗时展示：小于 1000ms 显示毫秒，否则显示秒（保留 1 位小数） */
const formatDuration = (ms?: number | null): string => {
  if (ms === null || ms === undefined) return '-';
  const num = Number(ms);
  if (Number.isNaN(num)) return '-';
  if (num < 1000) return `${num} ms`;
  return `${(num / 1000).toFixed(1)} s`;
};

/** 数值展示（千分位；空值显示 -） */
const formatNumber = (value?: number | null): string => {
  if (value === null || value === undefined) return '-';
  const num = Number(value);
  return Number.isNaN(num) ? '-' : num.toLocaleString();
};

/** 两个可空数值求和（用于把「输入 + 输出」显示成一列） */
const sumTokens = (left?: number | null, right?: number | null): number | null => {
  if (left === null || left === undefined) {
    return right === null || right === undefined ? null : Number(right);
  }
  return Number(left) + (right === null || right === undefined ? 0 : Number(right));
};

/** 百分比展示（后端已保留 1 位；空值显示 -） */
const formatRate = (rate?: number | null): string => {
  if (rate === null || rate === undefined) return '-';
  return `${rate}%`;
};

/** 动作文案 */
const actionLabel = (action?: string) => {
  if (action === 'self') return '自己回答';
  if (action === 'route') return '路由专家';
  if (action === 'pinned') return '指定专家';
  return action || '-';
};

/** 动作标签颜色 */
const actionTagType = (action?: string): ElTagType => {
  if (action === 'route') return 'primary';
  if (action === 'pinned') return 'warning';
  if (action === 'self') return 'info';
  return 'info';
};

/** 编排状态文案 */
const statusLabel = (status?: string) => {
  const map: Record<string, string> = {
    running: '执行中',
    pending: '待人工确认',
    success: '成功',
    degraded: '降级',
    aborted: '中止'
  };
  return status ? map[status] || status : '-';
};

/** 编排状态标签颜色 */
const statusTagType = (status?: string): ElTagType => {
  const map: Record<string, ElTagType> = {
    running: 'primary',
    pending: 'warning',
    success: 'success',
    degraded: 'warning',
    aborted: 'info'
  };
  return status ? map[status] || 'info' : 'info';
};

/** 专家调用类型文案 */
const callKindLabel = (kind?: string) => {
  const map: Record<string, string> = {
    first: '首次',
    retry: '重试',
    switch: '换专家',
    resume: '确认后续跑'
  };
  return kind ? map[kind] || kind : '-';
};

/** 专家调用状态文案 */
const callStatusLabel = (status?: string) => {
  const map: Record<string, string> = {
    success: '已回答',
    invalid: '未完成',
    failed: '调用失败',
    timeout: '超时',
    pending_confirm: '待人工确认'
  };
  return status ? map[status] || status : '-';
};

/** 专家调用状态标签颜色 */
const callStatusTagType = (status?: string): ElTagType => {
  const map: Record<string, ElTagType> = {
    success: 'success',
    invalid: 'warning',
    failed: 'danger',
    timeout: 'danger',
    pending_confirm: 'warning'
  };
  return status ? map[status] || 'info' : 'info';
};

/** 组装含时间范围的查询参数（时间格式已由选择器产出 YYYY-MM-DD HH:mm:ss） */
const buildParams = (): RouteRunQuery => {
  const params: RouteRunQuery = { ...queryParams.value };
  if (dateRange.value && dateRange.value.length === 2) {
    params.beginTime = dateRange.value[0];
    params.endTime = dateRange.value[1];
  }
  return params;
};

/** 查询列表与统计（同一筛选条件） */
const getList = async () => {
  const params = buildParams();
  const statParams: RouteRunQuery = { ...params };
  delete statParams.pageNum;
  delete statParams.pageSize;
  loading.value = true;
  statLoading.value = true;
  try {
    const [statRes, listRes] = await Promise.all([routeRunStats(statParams), listRouteRuns(params)]);
    stat.value = statRes.data ?? {};
    runList.value = listRes.data?.rows ?? [];
    total.value = listRes.data?.total ?? 0;
  } finally {
    loading.value = false;
    statLoading.value = false;
  }
};

/** 查询专家选项（含已停用：历史数据里的专家可能已停用） */
const getAgentOptions = async () => {
  const res = await optionAuditAgents();
  agentOptions.value = res.data ?? [];
};

/** 应用地址栏带入的筛选（供从任务工作台 / 链路追踪深链直达某会话的编排） */
const applyRouteQuery = () => {
  const sessionId = proxy?.$route?.query?.sessionId;
  if (typeof sessionId === 'string' && sessionId) {
    queryParams.value.sessionId = sessionId;
  }
};

const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryFormRef.value?.resetFields();
  dateRange.value = defaultDateRange();
  queryParams.value.sessionId = undefined;
  queryParams.value.pageNum = 1;
  getList();
};

/** 查看详情（先展示列表行，再拉取含决策原文与专家明细的详情） */
const handleDetail = async (row: Partial<RouteRunVO>) => {
  detail.visible = true;
  detail.record = row as RouteRunVO;
  const id = row.id;
  if (id === undefined || id === null || id === '') {
    return;
  }
  detail.loading = true;
  try {
    const res = await getRouteRun(id);
    if (res.data) {
      detail.record = res.data;
    }
  } finally {
    detail.loading = false;
  }
};

onMounted(() => {
  dateRange.value = defaultDateRange();
  applyRouteQuery();
  getAgentOptions();
  getList();
});
</script>

<style scoped lang="scss">
/* 详情弹窗标签列给足宽度（≥7 汉字），避免短标签挤压与长标签换行 */
:deep(.el-descriptions__label) {
  min-width: 110px;
}
.text-muted {
  color: var(--app-text-muted, #909399);
}

.status-tip-icon {
  margin-left: 6px;
  color: var(--el-color-warning);
  cursor: help;
  vertical-align: middle;
}

.stat-panel {
  margin-bottom: 12px;
}

.stat-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.stat-item {
  flex: 1 1 180px;
  min-width: 180px;
  padding: 10px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.stat-label {
  color: var(--app-text-muted, #909399);
  font-size: 13px;
}

.stat-value {
  margin: 4px 0;
  font-size: 22px;
  font-weight: 600;
}

.stat-sub {
  color: var(--app-text-muted, #909399);
  font-size: 12px;
}

.stat-tip {
  margin-top: 10px;
  color: var(--app-text-muted, #909399);
  font-size: 12px;
}

.run-detail {
  min-height: 200px;
}

.detail-card {
  margin-bottom: 14px;
}

.detail-card-title {
  font-size: 14px;
  font-weight: 600;
}

.detail-error {
  color: var(--el-color-danger);
}

.detail-warning {
  color: var(--el-color-warning);
  white-space: pre-wrap;
}

.form-tip {
  margin-left: 10px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.context-block {
  margin-top: 14px;
}

.expand-block {
  margin-bottom: 10px;
}

.context-title {
  margin-bottom: 8px;
  color: var(--app-text-muted);
  font-size: 13px;
}

.detail-pre {
  max-height: 260px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border-radius: 4px;
  background: var(--el-fill-color-lighter);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>