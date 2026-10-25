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
          <el-form-item label="知识库名称" prop="kbName">
            <el-input v-model="queryParams.kbName" placeholder="请输入知识库名称" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="知识库编码" prop="kbCode">
            <el-input v-model="queryParams.kbCode" placeholder="请输入知识库编码" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 140px">
              <el-option label="正常" value="0" />
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
            <h3>知识库</h3>
            <p>知识库决定向量模型、维度与切分策略，创建后即可上传文档。生效范围只约束「检索链路」（按提问用户过滤）与智能体绑定校验，本管理页始终全量可见。</p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:kb:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:kb:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:kb:remove']"
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

      <el-table v-loading="loading" border class="data-table" :data="kbList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="知识库名称" align="center" prop="kbName" :show-overflow-tooltip="true" />
        <el-table-column label="编码" align="center" prop="kbCode" width="140" />
        <el-table-column label="向量模型" align="center" :show-overflow-tooltip="true">
          <template #default="scope">{{ scope.row.embeddingModelName || '-' }}</template>
        </el-table-column>
        <el-table-column label="检索重排" align="center" :show-overflow-tooltip="true">
          <template #default="scope">
            <span v-if="scope.row.rerankEnabled === '1'">{{ scope.row.rerankModelName || '已启用（未绑模型）' }}</span>
            <span v-else>未启用</span>
          </template>
        </el-table-column>
        <el-table-column label="检索方式" align="center" width="130">
          <template #default="scope">
            <el-tag :type="scope.row.retrievalMode === 'hybrid' ? 'warning' : 'info'" effect="plain" size="small">
              {{ scope.row.retrievalMode === 'hybrid' ? '向量 + BM25' : '向量' }}
            </el-tag>
            <el-tag
              v-if="scope.row.retrievalMode === 'hybrid'"
              class="bm25-status"
              :type="bm25StatusType(scope.row.bm25IndexStatus)"
              effect="plain"
              size="small"
            >
              {{ bm25StatusLabel(scope.row.bm25IndexStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="维度" align="center" prop="dimension" width="80" />
        <el-table-column label="切片/重叠" align="center" width="120">
          <template #default="scope">{{ scope.row.chunkSize }} / {{ scope.row.chunkOverlap }}</template>
        </el-table-column>
        <el-table-column label="召回 TopK" align="center" prop="retrievalTopK" width="110" />
        <el-table-column label="相似度阈值" align="center" prop="similarityThreshold" width="110" />
        <el-table-column label="生效范围" align="center" width="110">
          <template #default="scope">
            <el-tag :type="scopeTagType(scope.row.visibleScope)" effect="plain" size="small">
              {{ scopeLabel(scope.row.visibleScope) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'info'">
              {{ scope.row.status === '0' ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" align="center" prop="createTime" width="170" />
        <el-table-column label="操作" align="center" width="290" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看" placement="top">
              <el-button
                v-hasPermi="['ai:kb:query']"
                link
                type="primary"
                icon="View"
                @click="handleView(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="文档与切片" placement="top">
              <el-button
                v-hasPermi="['ai:kb:doc']"
                link
                type="primary"
                icon="Document"
                @click="handleDocs(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip v-if="scope.row.retrievalMode === 'hybrid'" content="重建 BM25 索引" placement="top">
              <el-button
                v-hasPermi="['ai:kb:edit']"
                link
                type="warning"
                icon="Refresh"
                @click="handleRebuild(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:kb:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:kb:remove']"
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

    <el-dialog v-model="dialog.visible" :title="dialog.title" width="680px" append-to-body>
      <el-form ref="kbFormRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="知识库名称" prop="kbName">
          <el-input v-model="form.kbName" placeholder="如：产品手册知识库" />
        </el-form-item>
        <el-form-item label="知识库编码" prop="kbCode">
          <el-input v-model="form.kbCode" placeholder="小写字母、数字、下划线，如 product_manual" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="向量模型" prop="embeddingModelId">
          <template #label>
            <span class="label-tip">
              向量模型
              <el-tooltip v-if="!embeddingModels.length" placement="top">
                <template #content>
                  <div class="tip-content">暂无可用向量模型，请先到「模型与推理 → 模型配置」新增 embedding 类型的模型。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="form.embeddingModelId" placeholder="请选择向量模型" style="width: 100%" @change="handleModelChange">
            <el-option v-for="item in embeddingModels" :key="item.id" :label="item.configName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="向量维度" prop="dimension">
          <template #label>
            <span class="label-tip">
              向量维度
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">留空则取向量模型配置的维度</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.dimension" :min="1" :max="65536" controls-position="right" />
        </el-form-item>
        <el-form-item label="切片长度" prop="chunkSize">
          <template #label>
            <span class="label-tip">
              切片长度
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">字符数，建议 300~800</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.chunkSize" :min="50" :max="8000" :step="50" controls-position="right" />
        </el-form-item>
        <el-form-item label="切片重叠" prop="chunkOverlap">
          <template #label>
            <span class="label-tip">
              切片重叠
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">必须小于切片长度</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.chunkOverlap" :min="0" :max="2000" :step="10" controls-position="right" />
        </el-form-item>
        <el-form-item label="召回 TopK" prop="retrievalTopK">
          <el-input-number v-model="form.retrievalTopK" :min="1" :max="50" controls-position="right" />
        </el-form-item>
        <el-form-item label="相似度阈值" prop="similarityThreshold">
          <el-input-number v-model="form.similarityThreshold" :min="0" :max="1" :step="0.05" :precision="3" controls-position="right" />
        </el-form-item>
        <el-form-item label="检索方式" prop="retrievalMode">
          <template #label>
            <span class="label-tip">
              检索方式
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    向量：仅按语义相似度召回。向量 + BM25：同时做语义召回与关键词全文召回，再按 RRF 融合；
                    BM25 索引由平台在保存后自动构建，失败或未就绪时自动降级为纯向量检索。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-radio-group v-model="form.retrievalMode">
            <el-radio value="vector">向量</el-radio>
            <el-radio value="hybrid">向量 + BM25</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="检索重排" prop="rerankEnabled">
          <template #label>
            <span class="label-tip">
              检索重排
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    开启后检索走「稠密召回 → 重排 → 融合 → 截断 TopK」。重排模型需先在「模型与推理 → 模型配置」新增
                    rerank 类型的模型；重排不可用时会自动降级为向量原序，不影响检索。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.rerankEnabled" active-value="1" inactive-value="0" @change="handleRerankToggle" />
        </el-form-item>
        <el-form-item v-if="form.rerankEnabled === '1'" label="重排模型" prop="rerankModelId">
          <template #label>
            <span class="label-tip">
              重排模型
              <el-tooltip v-if="!rerankModels.length" placement="top">
                <template #content>
                  <div class="tip-content">暂无可用重排模型，请先到「模型与推理 → 模型配置」新增 rerank 类型的模型（地址填完整重排端点）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="form.rerankModelId" placeholder="请选择重排模型" style="width: 100%">
            <el-option v-for="item in rerankModels" :key="item.id" :label="item.configName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效范围" prop="visibleScope">
          <el-radio-group v-model="form.visibleScope">
            <el-radio value="0">公开</el-radio>
            <el-radio value="1">本部门</el-radio>
            <el-radio value="2">仅本人</el-radio>
          </el-radio-group>
          <div class="kb-scope-tip">
            决定「谁能检索到这个知识库」：公开=全平台；本部门=与你同部门的同事；仅本人=只有你自己。管理页仍可看到全部知识库。
          </div>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio value="0">正常</el-radio>
            <el-radio value="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="知识库用途说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="buttonLoading" type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="detail.visible" title="知识库详情" width="860px" append-to-body>
      <template v-if="detail.record">
        <el-descriptions :column="2" border size="small" class="detail-summary">
          <el-descriptions-item label="知识库名称">{{ detail.record.kbName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="编码">{{ detail.record.kbCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="向量模型">{{ detail.record.embeddingModelName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="向量维度">{{ detail.record.dimension ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="切片/重叠">
            {{ detail.record.chunkSize ?? '-' }} / {{ detail.record.chunkOverlap ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="召回 TopK">{{ detail.record.retrievalTopK ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="相似度阈值">{{ detail.record.similarityThreshold ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="检索重排">
            {{ detail.record.rerankEnabled === '1' ? (detail.record.rerankModelName || '已启用（未绑模型）') : '未启用' }}
          </el-descriptions-item>
          <el-descriptions-item label="检索方式">
            {{ detail.record.retrievalMode === 'hybrid' ? '向量 + BM25' : '向量' }}
            <template v-if="detail.record.retrievalMode === 'hybrid'">
              （BM25：{{ bm25StatusLabel(detail.record.bm25IndexStatus) }}）
            </template>
          </el-descriptions-item>
          <el-descriptions-item label="生效范围">
            <el-tag :type="scopeTagType(detail.record.visibleScope)" effect="plain" size="small">
              {{ scopeLabel(detail.record.visibleScope) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.record.status === '0' ? 'success' : 'info'">
              {{ detail.record.status === '0' ? '正常' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="向量集合">{{ detail.record.collectionName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="描述" :span="2">{{ detail.record.description || '-' }}</el-descriptions-item>
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

<script setup name="AiKnowledgeBase" lang="ts">
import { addKnowledgeBase, delKnowledgeBase, getKnowledgeBase, listKnowledgeBase, rebuildBm25Index, updateKnowledgeBase } from '@/api/ai/kb/base';
import type { KnowledgeBaseForm, KnowledgeBaseQuery, KnowledgeBaseVO } from '@/api/ai/kb/base/types';
import { optionModelConfig } from '@/api/ai/model/config';
import type { ModelConfigVO } from '@/api/ai/model/config/types';
import { useLoading } from '@/hooks/async/useLoading';
import { useFormDialog } from '@/hooks/dialog/useFormDialog';
import { useSearchReset } from '@/hooks/form/useSearchReset';
import { useSearchToggle } from '@/hooks/form/useSearchToggle';
import { useTableSelection } from '@/hooks/table/useTableSelection';
import modal from '@/plugins/modal';

const router = useRouter();

const kbList = ref<KnowledgeBaseVO[]>([]);
const embeddingModels = ref<ModelConfigVO[]>([]);

/** 可选重排模型（model_type=rerank） */
const rerankModels = ref<ModelConfigVO[]>([]);
const buttonLoading = ref(false);
const { loading, withLoading } = useLoading(true);
const { showSearch } = useSearchToggle();
const total = ref(0);

const queryFormRef = ref<ElFormInstance>();
const kbFormRef = ref<ElFormInstance>();

const initFormData: KnowledgeBaseForm = {
  id: undefined,
  kbName: undefined,
  kbCode: undefined,
  description: undefined,
  embeddingModelId: undefined,
  dimension: undefined,
  chunkSize: 500,
  chunkOverlap: 50,
  retrievalTopK: 5,
  similarityThreshold: 0.5,
  rerankEnabled: '0',
  rerankModelId: undefined,
  retrievalMode: 'vector',
  status: '0',
  visibleScope: '0',
  remark: undefined
};

const data = reactive<PageData<KnowledgeBaseForm, KnowledgeBaseQuery>>({
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    kbName: undefined,
    kbCode: undefined,
    status: undefined
  },
  rules: {
    kbName: [{ required: true, message: '知识库名称不能为空', trigger: 'blur' }],
    kbCode: [
      { required: true, message: '知识库编码不能为空', trigger: 'blur' },
      { pattern: /^[a-z][a-z0-9_]{1,60}$/, message: '只能包含小写字母、数字与下划线，且以字母开头', trigger: 'blur' }
    ],
    embeddingModelId: [{ required: true, message: '向量模型不能为空', trigger: 'change' }],
    rerankModelId: [
      {
        // 仅在「启用检索重排」时必填（开关关闭时后端会清空该字段）
        validator: (_rule: any, value: unknown, callback: (error?: Error) => void) => {
          if (form.value.rerankEnabled === '1' && !value) {
            callback(new Error('启用检索重排时必须选择重排模型'));
            return;
          }
          callback();
        },
        trigger: 'change'
      }
    ]
  }
});

const { queryParams, form, rules } = toRefs(data);

const { ids, single, multiple, handleSelectionChange } = useTableSelection<KnowledgeBaseVO>((item) => item.id);

/** 详情弹窗（查看知识库完整信息，只读） */
const detail = reactive<{ visible: boolean; record: KnowledgeBaseVO | null }>({
  visible: false,
  record: null
});
const {
  dialog,
  resetForm: reset,
  openDialog,
  showDialog,
  closeDialog
} = useFormDialog({
  form,
  formRef: kbFormRef,
  initialFormData: initFormData
});

/** 生效范围文案（口径与智能体一致：0公开 1本部门 2仅本人） */
const scopeLabel = (value?: string) => {
  if (value === '2') return '仅本人';
  if (value === '1') return '本部门';
  return '公开';
};

/** 生效范围标签配色 */
const scopeTagType = (value?: string) => {
  if (value === '2') return 'danger';
  if (value === '1') return 'warning';
  return 'success';
};

/** BM25 索引状态文案 */
const bm25StatusLabel = (value?: string) => {
  if (value === 'ready') return '索引可用';
  if (value === 'building') return '构建中';
  if (value === 'failed') return '构建失败';
  return '未构建';
};

/** BM25 索引状态配色 */
const bm25StatusType = (value?: string) => {
  if (value === 'ready') return 'success';
  if (value === 'building') return 'warning';
  if (value === 'failed') return 'danger';
  return 'info';
};

/** 查询可用向量模型 */
const loadEmbeddingModels = async () => {
  const res = await optionModelConfig('embedding');
  embeddingModels.value = res.data ?? [];
};

/** 选择向量模型后自动带出维度 */
const handleModelChange = (value: string | number) => {
  const matched = embeddingModels.value.find((item) => item.id === value);
  if (matched?.dimension) {
    form.value.dimension = matched.dimension;
  }
};

/** 查询可用重排模型（model_type=rerank） */
const loadRerankModels = async () => {
  const res = await optionModelConfig('rerank');
  rerankModels.value = res.data ?? [];
};

/** 切换「检索重排」：开启时刷新候选、关闭时清空已选模型（与后端归一化口径一致） */
const handleRerankToggle = async (value: string | number | boolean) => {
  if (String(value) !== '1') {
    form.value.rerankModelId = undefined;
    return;
  }
  await loadRerankModels();
};

/** 查询知识库列表 */
const getList = async () => {
  await withLoading(async () => {
    const res = await listKnowledgeBase(queryParams.value);
    kbList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  });
};

/** 取消按钮 */
const cancel = () => {
  reset();
  closeDialog();
};

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

const { resetQuery } = useSearchReset({
  queryFormRef,
  queryParams,
  pageNumKey: 'pageNum',
  afterReset: () => {
    handleQuery();
  }
});

/** 新增按钮操作 */
const handleAdd = () => {
  openDialog('新增知识库');
};

/** 查看按钮操作（拉取完整字段后只读展示） */
const handleView = async (row: Partial<KnowledgeBaseVO>) => {
  const res = await getKnowledgeBase(row.id);
  detail.record = (res.data ?? null) as KnowledgeBaseVO | null;
  detail.visible = true;
};

/** 修改按钮操作 */
const handleUpdate = async (row?: Partial<KnowledgeBaseVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getKnowledgeBase(id);
  Object.assign(form.value, res.data);
  showDialog('修改知识库');
};

/** 进入文档与切片页面 */
const handleDocs = (row: KnowledgeBaseVO) => {
  router.push({ path: '/bizbuddy/kb/doc', query: { kbId: String(row.id) } });
};

/** 重建 BM25 全文索引 */
const handleRebuild = async (row: KnowledgeBaseVO) => {
  await modal.confirm(`是否确认重建知识库「${row.kbName || row.id}」的 BM25 索引？`);
  await rebuildBm25Index(row.id);
  modal.msgSuccess('已提交重建，稍后刷新查看索引状态');
  await getList();
};

/** 提交按钮 */
const submitForm = () => {
  kbFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      if (form.value.id) {
        await updateKnowledgeBase(form.value);
        modal.msgSuccess('修改成功');
      } else {
        await addKnowledgeBase(form.value);
        modal.msgSuccess('新增成功');
      }
      closeDialog();
      await getList();
    } finally {
      buttonLoading.value = false;
    }
  });
};

/** 删除按钮操作 */
const handleDelete = async (row?: Partial<KnowledgeBaseVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm(
    row
      ? `是否确认删除知识库「${row.kbName || row.id}」？`
      : `是否确认删除选中的 ${ids.value.length} 个知识库？`
  );
  await delKnowledgeBase(target);
  modal.msgSuccess('删除成功');
  await getList();
};

loadEmbeddingModels();
loadRerankModels();
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

.kb-scope-tip {
  width: 100%;
  margin-top: 4px;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.detail-summary {
  margin-bottom: 12px;
}

.bm25-status {
  margin-left: 4px;
}
</style>