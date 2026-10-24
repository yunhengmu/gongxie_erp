<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="configLoading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>工作区文件</h3>
            <p>
              工作区是智能体「人格与行为约定」的落点：AGENTS.md 与附加上下文文件会在每轮对话时全文注入 system prompt。
              文件按用户覆盖存放——工作区根为共享默认（所有人兜底），用户目录 &lt;用户&gt;/ 为按用户覆盖（优先生效）。
              保存后写入运行期配置，下一次对话即按新策略重新装配生效；本开关默认开启，且与长期记忆开关相互独立。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Refresh" @click="loadConfig">刷新</el-button>
            <el-button
              v-hasPermi="['ai:workspace:edit']"
              type="primary"
              icon="Check"
              :loading="configSaving"
              @click="handleSaveConfig"
            >
              保存配置
            </el-button>
          </div>
        </div>
      </template>

      <el-form :model="form" label-width="200px" class="config-form">
        <el-form-item label="启用工作区上下文">
          <template #label>
            <span class="label-tip">
              启用工作区上下文
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    开启后 AGENTS.md 与附加上下文文件注入 system prompt；关闭后不再注入（记忆开关关闭也不影响本开关）。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="附加上下文文件">
          <template #label>
            <span class="label-tip">
              附加上下文文件
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">新增或移除后请点「保存配置」，刷新文件列表后方可编辑。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <div class="file-config">
            <div class="file-config-add">
              <el-input
                v-model="newFile"
                placeholder="工作区相对路径，如 SOUL.md 或 rules/STYLE.md"
                style="width: 320px"
                clearable
                @keyup.enter="handleAddFile"
              />
              <el-button icon="Plus" @click="handleAddFile">添加</el-button>
            </div>
            <div v-if="form.additionalContextFiles.length" class="file-config-tags">
              <el-tag
                v-for="file in form.additionalContextFiles"
                :key="file"
                closable
                type="info"
                class="file-tag"
                @close="handleRemoveFile(file)"
              >
                {{ file }}
              </el-tag>
            </div>
            <span v-else class="form-tip">尚未登记附加上下文文件；AGENTS.md 始终受管，无需登记。</span>
          </div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel workspace-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>文件内容</h3>
            <p>选择智能体与用户后，可分别查看/编辑「用户覆盖」层与「共享默认」层；生效内容取用户覆盖优先。</p>
          </div>
        </div>
      </template>

      <div class="workspace-filter">
        <el-select v-model="query.agentId" placeholder="选择智能体" style="width: 220px" @change="handleScopeChange">
          <el-option v-for="item in agentOptions" :key="item.id" :label="item.agentName" :value="item.id" />
        </el-select>
        <el-select
          v-model="query.userId"
          placeholder="选择用户"
          filterable
          style="width: 240px"
          @change="handleScopeChange"
        >
          <el-option
            v-for="item in userOptions"
            :key="item.userId"
            :label="`${item.nickName || item.userName}（${item.userName}）`"
            :value="item.userId"
          />
        </el-select>
        <el-button icon="Refresh" @click="handleScopeChange">刷新文件</el-button>
        <span v-if="!form.enabled" class="filter-tip">工作区上下文当前为关闭状态，以下为工作区中已有的文件。</span>
      </div>

      <el-row :gutter="12">
        <el-col :span="9">
          <el-table
            v-loading="fileLoading"
            border
            highlight-current-row
            empty-text="暂无工作区文件"
            :data="files"
            @current-change="handleSelectFile"
          >
            <el-table-column label="文件" prop="name" :show-overflow-tooltip="true" />
            <el-table-column label="类型" align="center" width="90">
              <template #default="scope">
                <el-tag :type="scope.row.type === 'AGENTS' ? 'success' : 'info'" effect="plain">
                  {{ scope.row.type === 'AGENTS' ? '人格约定' : '附加' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="共享默认" align="center" width="90">
              <template #default="scope">{{ scope.row.hasShared ? scope.row.sharedChars : '—' }}</template>
            </el-table-column>
            <el-table-column label="用户覆盖" align="center" width="90">
              <template #default="scope">{{ scope.row.hasUser ? scope.row.userChars : '—' }}</template>
            </el-table-column>
            <el-table-column label="生效" align="center" width="90">
              <template #default="scope">
                <el-tag v-if="scope.row.effective === 'USER'" type="warning" effect="plain">用户</el-tag>
                <el-tag v-else-if="scope.row.effective === 'SHARED'" type="success" effect="plain">共享</el-tag>
                <el-tag v-else type="info" effect="plain">空</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-col>
        <el-col :span="15">
          <div class="editor-shell">
            <div class="editor-head">
              <span class="editor-path">{{ current.path || '未选择文件' }}</span>
              <div class="editor-actions">
                <el-radio-group v-model="scope" size="small" :disabled="!current.path">
                  <el-radio-button value="USER">用户覆盖</el-radio-button>
                  <el-radio-button value="SHARED">共享默认</el-radio-button>
                </el-radio-group>
                <el-button
                  v-hasPermi="['ai:workspace:edit']"
                  type="danger"
                  plain
                  size="small"
                  :disabled="!current.path || scope !== 'USER' || !current.hasUser"
                  :loading="resetting"
                  @click="handleResetOverride"
                >
                  清除用户覆盖
                </el-button>
                <el-button
                  v-hasPermi="['ai:workspace:edit']"
                  type="primary"
                  size="small"
                  icon="Check"
                  :disabled="!current.path"
                  :loading="contentSaving"
                  @click="handleSaveContent"
                >
                  保存文件
                </el-button>
              </div>
            </div>
            <el-input
              v-model="editingContent"
              type="textarea"
              :rows="20"
              :disabled="!current.path"
              :placeholder="scope === 'USER' ? '该用户暂无覆盖，编辑并保存后即成为该用户的专属文件' : '共享默认对该智能体下的所有用户生效'"
            />
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup name="AiWorkspace" lang="ts">
import { optionAgent } from '@/api/ai/agent';
import type { AgentVO } from '@/api/ai/agent/types';
import {
  getWorkspaceConfig,
  getWorkspaceContent,
  listWorkspaceFiles,
  resetWorkspaceOverride,
  updateWorkspaceConfig,
  updateWorkspaceContent
} from '@/api/ai/workspace';
import type { WorkspaceConfig, WorkspaceFile } from '@/api/ai/workspace/types';
import { listUser } from '@/api/system/user';
import type { UserQuery, UserVO } from '@/api/system/user/types';
import modal from '@/plugins/modal';

const configLoading = ref(true);
const configSaving = ref(false);

/** 表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const form = ref<WorkspaceConfig>({
  enabled: true,
  additionalContextFiles: []
});
const newFile = ref('');

const agentOptions = ref<AgentVO[]>([]);
const userOptions = ref<UserVO[]>([]);
const query = ref<{ agentId?: string | number; userId?: string | number }>({});
const files = ref<WorkspaceFile[]>([]);
const fileLoading = ref(false);
const current = ref<{ path: string; hasUser: boolean }>({ path: '', hasUser: false });
/** 当前编辑的层：USER 用户覆盖 / SHARED 共享默认 */
const scope = ref('USER');
const userContent = ref('');
const sharedContent = ref('');
const contentSaving = ref(false);
const resetting = ref(false);

/** 当前层对应的编辑内容 */
const editingContent = computed({
  get: () => (scope.value === 'USER' ? userContent.value : sharedContent.value),
  set: (value: string) => {
    if (scope.value === 'USER') {
      userContent.value = value;
    } else {
      sharedContent.value = value;
    }
  }
});

/** 加载当前生效配置 */
const loadConfig = async () => {
  configLoading.value = true;
  try {
    const res = await getWorkspaceConfig();
    if (res.data) {
      form.value = {
        enabled: res.data.enabled,
        additionalContextFiles: res.data.additionalContextFiles || []
      };
    }
  } finally {
    configLoading.value = false;
  }
};

/** 保存配置 */
const handleSaveConfig = async () => {
  configSaving.value = true;
  try {
    await updateWorkspaceConfig(form.value);
    modal.msgSuccess('保存成功，下一次对话即按新策略装配生效');
    await Promise.all([loadConfig(), loadFiles()]);
  } finally {
    configSaving.value = false;
  }
};

/** 添加附加上下文文件 */
const handleAddFile = () => {
  const file = newFile.value.trim().replace(/^\/+/, '');
  if (!file) {
    return;
  }
  if (!/^(?!.*\.\.)[A-Za-z0-9][A-Za-z0-9._/-]*\.md$/.test(file)) {
    modal.msgWarning('请填写合法的工作区相对路径（Markdown 文件，不含 .. 与绝对路径）');
    return;
  }
  if (form.value.additionalContextFiles.includes(file)) {
    modal.msgWarning('该文件已在清单中');
    return;
  }
  form.value.additionalContextFiles.push(file);
  newFile.value = '';
};

/** 移除附加上下文文件 */
const handleRemoveFile = (file: string) => {
  form.value.additionalContextFiles = form.value.additionalContextFiles.filter((item) => item !== file);
};

/** 加载智能体与用户下拉（用户列表复用系统用户接口） */
const loadOptions = async () => {
  const res = await optionAgent();
  agentOptions.value = res.data || [];
  if (agentOptions.value.length) {
    query.value.agentId = agentOptions.value[0].id;
  }
  try {
    // 系统用户列表走 R<PageResult> 包装，分页数据在 data.rows
    const userRes = (await listUser({ pageNum: 1, pageSize: 500 } as UserQuery)) as unknown as {
      data?: { rows?: UserVO[] };
    };
    userOptions.value = userRes.data?.rows || [];
  } catch {
    userOptions.value = [];
  }
  if (userOptions.value.length) {
    query.value.userId = userOptions.value[0].userId;
  }
};

/** 加载工作区文件清单（切换智能体 / 用户后调用） */
const loadFiles = async () => {
  current.value = { path: '', hasUser: false };
  userContent.value = '';
  sharedContent.value = '';
  if (!query.value.agentId || !query.value.userId) {
    files.value = [];
    return;
  }
  fileLoading.value = true;
  try {
    const res = await listWorkspaceFiles(query.value.agentId, query.value.userId);
    files.value = res.data || [];
  } finally {
    fileLoading.value = false;
  }
};

/** 切换智能体或用户 */
const handleScopeChange = async () => {
  await loadFiles();
};

/** 加载指定文件的两层内容 */
const loadLayerContents = async (path: string) => {
  const [userRes, sharedRes] = await Promise.all([
    getWorkspaceContent(query.value.agentId as string | number, query.value.userId as string | number, path, 'USER'),
    getWorkspaceContent(query.value.agentId as string | number, query.value.userId as string | number, path, 'SHARED')
  ]);
  userContent.value = userRes.data?.content ?? '';
  sharedContent.value = sharedRes.data?.content ?? '';
};

/** 选中文件并加载两层内容 */
const handleSelectFile = async (row?: WorkspaceFile) => {
  if (!row?.path || !query.value.agentId || !query.value.userId) {
    return;
  }
  current.value = { path: row.path, hasUser: row.hasUser };
  await loadLayerContents(row.path);
  scope.value = 'USER';
};

/** 保存当前层文件 */
const handleSaveContent = async () => {
  const path = current.value.path;
  if (!path) {
    return;
  }
  contentSaving.value = true;
  try {
    await updateWorkspaceContent({
      agentId: query.value.agentId,
      userId: query.value.userId,
      path,
      scope: scope.value,
      content: editingContent.value
    });
    modal.msgSuccess(scope.value === 'USER' ? '用户覆盖已保存' : '共享默认已保存');
    // 仅本地更新该行的层信息，避免整表刷新导致选中态丢失
    const row = files.value.find((item) => item.path === path);
    if (row) {
      if (scope.value === 'USER') {
        row.hasUser = editingContent.value.length > 0;
        row.userChars = editingContent.value.length;
      } else {
        row.hasShared = editingContent.value.length > 0;
        row.sharedChars = editingContent.value.length;
      }
      row.effective = row.hasUser ? 'USER' : row.hasShared ? 'SHARED' : 'NONE';
      current.value = { path, hasUser: row.hasUser };
    }
  } finally {
    contentSaving.value = false;
  }
};

/** 清除用户覆盖，回落共享默认 */
const handleResetOverride = async () => {
  const path = current.value.path;
  if (!path) {
    return;
  }
  await modal.confirm('确认清除该用户的覆盖？清除后该文件将回落共享默认。');
  resetting.value = true;
  try {
    await resetWorkspaceOverride(
      query.value.agentId as string | number,
      query.value.userId as string | number,
      path
    );
    modal.msgSuccess('用户覆盖已清除');
    const row = files.value.find((item) => item.path === path);
    userContent.value = '';
    if (row) {
      row.hasUser = false;
      row.userChars = 0;
      row.effective = row.hasShared ? 'SHARED' : 'NONE';
    }
    current.value = { path, hasUser: false };
  } finally {
    resetting.value = false;
  }
};

onMounted(async () => {
  await Promise.all([loadConfig(), loadOptions()]);
  await loadFiles();
});
</script>

<style scoped lang="scss">
.config-form {
  max-width: 900px;
  padding-top: 4px;
}

.form-tip {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.file-config {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.file-config-add {
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-config-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.workspace-panel {
  margin-top: 12px;
}

.workspace-filter {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.filter-tip {
  color: var(--el-color-warning);
  font-size: 12px;
}

.editor-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.editor-path {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.editor-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>