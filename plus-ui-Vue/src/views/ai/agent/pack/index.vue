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
              placeholder="请输入场景包编码 / 名称 / 用途说明"
              clearable
              style="width: 240px"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="生效范围" prop="visibleScope">
            <el-select v-model="queryParams.visibleScope" placeholder="全部" clearable style="width: 140px">
              <el-option v-for="item in visibleScopeOptions" :key="item.value" :label="item.label" :value="item.value" />
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
            <h3>场景包</h3>
            <p>
              场景包按角色 / 部门把业务工具、外部 MCP 工具与技能打包；用户在创建任务时选定并固化到任务，
              包内能力叠加到智能体既有绑定之上（同一工具以包内决策优先）。<b>未选包的任务不接入任何 MCP 工具</b>。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:pack:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:pack:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:pack:remove']"
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

      <el-table v-loading="loading" border class="data-table" :data="packList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="场景包编码" align="center" prop="packCode" width="170" :show-overflow-tooltip="true" />
        <el-table-column label="场景包名称" align="center" prop="packName" width="160" :show-overflow-tooltip="true" />
        <el-table-column label="用途说明" align="left" prop="description" min-width="200" :show-overflow-tooltip="true" />
        <el-table-column label="包内能力" align="left" min-width="200">
          <template #default="scope">
            <el-tag size="small" effect="plain" class="cell-tag">业务工具 {{ scope.row.toolBindings?.length || 0 }}</el-tag>
            <el-tag size="small" effect="plain" type="warning" class="cell-tag">
              MCP 工具 {{ scope.row.mcpToolBindings?.length || 0 }}
            </el-tag>
            <el-tag size="small" effect="plain" type="success" class="cell-tag">
              技能 {{ scope.row.skillIds?.length || 0 }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生效范围" align="center" width="110">
          <template #default="scope">{{ visibleScopeLabel(scope.row.visibleScope) }}</template>
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
                v-hasPermi="['ai:pack:query']"
                link
                type="primary"
                icon="View"
                @click="handleView(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:pack:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:pack:remove']"
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

    <el-dialog v-model="dialog.visible" :title="dialog.title" width="900px" append-to-body>
      <el-form ref="packFormRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="场景包编码" prop="packCode">
          <template #label>
            <span class="label-tip">
              场景包编码
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">唯一标识，创建后不可修改；会作为任务快照保存。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="form.packCode"
            :disabled="!!form.id"
            placeholder="小写字母开头，可含数字、下划线、短横线"
          />
        </el-form-item>
        <el-form-item label="场景包名称" prop="packName">
          <el-input v-model="form.packName" placeholder="如：财务场景包 / 人力场景包" />
        </el-form-item>
        <el-form-item label="用途说明" prop="description">
          <template #label>
            <span class="label-tip">
              用途说明
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">创建任务时会展示给用户，请写清「适合哪些角色 / 什么场景使用」。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="适合哪些角色 / 什么场景使用" />
        </el-form-item>
        <el-form-item label="生效范围" prop="visibleScope">
          <el-radio-group v-model="form.visibleScope">
            <el-radio v-for="item in visibleScopeOptions" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="业务工具" prop="toolBindings">
          <template #label>
            <span class="label-tip">
              业务工具
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    叠加到智能体既有绑定之上；<b>同一工具以包内决策优先</b>。写入类工具建议设为「需确认」。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="selectedToolCodes" multiple clearable placeholder="请选择包内业务工具" style="width: 100%">
            <el-option v-for="item in toolOptions" :key="item.toolCode" :label="item.toolName" :value="item.toolCode">
              <span>{{ item.toolName }}</span>
              <span class="option-suffix">{{ item.toolType === '0' ? '只读' : '写入' }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.toolBindings?.length" label="工具授权">
          <div class="binding-list">
            <div v-for="bind in form.toolBindings" :key="bind.toolCode" class="binding-row">
              <span class="binding-name">{{ toolLabel(bind.toolCode) }}</span>
              <el-select v-model="bind.decision" size="small" style="width: 150px">
                <el-option v-for="item in DECISION_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="外部 MCP 工具" prop="mcpToolBindings">
          <template #label>
            <span class="label-tip">
              外部 MCP 工具
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    仅装配包内勾选项且必须在该连接的工具白名单内；<b>未选包的任务不接入任何 MCP 工具</b>。
                    连接未配置工具白名单时无可选项，请先到「MCP 客户端」页填写。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="selectedMcpKeys" multiple clearable placeholder="请选择包内 MCP 工具" style="width: 100%">
            <el-option-group
              v-for="group in mcpOptions"
              :key="String(group.mcpClientId)"
              :label="group.serverName"
            >
              <el-option
                v-for="toolName in group.tools || []"
                :key="mcpKey(group.mcpClientId, toolName)"
                :label="toolName"
                :value="mcpKey(group.mcpClientId, toolName)"
              />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.mcpToolBindings?.length" label="MCP 授权">
          <div class="binding-list">
            <div
              v-for="bind in form.mcpToolBindings"
              :key="mcpKey(bind.mcpClientId, bind.toolName)"
              class="binding-row"
            >
              <span class="binding-name">
                {{ serverNameOf(bind.mcpClientId) }} · {{ bind.toolName }}
              </span>
              <el-select v-model="bind.decision" size="small" style="width: 150px">
                <el-option v-for="item in DECISION_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="技能" prop="skillIds">
          <template #label>
            <span class="label-tip">
              技能
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    叠加到智能体既有技能绑定之上；仍按提问用户的技能生效范围过滤（仅列当前用户可见的技能）。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="form.skillIds" multiple clearable placeholder="请选择包内技能" style="width: 100%">
            <el-option v-for="item in skillOptions" :key="item.id" :label="item.name" :value="item.id" />
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

    <el-dialog v-model="detail.visible" title="场景包详情" width="900px" append-to-body>
      <template v-if="detail.record">
        <el-descriptions :column="2" border size="small" class="detail-summary">
          <el-descriptions-item label="场景包编码">{{ detail.record.packCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="场景包名称">{{ detail.record.packName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="生效范围">{{ visibleScopeLabel(detail.record.visibleScope) }}</el-descriptions-item>
          <el-descriptions-item label="归属部门">
            {{ detail.record.ownerDeptName || (detail.record.visibleScope === '1' ? '-' : '不适用') }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.record.status === '0' ? 'success' : 'danger'">
              {{ detail.record.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建人">{{ detail.record.createByName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="用途说明" :span="2">{{ detail.record.description || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务工具" :span="2">
            <template v-if="detail.record.toolBindings?.length">
              <el-tag
                v-for="bind in detail.record.toolBindings"
                :key="bind.toolCode"
                class="cell-tag"
                :type="decisionTagType(bind.decision)"
                effect="plain"
                size="small"
              >
                {{ bind.toolName || bind.toolCode }} · {{ decisionLabel(bind.decision) }}
              </el-tag>
            </template>
            <span v-else class="text-muted">未配置</span>
          </el-descriptions-item>
          <el-descriptions-item label="外部 MCP 工具" :span="2">
            <template v-if="detail.record.mcpToolBindings?.length">
              <el-tag
                v-for="bind in detail.record.mcpToolBindings"
                :key="mcpKey(bind.mcpClientId, bind.toolName)"
                class="cell-tag"
                :type="decisionTagType(bind.decision)"
                effect="plain"
                size="small"
              >
                {{ bind.serverName || bind.mcpClientId }} · {{ bind.toolName }} · {{ decisionLabel(bind.decision) }}
              </el-tag>
            </template>
            <span v-else class="text-muted">未配置（该会话不接入 MCP 工具）</span>
          </el-descriptions-item>
          <el-descriptions-item label="技能" :span="2">
            <template v-if="detail.record.skillNames?.length">
              <el-tag
                v-for="name in detail.record.skillNames"
                :key="name"
                class="cell-tag"
                type="success"
                effect="plain"
                size="small"
              >
                {{ name }}
              </el-tag>
            </template>
            <span v-else class="text-muted">未配置</span>
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.record.remark || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.record.createTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detail.record.updateTime || '-' }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="detail.visible = false">关 闭</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="AiScenarioPack" lang="ts">
import {
  addScenarioPack,
  delScenarioPack,
  getScenarioPack,
  listScenarioPack,
  scenarioPackMcpOptions,
  updateScenarioPack
} from '@/api/ai/pack';
import type {
  ScenarioPackForm,
  ScenarioPackMcpOptionVO,
  ScenarioPackMcpToolVO,
  ScenarioPackQuery,
  ScenarioPackVO
} from '@/api/ai/pack/types';
import { visibleSkillOptions } from '@/api/ai/skill';
import type { SkillOptionVO } from '@/api/ai/skill/types';
import { listTool } from '@/api/ai/tool';
import type { ToolVO } from '@/api/ai/tool/types';
import modal from '@/plugins/modal';

const VISIBLE_SCOPE_OPTIONS = [
  { label: '公开', value: '0' },
  { label: '本部门', value: '1' },
  { label: '仅本人', value: '2' }
];

/** 工具授权决策选项 */
const DECISION_OPTIONS = [
  { label: '允许', value: '0' },
  { label: '需确认', value: '1' },
  { label: '禁止', value: '2' }
];

const visibleScopeOptions = VISIBLE_SCOPE_OPTIONS;

const packList = ref<ScenarioPackVO[]>([]);
const toolOptions = ref<ToolVO[]>([]);
const mcpOptions = ref<ScenarioPackMcpOptionVO[]>([]);
const skillOptions = ref<SkillOptionVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const buttonLoading = ref(false);
const queryFormRef = ref<ElFormInstance>();
const packFormRef = ref<ElFormInstance>();

const queryParams = ref<ScenarioPackQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  status: undefined,
  visibleScope: undefined
});

const initFormData: ScenarioPackForm = {
  id: undefined,
  packCode: undefined,
  packName: undefined,
  description: undefined,
  visibleScope: '0',
  status: '0',
  remark: undefined,
  toolBindings: [],
  mcpToolBindings: [],
  skillIds: []
};

const dialog = reactive<{ visible: boolean; title: string }>({
  visible: false,
  title: ''
});

const form = ref<ScenarioPackForm>({ ...initFormData });

const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);

/** 详情弹窗（查看场景包完整信息，只读） */
const detail = reactive<{ visible: boolean; record: ScenarioPackVO | null }>({
  visible: false,
  record: null
});

/** MCP 工具选择值：连接主键::远端工具名 */
const mcpKey = (mcpClientId?: string | number, toolName?: string) => `${mcpClientId}::${toolName}`;

/** 业务工具多选（保留已选工具的授权决策） */
const selectedToolCodes = computed<string[]>({
  get: () => (form.value.toolBindings ?? []).map((bind) => bind.toolCode),
  set: (codes: string[]) => {
    const exists = new Map((form.value.toolBindings ?? []).map((bind) => [bind.toolCode, bind]));
    form.value.toolBindings = codes.map((code) => exists.get(code) ?? { toolCode: code, decision: '0' });
  }
});

/** MCP 工具多选（保留已选工具的授权决策） */
const selectedMcpKeys = computed<string[]>({
  get: () => (form.value.mcpToolBindings ?? []).map((bind) => mcpKey(bind.mcpClientId, bind.toolName)),
  set: (keys: string[]) => {
    const exists = new Map(
      (form.value.mcpToolBindings ?? []).map((bind) => [mcpKey(bind.mcpClientId, bind.toolName), bind])
    );
    form.value.mcpToolBindings = keys.map((key): ScenarioPackMcpToolVO => {
      const found = exists.get(key);
      if (found) {
        return found;
      }
      const idx = key.indexOf('::');
      return { mcpClientId: key.slice(0, idx), toolName: key.slice(idx + 2), decision: '0' };
    });
  }
});

/** 工具显示名（工具停用 / 删除时回落到编码，避免下拉与列表空白） */
const toolLabel = (toolCode?: string) =>
  toolOptions.value.find((item) => item.toolCode === toolCode)?.toolName ?? toolCode ?? '-';

/** Server 名称（连接停用 / 删除时回落到主键） */
const serverNameOf = (mcpClientId?: string | number) =>
  mcpOptions.value.find((item) => String(item.mcpClientId) === String(mcpClientId))?.serverName ??
  String(mcpClientId ?? '-');

const visibleScopeLabel = (value?: string) =>
  visibleScopeOptions.find((item) => item.value === value)?.label ?? value ?? '-';

const decisionLabel = (decision?: string) =>
  DECISION_OPTIONS.find((item) => item.value === decision)?.label ?? '允许';

const decisionTagType = (decision?: string): ElTagType => {
  if (decision === '1') {
    return 'warning';
  }
  if (decision === '2') {
    return 'danger';
  }
  return 'success';
};

/** 表单校验规则 */
const rules = computed<ElFormRules>(() => ({
  packCode: [
    { required: true, message: '场景包编码不能为空', trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9_-]{1,63}$/,
      message: '小写字母开头，仅含小写字母、数字、下划线或短横线，长度 2~64 位',
      trigger: 'blur'
    }
  ],
  packName: [{ required: true, message: '场景包名称不能为空', trigger: 'blur' }]
}));

/** 查询场景包列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listScenarioPack(queryParams.value);
    packList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  } finally {
    loading.value = false;
  }
};

/** 加载表单依赖的三类选项（逐项降级：单项失败仅置空该项并汇总警告） */
const loadOptions = async () => {
  const [toolRes, mcpRes, skillRes] = await Promise.allSettled([
    listTool({ pageNum: 1, pageSize: 200, status: '0' }),
    scenarioPackMcpOptions(),
    visibleSkillOptions()
  ]);
  const failed: string[] = [];
  if (toolRes.status === 'fulfilled') {
    toolOptions.value = toolRes.value.data?.rows ?? [];
  } else {
    failed.push('业务工具');
  }
  if (mcpRes.status === 'fulfilled') {
    mcpOptions.value = mcpRes.value.data ?? [];
  } else {
    failed.push('外部 MCP 工具');
  }
  if (skillRes.status === 'fulfilled') {
    skillOptions.value = skillRes.value.data ?? [];
  } else {
    failed.push('技能');
  }
  if (failed.length) {
    modal.msgWarning(`以下选项加载失败（可能缺少相应权限）：${failed.join('、')}`);
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
  queryParams.value.visibleScope = undefined;
  queryParams.value.pageNum = 1;
  getList();
};

const handleSelectionChange = (selection: ScenarioPackVO[]) => {
  ids.value = selection.map((item) => item.id as string | number);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};

/** 重置表单为初始值 */
const reset = () => {
  form.value = { ...initFormData, toolBindings: [], mcpToolBindings: [], skillIds: [] };
  packFormRef.value?.resetFields();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.title = '新增场景包';
  dialog.visible = true;
};

/** 查看按钮操作（拉取完整字段后只读展示） */
const handleView = async (row: Partial<ScenarioPackVO>) => {
  const res = await getScenarioPack(row.id as string | number);
  detail.record = (res.data ?? null) as ScenarioPackVO | null;
  detail.visible = true;
};

/** 修改按钮操作 */
const handleUpdate = async (row?: Partial<ScenarioPackVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getScenarioPack(id as string | number);
  const record = res.data;
  form.value = {
    id: record.id,
    packCode: record.packCode,
    packName: record.packName,
    description: record.description,
    visibleScope: record.visibleScope ?? '0',
    status: record.status ?? '0',
    remark: record.remark,
    toolBindings: (record.toolBindings ?? []).map((bind) => ({ ...bind })),
    mcpToolBindings: (record.mcpToolBindings ?? []).map((bind) => ({ ...bind })),
    skillIds: [...(record.skillIds ?? [])]
  };
  dialog.title = '修改场景包';
  dialog.visible = true;
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = () => {
  packFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: ScenarioPackForm = {
        ...form.value,
        toolBindings: (form.value.toolBindings ?? []).filter((bind) => !!bind.toolCode),
        mcpToolBindings: (form.value.mcpToolBindings ?? []).filter((bind) => !!bind.toolName),
        skillIds: (form.value.skillIds ?? []).filter((id) => id !== undefined && id !== null && id !== '')
      };
      if (payload.id) {
        await updateScenarioPack(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addScenarioPack(payload);
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
const handleDelete = async (row?: Partial<ScenarioPackVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm(
    row
      ? `是否确认删除场景包「${row.packName || row.id}」？`
      : `是否确认删除选中的 ${ids.value.length} 个场景包？`
  );
  await delScenarioPack(target as string | number | Array<string | number>);
  modal.msgSuccess('删除成功');
  await getList();
};

loadOptions();
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

.cell-tag {
  margin: 2px 4px 2px 0;
}

.option-suffix {
  float: right;
  color: var(--app-text-muted, #909399);
  font-size: 12px;
}

.binding-list {
  width: 100%;
  max-height: 220px;
  overflow-y: auto;
}

.binding-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
}

.binding-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-heading p {
  margin: 6px 0 0;
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.detail-summary {
  margin-bottom: 12px;
}
</style>