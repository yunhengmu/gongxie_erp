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
              placeholder="请输入技能标识 / 用途说明"
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
            <h3>技能仓库</h3>
            <p>
              技能正文即 SKILL.md（frontmatter 由技能标识与用途说明自动拼装），可附参考资料；技能需绑定到智能体后才会装配，
              清单注入 system prompt、由模型按需加载，运行期还会按提问用户的技能生效范围过滤。使用计数取自工具调用明细中的技能加载调用；
              陈旧状态按最近使用时间与阈值（30 / 90 天）计算，<b>仅作标注，不会自动归档</b>。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:skill:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:skill:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:skill:remove']"
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

      <el-table v-loading="loading" border class="data-table" :data="skillList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="技能标识" align="center" prop="name" width="170" :show-overflow-tooltip="true" />
        <el-table-column label="用途说明" align="left" prop="description" :show-overflow-tooltip="true" />
        <el-table-column label="生效范围" align="center" width="100">
          <template #default="scope">{{ visibleScopeLabel(scope.row.visibleScope) }}</template>
        </el-table-column>
        <el-table-column label="参考资料" align="center" width="90">
          <template #default="scope">{{ scope.row.resourceCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="使用计数" align="center" width="90">
          <template #default="scope">{{ scope.row.useCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="最近使用" align="center" width="170">
          <template #default="scope">
            <span v-if="scope.row.lastUsedAt">{{ scope.row.lastUsedAt }}</span>
            <span v-else class="text-muted">从未使用</span>
          </template>
        </el-table-column>
        <el-table-column label="生命周期" align="center" width="110">
          <template #default="scope">
            <el-tag :type="lifecycleTagType(scope.row.lifecycleState)" size="small" effect="plain">
              {{ lifecycleLabel(scope.row.lifecycleState) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="80">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'info'">
              {{ scope.row.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" align="center" prop="updatedAt" width="170" />
        <el-table-column label="操作" align="center" width="190" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看 SKILL.md" placement="top">
              <el-button
                v-hasPermi="['ai:skill:query']"
                link
                type="primary"
                icon="View"
                @click="handlePreview(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:skill:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:skill:remove']"
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

    <el-dialog v-model="dialog.visible" :title="dialog.title" width="860px" append-to-body>
      <el-form ref="skillFormRef" :model="form" :rules="rules" label-width="140px">
        <el-form-item label="技能标识" prop="name">
          <template #label>
            <span class="label-tip">
              技能标识
              <el-tooltip v-if="form.id" placement="top">
                <template #content>
                  <div class="tip-content">技能标识创建后不可修改（已作为 AgentScope 的 skillId 使用）</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="form.name"
            :disabled="!!form.id"
            placeholder="如：weekly-report（小写字母开头，可含数字、下划线、短横线）"
          />
        </el-form-item>
        <el-form-item label="用途说明" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="一句话说明该技能解决什么问题、何时使用——模型据此判断是否加载该技能"
          />
        </el-form-item>
        <el-form-item label="SKILL.md 正文" prop="skillBody">
          <template #label>
            <span class="label-tip">
              SKILL.md 正文
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    frontmatter（name / description）由后端按上面的字段自动拼装，此处只写 Markdown 正文（步骤、约束、示例等）。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="form.skillBody"
            type="textarea"
            :rows="10"
            class="mono-textarea"
            :placeholder="skillBodyPlaceholder"
          />
        </el-form-item>
        <el-form-item label="参考资料">
          <div class="resource-list">
            <div v-for="(item, index) in form.resources" :key="index" class="resource-item">
              <el-input v-model="item.resourcePath" placeholder="相对路径，如 references/faq.md" style="width: 280px" />
              <el-input v-model="item.resourceContent" placeholder="文件内容" class="resource-content" />
              <el-button link type="danger" icon="Delete" @click="removeResource(index)"></el-button>
            </div>
            <el-button link type="primary" icon="Plus" @click="addResource">添加参考资料</el-button>
          </div>
        </el-form-item>
        <el-form-item label="生效范围" prop="visibleScope">
          <template #label>
            <span class="label-tip">
              生效范围
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    决定谁可以把它绑定给智能体，以及运行期是否对该提问用户生效；口径与智能体、知识库一致。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-radio-group v-model="form.visibleScope">
            <el-radio value="0">公开</el-radio>
            <el-radio value="1">本部门</el-radio>
            <el-radio value="2">仅本人</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.visibleScope === '1'" label="归属部门" prop="ownerDeptId">
          <el-tree-select
            v-model="form.ownerDeptId"
            :data="deptOptions"
            :props="{ value: 'id', label: 'label', children: 'children' } as any"
            value-key="id"
            placeholder="留空则取当前用户的部门"
            check-strictly
            clearable
            style="width: 100%"
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

    <el-dialog v-model="preview.visible" title="SKILL.md 预览" width="800px" append-to-body>
      <el-descriptions :column="2" border size="small" class="preview-summary">
        <el-descriptions-item label="技能标识">{{ preview.row?.name }}</el-descriptions-item>
        <el-descriptions-item label="使用计数">{{ preview.row?.useCount ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="参考资料">{{ preview.row?.resourceCount ?? 0 }} 个</el-descriptions-item>
        <el-descriptions-item label="生命周期">{{ lifecycleLabel(preview.row?.lifecycleState) }}</el-descriptions-item>
      </el-descriptions>
      <pre class="skill-preview">{{ preview.content }}</pre>
      <div v-if="preview.resources.length" class="preview-resources">
        <div class="preview-resources-title">参考资料</div>
        <el-collapse>
          <el-collapse-item
            v-for="item in preview.resources"
            :key="item.resourcePath"
            :title="item.resourcePath"
            :name="item.resourcePath"
          >
            <pre class="skill-preview">{{ item.resourceContent }}</pre>
          </el-collapse-item>
        </el-collapse>
      </div>
      <template #footer>
        <el-button @click="preview.visible = false">关 闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="AiSkill" lang="ts">
import type { SkillForm, SkillQuery, SkillResourceForm, SkillResourceVO, SkillVO } from '@/api/ai/skill/types';
import { addSkill, delSkill, getSkill, listSkill, updateSkill } from '@/api/ai/skill';
import type { DeptTreeVO } from '@/api/system/dept/types';
import { deptTreeSelect } from '@/api/system/user';
import modal from '@/plugins/modal';

const VISIBLE_SCOPE_OPTIONS = [
  { label: '公开', value: '0' },
  { label: '本部门', value: '1' },
  { label: '仅本人', value: '2' }
];

const LIFECYCLE_OPTIONS: Record<string, { label: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  active: { label: '活跃', type: 'success' },
  stale: { label: '陈旧', type: 'warning' },
  archived: { label: '待归档', type: 'danger' },
  disabled: { label: '已停用', type: 'info' }
};

const skillBodyPlaceholder = `## 使用步骤
1. ……
2. ……

## 约束
- ……`;

const skillList = ref<SkillVO[]>([]);
const deptOptions = ref<DeptTreeVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const buttonLoading = ref(false);
const queryFormRef = ref<ElFormInstance>();
const skillFormRef = ref<ElFormInstance>();

const queryParams = ref<SkillQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  status: undefined
});

const initFormData: SkillForm = {
  id: undefined,
  name: undefined,
  description: undefined,
  skillBody: undefined,
  ownerDeptId: undefined,
  visibleScope: '0',
  status: '0',
  remark: undefined,
  resources: []
};

const dialog = reactive<{ visible: boolean; title: string }>({
  visible: false,
  title: ''
});

const form = ref<SkillForm>({ ...initFormData });

const preview = reactive<{
  visible: boolean;
  row?: SkillVO;
  content: string;
  resources: SkillResourceVO[];
}>({
  visible: false,
  row: undefined,
  content: '',
  resources: []
});

const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);

/** 表单校验规则 */
const rules = computed<ElFormRules>(() => ({
  name: [
    { required: true, message: '技能标识不能为空', trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9_-]{1,60}$/,
      message: '小写字母开头，仅含小写字母、数字、下划线或短横线，长度 2~61 位',
      trigger: 'blur'
    }
  ],
  description: [{ required: true, message: '用途说明不能为空', trigger: 'blur' }]
}));

/** 查询技能列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listSkill(queryParams.value);
    skillList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  } finally {
    loading.value = false;
  }
};

/** 加载部门树（生效范围＝本部门时选择归属部门） */
const loadDeptOptions = async () => {
  const res = await deptTreeSelect();
  deptOptions.value = res.data ?? [];
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

const handleSelectionChange = (selection: SkillVO[]) => {
  ids.value = selection.map((item) => item.id);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};

/** 重置表单为初始值 */
const reset = () => {
  form.value = { ...initFormData, resources: [] };
  skillFormRef.value?.resetFields();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.title = '新增技能';
  dialog.visible = true;
};

/** 修改按钮操作 */
const handleUpdate = async (row?: Partial<SkillVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getSkill(id);
  const detail = res.data;
  form.value = {
    id: detail.id,
    name: detail.name,
    description: detail.description,
    skillBody: detail.skillBody,
    ownerDeptId: detail.ownerDeptId,
    visibleScope: detail.visibleScope ?? '0',
    status: detail.status ?? '0',
    remark: detail.remark,
    resources: (detail.resources ?? []).map((item) => ({
      resourcePath: item.resourcePath,
      resourceContent: item.resourceContent
    }))
  };
  dialog.title = '修改技能';
  dialog.visible = true;
};

/** 参考资料：新增一行 */
const addResource = () => {
  form.value.resources = form.value.resources ?? [];
  form.value.resources.push({ resourcePath: '', resourceContent: '' } as SkillResourceForm);
};

/** 参考资料：删除一行 */
const removeResource = (index: number) => {
  form.value.resources?.splice(index, 1);
};

/** 查看 SKILL.md */
const handlePreview = async (row: Partial<SkillVO>) => {
  if (row.id === undefined || row.id === null) {
    return;
  }
  const res = await getSkill(row.id);
  preview.row = res.data;
  preview.content = res.data?.skillContent ?? '';
  preview.resources = res.data?.resources ?? [];
  preview.visible = true;
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = () => {
  skillFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: SkillForm = { ...form.value };
      payload.resources = (payload.resources ?? []).filter((item) => item.resourcePath);
      if (payload.id) {
        await updateSkill(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addSkill(payload);
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
const handleDelete = async (row?: Partial<SkillVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm(
    row
      ? `是否确认删除技能「${row.name || row.id}」？参考资料将一并清理。`
      : `是否确认删除选中的 ${ids.value.length} 个技能？参考资料将一并清理。`
  );
  await delSkill(target);
  modal.msgSuccess('删除成功');
  await getList();
};

const visibleScopeLabel = (scope?: string) =>
  VISIBLE_SCOPE_OPTIONS.find((item) => item.value === scope)?.label ?? '公开';

const lifecycleLabel = (state?: string) => (state ? LIFECYCLE_OPTIONS[state]?.label ?? state : '-');

const lifecycleTagType = (state?: string) => LIFECYCLE_OPTIONS[state ?? '']?.type ?? 'info';

loadDeptOptions();
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

.resource-list {
  width: 100%;
}

.resource-item {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.resource-content {
  flex: 1;
}

.preview-summary {
  margin-bottom: 12px;
}

.skill-preview {
  max-height: 45vh;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  background: var(--el-fill-color-light);
  border-radius: var(--app-radius-base, 6px);
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.preview-resources {
  margin-top: 12px;
}

.preview-resources-title {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
}
</style>
