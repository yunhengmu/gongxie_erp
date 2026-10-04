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
          <el-form-item label="智能体" prop="agentId">
            <el-select v-model="queryParams.agentId" placeholder="全部" clearable style="width: 200px">
              <el-option v-for="item in agentOptions" :key="item.id" :label="item.agentName" :value="item.id">
                <span>{{ item.agentName }}</span>
                <span v-if="item.status === '1'" class="text-muted">（已停用）</span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="来源" prop="source">
            <el-select v-model="queryParams.source" placeholder="全部" clearable style="width: 140px">
              <el-option label="实时对话" value="chat" />
              <el-option label="定时任务" value="task" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 150px">
              <el-option label="执行中" value="running" />
              <el-option label="待人工确认" value="pending_confirm" />
              <el-option label="成功" value="success" />
              <el-option label="失败" value="failed" />
              <el-option label="中止" value="aborted" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型标识" prop="modelCode">
            <el-input
              v-model="queryParams.modelCode"
              placeholder="请输入模型标识"
              clearable
              style="width: 180px"
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
            <h3>链路追踪</h3>
            <p>
              一次任务落一条链路，记录执行当时的模型版本、上下文清单与状态迁移，可按会话、智能体、来源、状态与时间范围检索，用于回溯与排障。
            </p>
          </div>
          <div class="toolbar-actions">
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border class="data-table" :data="traceList">
        <template #empty>
          <el-empty description="暂无链路数据" :image-size="80" />
        </template>
        <el-table-column label="创建时间" align="center" prop="createTime" width="170" />
        <el-table-column label="智能体" align="center" prop="agentName" width="140" :show-overflow-tooltip="true" />
        <el-table-column label="会话标题" align="left" prop="sessionTitle" :show-overflow-tooltip="true" />
        <el-table-column label="来源" align="center" width="110">
          <template #default="scope">
            <el-tag :type="sourceTagType(scope.row.source)" effect="plain">{{ sourceLabel(scope.row.source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="160">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row.status)" effect="plain">{{ statusLabel(scope.row.status) }}</el-tag>
            <el-tooltip v-if="scope.row.errorMsg" :content="scope.row.errorMsg" placement="top">
              <el-icon class="status-error-icon"><WarningFilled /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="模型" align="center" width="150" :show-overflow-tooltip="true">
          <template #default="scope">{{ scope.row.modelCode || '-' }}</template>
        </el-table-column>
        <el-table-column label="耗时" align="center" width="110">
          <template #default="scope">{{ formatDuration(scope.row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="工具次数" align="center" width="100">
          <template #default="scope">{{ scope.row.toolCallCount ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="Token" align="center" width="100">
          <template #default="scope">{{ scope.row.totalTokens ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="100" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-button
              v-hasPermi="['ai:audit:trace:query']"
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

    <el-drawer v-model="detail.visible" title="链路详情" size="60%" append-to-body>
      <div v-loading="detail.loading" class="trace-detail">
        <template v-if="detail.record">
          <!-- 基本信息 -->
          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">基本信息</span></template>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="会话标题">{{ detail.record.sessionTitle || '-' }}</el-descriptions-item>
              <el-descriptions-item label="智能体">{{ detail.record.agentName || '-' }}</el-descriptions-item>
              <el-descriptions-item label="来源">
                <el-tag :type="sourceTagType(detail.record.source)" effect="plain">
                  {{ sourceLabel(detail.record.source) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag :type="statusTagType(detail.record.status)" effect="plain">
                  {{ statusLabel(detail.record.status) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="耗时">{{ formatDuration(detail.record.durationMs) }}</el-descriptions-item>
              <el-descriptions-item label="工具调用次数">{{ detail.record.toolCallCount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="输入 Token">{{ detail.record.promptTokens ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="输出 Token">{{ detail.record.completionTokens ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="合计 Token">{{ detail.record.totalTokens ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="创建时间" :span="3">{{ detail.record.createTime || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="detail.record.errorMsg" label="失败原因" :span="3">
                <span class="detail-error">{{ detail.record.errorMsg }}</span>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <!-- 模型版本（执行期快照） -->
          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">模型版本（执行期快照）</span></template>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="主对话模型">
                <span>{{ detail.record.modelCode || '-' }}</span>
                <span v-if="detail.record.modelProvider" class="form-tip">{{ detail.record.modelProvider }}</span>
                <span class="form-tip">
                  超时 {{ ctxNestedText('model', 'timeoutSeconds') }} s / 重试 {{ ctxNestedText('model', 'maxRetries') }} 次
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="压缩摘要模型">
                {{ detail.record.summaryModelCode || '未配置（回退主模型）' }}
              </el-descriptions-item>
              <el-descriptions-item label="记忆抽取模型">
                {{ detail.record.memoryModelCode || '未配置（回退主模型）' }}
              </el-descriptions-item>
              <el-descriptions-item label="子智能体默认模型">
                {{ detail.record.subagentModelCode || '未配置（回退主模型）' }}
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <!-- 上下文清单 -->
          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">上下文清单</span></template>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="工具白名单">
                <template v-if="ctxList('tools').length">
                  <el-tag v-for="item in ctxList('tools')" :key="item" class="context-tag" effect="plain">
                    {{ item }}
                  </el-tag>
                </template>
                <span v-else class="text-muted">-</span>
              </el-descriptions-item>
              <el-descriptions-item label="知识库">
                <template v-if="ctxList('kbIds').length">
                  <el-tag v-for="item in ctxList('kbIds')" :key="item" class="context-tag" effect="plain">
                    {{ item }}
                  </el-tag>
                </template>
                <span v-else class="text-muted">未绑定</span>
              </el-descriptions-item>
              <el-descriptions-item label="技能过滤键">
                <template v-if="ctxList('skills').length">
                  <el-tag v-for="item in ctxList('skills')" :key="item" class="context-tag" effect="plain">
                    {{ item }}
                  </el-tag>
                </template>
                <span v-else class="text-muted">-</span>
              </el-descriptions-item>
              <el-descriptions-item label="子智能体标识">
                <template v-if="ctxList('subagents').length">
                  <el-tag v-for="item in ctxList('subagents')" :key="item" class="context-tag" effect="plain">
                    {{ item }}
                  </el-tag>
                </template>
                <span v-else class="text-muted">-</span>
              </el-descriptions-item>
              <el-descriptions-item label="外部 MCP 工具">
                <template v-if="ctxList('mcpTools').length">
                  <el-tag v-for="item in ctxList('mcpTools')" :key="item" class="context-tag" effect="plain">
                    {{ item }}
                  </el-tag>
                </template>
                <span v-else class="text-muted">-</span>
              </el-descriptions-item>
              <el-descriptions-item label="全局权限模式">{{ ctxText('permissionMode') }}</el-descriptions-item>
              <el-descriptions-item label="单轮最大迭代">{{ ctxText('maxIters') }}</el-descriptions-item>
            </el-descriptions>

            <div class="context-block">
              <div class="context-title">工具决策（工具编码 → 决策）</div>
              <el-table v-if="toolDecisionList.length" :data="toolDecisionList" border size="small">
                <el-table-column label="工具编码" prop="code" :show-overflow-tooltip="true" />
                <el-table-column label="决策" align="center" width="140">
                  <template #default="scope">
                    <el-tag :type="decisionTagType(scope.row.decision)" effect="plain">
                      {{ decisionLabel(scope.row.decision) }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
              <span v-else class="text-muted">-</span>
            </div>

            <div v-for="section in CONTEXT_OBJECTS" :key="section.key" class="context-block">
              <div class="context-title">{{ section.title }}</div>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item v-for="field in section.fields" :key="field.key" :label="field.label">
                  <el-tag v-if="field.bool" :type="boolTagType(ctxObj(section.key)[field.key])" effect="plain">
                    {{ fmtBool(ctxObj(section.key)[field.key]) }}
                  </el-tag>
                  <span v-else>{{ fmtValue(ctxObj(section.key)[field.key]) }}</span>
                </el-descriptions-item>
              </el-descriptions>
            </div>
          </el-card>

          <!-- 状态迁移时间线 -->
          <el-card shadow="never" class="detail-card">
            <template #header><span class="detail-card-title">状态迁移时间线</span></template>
            <el-timeline v-if="transitionList.length">
              <el-timeline-item
                v-for="(item, index) in transitionList"
                :key="index"
                :timestamp="item.at || ''"
                placement="top"
              >
                <span class="transition-state">{{ transitionLabel(item.state) }}</span>
                <span v-if="item.detail" class="transition-detail">{{ item.detail }}</span>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else description="暂无状态迁移记录" :image-size="60" />
          </el-card>
        </template>
        <el-empty v-else-if="!detail.loading" description="暂无链路详情" :image-size="80" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import type { AgentVO } from '@/api/ai/agent/types';
import { optionSessionAgent } from '@/api/ai/session';
import { getTrace, listTrace } from '@/api/ai/trace';
import type { TraceQuery, TraceVO } from '@/api/ai/trace/types';

defineOptions({ name: 'AiTrace' });

/** 上下文清单中对象型配置的展示字段定义 */
interface ContextField {
  /** 字段键 */
  key: string;
  /** 字段中文名 */
  label: string;
  /** 是否为布尔字段（用标签呈现 是 / 否） */
  bool?: boolean;
}

/** 上下文清单中的一个对象型配置区块 */
interface ContextSection {
  /** 上下文中的键名 */
  key: string;
  /** 区块标题 */
  title: string;
  /** 展示字段 */
  fields: ContextField[];
}

/** 对象型配置区块定义（字段缺失时统一按空值兜底） */
const CONTEXT_OBJECTS: ContextSection[] = [
  {
    key: 'compaction',
    title: '上下文压缩（compaction）',
    fields: [
      { key: 'enabled', label: '启用', bool: true },
      { key: 'triggerMessages', label: '触发消息数' },
      { key: 'triggerTokens', label: '触发 Token 数' },
      { key: 'keepMessages', label: '保留消息数' },
      { key: 'flushBeforeCompact', label: '压缩前落盘', bool: true },
      { key: 'offloadBeforeCompact', label: '压缩前卸载', bool: true },
      { key: 'toolResultEviction', label: '工具结果回收', bool: true },
      { key: 'toolResultEvictionMaxChars', label: '回收字符上限' }
    ]
  },
  {
    key: 'plan',
    title: '计划模式（plan）',
    fields: [
      { key: 'enabled', label: '启用', bool: true },
      { key: 'planFileDirectory', label: '计划文件目录' },
      { key: 'taskListEnabled', label: '任务清单启用', bool: true }
    ]
  },
  {
    key: 'memory',
    title: '记忆（memory）',
    fields: [
      { key: 'enabled', label: '启用', bool: true },
      { key: 'maxContextTokens', label: '上下文 Token 上限' },
      { key: 'consolidationMaxTokens', label: '合并 Token 上限' },
      { key: 'dailyFileRetentionDays', label: '每日流水保留天数' },
      { key: 'sessionRetentionDays', label: '会话保留天数' }
    ]
  },
  {
    key: 'workspace',
    title: '工作区（workspace）',
    fields: [
      { key: 'enabled', label: '启用', bool: true },
      { key: 'additionalContextFiles', label: '附加上下文文件' }
    ]
  }
];

/** 状态迁移节点文案映射 */
const TRANSITION_LABELS: Record<string, string> = {
  start: '开始',
  tool_call: '调用工具',
  tool_error: '工具异常',
  pending_confirm: '等待人工确认',
  resume: '恢复执行',
  done: '完成',
  error: '失败'
};

const { proxy } = getCurrentInstance() as any;

const traceList = ref<TraceVO[]>([]);
const agentOptions = ref<AgentVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const dateRange = ref<string[]>([]);
const queryFormRef = ref<ElFormInstance>();

const queryParams = ref<TraceQuery>({
  pageNum: 1,
  pageSize: 10,
  sessionId: undefined,
  agentId: undefined,
  source: undefined,
  status: undefined,
  modelCode: ''
});

const detail = reactive<{
  visible: boolean;
  loading: boolean;
  record?: TraceVO;
}>({
  visible: false,
  loading: false,
  record: undefined
});

/** 上下文对象（后端可能为 null 或历史未采集，统一兜底为空对象） */
const ctx = computed<Record<string, any>>(() => (detail.record?.context as Record<string, any>) ?? {});

/** 通用值格式化（空值兜底 '-'，布尔转为是 / 否，数组用顿号连接） */
const fmtValue = (val: any): string => {
  if (val === null || val === undefined || val === '') return '-';
  if (typeof val === 'boolean') return val ? '是' : '否';
  if (Array.isArray(val)) return val.length ? val.map((item) => String(item)).join('、') : '-';
  return String(val);
};

/** 布尔值文本（缺失兜底 '-'） */
const fmtBool = (val: any): string => {
  if (val === null || val === undefined) return '-';
  return val ? '是' : '否';
};

/** 布尔标签颜色（真=success，假或缺失=info） */
const boolTagType = (val: any): ElTagType => (val ? 'success' : 'info');

/** 取上下文中的列表（空值或非数组一律兜底为空数组） */
const ctxList = (key: string): string[] => {
  const val = ctx.value?.[key];
  if (!Array.isArray(val)) return [];
  return val.filter((item) => item !== null && item !== undefined && item !== '').map((item) => String(item));
};

/** 取上下文中的子对象（空值或非对象一律兜底为空对象） */
const ctxObj = (key: string): Record<string, any> => {
  const val = ctx.value?.[key];
  return val && typeof val === 'object' && !Array.isArray(val) ? (val as Record<string, any>) : {};
};

/** 取上下文中的标量文本（空值兜底 '-'） */
const ctxText = (key: string) => fmtValue(ctx.value?.[key]);

/** 取嵌套对象中的标量文本（如 context.model.timeoutSeconds） */
const ctxNestedText = (parentKey: string, key: string) => fmtValue(ctxObj(parentKey)?.[key]);

/** 状态迁移时间线（缺失时兜底为空数组） */
const transitionList = computed(() => detail.record?.transitions ?? []);

/** 工具决策列表（对象 → 数组；对象缺失时为空数组） */
const toolDecisionList = computed(() => {
  const obj = ctxObj('toolDecisions');
  return Object.keys(obj).map((code) => ({ code, decision: obj[code] }));
});

/** 耗时格式化：小于 1000ms 显示毫秒，否则显示秒（保留 1 位小数） */
const formatDuration = (ms?: number | null): string => {
  if (ms === null || ms === undefined || ms === ('' as any)) return '-';
  const num = Number(ms);
  if (Number.isNaN(num)) return '-';
  if (num < 1000) return `${num} ms`;
  return `${(num / 1000).toFixed(1)} s`;
};

/** 来源文案 */
const sourceLabel = (source?: string) => {
  if (source === 'chat') return '实时对话';
  if (source === 'task') return '定时任务';
  return source || '-';
};

/** 来源标签颜色 */
const sourceTagType = (source?: string): ElTagType => {
  if (source === 'chat') return 'primary';
  if (source === 'task') return 'warning';
  return 'info';
};

/** 状态文案 */
const statusLabel = (status?: string) => {
  const map: Record<string, string> = {
    running: '执行中',
    pending_confirm: '待确认',
    success: '成功',
    failed: '失败',
    aborted: '中止'
  };
  return status ? map[status] || status : '-';
};

/** 状态标签颜色 */
const statusTagType = (status?: string): ElTagType => {
  const map: Record<string, ElTagType> = {
    running: 'primary',
    pending_confirm: 'warning',
    success: 'success',
    failed: 'danger',
    aborted: 'info'
  };
  return status ? map[status] || 'info' : 'info';
};

/** 工具决策文案（0 允许 / 1 需确认 / 2 禁止） */
const decisionLabel = (decision?: string | number) => {
  const val = String(decision);
  if (val === '0') return '允许';
  if (val === '1') return '需确认';
  if (val === '2') return '禁止';
  return '-';
};

/** 工具决策标签颜色 */
const decisionTagType = (decision?: string | number): ElTagType => {
  const val = String(decision);
  if (val === '0') return 'success';
  if (val === '1') return 'warning';
  if (val === '2') return 'danger';
  return 'info';
};

/** 状态迁移节点文案 */
const transitionLabel = (state?: string) => {
  if (state && TRANSITION_LABELS[state]) return TRANSITION_LABELS[state];
  return state || '-';
};

/** 组装含时间范围的查询参数（时间格式已由选择器产出 YYYY-MM-DD HH:mm:ss） */
const buildParams = () => {
  const params: TraceQuery = { ...queryParams.value };
  if (dateRange.value && dateRange.value.length === 2) {
    params.beginTime = dateRange.value[0];
    params.endTime = dateRange.value[1];
  }
  return params;
};

/** 查询链路追踪列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listTrace(buildParams());
    traceList.value = res.data?.rows ?? [];
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

/** 应用地址栏带入的筛选（供从会话管理页深链直达指定会话链路） */
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
  dateRange.value = [];
  queryParams.value.sessionId = undefined;
  queryParams.value.pageNum = 1;
  getList();
};

/** 查看链路详情（先展示列表行数据，再拉取含上下文与时间线的详情） */
const handleDetail = async (row: Partial<TraceVO>) => {
  detail.visible = true;
  detail.record = row as TraceVO;
  const id = row.id;
  if (id === undefined || id === null || id === '') {
    return;
  }
  detail.loading = true;
  try {
    const res = await getTrace(id);
    if (res.data) {
      detail.record = res.data;
    }
  } finally {
    detail.loading = false;
  }
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

.status-error-icon {
  margin-left: 6px;
  color: var(--el-color-danger);
  cursor: help;
  vertical-align: middle;
}

.trace-detail {
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

.form-tip {
  margin-left: 10px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.context-tag {
  margin: 0 6px 6px 0;
}

.context-block {
  margin-top: 14px;
}

.context-title {
  margin-bottom: 8px;
  color: var(--app-text-muted);
  font-size: 13px;
}

.transition-state {
  font-weight: 600;
}

.transition-detail {
  margin-left: 10px;
  color: var(--app-text-muted);
  font-size: 13px;
}
</style>