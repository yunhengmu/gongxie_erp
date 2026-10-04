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
          <el-form-item label="工具编码" prop="toolCode">
            <el-input
              v-model="queryParams.toolCode"
              placeholder="请输入工具编码"
              clearable
              style="width: 180px"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="决策" prop="decision">
            <el-select v-model="queryParams.decision" placeholder="全部" clearable style="width: 140px">
              <el-option label="允许" value="0" />
              <el-option label="需确认" value="1" />
              <el-option label="拒绝" value="2" />
            </el-select>
          </el-form-item>
          <el-form-item label="确认结果" prop="confirmed">
            <el-select v-model="queryParams.confirmed" placeholder="全部" clearable style="width: 140px">
              <el-option label="已确认" value="1" />
              <el-option label="已拒绝" value="0" />
            </el-select>
          </el-form-item>
          <el-form-item label="会话ID" prop="sessionId">
            <el-input
              v-model="queryParams.sessionId"
              placeholder="请输入会话ID"
              clearable
              style="width: 180px"
              @keyup.enter="handleQuery"
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
            <h3>权限决策日志</h3>
            <p>记录每次工具授权的决策（允许 / 需确认 / 拒绝）与人工确认结果，包含被拒绝而从未执行的调用。</p>
          </div>
          <div class="toolbar-actions">
            <el-tag type="info" effect="plain">当前全局模式：{{ permissionMode || '-' }}</el-tag>
            <el-button v-hasPermi="['ai:perm:mode']" type="primary" plain icon="Setting" @click="handleModeDialog">
              切换模式
            </el-button>
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border class="data-table" :data="decisionList">
        <el-table-column label="时间" align="center" prop="createTime" width="170" />
        <el-table-column label="工具" align="left" width="180" :show-overflow-tooltip="true">
          <template #default="scope">{{ scope.row.toolName || scope.row.toolCode }}</template>
        </el-table-column>
        <el-table-column label="决策" align="center" width="110">
          <template #default="scope">
            <el-tag :type="decisionTagType(scope.row.decision)" effect="plain">
              {{ decisionLabel(scope.row.decision) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="确认结果" align="center" width="110">
          <template #default="scope">
            <span v-if="!scope.row.confirmed" class="text-muted">-</span>
            <el-tag v-else :type="scope.row.confirmed === '1' ? 'success' : 'danger'" effect="plain">
              {{ scope.row.confirmed === '1' ? '已确认' : '已拒绝' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" align="center" width="120" prop="decisionSource" :show-overflow-tooltip="true" />
        <el-table-column label="会话标题" align="left" prop="sessionTitle" :show-overflow-tooltip="true" />
        <el-table-column label="智能体" align="center" width="140" prop="agentName" :show-overflow-tooltip="true" />
        <el-table-column label="触发用户" align="center" width="120">
          <template #default="scope">
            <span v-if="scope.row.userName">{{ scope.row.userName }}</span>
            <span v-else class="text-muted">{{ scope.row.userId ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时(ms)" align="center" prop="durationMs" width="100" />
        <el-table-column label="操作" align="center" width="110" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-button v-hasPermi="['ai:perm:query']" link type="primary" icon="View" @click="handleDetail(scope.row)">
              查看入参
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

    <el-dialog v-model="detail.visible" title="决策详情" width="720px" append-to-body>
      <el-descriptions v-if="detail.record" :column="2" border size="small">
        <el-descriptions-item label="工具">{{ detail.record.toolName || detail.record.toolCode }}</el-descriptions-item>
        <el-descriptions-item label="决策">
          <el-tag :type="decisionTagType(detail.record.decision)" effect="plain">
            {{ decisionLabel(detail.record.decision) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="决策来源">{{ detail.record.decisionSource || '-' }}</el-descriptions-item>
        <el-descriptions-item label="命中规则">{{ detail.record.matchedRule || '-' }}</el-descriptions-item>
        <el-descriptions-item label="确认结果">
          <span v-if="!detail.record.confirmed">-</span>
          <span v-else>{{ detail.record.confirmed === '1' ? '已确认' : '已拒绝' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="确认范围">{{ detail.record.confirmSource || '-' }}</el-descriptions-item>
        <el-descriptions-item label="决策说明" :span="2">{{ detail.record.reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="会话标题">{{ detail.record.sessionTitle || '-' }}</el-descriptions-item>
        <el-descriptions-item label="智能体">{{ detail.record.agentName || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="detail-block">
        <div class="detail-label">工具入参</div>
        <pre class="detail-pre">{{ detail.record?.toolInput || '-' }}</pre>
      </div>
    </el-dialog>

    <el-dialog v-model="modeDialog.visible" title="切换全局工具权限模式" width="560px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="权限模式">
          <el-select v-model="modeDialog.mode" style="width: 100%">
            <el-option v-for="item in MODE_OPTIONS" :key="item.value" :label="item.label" :value="item.value">
              <span>{{ item.label }}</span>
              <span class="option-tip">{{ item.value }}</span>
            </el-option>
          </el-select>
        </el-form-item>
      </el-form>
      <div class="mode-tip">
        {{ MODE_OPTIONS.find((item) => item.value === modeDialog.mode)?.tip }}
      </div>
      <template #footer>
        <el-button @click="modeDialog.visible = false">取 消</el-button>
        <el-button type="primary" @click="handleModeSubmit">确 定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="AiPermissionDecision" lang="ts">
import { getPermissionMode, listPermissionDecision, updatePermissionMode } from '@/api/ai/permission';
import type { PermissionDecisionQuery, PermissionDecisionVO } from '@/api/ai/permission/types';
import modal from '@/plugins/modal';

/** 全局权限模式选项（与 AgentScope PermissionMode 对齐） */
const MODE_OPTIONS = [
  { value: 'DEFAULT', label: '默认（推荐）', tip: '未命中显式规则的工具一律需人工确认；平台内置的无害只读工具（如「获取当前时间」）已默认放行，无需绑定。' },
  { value: 'EXPLORE', label: '只读', tip: '只放行只读工具，拒绝其余（含写操作与命令），适合只允许检索的场景。' },
  { value: 'ACCEPT_EDITS', label: '自动放行编辑', tip: '自动放行只读工具，其余仍按规则判定。' },
  { value: 'BYPASS', label: '绕过', tip: '兜底放行一切，但显式的「拒绝 / 需确认」规则仍然优先生效。' },
  { value: 'DONT_ASK', label: '不询问', tip: '把「未命中规则时的默认需确认」转为拒绝；不改写显式的「需确认」规则。' }
];

const decisionList = ref<PermissionDecisionVO[]>([]);
const permissionMode = ref<string>('');
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const dateRange = ref<string[]>([]);
const queryFormRef = ref<ElFormInstance>();

const queryParams = ref<PermissionDecisionQuery>({
  pageNum: 1,
  pageSize: 10,
  toolCode: '',
  decision: undefined,
  confirmed: undefined,
  sessionId: ''
});

const detail = reactive<{
  visible: boolean;
  record?: PermissionDecisionVO;
}>({
  visible: false,
  record: undefined
});

const modeDialog = reactive<{
  visible: boolean;
  mode: string;
}>({
  visible: false,
  mode: 'DEFAULT'
});

/** 组装含时间范围的查询参数 */
const buildParams = () => {
  const params: PermissionDecisionQuery = { ...queryParams.value };
  if (dateRange.value && dateRange.value.length === 2) {
    params.beginTime = `${dateRange.value[0]}T00:00:00`;
    params.endTime = `${dateRange.value[1]}T23:59:59`;
  }
  return params;
};

/** 查询决策日志 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listPermissionDecision(buildParams());
    decisionList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  } finally {
    loading.value = false;
  }
};

/** 查询当前全局权限模式 */
const loadMode = async () => {
  const res = await getPermissionMode();
  permissionMode.value = res.data ?? '';
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

/** 决策标签颜色 */
const decisionTagType = (decision?: string): ElTagType => {
  if (decision === '0') return 'success';
  if (decision === '1') return 'warning';
  if (decision === '2') return 'danger';
  return 'info';
};

/** 决策文案 */
const decisionLabel = (decision?: string) => {
  if (decision === '0') return '允许';
  if (decision === '1') return '需确认';
  if (decision === '2') return '拒绝';
  return decision || '未知';
};

/** 查看入参 */
const handleDetail = (row: Partial<PermissionDecisionVO>) => {
  detail.record = row as PermissionDecisionVO;
  detail.visible = true;
};

/** 打开模式切换弹窗 */
const handleModeDialog = () => {
  modeDialog.mode = permissionMode.value || 'DEFAULT';
  modeDialog.visible = true;
};

/** 提交模式切换（运行期生效） */
const handleModeSubmit = async () => {
  await updatePermissionMode(modeDialog.mode);
  modal.msgSuccess('权限模式已切换');
  modeDialog.visible = false;
  await loadMode();
};

onMounted(() => {
  loadMode();
  getList();
});
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

.option-tip {
  float: right;
  color: var(--app-text-muted);
  font-size: 12px;
}

.mode-tip {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--app-elevated-soft-bg);
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.6;
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