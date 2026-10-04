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
          <el-form-item label="统计日期">
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
          <el-form-item label="智能体" prop="agentId">
            <el-select v-model="queryParams.agentId" placeholder="全部" clearable style="width: 180px">
              <el-option v-for="item in agentOptions" :key="String(item.id)" :label="item.agentName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="所属用户" prop="userId">
            <el-input
              v-model="queryParams.userId"
              placeholder="请输入用户ID"
              clearable
              style="width: 180px"
              @keyup.enter="handleQuery"
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
            <h3>用量与配额</h3>
            <p>按「日期 + 智能体 + 模型 + 用户」汇总 Token 与费用；费用按模型配置的单价估算，未配置单价时按 0 计。</p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:usage:export']" type="primary" plain icon="Download" @click="handleExport">
              导出
            </el-button>
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <div class="section-block">
        <div class="section-title">合计</div>
        <el-descriptions :column="5" border size="small">
          <el-descriptions-item label="总 Token">{{ overview.total?.totalTokens ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="输入 / 输出">
            {{ overview.total?.promptTokens ?? 0 }} / {{ overview.total?.completionTokens ?? 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="请求数">{{ overview.total?.requestCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="工具调用">{{ overview.total?.toolCallCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="费用(元)">{{ formatCost(overview.total?.cost) }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="section-block">
        <div class="section-title">按日期</div>
        <el-table v-loading="overviewLoading" border class="data-table" :data="overview.byDate">
          <el-table-column label="日期" align="center" prop="label" width="140" />
          <el-table-column label="输入 Token" align="center" prop="promptTokens" width="130" />
          <el-table-column label="输出 Token" align="center" prop="completionTokens" width="130" />
          <el-table-column label="总 Token" align="center" prop="totalTokens" width="130" />
          <el-table-column label="请求数" align="center" prop="requestCount" width="100" />
          <el-table-column label="工具调用" align="center" prop="toolCallCount" width="100" />
          <el-table-column label="费用(元)" align="center" width="130">
            <template #default="scope">{{ formatCost(scope.row.cost) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!overviewLoading && !overview.byDate.length" description="暂无数据" :image-size="60" />
      </div>

      <div class="section-block">
        <div class="section-title">按智能体 / 模型</div>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-table border class="data-table" :data="overview.byAgent">
              <el-table-column label="智能体" align="left" prop="label" :show-overflow-tooltip="true" />
              <el-table-column label="总 Token" align="center" prop="totalTokens" width="120" />
              <el-table-column label="费用(元)" align="center" width="120">
                <template #default="scope">{{ formatCost(scope.row.cost) }}</template>
              </el-table-column>
            </el-table>
          </el-col>
          <el-col :span="12">
            <el-table border class="data-table" :data="overview.byModel">
              <el-table-column label="模型" align="left" prop="label" :show-overflow-tooltip="true" />
              <el-table-column label="总 Token" align="center" prop="totalTokens" width="120" />
              <el-table-column label="费用(元)" align="center" width="120">
                <template #default="scope">{{ formatCost(scope.row.cost) }}</template>
              </el-table-column>
            </el-table>
          </el-col>
        </el-row>
      </div>

      <div class="section-block">
        <div class="section-title">用量明细</div>
        <el-table v-loading="loading" border class="data-table" :data="usageList">
          <el-table-column label="日期" align="center" prop="statDate" width="120" />
          <el-table-column label="智能体" align="left" prop="agentName" :show-overflow-tooltip="true" />
          <el-table-column label="模型" align="left" width="160" :show-overflow-tooltip="true">
            <template #default="scope">
              <span>{{ scope.row.modelName || scope.row.modelId || '-' }}</span>
              <el-tag v-if="scope.row.priceConfigured === false" class="price-tag" type="warning" effect="plain" size="small">
                未配置单价
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="用户" align="center" width="120">
            <template #default="scope">
              <span v-if="scope.row.userName">{{ scope.row.userName }}</span>
              <span v-else class="text-muted">{{ scope.row.userId ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="输入 Token" align="center" prop="promptTokens" width="120" />
          <el-table-column label="输出 Token" align="center" prop="completionTokens" width="120" />
          <el-table-column label="总 Token" align="center" prop="totalTokens" width="120" />
          <el-table-column label="请求数" align="center" prop="requestCount" width="90" />
          <el-table-column label="工具调用" align="center" prop="toolCallCount" width="100" />
          <el-table-column label="费用(元)" align="center" width="120">
            <template #default="scope">{{ formatCost(scope.row.cost) }}</template>
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
  </div>
</template>

<script setup name="AiUsage" lang="ts">
import { optionAgent } from '@/api/ai/agent';
import type { AgentVO } from '@/api/ai/agent/types';
import { exportUsageUrl, listUsageStat, usageOverview } from '@/api/ai/usage';
import type { UsageOverviewVO, UsageStatQuery, UsageStatVO } from '@/api/ai/usage/types';
import { download } from '@/utils/request';

const usageList = ref<UsageStatVO[]>([]);
const agentOptions = ref<AgentVO[]>([]);
const overview = ref<UsageOverviewVO>({ byDate: [], byAgent: [], byModel: [] });
const loading = ref(true);
const overviewLoading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const dateRange = ref<string[]>([]);
const queryFormRef = ref<ElFormInstance>();

const queryParams = ref<UsageStatQuery>({
  pageNum: 1,
  pageSize: 10,
  beginDate: undefined,
  endDate: undefined,
  agentId: undefined,
  userId: undefined
});

/** 组装含日期范围的查询参数 */
const buildParams = () => {
  const params: UsageStatQuery = { ...queryParams.value };
  if (dateRange.value && dateRange.value.length === 2) {
    params.beginDate = dateRange.value[0];
    params.endDate = dateRange.value[1];
  }
  return params;
};

/** 查询明细与总览（同一份筛选条件） */
const getList = async () => {
  const params = buildParams();
  const overviewParams: UsageStatQuery = { ...params };
  delete overviewParams.pageNum;
  delete overviewParams.pageSize;
  loading.value = true;
  overviewLoading.value = true;
  try {
    const [overviewRes, listRes] = await Promise.all([usageOverview(overviewParams), listUsageStat(params)]);
    overview.value = overviewRes.data ?? { byDate: [], byAgent: [], byModel: [] };
    usageList.value = listRes.data?.rows ?? [];
    total.value = listRes.data?.total ?? 0;
  } finally {
    loading.value = false;
    overviewLoading.value = false;
  }
};

/** 加载智能体下拉选项 */
const getAgentOptions = async () => {
  const res = await optionAgent();
  agentOptions.value = res.data ?? [];
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

/** 费用展示（保留 6 位小数，去尾零） */
const formatCost = (cost?: number) => {
  if (cost === undefined || cost === null) {
    return '0';
  }
  return Number(cost).toFixed(6).replace(/\.?0+$/, '') || '0';
};

/** 导出用量明细 */
const handleExport = () => {
  const params: UsageStatQuery = { ...buildParams() };
  delete params.pageNum;
  delete params.pageSize;
  download(exportUsageUrl, params, `用量统计_${new Date().getTime()}.xlsx`);
};

onMounted(() => {
  getAgentOptions();
  getList();
});
</script>

<style scoped lang="scss">
.table-heading p {
  margin: 6px 0 0;
  color: var(--app-text-muted);
  font-size: 13px;
}

.text-muted {
  color: var(--app-text-muted, #909399);
}

.price-tag {
  margin-left: 6px;
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
  font-size: 14px;
  font-weight: 600;
}
</style>