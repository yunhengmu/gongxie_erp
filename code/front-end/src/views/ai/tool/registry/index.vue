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
              placeholder="请输入工具编码 / 名称"
              clearable
              style="width: 220px"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="工具类型" prop="toolType">
            <el-select v-model="queryParams.toolType" placeholder="全部" clearable style="width: 140px">
              <el-option label="只读" value="0" />
              <el-option label="写入" value="1" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 140px">
              <el-option label="启用" value="0" />
              <el-option label="停用" value="1" />
            </el-select>
          </el-form-item>
          <el-form-item label="标签" prop="tagIds">
            <el-select
              v-model="tagFilter"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="全部（任一命中）"
              clearable
              style="width: 240px"
              @change="handleQuery"
            >
              <el-option v-for="item in tagOptions" :key="item.id" :label="item.tagName" :value="item.id">
                <span>{{ item.tagName }}</span>
                <span class="option-count">{{ item.useCount ?? 0 }} 个工具</span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="未分类" prop="noTag">
            <el-checkbox v-model="queryParams.noTag" @change="handleQuery">仅看未打标签</el-checkbox>
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
            <h3>业务工具注册</h3>
            <p>
              本页只登记工具的元数据与启停状态：工具的实现必须已在代码中存在（如 knowledge_search / create_notice / get_current_time），不会由本页动态生成。停用后立即不再装配。
              工具可打一个或多个标签用于分类与检索（标签仅用于管理面，不影响装配）。勾选多个工具后可「批量打标签」或「移除标签」。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:tool:tag:query']" type="warning" plain icon="CollectionTag" @click="openTagDialog">
              标签管理
            </el-button>
            <el-button
              v-hasPermi="['ai:tool:tag:add']"
              type="warning"
              plain
              icon="PriceTag"
              :disabled="multiple"
              @click="openBatchTagDialog('apply')"
            >
              批量打标签
            </el-button>
            <el-button
              v-hasPermi="['ai:tool:tag:remove']"
              type="info"
              plain
              icon="Remove"
              :disabled="multiple"
              @click="openBatchTagDialog('remove')"
            >
              移除标签
            </el-button>
            <el-button v-hasPermi="['ai:tool:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:tool:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:tool:remove']"
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

      <el-table v-loading="loading" border class="data-table" :data="toolList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="工具编码" align="center" prop="toolCode" width="180" :show-overflow-tooltip="true" />
        <el-table-column label="工具名称" align="center" prop="toolName" :show-overflow-tooltip="true" />
        <el-table-column label="类型" align="center" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.toolType === '1' ? 'warning' : 'primary'" effect="plain">
              {{ scope.row.toolType === '1' ? '写入' : '只读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标签" align="left" width="200">
          <template #default="scope">
            <template v-if="scope.row.tags && scope.row.tags.length">
              <el-tag v-for="tag in scope.row.tags" :key="tag.id" size="small" effect="plain" class="tag-item">
                {{ tag.tagName }}
              </el-tag>
            </template>
            <span v-else class="text-muted">未打标签</span>
          </template>
        </el-table-column>
        <el-table-column label="实现标识" align="center" prop="implName" width="160" :show-overflow-tooltip="true" />
        <el-table-column label="状态" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'info'">
              {{ scope.row.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" align="left" prop="description" :show-overflow-tooltip="true" />
        <el-table-column label="更新时间" align="center" prop="updateTime" width="170" />
        <el-table-column label="操作" align="center" width="200" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看" placement="top">
              <el-button
                v-hasPermi="['ai:tool:list']"
                link
                type="primary"
                icon="View"
                @click="handleView(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:tool:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:tool:remove']"
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

    <el-dialog v-model="dialog.visible" :title="dialog.title" width="720px" append-to-body>
      <el-form ref="toolFormRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="工具编码" prop="toolCode">
          <template #label>
            <span class="label-tip">
              工具编码
              <el-tooltip v-if="form.id" placement="top">
                <template #content>
                  <div class="tip-content">编码创建后不可修改</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="form.toolCode"
            :disabled="!!form.id"
            placeholder="如：knowledge_search（小写字母开头，可含数字与下划线）"
          />
        </el-form-item>
        <el-form-item label="工具名称" prop="toolName">
          <el-input v-model="form.toolName" placeholder="如：知识库检索" />
        </el-form-item>
        <el-form-item label="工具类型" prop="toolType">
          <el-radio-group v-model="form.toolType">
            <el-radio value="0">只读</el-radio>
            <el-radio value="1">写入</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="实现标识" prop="implName">
          <template #label>
            <span class="label-tip">
              实现标识
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    实现需已在代码中登记（未登记的工具会被跳过并记警告日志）。写入类工具同样会装配，其确认闸门由智能体定义页的「工具授权」决定。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.implName" placeholder="如：knowledge_search" />
        </el-form-item>
        <el-form-item label="参数定义" prop="paramsSchema">
          <el-input
            v-model="form.paramsSchema"
            type="textarea"
            :rows="8"
            class="mono-textarea"
            :placeholder="paramsSchemaPlaceholder"
          />
        </el-form-item>
        <el-form-item label="标签" prop="tagIds">
          <template #label>
            <span class="label-tip">
              标签
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    用于分类与检索（一个工具可打多个标签）；标签仅用于管理面，不影响工具装配与生效范围。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select
            v-model="form.tagIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="可多选；留空表示不打标签"
            style="width: 100%"
          >
            <el-option v-for="item in tagOptions" :key="item.id" :label="item.tagName" :value="item.id" />
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
        <el-form-item label="说明" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话说明该工具的用途，供模型理解" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="buttonLoading" type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 工具标签管理（挂在业务工具注册页内，不新增菜单页） -->
    <el-dialog v-model="tagDialog.visible" title="工具标签管理" width="860px" append-to-body>
      <div class="tag-search">
        <el-input
          v-model="tagQuery.keyword"
          placeholder="按标签名称搜索"
          clearable
          style="width: 240px"
          @keyup.enter="handleTagQuery"
        />
        <el-button icon="Search" @click="handleTagQuery">搜索</el-button>
      </div>

      <el-form ref="tagFormRef" :model="tagForm" :rules="tagRules" inline label-width="84px" class="tag-form">
        <el-form-item label="标签名称" prop="tagName">
          <el-input v-model="tagForm.tagName" placeholder="如：知识检索 / 通知类 / 实验性" style="width: 200px" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="tagForm.remark" placeholder="用途说明（选填）" style="width: 240px" />
        </el-form-item>
        <el-form-item>
          <el-button :loading="buttonLoading" type="primary" @click="submitTagForm">
            {{ tagForm.id ? '保存修改' : '新增标签' }}
          </el-button>
          <el-button v-if="tagForm.id" @click="resetTagForm">取消编辑</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="tagLoading" border :data="tagList" max-height="360" :row-class-name="tagRowClass">
        <el-table-column label="标签名称" align="left" min-width="240" :show-overflow-tooltip="true">
          <template #default="scope">
            <el-tag size="small" effect="plain">{{ scope.row.tagName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="使用工具数" align="center" width="110">
          <template #default="scope">
            <el-tag :type="(scope.row.useCount ?? 0) > 0 ? 'primary' : 'info'" size="small" effect="plain">
              {{ scope.row.useCount ?? 0 }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备注" align="left" min-width="180" prop="remark" :show-overflow-tooltip="true" />
        <el-table-column label="操作" align="center" width="130" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="编辑" placement="top">
              <el-button
                v-hasPermi="['ai:tool:tag:edit']"
                link
                type="primary"
                icon="Edit"
                @click="editTag(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:tool:tag:remove']"
                link
                type="danger"
                icon="Delete"
                @click="handleDeleteTag(scope.row)"
              ></el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!tagLoading && !tagList.length" description="暂无标签，可在上方新增" :image-size="60" />

      <pagination
        v-show="tagTotal > 0"
        v-model:page="tagQuery.pageNum"
        v-model:limit="tagQuery.pageSize"
        :total="tagTotal"
        @pagination="getTagList"
      />
      <div class="form-tip">
        标签仅作分类标记：删除标签会一并解除它与全部工具的关联；「使用工具数」便于发现未使用的标签。
      </div>
    </el-dialog>

    <!-- 批量打标签 / 批量移除标签（对勾选的工具生效） -->
    <el-dialog v-model="batchDialog.visible" :title="batchDialog.title" width="560px" append-to-body>
      <div class="form-tip batch-tip">
        已勾选 <strong>{{ batchToolIds.length }}</strong> 个工具：
        {{ batchToolNames }}
      </div>
      <el-form label-width="90px">
        <el-form-item :label="batchDialog.mode === 'apply' ? '追加标签' : '移除标签'">
          <el-select
            v-model="batchTagIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            :placeholder="batchDialog.mode === 'apply' ? '选择要追加的标签（可多选）' : '选择要移除的标签（可多选）'"
            style="width: 100%"
          >
            <el-option v-for="tag in batchTagOptions" :key="tag.id" :label="optionLabel(tag)" :value="tag.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="form-tip">
        <template v-if="batchDialog.mode === 'apply'">
          批量打标签为<strong>追加</strong>语义：只补上所选标签，各工具原有的其他标签不受影响；已打过的标签会自动跳过。
        </template>
        <template v-else>
          批量移除只解除「所选标签 × 所选工具」的关联：工具上的其他标签保留，未打该标签的工具不受影响。
          <template v-if="batchTagOptions.length === 0">（所选工具当前都没有标签，无需移除）</template>
        </template>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" :loading="buttonLoading" @click="submitBatchTag">确 定</el-button>
          <el-button @click="batchDialog.visible = false">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="detail.visible" title="业务工具详情" width="860px" append-to-body>
      <template v-if="detail.record">
        <el-descriptions :column="2" border size="small" class="detail-summary">
          <el-descriptions-item label="工具编码">{{ detail.record.toolCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="工具名称">{{ detail.record.toolName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="类型">
            <el-tag :type="detail.record.toolType === '1' ? 'warning' : 'primary'" effect="plain">
              {{ detail.record.toolType === '1' ? '写入' : '只读' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.record.status === '0' ? 'success' : 'info'">
              {{ detail.record.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="实现标识">{{ detail.record.implName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="标签">
            <template v-if="detail.record.tags?.length">
              <el-tag v-for="tag in detail.record.tags" :key="tag.id" size="small" effect="plain" class="tag-item">
                {{ tag.tagName }}
              </el-tag>
            </template>
            <span v-else class="text-muted">未打标签</span>
          </el-descriptions-item>
          <el-descriptions-item label="说明" :span="2">{{ detail.record.description || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.record.remark || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.record.createTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detail.record.updateTime || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-block">
          <div class="detail-label">参数定义（JSON Schema）</div>
          <pre v-if="detail.record.paramsSchema" class="detail-plain">{{ detail.record.paramsSchema }}</pre>
          <el-empty v-else description="未配置" :image-size="60" />
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

<script setup name="AiToolRegistry" lang="ts">
import {
  addTool,
  addToolTag,
  applyToolTags,
  delTool,
  delToolTag,
  getTool,
  listTool,
  listToolTag,
  optionToolTag,
  removeToolTags,
  updateTool,
  updateToolTag
} from '@/api/ai/tool';
import type { ToolForm, ToolQuery, ToolTagForm, ToolTagQuery, ToolTagVO, ToolVO } from '@/api/ai/tool/types';
import modal from '@/plugins/modal';

const paramsSchemaPlaceholder = `{
  "type": "object",
  "properties": {
    "query": { "type": "string", "description": "检索关键词" }
  },
  "required": ["query"]
}`;

const toolList = ref<ToolVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const buttonLoading = ref(false);
const queryFormRef = ref<ElFormInstance>();
const toolFormRef = ref<ElFormInstance>();

const queryParams = ref<ToolQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  toolType: undefined,
  status: undefined,
  tagIds: undefined,
  noTag: false
});

/** 标签筛选（多选；提交时拼为逗号分隔字符串，避免数组序列化差异） */
const tagFilter = ref<Array<string | number>>([]);

/** 全部标签选项（供筛选与表单多选；含使用计数） */
const tagOptions = ref<ToolTagVO[]>([]);

// ------------------------------------------------------------------
//  标签管理弹窗状态
// ------------------------------------------------------------------
const tagDialog = reactive<{ visible: boolean }>({ visible: false });
const tagLoading = ref(false);
const tagList = ref<ToolTagVO[]>([]);
const tagTotal = ref(0);
const tagFormRef = ref<ElFormInstance>();
const tagQuery = ref<ToolTagQuery>({ pageNum: 1, pageSize: 10, keyword: '' });

const initTagForm: ToolTagForm = {
  id: undefined,
  tagName: undefined,
  remark: undefined
};

const tagForm = ref<ToolTagForm>({ ...initTagForm });

const tagRules: ElFormRules = {
  tagName: [{ required: true, message: '标签名称不能为空', trigger: 'blur' }]
};

const initFormData: ToolForm = {
  id: undefined,
  toolCode: undefined,
  toolName: undefined,
  description: undefined,
  toolType: '0',
  paramsSchema: undefined,
  implName: undefined,
  status: '0',
  tagIds: [],
  remark: undefined
};

const dialog = reactive<{ visible: boolean; title: string }>({
  visible: false,
  title: ''
});

const form = ref<ToolForm>({ ...initFormData });

const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);

/** 详情弹窗（查看业务工具完整信息，只读） */
const detail = reactive<{ visible: boolean; record: ToolVO | null }>({
  visible: false,
  record: null
});

/** 勾选的工具行（批量打标签 / 移除标签需要读它们已有的标签） */
const selectedTools = ref<ToolVO[]>([]);

/** 表单校验规则 */
const rules = computed<ElFormRules>(() => ({
  toolCode: [
    { required: true, message: '工具编码不能为空', trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9_]{1,60}$/,
      message: '小写字母开头，仅含小写字母、数字与下划线，长度 2~61 位',
      trigger: 'blur'
    }
  ],
  toolName: [{ required: true, message: '工具名称不能为空', trigger: 'blur' }],
  toolType: [{ required: true, message: '请选择工具类型', trigger: 'change' }],
  implName: [{ required: true, message: '实现标识不能为空', trigger: 'blur' }]
}));

/** 查询工具注册列表 */
const getList = async () => {
  loading.value = true;
  try {
    queryParams.value.tagIds = tagFilter.value.length ? tagFilter.value.join(',') : undefined;
    const res = await listTool(queryParams.value);
    toolList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  } finally {
    loading.value = false;
  }
};

/** 查询全部标签选项 */
const getTagOptions = async () => {
  const res = await optionToolTag();
  tagOptions.value = res.data ?? [];
};

const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryFormRef.value?.resetFields();
  tagFilter.value = [];
  queryParams.value.keyword = '';
  queryParams.value.toolType = undefined;
  queryParams.value.status = undefined;
  queryParams.value.tagIds = undefined;
  queryParams.value.noTag = false;
  queryParams.value.pageNum = 1;
  getList();
};

const handleSelectionChange = (selection: ToolVO[]) => {
  selectedTools.value = selection;
  ids.value = selection.map((item) => item.id);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};

/** 重置表单为初始值 */
const reset = () => {
  form.value = { ...initFormData };
  toolFormRef.value?.resetFields();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.title = '新增业务工具';
  dialog.visible = true;
};

/** 查看按钮操作（拉取完整字段后只读展示） */
const handleView = async (row: Partial<ToolVO>) => {
  const res = await getTool(row.id);
  detail.record = (res.data ?? null) as ToolVO | null;
  detail.visible = true;
};

/** 修改按钮操作（编码在修改态置灰只读） */
const handleUpdate = async (row?: Partial<ToolVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getTool(id);
  const detail = res.data;
  form.value = {
    id: detail.id,
    toolCode: detail.toolCode,
    toolName: detail.toolName,
    description: detail.description,
    toolType: detail.toolType ?? '0',
    paramsSchema: detail.paramsSchema,
    implName: detail.implName,
    status: detail.status ?? '0',
    tagIds: (detail.tags ?? []).map((tag) => tag.id),
    remark: detail.remark
  };
  dialog.title = '修改业务工具';
  dialog.visible = true;
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = () => {
  toolFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: ToolForm = { ...form.value };
      if (payload.id) {
        await updateTool(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addTool(payload);
        modal.msgSuccess('新增成功');
      }
      dialog.visible = false;
      await getList();
      // 标签使用计数会随打标变化，一并刷新
      await getTagOptions();
    } finally {
      buttonLoading.value = false;
    }
  });
};

/** 删除按钮操作 */
const handleDelete = async (row?: Partial<ToolVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm(
    row
      ? `是否确认删除业务工具「${row.toolName || row.toolCode || row.id}」？`
      : `是否确认删除选中的 ${ids.value.length} 个业务工具？`
  );
  await delTool(target);
  modal.msgSuccess('删除成功');
  await getList();
  await getTagOptions();
};

// ------------------------------------------------------------------
//  标签管理
// ------------------------------------------------------------------

/** 打开标签管理弹窗 */
const openTagDialog = async () => {
  resetTagForm();
  tagQuery.value.pageNum = 1;
  tagDialog.visible = true;
  await getTagList();
  await getTagOptions();
};

/** 查询标签列表 */
const getTagList = async () => {
  tagLoading.value = true;
  try {
    const res = await listToolTag(tagQuery.value);
    tagList.value = res.data?.rows ?? [];
    tagTotal.value = res.data?.total ?? 0;
  } finally {
    tagLoading.value = false;
  }
};

const handleTagQuery = () => {
  tagQuery.value.pageNum = 1;
  getTagList();
};

/** 重置标签表单 */
const resetTagForm = () => {
  tagForm.value = { ...initTagForm };
  tagFormRef.value?.resetFields();
};

/** 载入某行到表单进行编辑 */
const editTag = (row: ToolTagVO) => {
  tagForm.value = { id: row.id, tagName: row.tagName, remark: row.remark };
};

/** 正在编辑的标签行高亮，便于与上方表单对照 */
const tagRowClass = ({ row }: { row: ToolTagVO }) => (tagForm.value.id && row.id === tagForm.value.id ? 'is-editing' : '');

/** 提交标签表单（新增 / 修改） */
const submitTagForm = () => {
  tagFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: ToolTagForm = { ...tagForm.value };
      if (payload.id) {
        await updateToolTag(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addToolTag(payload);
        modal.msgSuccess('新增成功');
      }
      resetTagForm();
      await getTagList();
      await getTagOptions();
      // 工具列表的标签列同步刷新
      await getList();
    } finally {
      buttonLoading.value = false;
    }
  });
};

/** 删除标签（级联解除与工具的关联） */
const handleDeleteTag = async (row: ToolTagVO) => {
  const used = row.useCount ?? 0;
  const tip =
    used > 0
      ? `标签「${row.tagName}」已被 ${used} 个工具使用，删除后将一并解除这些关联。是否继续？`
      : `是否确认删除标签「${row.tagName}」？`;
  await modal.confirm(tip);
  await delToolTag(row.id);
  modal.msgSuccess('删除成功');
  await getTagList();
  await getTagOptions();
  await getList();
};

// ------------------------------------------------------------------
//  批量打标签 / 批量移除标签
// ------------------------------------------------------------------

/** 批量弹窗状态（mode：apply 追加打标 / remove 移除已有标签） */
const batchDialog = reactive<{ visible: boolean; mode: 'apply' | 'remove'; title: string }>({
  visible: false,
  mode: 'apply',
  title: ''
});

const batchTagIds = ref<Array<string | number>>([]);

/** 勾选的工具主键 */
const batchToolIds = computed(() => selectedTools.value.map((item) => item.id));

/** 勾选的工具名称（超长截断，仅用于提示） */
const batchToolNames = computed(() => {
  const names = selectedTools.value.map((item) => item.toolName);
  return names.length > 8 ? `${names.slice(0, 8).join('、')} 等` : names.join('、');
});

/**
 * 批量弹窗的标签候选：
 * - 追加打标：全部标签（含使用计数）
 * - 移除标签：**所选工具已有标签的并集**（未打过的标签无需移除）
 */
const batchTagOptions = computed<ToolTagVO[]>(() => {
  if (batchDialog.mode === 'apply') {
    return tagOptions.value;
  }
  const merged = new Map<string | number, ToolTagVO>();
  selectedTools.value.forEach((tool) => (tool.tags ?? []).forEach((tag) => merged.set(tag.id, tag)));
  return Array.from(merged.values()).toSorted((a, b) => a.tagName.localeCompare(b.tagName, 'zh-Hans-CN'));
});

/** 标签选项文案（含使用计数） */
const optionLabel = (tag: ToolTagVO) => (batchDialog.mode === 'apply' ? `${tag.tagName}（${tag.useCount ?? 0} 个工具）` : tag.tagName);

/** 打开批量弹窗 */
const openBatchTagDialog = async (mode: 'apply' | 'remove') => {
  if (!ids.value.length) {
    modal.msgWarning('请先勾选要操作的工具');
    return;
  }
  batchDialog.mode = mode;
  batchDialog.title = mode === 'apply' ? '批量打标签' : '批量移除标签';
  batchTagIds.value = [];
  batchDialog.visible = true;
  if (mode === 'apply') {
    // 追加打标以最新的标签清单为准
    await getTagOptions();
  }
};

/** 提交批量操作 */
const submitBatchTag = async () => {
  if (!batchTagIds.value.length) {
    modal.msgWarning('请先选择标签');
    return;
  }
  const verb = batchDialog.mode === 'apply' ? '打标签' : '移除标签';
  await modal.confirm(`确认给勾选的 ${ids.value.length} 个工具${verb}（${batchTagIds.value.length} 个标签）吗？`);
  buttonLoading.value = true;
  try {
    const payload = { toolIds: [...ids.value], tagIds: [...batchTagIds.value] };
    if (batchDialog.mode === 'apply') {
      await applyToolTags(payload);
    } else {
      await removeToolTags(payload);
    }
    modal.msgSuccess(`${verb}成功`);
    batchDialog.visible = false;
    await getTagOptions();
    // 刷新列表：标签列随之后端返回同步
    await getList();
  } finally {
    buttonLoading.value = false;
  }
};

getTagOptions();
getList();
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

.tag-item {
  margin: 2px 4px 2px 0;
}

.option-count {
  float: right;
  color: var(--app-text-muted, #909399);
  font-size: 12px;
}

.tag-search {
  display: flex;
  gap: 8px;
  margin: 4px 0 10px;
}

.tag-form {
  :deep(.el-form-item) {
    margin-bottom: 12px;
  }

  /* 表单 label 不换行：窄窗口下「标签名称」曾被折成两行 */
  :deep(.el-form-item__label) {
    white-space: nowrap;
  }
}

/* 正在编辑的标签行：整行底色区分（行高亮靠 el-table 的行底色变量，避免逐 td 覆盖） */
:deep(.el-table .is-editing) {
  --el-table-tr-bg-color: var(--el-fill-color-light);
}

.form-tip {
  margin-top: 4px;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.batch-tip {
  margin: 0 0 12px;
  font-size: 13px;
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
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
