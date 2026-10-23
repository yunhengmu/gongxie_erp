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
          <el-form-item label="任务名称" prop="keyword">
            <el-input
              v-model="queryParams.keyword"
              placeholder="请输入任务名称"
              clearable
              style="width: 220px"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="智能体" prop="agentId">
            <el-select v-model="queryParams.agentId" placeholder="全部" clearable style="width: 200px">
              <el-option v-for="item in agentOptions" :key="item.id" :label="item.agentName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 140px">
              <el-option label="启用" value="0" />
              <el-option label="停用" value="1" />
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
            <h3>定时任务</h3>
            <p>
              按 Cron 定时运行智能体，产出即「后台任务结果」（以运行身份用户落在任务管理页，来源标记为定时任务）。
              调度由 SnailJob 承担，新增任务无需到 SnailJob 控制台登记；执行中的写工具若被配置为「需确认」，将直接拒绝而不悬挂等待。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:task:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:task:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:task:remove']"
              type="danger"
              plain
              icon="Delete"
              :disabled="multiple"
              @click="handleDelete()"
            >
              删除
            </el-button>
            <right-toolbar v-model:show-search="showSearch" :search="false" @query-table="getList"></right-toolbar>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border class="data-table" :data="taskList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="任务名称" align="left" prop="taskName" :show-overflow-tooltip="true" />
        <el-table-column label="智能体" align="center" width="160" :show-overflow-tooltip="true">
          <template #default="scope">
            <span>{{ scope.row.agentName || scope.row.agentCode }}</span>
            <el-tag v-if="scope.row.agentStatus === '1'" type="danger" size="small" effect="plain" class="inline-tag">
              已停用
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="运行身份" align="center" width="160" :show-overflow-tooltip="true">
          <template #default="scope">
            <span v-if="scope.row.runAsUserName">{{ scope.row.runAsUserName }}</span>
            <span v-else class="text-muted">{{ scope.row.runAsUserId ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Cron 表达式" align="center" prop="cronExpression" width="150" :show-overflow-tooltip="true" />
        <el-table-column label="状态" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'info'">
              {{ scope.row.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上次计划执行" align="center" prop="lastRunTime" width="170" />
        <el-table-column label="预计下次执行" align="center" prop="nextRunTime" width="170" />
        <el-table-column label="操作" align="center" width="250" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看" placement="top">
              <el-button
                v-hasPermi="['ai:task:query']"
                link
                type="primary"
                icon="View"
                @click="handleView(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="执行记录" placement="top">
              <el-button
                v-hasPermi="['ai:task:query']"
                link
                type="primary"
                icon="List"
                @click="handleRuns(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="立即执行" placement="top">
              <el-button
                v-hasPermi="['ai:task:run']"
                link
                type="warning"
                icon="VideoPlay"
                @click="handleRun(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:task:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:task:remove']"
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

    <el-dialog v-model="dialog.visible" :title="dialog.title" width="760px" append-to-body>
      <el-form ref="taskFormRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="任务名称" prop="taskName">
          <el-input v-model="form.taskName" placeholder="如：每日经营简报" />
        </el-form-item>
        <el-form-item label="智能体" prop="agentId">
          <el-select v-model="form.agentId" placeholder="请选择启用中的智能体" style="width: 100%">
            <el-option v-for="item in agentOptions" :key="item.id" :label="item.agentName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="运行身份用户" prop="runAsUserId">
          <template #label>
            <span class="label-tip">
              运行身份用户
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">定时执行没有登录上下文，会话与数据权限一律按该用户落库；部门由该用户自动带出。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="runAsUserLabel"
            placeholder="请选择运行身份用户"
            readonly
            clearable
            @click="handleSelectRunAsUser"
            @clear="handleClearRunAsUser"
          />
        </el-form-item>
        <el-form-item label="Cron 表达式" prop="cronExpression">
          <template #label>
            <span class="label-tip">
              Cron 表达式
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    6 位（秒 分 时 日 月 周），例：每天 09:00 = <code>0 0 9 * * *</code>；每小时整点 = <code>0 0 * * * *</code>。任务启用后按此排期运行。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.cronExpression" :placeholder="cronPlaceholder" />
        </el-form-item>
        <el-form-item label="任务提示词" prop="taskPrompt">
          <el-input
            v-model="form.taskPrompt"
            type="textarea"
            :rows="5"
            placeholder="每次执行时作为用户消息发送给智能体，如：汇总昨日新增公告，输出一份简报。"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio value="0">启用</el-radio>
            <el-radio value="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="buttonLoading" type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="runDialog.visible" title="执行记录" width="1000px" append-to-body>
      <div class="run-toolbar">
        <span class="text-muted">任务：{{ runDialog.taskName }}</span>
        <el-button link type="primary" icon="Refresh" @click="getRunList">刷新</el-button>
      </div>
      <el-table v-loading="runDialog.loading" border :data="runDialog.rows">
        <el-table-column label="触发方式" align="center" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.triggerType === '1' ? 'warning' : 'primary'" size="small" effect="plain">
              {{ scope.row.triggerType === '1' ? '手动' : '定时' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="100">
          <template #default="scope">
            <el-tag :type="runStatusType(scope.row.status)" size="small">
              {{ runStatusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" align="center" prop="startTime" width="170" />
        <el-table-column label="耗时" align="center" width="110">
          <template #default="scope">
            <span v-if="scope.row.durationMs !== null && scope.row.durationMs !== undefined">
              {{ scope.row.durationMs }} ms
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="Token" align="center" width="100">
          <template #default="scope">
            <span v-if="scope.row.totalTokens">{{ scope.row.totalTokens }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="工具调用" align="center" prop="toolCallCount" width="100" />
        <el-table-column label="结果" align="left" :show-overflow-tooltip="true">
          <template #default="scope">
            <span v-if="scope.row.errorMsg" class="run-error">{{ scope.row.errorMsg }}</span>
            <span v-else-if="scope.row.sessionId" class="text-muted">已在任务中留存回答</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="120" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看后台任务结果（任务明细）" placement="top">
              <el-button
                v-hasPermi="['ai:task:query']"
                link
                type="primary"
                icon="View"
                :disabled="!scope.row.sessionId"
                @click="goSession(scope.row)"
              ></el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
      <pagination
        v-show="runDialog.total > 0"
        v-model:page="runQuery.pageNum"
        v-model:limit="runQuery.pageSize"
        :total="runDialog.total"
        @pagination="getRunList"
      />
      <template #footer>
        <el-button @click="runDialog.visible = false">关 闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detail.visible" title="定时任务详情" width="860px" append-to-body>
      <template v-if="detail.record">
        <el-descriptions :column="2" border size="small" class="detail-summary">
          <el-descriptions-item label="任务名称">{{ detail.record.taskName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="智能体">
            <span>{{ detail.record.agentName || detail.record.agentCode || '-' }}</span>
            <el-tag v-if="detail.record.agentStatus === '1'" type="danger" size="small" effect="plain" class="inline-tag">
              已停用
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="运行身份用户">
            {{ detail.record.runAsUserName || detail.record.runAsUserId || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="运行身份部门">{{ detail.record.runAsDeptName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Cron 表达式">{{ detail.record.cronExpression || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.record.status === '0' ? 'success' : 'info'">
              {{ detail.record.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="上次计划执行">{{ detail.record.lastRunTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="预计下次执行">{{ detail.record.nextRunTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.record.remark || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.record.createTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detail.record.updateTime || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-block">
          <div class="detail-label">任务提示词</div>
          <pre v-if="detail.record.taskPrompt" class="detail-plain">{{ detail.record.taskPrompt }}</pre>
          <el-empty v-else description="未配置" :image-size="60" />
        </div>
      </template>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="detail.visible = false">关 闭</el-button>
        </div>
      </template>
    </el-dialog>

    <UserSelect ref="userSelectRef" :multiple="false" @confirm-call-back="handleRunAsUserSelected"></UserSelect>
  </div>
</template>

<script setup name="AiAgentTask" lang="ts">
import type { AgentVO } from '@/api/ai/agent/types';
import type { TaskForm, TaskQuery, TaskRunVO, TaskVO } from '@/api/ai/task/types';
import { addTask, delTask, getTask, listTask, listTaskRun, optionTaskAgent, runTaskNow, updateTask } from '@/api/ai/task';
import type { UserVO } from '@/api/system/user/types';
import UserSelect from '@/components/UserSelect/index.vue';
import modal from '@/plugins/modal';

const router = useRouter();

const cronPlaceholder = '如：0 0 9 * * *';
const taskList = ref<TaskVO[]>([]);
const agentOptions = ref<AgentVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const buttonLoading = ref(false);
const runAsUserLabel = ref('');
const queryFormRef = ref<ElFormInstance>();
const taskFormRef = ref<ElFormInstance>();
const userSelectRef = ref<InstanceType<typeof UserSelect>>();

const queryParams = ref<TaskQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  agentId: undefined,
  status: undefined
});

const initFormData: TaskForm = {
  id: undefined,
  taskName: undefined,
  agentId: undefined,
  taskPrompt: undefined,
  cronExpression: undefined,
  runAsUserId: undefined,
  status: '0',
  remark: undefined
};

const dialog = reactive<{ visible: boolean; title: string }>({
  visible: false,
  title: ''
});

const form = ref<TaskForm>({ ...initFormData });

const runQuery = ref<PageQuery>({ pageNum: 1, pageSize: 10 });
const runDialog = reactive<{
  visible: boolean;
  loading: boolean;
  taskId?: string | number;
  taskName?: string;
  rows: TaskRunVO[];
  total: number;
}>({
  visible: false,
  loading: false,
  taskId: undefined,
  taskName: undefined,
  rows: [],
  total: 0
});

const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);

/** 详情弹窗（查看定时任务完整信息，只读） */
const detail = reactive<{ visible: boolean; record: TaskVO | null }>({
  visible: false,
  record: null
});

/** 表单校验规则 */
const rules = computed<ElFormRules>(() => ({
  taskName: [{ required: true, message: '任务名称不能为空', trigger: 'blur' }],
  agentId: [{ required: true, message: '请选择智能体', trigger: 'change' }],
  runAsUserId: [{ required: true, message: '请选择运行身份用户', trigger: 'change' }],
  cronExpression: [{ required: true, message: 'Cron 表达式不能为空', trigger: 'blur' }],
  taskPrompt: [{ required: true, message: '任务提示词不能为空', trigger: 'blur' }]
}));

/** 查询定时任务列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listTask(queryParams.value);
    taskList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  } finally {
    loading.value = false;
  }
};

/** 查询启用中的智能体选项 */
const getAgentOptions = async () => {
  const res = await optionTaskAgent();
  agentOptions.value = res.data ?? [];
};

const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryFormRef.value?.resetFields();
  queryParams.value.keyword = '';
  queryParams.value.agentId = undefined;
  queryParams.value.status = undefined;
  queryParams.value.pageNum = 1;
  getList();
};

const handleSelectionChange = (selection: TaskVO[]) => {
  ids.value = selection.map((item) => item.id);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};

/** 重置表单为初始值 */
const reset = () => {
  form.value = { ...initFormData };
  runAsUserLabel.value = '';
  taskFormRef.value?.resetFields();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.title = '新增定时任务';
  dialog.visible = true;
};

/** 修改按钮操作 */
const handleUpdate = async (row?: Partial<TaskVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getTask(id);
  const detail = res.data;
  form.value = {
    id: detail.id,
    taskName: detail.taskName,
    agentId: detail.agentId,
    taskPrompt: detail.taskPrompt,
    cronExpression: detail.cronExpression,
    runAsUserId: detail.runAsUserId,
    status: detail.status ?? '0',
    remark: detail.remark
  };
  runAsUserLabel.value = detail.runAsUserName ?? (detail.runAsUserId ? String(detail.runAsUserId) : '');
  dialog.title = '修改定时任务';
  dialog.visible = true;
};

/** 选择运行身份用户 */
const handleSelectRunAsUser = () => {
  userSelectRef.value?.open();
};

const handleRunAsUserSelected = (users: UserVO[]) => {
  const user = users?.[0];
  if (!user) {
    modal.msgWarning('请选择用户');
    return;
  }
  form.value.runAsUserId = user.userId;
  runAsUserLabel.value = user.nickName || user.userName;
};

const handleClearRunAsUser = () => {
  form.value.runAsUserId = undefined;
  runAsUserLabel.value = '';
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = () => {
  taskFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: TaskForm = { ...form.value };
      if (payload.id) {
        await updateTask(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addTask(payload);
        modal.msgSuccess('新增成功');
      }
      dialog.visible = false;
      await getList();
    } finally {
      buttonLoading.value = false;
    }
  });
};

/** 删除按钮操作 */
const handleDelete = async (row?: Partial<TaskVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm(
    row
      ? `是否确认删除定时任务「${row.taskName || row.id}」？历史执行记录将保留。`
      : `是否确认删除选中的 ${ids.value.length} 个定时任务？历史执行记录将保留。`
  );
  await delTask(target);
  modal.msgSuccess('删除成功');
  await getList();
};

/** 立即执行（异步提交，结果在执行记录中查看） */
const handleRun = async (row: Partial<TaskVO>) => {
  if (row.id === undefined || row.id === null) {
    return;
  }
  await modal.confirm(`是否立即执行任务「${row.taskName}」？该操作不影响原 Cron 排期。`);
  await runTaskNow(row.id);
  modal.msgSuccess('已提交执行，可在执行记录中查看结果');
  await handleRuns(row as TaskVO);
};

/** 查看按钮操作（拉取完整字段后只读展示） */
const handleView = async (row: Partial<TaskVO>) => {
  const res = await getTask(row.id);
  detail.record = (res.data ?? null) as TaskVO | null;
  detail.visible = true;
};

/** 查看执行记录 */
const handleRuns = async (row: Partial<TaskVO>) => {
  runDialog.visible = true;
  runDialog.taskId = row.id;
  runDialog.taskName = row.taskName;
  runQuery.value.pageNum = 1;
  await getRunList();
};

/** 查询执行记录 */
const getRunList = async () => {
  if (runDialog.taskId === undefined || runDialog.taskId === null) {
    return;
  }
  runDialog.loading = true;
  try {
    const res = await listTaskRun(runDialog.taskId, runQuery.value);
    runDialog.rows = res.data?.rows ?? [];
    runDialog.total = res.data?.total ?? 0;
  } finally {
    runDialog.loading = false;
  }
};

/** 跳转到任务管理页查看本次执行的完整对话（后台任务结果） */
const goSession = (row: TaskRunVO) => {
  if (!row.sessionId) {
    return;
  }
  router.push({
    path: '/bizbuddy/agent/session',
    query: { sessionId: String(row.sessionId), source: 'task' }
  });
};

const runStatusLabel = (status?: string) => {
  if (status === '0') return '成功';
  if (status === '1') return '失败';
  return '执行中';
};

const runStatusType = (status?: string) => {
  if (status === '0') return 'success';
  if (status === '1') return 'danger';
  return 'warning';
};

getAgentOptions();
getList();
</script>

<style scoped lang="scss">
/* 详情弹窗标签列给足宽度（≥5 汉字），避免短标签挤压与长标签换行 */
:deep(.el-descriptions__label) {
  min-width: 110px;
}
.text-muted {
  color: var(--app-text-muted, #909399);
}

.inline-tag {
  margin-left: 4px;
}

.table-heading p {
  margin: 6px 0 0;
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.run-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.run-error {
  color: var(--el-color-danger);
  font-size: 12px;
}

.detail-summary {
  margin-bottom: 12px;
}

.detail-block {
  max-height: 40vh;
  overflow-y: auto;
}

.detail-label {
  margin-bottom: 6px;
  color: var(--app-text-muted);
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
</style>
