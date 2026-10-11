<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="configLoading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>长期记忆</h3>
            <p>
              记忆按用户隔离存放于智能体工作区：MEMORY.md 为合并后的长期记忆，memory/YYYY-MM-DD.md 为每日事实流水。
              保存后写入运行期配置，下一次对话即按新策略重新装配生效。默认关闭，需显式开启后才会产生抽取与合并的模型调用。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Refresh" @click="loadConfig">刷新</el-button>
            <el-button
              v-hasPermi="['ai:memory:edit']"
              type="primary"
              icon="Check"
              :loading="configSaving"
              @click="handleSaveConfig"
            >
              保存
            </el-button>
          </div>
        </div>
      </template>

      <el-form ref="configFormRef" :model="form" :rules="rules" label-width="220px" class="config-form">
        <el-divider content-position="left">记忆开关与注入</el-divider>
        <el-form-item label="启用长期记忆" prop="enabled">
          <template #label>
            <span class="label-tip">
              启用长期记忆
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    开启后每轮对话结束异步抽取事实写入当日流水，并按节流策略合并进 MEMORY.md（会产生额外模型调用）。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="记忆注入上限（Token）" prop="maxContextTokens">
          <template #label>
            <span class="label-tip">
              记忆注入上限（Token）
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">MEMORY.md 等记忆内容注入 system prompt 的 Token 预算，超出时截断（框架默认 8000）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.maxContextTokens" :min="500" :max="200000" :step="500" />
        </el-form-item>

        <el-divider content-position="left">事实抽取与合并</el-divider>
        <el-form-item label="事实抽取策略" prop="flushMode">
          <template #label>
            <span class="label-tip">
              事实抽取策略
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">抽取指从对话中提炼值得记住的事实并追加到当日流水。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="form.flushMode" style="width: 220px">
            <el-option label="每轮抽取（always）" value="always" />
            <el-option label="按间隔节流（throttled）" value="throttled" />
            <el-option label="不自动抽取（never）" value="never" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.flushMode === 'throttled'" label="抽取节流间隔（分钟）" prop="flushThrottleMinutes">
          <template #label>
            <span class="label-tip">
              抽取节流间隔（分钟）
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">两次抽取之间的最小间隔；间隔内的对话不触发抽取。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.flushThrottleMinutes" :min="1" :max="1440" :step="5" />
        </el-form-item>
        <el-form-item label="合并 Token 上限" prop="consolidationMaxTokens">
          <template #label>
            <span class="label-tip">
              合并 Token 上限
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">单次合并允许读取的每日流水 Token 上限（框架默认 4000）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.consolidationMaxTokens" :min="500" :max="100000" :step="500" />
        </el-form-item>
        <el-form-item label="合并最小间隔（分钟）" prop="consolidationMinGapMinutes">
          <template #label>
            <span class="label-tip">
              合并最小间隔（分钟）
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">两次自动合并之间的最小间隔（框架默认 30 分钟）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.consolidationMinGapMinutes" :min="1" :max="1440" :step="5" />
        </el-form-item>

        <el-divider content-position="left">归档与保留</el-divider>
        <el-form-item label="每日流水保留天数" prop="dailyFileRetentionDays">
          <template #label>
            <span class="label-tip">
              每日流水保留天数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">期望行为是把超期流水归档到 memory/archive/（框架默认 90 天）；<b>该归档依赖框架的文件系统检索，在当前「按用户隔离」的目录结构下不生效</b>，本项当前仅作配置保留。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.dailyFileRetentionDays" :min="1" :max="3650" :step="10" />
        </el-form-item>
        <el-form-item label="会话日志保留天数" prop="sessionRetentionDays">
          <template #label>
            <span class="label-tip">
              会话日志保留天数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">超过该天数的会话日志将被清理（框架默认 180 天）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.sessionRetentionDays" :min="1" :max="3650" :step="10" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel memory-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>记忆文件</h3>
            <p>按智能体与用户查看、编辑记忆文件；「触发合并」立即把当日流水合并进 MEMORY.md（人工干预自动节流）。</p>
          </div>
          <div class="toolbar-actions">
            <el-button
              v-hasPermi="['ai:memory:consolidate']"
              type="warning"
              plain
              icon="MagicStick"
              :loading="consolidating"
              @click="handleConsolidate"
            >
              触发合并
            </el-button>
            <el-button
              v-hasPermi="['ai:memory:archive']"
              plain
              icon="FolderOpened"
              :loading="archiving"
              @click="handleArchive"
            >
              归档
            </el-button>
            <el-button
              v-hasPermi="['ai:memory:cleanup']"
              plain
              icon="Delete"
              :loading="cleaning"
              @click="handleCleanup"
            >
              清理
            </el-button>
            <el-button
              v-hasPermi="['ai:memory:remove']"
              type="danger"
              plain
              icon="DeleteFilled"
              :loading="removing"
              @click="handleRemove"
            >
              删除记忆
            </el-button>
          </div>
        </div>
      </template>

      <div class="memory-filter">
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
        <span v-if="!form.enabled" class="filter-tip">长期记忆当前为关闭状态，以下为已落盘的历史记忆。</span>
      </div>

      <el-row :gutter="12">
        <el-col :span="9">
          <el-table
            v-loading="fileLoading"
            border
            highlight-current-row
            empty-text="暂无记忆文件"
            :data="files"
            @current-change="handleSelectFile"
          >
            <el-table-column label="文件" prop="name" :show-overflow-tooltip="true" />
            <el-table-column label="类型" align="center" width="90">
              <template #default="scope">
                <el-tag :type="scope.row.type === 'MEMORY' ? 'success' : 'info'" effect="plain">
                  {{ scope.row.type === 'MEMORY' ? '长期' : '流水' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="字符数" align="center" prop="chars" width="90" />
          </el-table>
        </el-col>
        <el-col :span="15">
          <div class="editor-shell">
            <div class="editor-head">
              <span class="editor-path">{{ current.path || '未选择文件' }}</span>
              <el-button
                v-hasPermi="['ai:memory:edit']"
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
            <el-input
              v-model="content"
              type="textarea"
              :rows="20"
              :disabled="!current.path"
              placeholder="选择左侧文件后在此查看与编辑"
            />
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup name="AiMemory" lang="ts">
import { optionAgent } from '@/api/ai/agent';
import type { AgentVO } from '@/api/ai/agent/types';
import {
  archiveMemory,
  cleanupMemory,
  consolidateMemory,
  getMemoryConfig,
  getMemoryContent,
  listMemoryFiles,
  removeMemory,
  updateMemoryConfig,
  updateMemoryContent
} from '@/api/ai/memory';
import type { MemoryConfig, MemoryFile } from '@/api/ai/memory/types';
import { listUser } from '@/api/system/user';
import type { UserQuery, UserVO } from '@/api/system/user/types';
import modal from '@/plugins/modal';

const configLoading = ref(true);
const configSaving = ref(false);
const configFormRef = ref<ElFormInstance>();

/** 表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const form = ref<MemoryConfig>({
  enabled: false,
  maxContextTokens: 8000,
  consolidationMaxTokens: 4000,
  consolidationMinGapMinutes: 30,
  dailyFileRetentionDays: 90,
  sessionRetentionDays: 180,
  flushMode: 'always',
  flushThrottleMinutes: 30
});

const rules = {
  maxContextTokens: [{ required: true, message: '记忆注入上限不能为空', trigger: 'blur' }],
  flushMode: [{ required: true, message: '事实抽取策略不能为空', trigger: 'change' }],
  flushThrottleMinutes: [{ required: true, message: '抽取节流间隔不能为空', trigger: 'blur' }],
  consolidationMaxTokens: [{ required: true, message: '合并 Token 上限不能为空', trigger: 'blur' }],
  consolidationMinGapMinutes: [{ required: true, message: '合并最小间隔不能为空', trigger: 'blur' }],
  dailyFileRetentionDays: [{ required: true, message: '流水保留天数不能为空', trigger: 'blur' }],
  sessionRetentionDays: [{ required: true, message: '会话保留天数不能为空', trigger: 'blur' }]
};

const agentOptions = ref<AgentVO[]>([]);
const userOptions = ref<UserVO[]>([]);
const query = ref<{ agentId?: string | number; userId?: string | number }>({});
const files = ref<MemoryFile[]>([]);
const fileLoading = ref(false);
const current = ref<{ path: string }>({ path: '' });
const content = ref('');
const contentSaving = ref(false);
const consolidating = ref(false);

/** 加载当前生效配置 */
const loadConfig = async () => {
  configLoading.value = true;
  try {
    const res = await getMemoryConfig();
    if (res.data) {
      form.value = res.data;
    }
  } finally {
    configLoading.value = false;
  }
};

/** 保存配置 */
const handleSaveConfig = async () => {
  await configFormRef.value?.validate();
  configSaving.value = true;
  try {
    await updateMemoryConfig(form.value);
    modal.msgSuccess('保存成功，下一次对话即按新策略装配生效');
    await loadConfig();
  } finally {
    configSaving.value = false;
  }
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

/** 加载记忆文件清单（切换智能体 / 用户后调用） */
const loadFiles = async () => {
  current.value = { path: '' };
  content.value = '';
  if (!query.value.agentId || !query.value.userId) {
    files.value = [];
    return;
  }
  fileLoading.value = true;
  try {
    const res = await listMemoryFiles(query.value.agentId, query.value.userId);
    files.value = res.data || [];
  } finally {
    fileLoading.value = false;
  }
};

/** 切换智能体或用户 */
const handleScopeChange = async () => {
  await loadFiles();
};

/** 选中文件并加载内容 */
const handleSelectFile = async (row?: MemoryFile) => {
  if (!row?.path || !query.value.agentId || !query.value.userId) {
    return;
  }
  current.value = { path: row.path };
  const res = await getMemoryContent(query.value.agentId, query.value.userId, row.path);
  content.value = res.data?.content ?? '';
};

/** 保存当前文件 */
const handleSaveContent = async () => {
  if (!current.value.path) {
    return;
  }
  contentSaving.value = true;
  try {
    await updateMemoryContent({
      agentId: query.value.agentId,
      userId: query.value.userId,
      path: current.value.path,
      content: content.value
    });
    modal.msgSuccess('记忆文件已保存');
    const row = files.value.find((item) => item.path === current.value.path);
    if (row) {
      row.chars = content.value.length;
    }
  } finally {
    contentSaving.value = false;
  }
};

/** 手动触发合并 */
const handleConsolidate = async () => {
  if (!query.value.agentId || !query.value.userId) {
    modal.msgWarning('请先选择智能体与用户');
    return;
  }
  consolidating.value = true;
  try {
    await consolidateMemory(query.value.agentId, query.value.userId);
    modal.msgSuccess('记忆合并已完成');
    await loadFiles();
  } finally {
    consolidating.value = false;
  }
};

const archiving = ref(false);
const cleaning = ref(false);
const removing = ref(false);

/** 校验已选智能体与用户 */
const requireScope = () => {
  if (!query.value.agentId || !query.value.userId) {
    modal.msgWarning('请先选择智能体与用户');
    return false;
  }
  return true;
};

/** 归档超期流水（移动到记忆目录下的 archive/ 子目录） */
const handleArchive = async () => {
  if (!requireScope()) return;
  archiving.value = true;
  try {
    const res = await archiveMemory(query.value.agentId, query.value.userId);
    modal.msgSuccess(`已归档 ${res.data ?? 0} 个流水文件`);
    await loadFiles();
  } finally {
    archiving.value = false;
  }
};

/** 清理超期记忆文件与会话日志 */
const handleCleanup = async () => {
  if (!requireScope()) return;
  cleaning.value = true;
  try {
    const res = await cleanupMemory(query.value.agentId, query.value.userId);
    modal.msgSuccess(`已清理 ${res.data ?? 0} 个文件`);
    await loadFiles();
  } finally {
    cleaning.value = false;
  }
};

/** 删除该用户的全部长期记忆（回滚 / 合规删除） */
const handleRemove = async () => {
  if (!requireScope()) return;
  try {
    await modal.confirm(
      '将删除该用户在小Z 与专家侧共用的全部长期记忆（MEMORY.md 与每日流水），且不可恢复。确认继续？'
    );
  } catch {
    return;
  }
  removing.value = true;
  try {
    await removeMemory(query.value.agentId, query.value.userId);
    modal.msgSuccess('长期记忆已删除');
    await loadFiles();
  } finally {
    removing.value = false;
  }
};

onMounted(async () => {
  await Promise.all([loadConfig(), loadOptions()]);
  await loadFiles();
});
</script>

<style scoped lang="scss">
.config-form {
  max-width: 760px;
  padding-top: 4px;
}

.memory-panel {
  margin-top: 12px;
}

.memory-filter {
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
</style>