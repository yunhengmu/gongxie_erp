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
              placeholder="请输入 Server 名称 / 地址"
              clearable
              style="width: 240px"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="传输方式" prop="transport">
            <el-select v-model="queryParams.transport" placeholder="全部" clearable style="width: 140px">
              <el-option label="http（streamable）" value="http" />
              <el-option label="sse" value="sse" />
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
            <h3>MCP 客户端</h3>
            <p>
              配置「外部 MCP Server」，把第三方系统能力接入本平台智能体。连接为平台级：启用后全部智能体装配时都会接入；
              暴露面由「工具白名单」收敛（留空表示放行该 Server 全部工具）。仅支持 http（streamable）/ sse 传输，不放开 stdio。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:mcp:client:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:mcp:client:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:mcp:client:remove']"
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

      <el-table v-loading="loading" border class="data-table" :data="clientList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="Server 名称" align="center" prop="serverName" width="170" :show-overflow-tooltip="true" />
        <el-table-column label="传输" align="center" width="150">
          <template #default="scope">
            <el-tag effect="plain">{{ scope.row.transport === 'sse' ? 'sse' : 'http（streamable）' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="地址" align="left" prop="url" :show-overflow-tooltip="true" />
        <el-table-column label="工具白名单" align="left" width="200" :show-overflow-tooltip="true">
          <template #default="scope">
            <span v-if="scope.row.enableTools">{{ scope.row.enableTools }}</span>
            <span v-else class="form-tip">全部工具</span>
          </template>
        </el-table-column>
        <el-table-column label="请求头" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.headersConfigured ? 'success' : 'info'" effect="plain">
              {{ scope.row.headersConfigured ? '已配置' : '无' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'info'">
              {{ scope.row.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" align="center" prop="updateTime" width="170" />
        <el-table-column label="操作" align="center" width="250" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看" placement="top">
              <el-button
                v-hasPermi="['ai:mcp:client:query']"
                link
                type="primary"
                icon="View"
                @click="handleView(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="测试连接" placement="top">
              <el-button
                v-hasPermi="['ai:mcp:client:query']"
                link
                type="primary"
                icon="Connection"
                @click="handleTest(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:mcp:client:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:mcp:client:remove']"
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

    <el-dialog v-model="dialog.visible" :title="dialog.title" width="720px" append-to-body>
      <el-form ref="clientFormRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="Server 名称" prop="serverName">
          <template #label>
            <span class="label-tip">
              Server 名称
              <el-tooltip v-if="form.id" placement="top">
                <template #content>
                  <div class="tip-content">名称创建后不可修改</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="form.serverName"
            :disabled="!!form.id"
            placeholder="唯一标识，作为连接键，如 crm-mcp"
          />
        </el-form-item>
        <el-form-item label="传输方式" prop="transport">
          <el-radio-group v-model="form.transport">
            <el-radio value="http">http（streamable）</el-radio>
            <el-radio value="sse">sse</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Server 地址" prop="url">
          <el-input v-model="form.url" placeholder="如 http://127.0.0.1:8080/mcp" />
        </el-form-item>
        <el-form-item label="请求头" prop="headersJson">
          <template #label>
            <span class="label-tip">
              请求头
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    请求头 JSON（可承载 Authorization 等凭据）。密文落库且不回显：<b>留空表示保持不变</b>，填写则整体覆盖。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="form.headersJson"
            type="textarea"
            :rows="3"
            class="mono-textarea"
            :placeholder="headersPlaceholder"
          />
        </el-form-item>
        <el-form-item label="工具白名单" prop="enableTools">
          <template #label>
            <span class="label-tip">
              工具白名单
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    可选：先点下方「测试连接」拉取该 Server 声明的工具名，再勾选收敛暴露面；也可直接手工输入工具名。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select
            v-model="enableToolList"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="留空表示放行该 Server 全部工具"
            style="width: 100%"
          >
            <el-option v-for="name in discoveredTools" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="超时设置">
          <template #label>
            <span class="label-tip">
              超时设置
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    请求超时（毫秒，留空取框架默认）
                    <br />
                    初始化超时（毫秒，留空取框架默认）
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.timeoutMs" :min="0" :step="1000" placeholder="请求超时（毫秒）" />
          <el-input-number
            v-model="form.initTimeoutMs"
            :min="0"
            :step="1000"
            placeholder="初始化超时"
            style="margin-left: 12px"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <template #label>
            <span class="label-tip">
              状态
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">停用后不再接入智能体装配</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-radio-group v-model="form.status">
            <el-radio value="0">启用</el-radio>
            <el-radio value="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="用途说明" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="如：接入 CRM 客户查询能力" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="请输入备注" />
        </el-form-item>
        <el-form-item v-if="testResult" label="测试结果">
          <el-alert
            :type="testResult.success ? 'success' : 'error'"
            :title="testResult.message"
            :closable="false"
            show-icon
          >
            <div v-if="testResult.tools.length" class="test-tools">
              <el-tag v-for="name in testResult.tools" :key="name" class="test-tag" effect="plain">{{ name }}</el-tag>
            </div>
          </el-alert>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button icon="Connection" :loading="testing" @click="handleTestInDialog">测试连接</el-button>
          <el-button :loading="buttonLoading" type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="detail.visible" title="MCP 客户端详情" width="860px" append-to-body>
      <template v-if="detail.record">
        <el-descriptions :column="2" border size="small" class="detail-summary">
          <el-descriptions-item label="Server 名称">{{ detail.record.serverName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="传输">
            <el-tag effect="plain">{{ detail.record.transport === 'sse' ? 'sse' : 'http（streamable）' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.record.status === '0' ? 'success' : 'info'">
              {{ detail.record.status === '0' ? '启用' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="请求头">
            <el-tag :type="detail.record.headersConfigured ? 'success' : 'info'" effect="plain">
              {{ detail.record.headersConfigured ? '已配置' : '无' }}
            </el-tag>
            <span class="text-muted">（密文落库，不回显）</span>
          </el-descriptions-item>
          <el-descriptions-item label="Server 地址" :span="2">{{ detail.record.url || '-' }}</el-descriptions-item>
          <el-descriptions-item label="工具白名单" :span="2">
            <span v-if="detail.record.enableTools">{{ detail.record.enableTools }}</span>
            <span v-else class="text-muted">全部工具</span>
          </el-descriptions-item>
          <el-descriptions-item label="请求超时">{{ detail.record.timeoutMs ?? '框架默认' }}</el-descriptions-item>
          <el-descriptions-item label="初始化超时">{{ detail.record.initTimeoutMs ?? '框架默认' }}</el-descriptions-item>
          <el-descriptions-item label="用途说明" :span="2">{{ detail.record.description || '-' }}</el-descriptions-item>
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

<script setup name="AiMcpClient" lang="ts">
import { addMcpClient, delMcpClient, getMcpClient, listMcpClient, testMcpClient, updateMcpClient } from '@/api/ai/mcp/client';
import type { McpClientForm, McpClientQuery, McpClientTestResult, McpClientVO } from '@/api/ai/mcp/client/types';
import modal from '@/plugins/modal';

const headersPlaceholder = `{
  "Authorization": "Bearer <token>"
}`;

const clientList = ref<McpClientVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const buttonLoading = ref(false);
const testing = ref(false);
const queryFormRef = ref<ElFormInstance>();
const clientFormRef = ref<ElFormInstance>();

const queryParams = ref<McpClientQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  transport: undefined,
  status: undefined
});

const initFormData: McpClientForm = {
  id: undefined,
  serverName: undefined,
  transport: 'http',
  url: undefined,
  headersJson: undefined,
  enableTools: undefined,
  timeoutMs: undefined,
  initTimeoutMs: undefined,
  description: undefined,
  status: '0',
  remark: undefined
};

const dialog = reactive<{ visible: boolean; title: string }>({
  visible: false,
  title: ''
});

const form = ref<McpClientForm>({ ...initFormData });
/** 工具白名单以数组编辑，提交时拼成逗号分隔文本 */
const enableToolList = ref<string[]>([]);
/** 测试连接发现的工具（供白名单下拉选择） */
const discoveredTools = ref<string[]>([]);
const testResult = ref<McpClientTestResult | null>(null);

const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);

/** 详情弹窗（查看 MCP 客户端完整信息，只读；请求头凭据不回显） */
const detail = reactive<{ visible: boolean; record: McpClientVO | null }>({
  visible: false,
  record: null
});

/** 表单校验规则 */
const rules = computed<ElFormRules>(() => ({
  serverName: [
    { required: true, message: 'Server 名称不能为空', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/,
      message: '字母或数字开头，仅含字母、数字、点、下划线、短横线，长度 1~100',
      trigger: 'blur'
    }
  ],
  transport: [{ required: true, message: '请选择传输方式', trigger: 'change' }],
  url: [
    { required: true, message: 'Server 地址不能为空', trigger: 'blur' },
    { pattern: /^https?:\/\/.+/i, message: '地址需以 http:// 或 https:// 开头', trigger: 'blur' }
  ]
}));

/** 查询外部 MCP 连接列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listMcpClient(queryParams.value);
    clientList.value = res.data?.rows ?? [];
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
  queryParams.value.pageNum = 1;
  getList();
};

const handleSelectionChange = (selection: McpClientVO[]) => {
  ids.value = selection.map((item) => item.id);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};

/** 重置表单为初始值 */
const reset = () => {
  form.value = { ...initFormData };
  enableToolList.value = [];
  discoveredTools.value = [];
  testResult.value = null;
  clientFormRef.value?.resetFields();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.title = '新增外部 MCP 连接';
  dialog.visible = true;
};

/** 查看按钮操作（拉取完整字段后只读展示） */
const handleView = async (row: Partial<McpClientVO>) => {
  const res = await getMcpClient(row.id);
  detail.record = (res.data ?? null) as McpClientVO | null;
  detail.visible = true;
};

/** 修改按钮操作 */
const handleUpdate = async (row?: Partial<McpClientVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getMcpClient(id);
  const detail = res.data;
  form.value = {
    id: detail.id,
    serverName: detail.serverName,
    transport: detail.transport ?? 'http',
    url: detail.url,
    headersJson: undefined,
    enableTools: detail.enableTools,
    timeoutMs: detail.timeoutMs,
    initTimeoutMs: detail.initTimeoutMs,
    description: detail.description,
    status: detail.status ?? '0',
    remark: detail.remark
  };
  enableToolList.value = (detail.enableTools || '')
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
  dialog.title = '修改外部 MCP 连接';
  dialog.visible = true;
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = () => {
  clientFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: McpClientForm = {
        ...form.value,
        enableTools: enableToolList.value.join(',')
      };
      if (payload.id) {
        await updateMcpClient(payload);
        modal.msgSuccess('修改成功，下一次对话即按新配置装配');
      } else {
        delete payload.id;
        await addMcpClient(payload);
        modal.msgSuccess('新增成功，下一次对话即按新配置装配');
      }
      dialog.visible = false;
      await getList();
    } finally {
      buttonLoading.value = false;
    }
  });
};

/** 删除按钮操作 */
const handleDelete = async (row?: Partial<McpClientVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm(
    row
      ? `是否确认删除外部 MCP 连接「${row.serverName || row.id}」？`
      : `是否确认删除选中的 ${ids.value.length} 个外部 MCP 连接？`
  );
  await delMcpClient(target);
  modal.msgSuccess('删除成功');
  await getList();
};

/** 表格行内「测试连接」：直接落结果提示 */
const handleTest = async (row: McpClientVO) => {
  testing.value = true;
  try {
    const res = await testMcpClient({ id: row.id });
    const result = res.data;
    if (result?.success) {
      modal.msgSuccess(`${result.message}：${(result.tools || []).join('、') || '（无工具）'}`);
    } else {
      modal.msgError(result?.message || '连接失败');
    }
  } finally {
    testing.value = false;
  }
};

/** 弹窗内「测试连接」：结果落到表单，并回填可选工具名 */
const handleTestInDialog = async () => {
  testing.value = true;
  try {
    const res = await testMcpClient({ ...form.value });
    const result = res.data;
    testResult.value = result ?? null;
    if (result?.success) {
      discoveredTools.value = result.tools || [];
      modal.msgSuccess(result.message);
    } else {
      discoveredTools.value = [];
      modal.msgError(result?.message || '连接失败');
    }
  } finally {
    testing.value = false;
  }
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

.form-tip {
  margin-top: 4px;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.6;
  margin-left: 8px;
}

.mono-textarea :deep(textarea) {
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
}

.test-tools {
  margin-top: 6px;
}

.test-tag {
  margin: 0 6px 6px 0;
}

.text-muted {
  color: var(--app-text-muted, #909399);
}

.detail-summary {
  margin-bottom: 12px;
}
</style>