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
          <el-form-item label="日报日期">
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
          <el-form-item label="结论级别" prop="level">
            <el-select v-model="queryParams.level" placeholder="全部" clearable style="width: 140px">
              <el-option label="平稳" value="0" />
              <el-option label="需关注" value="1" />
              <el-option label="需处理" value="2" />
            </el-select>
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
            <h3>平台运行日报</h3>
            <p>
              每天一行汇总平台用量、任务、待办、权限与登录的结论，用于回顾当日运行状况与风险定级。
            </p>
          </div>
          <div class="toolbar-actions">
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <el-alert
        v-if="missingDates.length"
        type="warning"
        show-icon
        :closable="false"
        class="missing-alert"
      >
        <template #title>
          近 7 天缺失日报（截至昨天）：{{ missingDates.join('、') }}
          <span class="text-muted">— 请检查对应日期的定时任务是否执行成功</span>
        </template>
      </el-alert>

      <el-table v-loading="loading" border class="data-table" :data="digestList">
        <el-table-column label="日报日期" align="center" prop="digestDate" width="120" />
        <el-table-column label="结论级别" align="center" width="110">
          <template #default="scope">
            <el-tag :type="levelTagType(scope.row.level)" effect="plain">{{ levelLabel(scope.row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="总 Token" align="center" width="120">
          <template #default="scope">{{ formatNumber(scope.row.usageTotalTokens) }}</template>
        </el-table-column>
        <el-table-column label="请求" align="center" width="90">
          <template #default="scope">{{ formatNumber(scope.row.usageRequestCount) }}</template>
        </el-table-column>
        <el-table-column label="工具" align="center" width="90">
          <template #default="scope">{{ formatNumber(scope.row.usageToolCallCount) }}</template>
        </el-table-column>
        <el-table-column label="环比" align="center" width="110">
          <template #default="scope">{{ formatCompare(scope.row.usageComparePct) }}</template>
        </el-table-column>
        <el-table-column label="配额使用率" align="center" width="120">
          <template #default="scope">{{ formatQuota(scope.row.quotaUsagePct) }}</template>
        </el-table-column>
        <el-table-column label="任务" align="center" width="150">
          <template #default="scope">
            成功 {{ taskSuccess(scope.row) }} / 失败 {{ scope.row.taskFailCount ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column label="待办" align="left" width="180" :show-overflow-tooltip="true">
          <template #default="scope">
            <span>{{ scope.row.backlogCount ?? 0 }} 条</span>
            <span v-if="scope.row.backlogTopNode" class="text-muted">（最多 {{ scope.row.backlogTopNode }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="权限" align="center" width="180">
          <template #default="scope">
            允许 {{ scope.row.permAllowCount ?? 0 }} / 需确认 {{ scope.row.permAskCount ?? 0 }} / 拒绝
            {{ scope.row.permDenyCount ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column label="登录" align="center" width="190">
          <template #default="scope">
            成功 {{ scope.row.loginSuccessCount ?? 0 }} / 失败 {{ scope.row.loginFailCount ?? 0 }} / 新 IP
            {{ scope.row.loginAnomalyCount ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column label="落库时间" align="center" prop="createTime" width="170" />
        <el-table-column label="操作" align="center" width="90" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-button v-hasPermi="['ai:digest:query']" link type="primary" icon="View" @click="handleDetail(scope.row)">
              查看
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

    <el-dialog v-model="detail.visible" title="日报详情" width="820px" append-to-body>
      <div v-loading="detail.loading">
        <el-descriptions v-if="detail.record" :column="2" border size="small">
          <el-descriptions-item label="日报日期">{{ detail.record.digestDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="结论级别">
            <el-tag :type="levelTagType(detail.record.level)" effect="plain">
              {{ levelLabel(detail.record.level) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总 Token">{{ formatNumber(detail.record.usageTotalTokens) }}</el-descriptions-item>
          <el-descriptions-item label="请求">{{ formatNumber(detail.record.usageRequestCount) }}</el-descriptions-item>
          <el-descriptions-item label="工具">{{ formatNumber(detail.record.usageToolCallCount) }}</el-descriptions-item>
          <el-descriptions-item label="环比">{{ formatCompare(detail.record.usageComparePct) }}</el-descriptions-item>
          <el-descriptions-item label="配额使用率">{{ formatQuota(detail.record.quotaUsagePct) }}</el-descriptions-item>
          <el-descriptions-item label="任务执行">{{ detail.record.taskRunCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="任务失败">{{ detail.record.taskFailCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="待办积压">{{ detail.record.backlogCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="最多节点">{{ detail.record.backlogTopNode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="最久滞留">{{ formatWait(detail.record.backlogMaxWaitMinutes) }}</el-descriptions-item>
          <el-descriptions-item label="权限允许">{{ detail.record.permAllowCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="权限需确认">{{ detail.record.permAskCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="权限拒绝">{{ detail.record.permDenyCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="登录成功">{{ detail.record.loginSuccessCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="登录失败">{{ detail.record.loginFailCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="新 IP">{{ detail.record.loginAnomalyCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="来源任务执行">{{ detail.record.taskRunId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="来源任务">{{ detail.record.sessionId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="落库时间" :span="2">{{ detail.record.createTime || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-block">
          <div class="detail-label">日报正文</div>
          <pre class="detail-pre">{{ detail.record?.digestText || '-' }}</pre>
        </div>

        <div v-if="detail.record?.remark" class="detail-block">
          <div class="detail-label">备注</div>
          <pre class="detail-pre">{{ detail.record.remark }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup name="AiDigest" lang="ts">
import { getDailyDigest, listDailyDigest } from '@/api/ai/digest';
import type { AiDailyDigestQuery, AiDailyDigestVO } from '@/api/ai/digest/types';

const digestList = ref<AiDailyDigestVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const dateRange = ref<string[]>([]);
const queryFormRef = ref<ElFormInstance>();
/** 近 7 天（截至昨天）缺失日报的日期 */
const missingDates = ref<string[]>([]);

const queryParams = ref<AiDailyDigestQuery>({
  pageNum: 1,
  pageSize: 10,
  level: undefined
});

const detail = reactive<{
  visible: boolean;
  loading: boolean;
  record?: AiDailyDigestVO;
}>({
  visible: false,
  loading: false,
  record: undefined
});

/** 结论级别文案 */
const levelLabel = (level?: string | null) => {
  if (level === '0') return '平稳';
  if (level === '1') return '需关注';
  if (level === '2') return '需处理';
  return '未定级';
};

/** 结论级别标签颜色 */
const levelTagType = (level?: string | null): ElTagType => {
  if (level === '0') return 'success';
  if (level === '1') return 'warning';
  if (level === '2') return 'danger';
  return 'info';
};

/** 数值展示（千分位；空值显示 -） */
const formatNumber = (value?: number | null): string => {
  if (value === null || value === undefined) return '-';
  const num = Number(value);
  return Number.isNaN(num) ? '-' : num.toLocaleString();
};

/** 环比展示（带正负号，保留两位小数；空值显示 -） */
const formatCompare = (value?: number | null): string => {
  if (value === null || value === undefined) return '-';
  const num = Number(value);
  if (Number.isNaN(num)) return '-';
  return `${num >= 0 ? '+' : ''}${num.toFixed(2)}%`;
};

/** 配额使用率展示（保留两位小数；为空表示不限） */
const formatQuota = (value?: number | null): string => {
  if (value === null || value === undefined) return '不限';
  const num = Number(value);
  return Number.isNaN(num) ? '不限' : `${num.toFixed(2)}%`;
};

/** 最久滞留时长（分钟） */
const formatWait = (value?: number | null): string => {
  if (value === null || value === undefined) return '-';
  return `${value} 分钟`;
};

/** 任务成功次数（执行 - 失败，兜底不为负） */
const taskSuccess = (row: Partial<AiDailyDigestVO>) => {
  const run = Number(row.taskRunCount ?? 0);
  const fail = Number(row.taskFailCount ?? 0);
  return Math.max(0, (Number.isNaN(run) ? 0 : run) - (Number.isNaN(fail) ? 0 : fail));
};

/** 组装含日期范围的查询参数 */
const buildParams = () => {
  const params: AiDailyDigestQuery = { ...queryParams.value };
  if (dateRange.value && dateRange.value.length === 2) {
    params.beginDate = dateRange.value[0];
    params.endDate = dateRange.value[1];
  }
  return params;
};

/** 本地日期格式化为 yyyy-MM-dd */
const toDateStr = (d: Date) => {
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
};

/** 计算「近 7 天（截至昨天）」缺失日报的日期（复用列表接口，不新增接口） */
const loadMissing = async () => {
  const days: string[] = [];
  const now = new Date();
  for (let i = 7; i >= 1; i--) {
    const d = new Date(now);
    d.setDate(now.getDate() - i);
    days.push(toDateStr(d));
  }
  const res = await listDailyDigest({
    pageNum: 1,
    pageSize: 50,
    beginDate: days[0],
    endDate: days[days.length - 1]
  });
  const exist = new Set((res.data?.rows ?? []).map(r => r.digestDate).filter((d): d is string => !!d));
  missingDates.value = days.filter(d => !exist.has(d));
};

/** 查询运行日报列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listDailyDigest(buildParams());
    digestList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
    try {
      await loadMissing();
    } catch {
      missingDates.value = [];
    }
  } finally {
    loading.value = false;
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

/** 查看日报详情（拉取完整正文与备注） */
const handleDetail = async (row: Partial<AiDailyDigestVO>) => {
  detail.visible = true;
  detail.record = row as AiDailyDigestVO;
  const digestDate = row.digestDate;
  if (!digestDate) {
    return;
  }
  detail.loading = true;
  try {
    const res = await getDailyDigest(digestDate);
    if (res.data) {
      detail.record = res.data;
    }
  } finally {
    detail.loading = false;
  }
};

onMounted(getList);
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

.missing-alert {
  margin-bottom: 12px;
}

.detail-block {
  margin-top: 14px;
}

.detail-label {
  margin-bottom: 6px;
  color: var(--app-text-muted);
  font-size: 13px;
}

.detail-pre {
  max-height: 260px;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  border-radius: 8px;
  background: var(--app-elevated-soft-bg);
  color: var(--app-text-title);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
