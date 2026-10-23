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
          <el-form-item label="配置名称" prop="configName">
            <el-input v-model="queryParams.configName" placeholder="请输入配置名称" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="提供商" prop="provider">
            <el-select v-model="queryParams.provider" placeholder="全部" clearable style="width: 200px">
              <el-option v-for="item in providerOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型类型" prop="modelType">
            <el-select v-model="queryParams.modelType" placeholder="全部" clearable style="width: 160px">
              <el-option v-for="item in modelTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
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
            <h3>模型配置</h3>
            <p>密钥密文存储，页面不回显明文；保存前可先做连通性测试。</p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:model:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:model:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:model:remove']"
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

      <el-table v-loading="loading" border class="data-table" :data="configList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="配置名称" align="left" prop="configName" :show-overflow-tooltip="true" />
        <el-table-column label="提供商" align="left" width="120">
          <template #default="scope">{{ providerLabel(scope.row.provider) }}</template>
        </el-table-column>
        <el-table-column label="类型" align="center" width="100">
          <template #default="scope">
            <el-tag :type="modelTypeTag(scope.row.modelType)" effect="plain">
              {{ modelTypeLabel(scope.row.modelType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模型标识" align="left" prop="modelCode" :show-overflow-tooltip="true" />
        <el-table-column label="支持视觉" align="center" width="100">
          <template #default="scope">
            <span v-if="scope.row.modelType !== 'chat'" class="text-muted">-</span>
            <el-tag v-else :type="scope.row.supportsVision === '0' ? 'info' : 'success'" effect="plain">
              {{ scope.row.supportsVision === '0' ? '否' : '是' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="服务地址" align="center" prop="baseUrl" :show-overflow-tooltip="true" />
        <el-table-column label="密钥" align="center" width="100">
          <template #default="scope">
            <span v-if="scope.row.apiKeyMask">{{ scope.row.apiKeyMask }}</span>
            <span v-else class="text-muted">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="向量维度" align="center" width="100">
          <template #default="scope">{{ scope.row.dimension ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'info'">
              {{ scope.row.status === '0' ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" align="center" prop="createTime" width="170" />
        <el-table-column label="操作" align="center" width="300" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看" placement="top">
              <el-button
                v-hasPermi="['ai:model:query']"
                link
                type="primary"
                icon="View"
                @click="handleView(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="连通性测试" placement="top">
              <el-button
                v-hasPermi="['ai:model:test']"
                link
                type="warning"
                icon="Connection"
                @click="handleTestRow(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:model:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="复制" placement="top">
              <el-button
                v-hasPermi="['ai:model:copy']"
                link
                type="primary"
                icon="CopyDocument"
                @click="handleCopy(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:model:remove']"
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
      <el-form ref="configFormRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="配置名称" prop="configName">
          <el-input v-model="form.configName" placeholder="如：通义千问 Plus" />
        </el-form-item>
        <el-form-item label="提供商" prop="provider">
          <el-select v-model="form.provider" placeholder="请选择提供商" style="width: 100%" @change="handleProviderChange">
            <el-option v-for="item in providerOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型类型" prop="modelType">
          <el-radio-group v-model="form.modelType" @change="handleModelTypeChange">
            <el-radio v-for="item in modelTypeOptions" :key="item.value" :value="item.value">{{ item.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="模型标识" prop="modelCode">
          <el-input
            v-model="form.modelCode"
            :placeholder="isDecisionForm ? 'decision-model-preview' : '如：qwen-plus、text-embedding-v3'"
          />
        </el-form-item>
        <el-form-item v-if="isChatForm" label="支持视觉" prop="supportsVision">
          <template #label>
            <span class="label-tip">
              支持视觉
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    仅<b>对话模型</b>有意义：聊天上传的<b>图片</b>会以多模态内容块下发给模型。
                    未开启时平台会在<b>上传图片</b>时直接拦下并提示，避免非视觉模型报错。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-radio-group v-model="form.supportsVision">
            <el-radio value="1">是</el-radio>
            <el-radio value="0">否</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-divider content-position="left">连接与密钥</el-divider>
        <el-form-item label="服务地址" prop="baseUrl">
          <template #label>
            <span class="label-tip">
              服务地址
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    <template v-if="isDecisionForm">
                      决策模型走 <b>TypeSafe System One</b> 协议，<b>平台自动拼 /v1/systemone</b>，无需自己拼。<br />
                      地址必须填<b>业务空间专属域名</b>（如
                      <code>https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode</code>）；
                      通用的 <code>dashscope.aliyuncs.com/compatible-mode</code> 上<b>没有该接口</b>（实测返回 404）。
                    </template>
                    <template v-else-if="isRerankForm">
                      重排模型填 <b>完整重排端点</b>（DashScope 文本重排原生接口），平台<b>不拼后缀</b>：<br />
                      <code>{{ RERANK_ENDPOINT }}</code>；模型标识填重排模型名（如 <code>gte-rerank-v2</code>）。
                    </template>
                    <template v-else>
                      统一填 <b>OpenAI 兼容根地址</b>，不用自己拼 /embeddings 或 /chat/completions。
                    </template>
                    <span v-if="baseUrlWarning" class="tip-warn">{{ baseUrlWarning }}</span>
                    <span v-else-if="recommendedBaseUrl && recommendedBaseUrl !== form.baseUrl">推荐：{{ recommendedBaseUrl }}</span>
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="form.baseUrl"
            :placeholder="isDecisionForm
              ? '业务空间专属域名，如 https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode'
              : 'OpenAI 兼容根地址，如 https://api.deepseek.com/v1'"
          />
        </el-form-item>
        <el-form-item label="API 密钥" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="form.id ? '留空表示不修改已保存的密钥' : '请输入 API 密钥，本地 Ollama 可留空'"
          />
        </el-form-item>
        <el-form-item v-if="isEmbeddingForm" label="向量维度" prop="dimension">
          <template #label>
            <span class="label-tip">
              向量维度
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">需与向量模型一致，试调时会自动校验</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.dimension" :min="1" :max="65536" controls-position="right" />
        </el-form-item>
        <el-form-item label="超时(秒)" prop="timeoutSeconds">
          <el-input-number v-model="form.timeoutSeconds" :min="1" :max="600" controls-position="right" />
        </el-form-item>
        <el-form-item label="重试次数" prop="maxRetries">
          <el-input-number v-model="form.maxRetries" :min="0" :max="5" controls-position="right" />
        </el-form-item>
        <el-divider content-position="left">计费与状态</el-divider>
        <el-form-item label="输入单价" prop="inputPrice">
          <template #label>
            <span class="label-tip">
              输入单价
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">元 / 千 Token，用于用量费用估算，留空按 0 计</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.inputPrice" :min="0" :precision="6" :step="0.001" controls-position="right" />
        </el-form-item>
        <el-form-item label="输出单价" prop="outputPrice">
          <template #label>
            <span class="label-tip">
              输出单价
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">元 / 千 Token，用于用量费用估算，留空按 0 计</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.outputPrice" :min="0" :precision="6" :step="0.001" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio value="0">正常</el-radio>
            <el-radio value="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="testing" icon="Connection" @click="handleTestForm">连通性测试</el-button>
          <el-button :loading="buttonLoading" type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="detail.visible" title="模型配置详情" width="860px" append-to-body>
      <template v-if="detail.record">
        <el-descriptions :column="2" border size="small" class="detail-summary">
          <el-descriptions-item label="配置名称">{{ detail.record.configName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.record.status === '0' ? 'success' : 'info'">
              {{ detail.record.status === '0' ? '正常' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="提供商">{{ providerLabel(detail.record.provider) }}</el-descriptions-item>
          <el-descriptions-item label="模型类型">{{ modelTypeLabel(detail.record.modelType) }}</el-descriptions-item>
          <el-descriptions-item label="模型标识">{{ detail.record.modelCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="支持视觉">
            {{ detail.record.modelType !== 'chat'
              ? '-'
              : (detail.record.supportsVision === '0' ? '否' : '是') }}
          </el-descriptions-item>
          <el-descriptions-item label="密钥">{{ detail.record.apiKeyMask || '未配置' }}</el-descriptions-item>
          <el-descriptions-item label="服务地址" :span="2">{{ detail.record.baseUrl || '-' }}</el-descriptions-item>
          <el-descriptions-item label="向量维度">{{ detail.record.dimension ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="超时(秒)">{{ detail.record.timeoutSeconds ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="重试次数">{{ detail.record.maxRetries ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="单价">
            输入：{{ detail.record.inputPrice ?? '-' }}；输出：{{ detail.record.outputPrice ?? '-' }}（元 / 千 Token）
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

<script setup name="AiModelConfig" lang="ts">
import {
  addModelConfig,
  copyModelConfig,
  delModelConfig,
  getModelConfig,
  listModelConfig,
  testModelConfig,
  updateModelConfig
} from '@/api/ai/model/config';
import type { ModelConfigForm, ModelConfigQuery, ModelConfigVO, ModelTestResult } from '@/api/ai/model/config/types';
import { useLoading } from '@/hooks/async/useLoading';
import { useFormDialog } from '@/hooks/dialog/useFormDialog';
import { useSearchReset } from '@/hooks/form/useSearchReset';
import { useSearchToggle } from '@/hooks/form/useSearchToggle';
import { useTableSelection } from '@/hooks/table/useTableSelection';
import modal from '@/plugins/modal';

const PROVIDER_OPTIONS = [
  { label: 'OpenAI', value: 'openai', baseUrl: 'https://api.openai.com/v1' },
  { label: '阿里云百炼（DashScope）', value: 'dashscope', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { label: 'DeepSeek', value: 'deepseek', baseUrl: 'https://api.deepseek.com/v1' },
  { label: '智谱 GLM', value: 'glm', baseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
  { label: '本地 Ollama', value: 'ollama', baseUrl: 'http://127.0.0.1:11434/v1' },
  { label: '其他 OpenAI 兼容', value: 'custom', baseUrl: '' }
];

const MODEL_TYPE_OPTIONS = [
  { label: '对话模型', value: 'chat' },
  { label: '向量模型', value: 'embedding' },
  { label: '决策模型', value: 'decision' },
  { label: '重排模型', value: 'rerank' }
];

const providerOptions = PROVIDER_OPTIONS;
const modelTypeOptions = MODEL_TYPE_OPTIONS;

const configList = ref<ModelConfigVO[]>([]);
const buttonLoading = ref(false);
const testing = ref(false);
const { loading, withLoading } = useLoading(true);
const { showSearch } = useSearchToggle();
const total = ref(0);

const queryFormRef = ref<ElFormInstance>();
const configFormRef = ref<ElFormInstance>();

const initFormData: ModelConfigForm = {
  id: undefined,
  configName: undefined,
  provider: 'dashscope',
  modelType: 'chat',
  modelCode: undefined,
  supportsVision: '1',
  baseUrl: PROVIDER_OPTIONS[1].baseUrl,
  apiKey: undefined,
  dimension: undefined,
  timeoutSeconds: 60,
  maxRetries: 1,
  inputPrice: undefined,
  outputPrice: undefined,
  status: '0',
  remark: undefined
};

const data = reactive<PageData<ModelConfigForm, ModelConfigQuery>>({
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    configName: undefined,
    provider: undefined,
    modelType: undefined,
    status: undefined
  },
  rules: {}
});

const { queryParams, form } = toRefs(data);

/** 向量模型才需要填写向量维度 */
const isEmbeddingForm = computed(() => form.value.modelType === 'embedding');

/** 仅对话模型有「支持视觉」能力（向量 / 决策模型无） */
const isChatForm = computed(() => form.value.modelType === 'chat');

/** 决策模型：走 TypeSafe System One 协议，服务地址由后端自动拼 /v1/systemone */
const isDecisionForm = computed(() => form.value.modelType === 'decision');

/**
 * 重排模型：服务地址要填**完整重排端点**（DashScope 文本重排原生接口），
 * 后端不拼 `/chat/completions` 之类的后缀，故这里给专门的提示与占位符
 */
const isRerankForm = computed(() => form.value.modelType === 'rerank');

/** 表单校验规则：向量维度按类型动态必填 */
const rules = computed<ElFormRules>(() => ({
  configName: [{ required: true, message: '配置名称不能为空', trigger: 'blur' }],
  provider: [{ required: true, message: '提供商不能为空', trigger: 'change' }],
  modelType: [{ required: true, message: '模型类型不能为空', trigger: 'change' }],
  modelCode: [{ required: true, message: '模型标识不能为空', trigger: 'blur' }],
  baseUrl: [
    { required: true, message: '服务地址不能为空', trigger: 'blur' },
    {
      validator: (_rule: any, value: string, callback: (error?: Error) => void) => {
        if (value && /[{}]/.test(value)) {
          callback(new Error('请把地址中的占位符（如 {WorkspaceId}）替换为真实值'));
          return;
        }
        callback();
      },
      trigger: 'blur'
    }
  ],
  dimension: isEmbeddingForm.value ? [{ required: true, message: '向量模型必须填写向量维度', trigger: 'blur' }] : []
}));

/** 当前提供商推荐的兼容地址（决策 / 重排模型不推荐通用地址：一个要业务空间专属域名，一个要重排端点） */
const recommendedBaseUrl = computed(() => {
  if (isDecisionForm.value) {
    return '';
  }
  if (isRerankForm.value) {
    return RERANK_ENDPOINT;
  }
  return providerOptions.find((item) => item.value === form.value.provider)?.baseUrl ?? '';
});

/** 服务地址写法提示（不阻塞提交，只做引导） */
const baseUrlWarning = computed(() => {
  const value = (form.value.baseUrl || '').trim();
  if (!value) {
    return '';
  }
  if (/[{}]/.test(value)) {
    return '⚠ 地址中仍有占位符（如 {WorkspaceId}），请替换为真实业务空间 ID';
  }
  // 重排模型**就是** DashScope 原生接口（完整端点），故不套用「请改用 compatible-mode」的通用告警
  if (isRerankForm.value) {
    return '';
  }
  if (value.includes('aliyuncs.com') && value.includes('/api/v1') && !value.includes('/compatible-mode')) {
    return '⚠ 这是 DashScope 原生接口地址，请改用 .../compatible-mode/v1';
  }
  // 决策模型必须用业务空间专属域名：通用域名上没有 /v1/systemone（实测 404）
  if (isDecisionForm.value && /(^|\/\/)dashscope(-intl)?\.aliyuncs\.com\//.test(value)) {
    return '⚠ 决策模型须用业务空间专属域名（https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode），通用域名上没有该接口';
  }
  if (value.includes('/embeddings') || value.includes('/chat/completions')) {
    return '⚠ 只需填到根地址（.../v1），不用带 /embeddings 或 /chat/completions';
  }
  return '';
});

const { ids, single, multiple, handleSelectionChange } = useTableSelection<ModelConfigVO>((item) => item.id);

/** 详情弹窗（查看模型配置完整信息，只读；密钥仅展示掩码） */
const detail = reactive<{ visible: boolean; record: ModelConfigVO | null }>({
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
  formRef: configFormRef,
  initialFormData: initFormData
});

/** 提供商名称 */
const providerLabel = (value?: string) => providerOptions.find((item) => item.value === value)?.label ?? value ?? '-';

/** 模型类型名称 */
const modelTypeLabel = (value?: string) => modelTypeOptions.find((item) => item.value === value)?.label ?? value ?? '-';

/** 模型类型标签配色：对话 / 向量 / 决策 / 重排 四色区分 */
const modelTypeTag = (value?: string): 'primary' | 'warning' | 'success' | 'info' => {
  if (value === 'embedding') {
    return 'warning';
  }
  if (value === 'decision') {
    return 'success';
  }
  return value === 'rerank' ? 'info' : 'primary';
};

/** 重排模型推荐（也是唯一支持的）端点：DashScope 文本重排原生接口 */
const RERANK_ENDPOINT = 'https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank';

/**
 * 切换模型类型：
 * ① 切到决策模型时清掉「提供商默认回填」的通用地址（通用域名上没有 System One 接口）；
 * ② 切到重排模型时把地址预填为 DashScope 重排端点（平台只支持该重排协议，避免填错）
 */
const handleModelTypeChange = (value: string) => {
  if (value === 'rerank') {
    const providerDefault = providerOptions.find((item) => item.value === form.value.provider)?.baseUrl;
    if (!form.value.baseUrl || form.value.baseUrl === providerDefault) {
      form.value.baseUrl = RERANK_ENDPOINT;
    }
    return;
  }
  if (value !== 'decision') {
    return;
  }
  const providerDefault = providerOptions.find((item) => item.value === form.value.provider)?.baseUrl;
  if (providerDefault && form.value.baseUrl === providerDefault) {
    form.value.baseUrl = '';
  }
};

/** 切换提供商时按需补全服务地址（决策模型不回填：通用域名上没有 System One 接口） */
const handleProviderChange = (value: string) => {
  if (isDecisionForm.value) {
    return;
  }
  const matched = providerOptions.find((item) => item.value === value);
  if (matched?.baseUrl && !form.value.baseUrl) {
    form.value.baseUrl = matched.baseUrl;
  }
};

/** 查询模型配置列表 */
const getList = async () => {
  await withLoading(async () => {
    const res = await listModelConfig(queryParams.value);
    configList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  });
};

/** 展示测试结果 */
const showTestResult = (result?: ModelTestResult) => {
  if (!result) {
    modal.notifyError('未获取到测试结果');
    return;
  }
  const content = `${result.message}（耗时 ${result.elapsedMs} ms）`;
  result.success ? modal.notifySuccess(content) : modal.notifyError(content);
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
  openDialog('新增模型配置');
};

/** 查看按钮操作（拉取完整字段后只读展示） */
const handleView = async (row: Partial<ModelConfigVO>) => {
  const res = await getModelConfig(row.id);
  detail.record = (res.data ?? null) as ModelConfigVO | null;
  detail.visible = true;
};

/** 修改按钮操作 */
const handleUpdate = async (row?: Partial<ModelConfigVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getModelConfig(id);
  Object.assign(form.value, res.data);
  // 密钥不回显，始终以空值提交，留空即不修改
  form.value.apiKey = undefined;
  showDialog('修改模型配置');
};

/** 复制按钮操作：按源配置生成一条新记录（名称自动追加 copy，重复则继续追加） */
const handleCopy = async (row: ModelConfigVO) => {
  await modal.confirm(
    `是否确认复制模型配置「${row.configName || row.modelCode || row.id}」？副本将沿用原配置的密钥与状态。`
  );
  const res = await copyModelConfig(row.id);
  modal.msgSuccess(`复制成功：${res.data?.configName ?? ''}`);
  await getList();
};

/** 行内连通性测试 */
const handleTestRow = async (row: ModelConfigVO) => {
  testing.value = true;
  try {
    const res = await testModelConfig({ id: row.id, modelType: row.modelType, modelCode: row.modelCode, baseUrl: row.baseUrl, dimension: row.dimension, timeoutSeconds: row.timeoutSeconds });
    showTestResult(res.data);
  } finally {
    testing.value = false;
  }
};

/** 弹窗内连通性测试（使用当前表单值，不落库） */
const handleTestForm = () => {
  if (!form.value.modelType || !form.value.modelCode || !form.value.baseUrl) {
    modal.msgWarning('请先填写模型类型、模型标识与服务地址');
    return;
  }
  testing.value = true;
  testModelConfig(form.value)
    .then((res) => showTestResult(res.data))
    .finally(() => (testing.value = false));
};

/** 提交按钮 */
const submitForm = () => {
  configFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      if (form.value.id) {
        await updateModelConfig(form.value);
        modal.msgSuccess('修改成功');
      } else {
        await addModelConfig(form.value);
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
const handleDelete = async (row?: Partial<ModelConfigVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm(
    row
      ? `是否确认删除模型配置「${row.configName || row.modelCode || row.id}」？`
      : `是否确认删除选中的 ${ids.value.length} 个模型配置？`
  );
  await delModelConfig(target);
  modal.msgSuccess('删除成功');
  await getList();
};

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
  color: var(--app-text-muted);
}

.tip-warn {
  color: var(--el-color-warning, #e6a23c);
}

.detail-summary {
  margin-bottom: 12px;
}
</style>