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
              placeholder="请输入子智能体标识 / 用途说明"
              clearable
              style="width: 240px"
              @keyup.enter="handleQuery"
            />
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
            <h3>子智能体</h3>
            <p>
              子智能体是平台级可复用定义，绑定给智能体后由主智能体在需要时委派任务。标识即 AgentScope 的 agent_id，
              创建后不可修改；工具白名单只允许只读工具，子智能体侧没有人工确认闸门。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:subagent:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:subagent:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:subagent:remove']"
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

      <el-table v-loading="loading" border class="data-table" :data="subagentList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="子智能体标识" align="center" prop="name" width="170" :show-overflow-tooltip="true" />
        <el-table-column label="用途说明" align="left" prop="description" :show-overflow-tooltip="true" />
        <el-table-column label="工作区模式" align="center" width="110">
          <template #default="scope">
            <el-tag :type="scope.row.workspaceMode === 'SHARED' ? 'info' : 'success'" size="small" effect="plain">
              {{ scope.row.workspaceMode === 'SHARED' ? '共享' : '隔离' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模型" align="center" width="150" :show-overflow-tooltip="true">
          <template #default="scope">
            <span v-if="scope.row.modelName">{{ scope.row.modelName }}</span>
            <span v-else class="text-muted">继承主模型</span>
          </template>
        </el-table-column>
        <el-table-column label="步数" align="center" prop="steps" width="80" />
        <el-table-column label="工具白名单" align="left" min-width="220">
          <template #default="scope">
            <template v-if="scope.row.toolNames && scope.row.toolNames.length">
              <el-tag v-for="name in scope.row.toolNames" :key="name" size="small" effect="plain" class="tool-tag">
                {{ name }}
              </el-tag>
            </template>
            <span v-else class="text-muted">继承主智能体的只读工具</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="80">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'danger'">
              {{ scope.row.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" align="center" prop="updateTime" width="170" />
        <el-table-column label="操作" align="center" width="200" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看" placement="top">
              <el-button
                v-hasPermi="['ai:subagent:query']"
                link
                type="primary"
                icon="View"
                @click="handleView(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:subagent:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:subagent:remove']"
                link
                type="primary"
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

    <el-dialog v-model="dialog.visible" :title="dialog.title" width="860px" append-to-body>
      <el-form ref="subagentFormRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="子智能体标识" prop="name">
          <template #label>
            <span class="label-tip">
              子智能体标识
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">标识即 AgentScope 的 agent_id，创建后不可修改。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="form.name"
            :disabled="!!form.id"
            placeholder="小写字母开头，可含数字、下划线、短横线"
          />
        </el-form-item>
        <el-form-item label="用途说明" prop="description">
          <template #label>
            <span class="label-tip">
              用途说明
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">主智能体据此判断何时委派，请写清「在什么场景下使用」。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="主智能体据此判断何时委派，请写清在什么场景下使用"
          />
        </el-form-item>
        <el-form-item label="系统提示词" prop="sysPrompt">
          <template #label>
            <span class="label-tip">
              系统提示词
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">子智能体的工作说明（工作流程、产出要求）。留空则使用框架通用子智能体提示词。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.sysPrompt" type="textarea" :rows="6" class="mono-textarea" placeholder="子智能体的工作说明（工作流程、产出要求）" />
        </el-form-item>
        <el-form-item label="工作区模式" prop="workspaceMode">
          <template #label>
            <span class="label-tip">
              工作区模式
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">隔离＝子智能体有独立工作区（推荐）；共享＝与主智能体共用工作区。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-radio-group v-model="form.workspaceMode">
            <el-radio value="ISOLATED">隔离</el-radio>
            <el-radio value="SHARED">共享</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="模型" prop="modelId">
          <template #label>
            <span class="label-tip">
              模型
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">留空则使用智能体的「子智能体默认模型」，仍为空则继承主模型。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="form.modelId" clearable placeholder="留空则继承" style="width: 100%">
            <el-option v-for="item in modelOptions" :key="item.id" :label="item.configName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="步数" prop="steps">
          <template #label>
            <span class="label-tip">
              步数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">单个子智能体一次委派内最多推理多少步。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.steps" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="工具白名单" prop="toolCodeList">
          <template #label>
            <span class="label-tip">
              工具白名单
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    <b>只允许只读工具</b>：子智能体侧没有人工确认闸门，写入类工具不可下放；留空表示继承主智能体的只读工具，且子智能体只能用到主智能体已绑定的只读工具。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="form.toolCodeList" multiple clearable placeholder="留空表示继承主智能体的只读工具" style="width: 100%">
            <el-option v-for="item in toolOptions" :key="item.toolCode" :label="item.toolName" :value="item.toolCode" />
          </el-select>
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

    <el-dialog v-model="detail.visible" title="子智能体详情" width="860px" append-to-body>
      <template v-if="detail.record">
        <el-descriptions :column="2" border size="small" class="detail-summary">
          <el-descriptions-item label="子智能体标识">{{ detail.record.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.record.status === '0' ? 'success' : 'danger'">
              {{ detail.record.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="工作区模式">{{ workspaceModeLabel(detail.record.workspaceMode) }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ detail.record.modelName || '继承主模型' }}</el-descriptions-item>
          <el-descriptions-item label="步数">{{ detail.record.steps ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建人">{{ detail.record.createByName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="用途说明" :span="2">{{ detail.record.description || '-' }}</el-descriptions-item>
          <el-descriptions-item label="工具白名单" :span="2">
            <template v-if="detail.record.toolNames?.length">
              <el-tag v-for="name in detail.record.toolNames" :key="name" size="small" effect="plain" class="tool-tag">
                {{ name }}
              </el-tag>
            </template>
            <span v-else class="text-muted">继承主智能体的只读工具</span>
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.record.remark || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.record.createTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detail.record.updateTime || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-block">
          <div class="detail-label">系统提示词</div>
          <pre v-if="detail.record.sysPrompt" class="detail-plain">{{ detail.record.sysPrompt }}</pre>
          <el-empty v-else description="留空，使用框架通用子智能体提示词" :image-size="60" />
        </div>
      </template>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="detail.visible = false">关 闭</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="AiSubagent" lang="ts">
import { addSubagent, delSubagent, getSubagent, listSubagent, updateSubagent } from '@/api/ai/subagent';
import type { SubagentForm, SubagentQuery, SubagentVO } from '@/api/ai/subagent/types';
import { optionModelConfig } from '@/api/ai/model/config';
import { listTool } from '@/api/ai/tool';
import type { ToolVO } from '@/api/ai/tool/types';
import modal from '@/plugins/modal';

/** 模型下拉选项（仅需标识与名称，便于编辑态补全回显） */
interface ModelOption {
  id: string | number;
  configName: string;
}

const subagentList = ref<SubagentVO[]>([]);
const modelOptions = ref<ModelOption[]>([]);
const toolOptions = ref<ToolVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const buttonLoading = ref(false);
const queryFormRef = ref<ElFormInstance>();
const subagentFormRef = ref<ElFormInstance>();

const queryParams = ref<SubagentQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  status: undefined
});

const initFormData: SubagentForm = {
  id: undefined,
  name: undefined,
  description: undefined,
  sysPrompt: undefined,
  workspaceMode: 'ISOLATED',
  modelId: undefined,
  steps: 10,
  toolCodeList: [],
  status: '0',
  remark: undefined
};

const dialog = reactive<{ visible: boolean; title: string }>({
  visible: false,
  title: ''
});

const form = ref<SubagentForm>({ ...initFormData });

const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);

/** 详情弹窗（查看子智能体完整信息，只读） */
const detail = reactive<{ visible: boolean; record: SubagentVO | null }>({
  visible: false,
  record: null
});

/** 工作区模式文案 */
const workspaceModeLabel = (value?: string) => (value === 'SHARED' ? '共享' : '隔离');

/** 表单校验规则 */
const rules = computed<ElFormRules>(() => ({
  name: [
    { required: true, message: '子智能体标识不能为空', trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9_-]{1,60}$/,
      message: '小写字母开头，仅含小写字母、数字、下划线或短横线，长度 2~61 位',
      trigger: 'blur'
    }
  ],
  description: [{ required: true, message: '用途说明不能为空', trigger: 'blur' }]
}));

/** 查询子智能体列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listSubagent(queryParams.value);
    subagentList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  } finally {
    loading.value = false;
  }
};

/** 加载对话模型选项 */
const loadModelOptions = async () => {
  const res = await optionModelConfig('chat');
  modelOptions.value = (res.data ?? []).map((item) => ({ id: item.id, configName: item.configName }));
};

/** 加载只读业务工具选项（工具类型 0＝只读） */
const loadToolOptions = async () => {
  const res = await listTool({ pageNum: 1, pageSize: 200, status: '0' });
  toolOptions.value = (res.data?.rows ?? []).filter((item) => item.toolType === '0');
};

/** 编辑回显：原模型已停用或不在启用选项中时补一条，避免下拉显示空白 */
const ensureModelOption = (id?: string | number | null, name?: string) => {
  if (id === undefined || id === null) {
    return;
  }
  const exists = modelOptions.value.some((item) => String(item.id) === String(id));
  if (!exists) {
    modelOptions.value.unshift({ id, configName: name ?? String(id) });
  }
};

const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryFormRef.value?.resetFields();
  queryParams.value.keyword = '';
  queryParams.value.status = undefined;
  queryParams.value.pageNum = 1;
  getList();
};

const handleSelectionChange = (selection: SubagentVO[]) => {
  ids.value = selection.map((item) => item.id as string | number);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};

/** 重置表单为初始值 */
const reset = () => {
  form.value = { ...initFormData, toolCodeList: [] };
  subagentFormRef.value?.resetFields();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.title = '新增子智能体';
  dialog.visible = true;
};

/** 查看按钮操作（拉取完整字段后只读展示） */
const handleView = async (row: Partial<SubagentVO>) => {
  const res = await getSubagent(row.id);
  detail.record = (res.data ?? null) as SubagentVO | null;
  detail.visible = true;
};

/** 修改按钮操作 */
const handleUpdate = async (row?: Partial<SubagentVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getSubagent(id);
  const detail = res.data;
  ensureModelOption(detail.modelId, detail.modelName);
  form.value = {
    id: detail.id,
    name: detail.name,
    description: detail.description,
    sysPrompt: detail.sysPrompt,
    workspaceMode: detail.workspaceMode ?? 'ISOLATED',
    modelId: detail.modelId ?? undefined,
    steps: detail.steps ?? 10,
    toolCodeList: [...(detail.toolCodeList ?? [])],
    status: detail.status ?? '0',
    remark: detail.remark
  };
  dialog.title = '修改子智能体';
  dialog.visible = true;
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = () => {
  subagentFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: SubagentForm = { ...form.value };
      payload.toolCodeList = (payload.toolCodeList ?? []).filter((code) => !!code);
      if (payload.id) {
        await updateSubagent(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addSubagent(payload);
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
const handleDelete = async (row?: Partial<SubagentVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm(
    row
      ? `是否确认删除子智能体「${row.name || row.id}」？`
      : `是否确认删除选中的 ${ids.value.length} 个子智能体？`
  );
  await delSubagent(target);
  modal.msgSuccess('删除成功');
  await getList();
};

loadModelOptions();
loadToolOptions();
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

.tool-tag {
  margin: 2px 4px 2px 0;
}

.table-heading p {
  margin: 6px 0 0;
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.mono-textarea :deep(textarea) {
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
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