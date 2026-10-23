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
          <el-form-item label="场景" prop="scene">
            <el-select v-model="queryParams.scene" placeholder="全部" clearable style="width: 150px">
              <el-option label="用户输入" value="input" />
              <el-option label="模型输出" value="output" />
              <el-option label="渠道出站" value="channel_out" />
            </el-select>
          </el-form-item>
          <el-form-item label="动作" prop="action">
            <el-select v-model="queryParams.action" placeholder="全部" clearable style="width: 140px">
              <el-option label="只记录" value="log" />
              <el-option label="脱敏放行" value="mask" />
              <el-option label="阻断" value="block" />
            </el-select>
          </el-form-item>
          <el-form-item label="用户ID" prop="userId">
            <el-input
              v-model="queryParams.userId"
              placeholder="用户ID"
              clearable
              style="width: 160px"
              @keyup.enter="handleQuery"
            />
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

    <el-card shadow="hover" class="table-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>安全事件</h3>
            <p>
              记录每一次敏感词 / PII 命中：场景、命中动作、引擎、命中数与命中片段，用于复盘误伤与灰度的拦截效果。
            </p>
          </div>
          <div class="toolbar-actions">
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border class="data-table" :data="eventList">
        <template #empty>
          <el-empty description="暂无安全事件数据" :image-size="80" />
        </template>
        <el-table-column label="创建时间" align="center" prop="createTime" width="170" />
        <el-table-column label="场景" align="center" width="110">
          <template #default="scope">
            <el-tag :type="sceneTagType(scope.row.scene)" size="small" effect="plain">{{ sceneLabel(scope.row.scene) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="动作" align="center" width="110">
          <template #default="scope">
            <el-tag :type="actionTagType(scope.row.action)" size="small" effect="plain">{{ actionLabel(scope.row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="引擎" align="center" width="120">
          <template #default="scope">{{ engineLabel(scope.row.engine) }}</template>
        </el-table-column>
        <el-table-column label="用户" align="center" width="130">
          <template #default="scope">
            <span v-if="scope.row.userName">{{ scope.row.userName }}</span>
            <span v-else class="text-muted">{{ scope.row.userId ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="命中数" align="center" width="90">
          <template #default="scope">{{ scope.row.hitCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="命中片段" align="left" prop="textExcerpt" :show-overflow-tooltip="true" />
        <el-table-column label="操作" align="center" width="100" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-button
              v-hasPermi="['ai:safety:event:query']"
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

    <el-drawer v-model="detail.visible" title="安全事件详情" size="60%" append-to-body>
      <div v-loading="detail.loading" class="event-detail">
        <template v-if="detail.record">
          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">基本信息</span></template>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="事件ID">{{ detail.record.id ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ detail.record.createTime || '-' }}</el-descriptions-item>
              <el-descriptions-item label="场景">
                <el-tag :type="sceneTagType(detail.record.scene)" size="small" effect="plain">
                  {{ sceneLabel(detail.record.scene) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="动作">
                <el-tag :type="actionTagType(detail.record.action)" size="small" effect="plain">
                  {{ actionLabel(detail.record.action) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="引擎">{{ engineLabel(detail.record.engine) }}</el-descriptions-item>
              <el-descriptions-item label="命中数">{{ detail.record.hitCount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="用户">
                {{ detail.record.userName || detail.record.userId || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="任务ID">{{ detail.record.sessionId ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="消息ID">{{ detail.record.messageId ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="智能体ID">{{ detail.record.agentId ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="命中片段" :span="2">
                <span class="detail-error">{{ detail.record.textExcerpt || '-' }}</span>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">命中明细（hitsJson）</span></template>
            <pre class="detail-pre">{{ prettyHits }}</pre>
          </el-card>
        </template>
        <el-empty v-else-if="!detail.loading" description="暂无安全事件详情" :image-size="80" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup name="AiSafetyEvent" lang="ts">
import { listEvent, queryEvent } from '@/api/ai/safety';
import type { SafetyEventQuery, SafetyEventVO } from '@/api/ai/safety/types';

const eventList = ref<SafetyEventVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const dateRange = ref<string[]>([]);
const queryFormRef = ref<ElFormInstance>();

const queryParams = ref<SafetyEventQuery>({
  pageNum: 1,
  pageSize: 10,
  scene: undefined,
  action: undefined,
  userId: undefined
});

const detail = reactive<{
  visible: boolean;
  loading: boolean;
  record?: SafetyEventVO;
}>({
  visible: false,
  loading: false,
  record: undefined
});

/** 命中明细格式化（非法 JSON 时原样展示） */
const prettyHits = computed<string>(() => {
  const raw = detail.record?.hitsJson;
  if (!raw) {
    return '-';
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
});

/** 场景文案 */
const sceneLabel = (scene?: string) => {
  if (scene === 'input') return '用户输入';
  if (scene === 'output') return '模型输出';
  if (scene === 'channel_out') return '渠道出站';
  return scene || '-';
};

/** 场景标签颜色 */
const sceneTagType = (scene?: string): ElTagType => {
  if (scene === 'input') return 'primary';
  if (scene === 'output') return 'success';
  if (scene === 'channel_out') return 'warning';
  return 'info';
};

/** 动作文案 */
const actionLabel = (action?: string) => {
  if (action === 'log') return '只记录';
  if (action === 'mask') return '脱敏放行';
  if (action === 'block') return '阻断';
  return action || '-';
};

/** 动作标签颜色 */
const actionTagType = (action?: string): ElTagType => {
  if (action === 'block') return 'danger';
  if (action === 'mask') return 'warning';
  if (action === 'log') return 'info';
  return 'info';
};

/** 引擎文案 */
const engineLabel = (engine?: string) => {
  if (engine === 'local') return '本地词库';
  if (engine === 'external') return '外部 API';
  return engine || '-';
};

/** 组装含时间范围的查询参数 */
const buildParams = (): SafetyEventQuery => {
  const params: SafetyEventQuery = { ...queryParams.value };
  if (dateRange.value && dateRange.value.length === 2) {
    params.beginTime = dateRange.value[0];
    params.endTime = dateRange.value[1];
  }
  return params;
};

/** 查询列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listEvent(buildParams());
    eventList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
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
  queryParams.value.scene = undefined;
  queryParams.value.action = undefined;
  queryParams.value.userId = undefined;
  queryParams.value.pageNum = 1;
  getList();
};

/** 查看详情（先展示列表行，再拉取完整详情） */
const handleDetail = async (row: Partial<SafetyEventVO>) => {
  detail.visible = true;
  detail.record = row as SafetyEventVO;
  const id = row.id;
  if (id === undefined || id === null) {
    return;
  }
  detail.loading = true;
  try {
    const res = await queryEvent(id);
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
.text-muted {
  color: var(--app-text-muted, #909399);
}

.event-detail {
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
  word-break: break-all;
}

.detail-pre {
  max-height: 360px;
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