<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="configLoading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>长期记忆</h3>
            <p>
              记忆按用户隔离存放于智能体工作区：MEMORY.md 为合并后的长期记忆，memory/YYYY-MM-DD.md 为每日事实流水。
              保存后写入运行期配置，下一轮对话即按新策略重新装配生效。默认开启；关闭后保留已有记忆文件，但不再产生新的抽取与合并调用。
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
        <el-form-item label="记忆管道模式" prop="pipelineMode">
          <template #label>
            <span class="label-tip">
              记忆管道模式
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    framework：框架负责抽取/合并；shadow：平台抽取只留痕；platform：关闭框架记忆 hooks，
                    由平台接管注入、抽取、合并与维护。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="form.pipelineMode" style="width: 260px">
            <el-option label="框架托管（现状）" value="framework" />
            <el-option label="影子模式（只记录不写入）" value="shadow" />
            <el-option label="平台托管（关闭框架记忆）" value="platform" />
          </el-select>
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
                  <div class="tip-content">框架自动合并两次执行之间的最小间隔（默认 30 分钟）。</div>
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
                  <div class="tip-content">超过该天数的每日流水会移入 memory/archive/ 归档目录（默认 90 天），归档文件再按「归档保留天数」清理。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.dailyFileRetentionDays" :min="1" :max="3650" :step="10" />
        </el-form-item>
        <el-form-item label="归档保留天数" prop="archiveRetentionDays">
          <template #label>
            <span class="label-tip">
              归档保留天数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">已移入 memory/archive/ 的流水保留天数（默认 365 天），到期后由自动清理删除。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.archiveRetentionDays" :min="1" :max="3650" :step="30" />
        </el-form-item>
        <el-form-item label="任务日志保留天数" prop="sessionRetentionDays">
          <template #label>
            <span class="label-tip">
              任务日志保留天数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">超过该天数的任务日志将被清理（框架默认 180 天）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.sessionRetentionDays" :min="1" :max="3650" :step="10" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel quality-panel" v-loading="qualityLoading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>运行健康</h3>
            <p>
              近 {{ quality.days }} 天抽取与合并的审计汇总；只反映「记忆是否还在工作」，语义召回率需用离线门禁评测。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-radio-group v-model="qualityDays" size="small" @change="loadQuality">
              <el-radio-button :value="1">近 1 天</el-radio-button>
              <el-radio-button :value="7">近 7 天</el-radio-button>
            </el-radio-group>
            <el-button icon="Refresh" @click="loadQuality">刷新</el-button>
          </div>
        </div>
      </template>

      <div class="quality-grid">
        <div class="quality-item">
          <span class="quality-label">抽取执行</span>
          <div class="quality-value">{{ quality.extractRuns }}<em>次</em></div>
          <span class="quality-hint">无产出 {{ quality.extractEmpty }} 次</span>
        </div>
        <div class="quality-item">
          <span class="quality-label">保留事实</span>
          <div class="quality-value">{{ quality.extractKept }}<em>条</em></div>
          <span class="quality-hint">
            needs_context {{ quality.needsContext }} · 低置信 {{ quality.lowConfidence }}
          </span>
        </div>
        <div class="quality-item">
          <span class="quality-label">抽取失败</span>
          <div class="quality-value" :class="{ 'quality-danger': quality.extractFailures > 0 }">
            {{ quality.extractFailures }}<em>次</em>
          </div>
          <span class="quality-hint">最近抽取 {{ formatQualityTime(quality.lastExtractAt) }}</span>
        </div>
        <div class="quality-item">
          <span class="quality-label">合并执行</span>
          <div class="quality-value">{{ quality.consolidateRuns }}<em>次</em></div>
          <span class="quality-hint">
            失败 {{ quality.consolidateFailures }} 次 · 最近 {{ formatQualityTime(quality.lastConsolidateAt) }}
          </span>
        </div>
        <div class="quality-item">
          <span class="quality-label">上次合并消耗</span>
          <div class="quality-value">{{ quality.lastConsolidateTokens }}<em>tokens</em></div>
          <span class="quality-hint">耗时 {{ quality.lastConsolidateMs }} ms</span>
        </div>
      </div>
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
              v-hasPermi="['ai:memory:query']"
              plain
              icon="Clock"
              :disabled="!query.agentId || !query.userId"
              @click="handleOpenVersions"
            >
              版本历史
            </el-button>
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
              icon="Brush"
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

    <el-card shadow="hover" class="table-panel declared-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>显式记忆</h3>
            <p>
              用户在对话中明确要求「记住」的个人偏好。独立存放、不参与自动合并，注入时固定排在长期记忆之前；
              平台托管模式下模型可通过 remember_user_preference / forget_user_preference 工具写入与撤销。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Refresh" @click="loadDeclared">刷新</el-button>
            <el-button
              v-hasPermi="['ai:memory:edit']"
              type="primary"
              icon="Plus"
              :disabled="!query.agentId || !query.userId"
              @click="handleOpenDeclared"
            >
              新增
            </el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="declaredLoading" border empty-text="暂无显式记忆" :data="declared">
        <el-table-column label="状态" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.revoked ? 'info' : 'success'" effect="plain">
              {{ scope.row.revoked ? '已撤销' : '生效中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="记忆内容" prop="fact" min-width="220" :show-overflow-tooltip="true" />
        <el-table-column label="用户原话" prop="evidence" min-width="220" :show-overflow-tooltip="true" />
        <el-table-column label="创建时间" prop="createdAt" width="200" :show-overflow-tooltip="true" />
        <el-table-column label="操作" align="center" width="100">
          <template #default="scope">
            <el-button
              v-hasPermi="['ai:memory:edit']"
              link
              type="danger"
              :disabled="scope.row.revoked"
              @click="handleRevokeDeclared(scope.row)"
            >
              撤销
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="declaredVisible" title="新增显式记忆" width="560px" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="记忆内容" required>
          <el-input
            v-model="declaredForm.fact"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="一句话描述要记住的偏好，如：用户偏好用表格统计数据"
          />
        </el-form-item>
        <el-form-item label="用户原话">
          <el-input
            v-model="declaredForm.evidence"
            maxlength="1000"
            placeholder="可选，记录用户的原始表述，便于事后追溯"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="declaredVisible = false">取消</el-button>
        <el-button type="primary" :loading="declaredSaving" @click="confirmDeclared">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionVisible" title="MEMORY.md 版本历史" width="900px" destroy-on-close>
      <el-table v-loading="versionLoading" border empty-text="暂无历史版本" :data="versions">
        <el-table-column label="时间" prop="createdAt" width="210" :show-overflow-tooltip="true" />
        <el-table-column label="来源" width="150">
          <template #default="scope">
            <el-tag :type="scope.row.current ? 'success' : 'info'" effect="plain">
              {{ sourceLabel(scope.row.source) }}
            </el-tag>
            <el-tag v-if="scope.row.current" type="success" size="small" class="version-current-tag">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="字符数" prop="chars" align="center" width="90" />
        <el-table-column label="备注" prop="note" :show-overflow-tooltip="true" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" @click="handleViewVersion(scope.row)">查看</el-button>
            <el-button
              v-hasPermi="['ai:memory:edit']"
              link
              type="warning"
              :disabled="scope.row.current"
              @click="handleRestoreVersion(scope.row)"
            >
              恢复
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="versionDetailVisible" title="版本详情" width="760px" destroy-on-close>
      <div v-if="versionDetail" class="version-detail-meta">
        <span>版本：{{ versionDetail.versionId }}</span>
        <span>来源：{{ sourceLabel(versionDetail.source) }}</span>
        <span>水位：{{ versionDetail.watermark || '—' }}</span>
      </div>
      <el-input
        :model-value="versionDetail?.content || ''"
        type="textarea"
        :rows="22"
        readonly
      />
    </el-dialog>

    <el-dialog v-model="restoreVisible" title="恢复 MEMORY.md 版本" width="560px" destroy-on-close>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="恢复会覆盖当前 MEMORY.md；系统会先自动保存当前版本，可再次回退。"
        class="restore-alert"
      />
      <el-form label-width="120px">
        <el-form-item label="恢复模式">
          <el-radio-group v-model="restoreForm.mode">
            <el-radio value="CONTENT">只恢复内容</el-radio>
            <el-radio value="CONTENT_AND_WATERMARK">内容 + 合并水位</el-radio>
          </el-radio-group>
          <div class="restore-tip">
            只恢复内容时水位不变；同时恢复水位后，仍在 memory/ 的流水可能被重新合并。
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="restoreForm.note" maxlength="200" show-word-limit placeholder="可选，记录本次恢复原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="restoreVisible = false">取消</el-button>
        <el-button type="warning" :loading="restoring" @click="confirmRestore">确认恢复</el-button>
      </template>
    </el-dialog>

    <div class="config-savebar">
      <span class="config-savebar__tip">修改配置后需保存才会生效</span>
      <div class="config-savebar__actions">
        <el-button icon="Refresh" @click="loadConfig">重置</el-button>
        <el-button
          v-hasPermi="['ai:memory:edit']"
          type="primary"
          icon="Check"
          :loading="configSaving"
          @click="handleSaveConfig"
        >
          保存配置
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup name="AiMemory" lang="ts">
import { optionAgent } from '@/api/ai/agent';
import type { AgentVO } from '@/api/ai/agent/types';
import {
  addDeclaredMemory,
  archiveMemory,
  cleanupMemory,
  consolidateMemory,
  getMemoryConfig,
  getMemoryContent,
  getMemoryQuality,
  getMemoryVersion,
  listDeclaredMemories,
  listMemoryFiles,
  listMemoryVersions,
  removeMemory,
  restoreMemoryVersion,
  revokeDeclaredMemory,
  updateMemoryConfig,
  updateMemoryContent
} from '@/api/ai/memory';
import type {
  MemoryConfig,
  MemoryDeclaredEntry,
  MemoryFile,
  MemoryQuality,
  MemoryRestoreForm,
  MemoryVersion
} from '@/api/ai/memory/types';
import { listUser } from '@/api/system/user';
import type { UserQuery, UserVO } from '@/api/system/user/types';
import modal from '@/plugins/modal';

const configLoading = ref(true);
const configSaving = ref(false);
const configFormRef = ref<ElFormInstance>();

/** 表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const form = ref<MemoryConfig>({
  enabled: false,
  pipelineMode: 'framework',
  extractContextMessages: 6,
  extractContextMaxChars: 8000,
  extractKnownMemoryMaxChars: 2000,
  injectDeclaredMaxTokens: 800,
  maxContextTokens: 8000,
  consolidationMaxTokens: 4000,
  consolidationMinGapMinutes: 30,
  dailyFileRetentionDays: 90,
  archiveRetentionDays: 365,
  sessionRetentionDays: 180,
  flushMode: 'throttled',
  flushThrottleMinutes: 30
});

const rules = {
  pipelineMode: [{ required: true, message: '记忆管道模式不能为空', trigger: 'change' }],
  maxContextTokens: [{ required: true, message: '记忆注入上限不能为空', trigger: 'blur' }],
  flushMode: [{ required: true, message: '事实抽取策略不能为空', trigger: 'change' }],
  flushThrottleMinutes: [{ required: true, message: '抽取节流间隔不能为空', trigger: 'blur' }],
  consolidationMaxTokens: [{ required: true, message: '合并 Token 上限不能为空', trigger: 'blur' }],
  consolidationMinGapMinutes: [{ required: true, message: '合并最小间隔不能为空', trigger: 'blur' }],
  dailyFileRetentionDays: [{ required: true, message: '流水保留天数不能为空', trigger: 'blur' }],
  archiveRetentionDays: [{ required: true, message: '归档保留天数不能为空', trigger: 'blur' }],
  sessionRetentionDays: [{ required: true, message: '任务日志保留天数不能为空', trigger: 'blur' }]
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

/** 显式记忆状态 */
const declared = ref<MemoryDeclaredEntry[]>([]);
const declaredLoading = ref(false);
const declaredVisible = ref(false);
const declaredSaving = ref(false);
const declaredForm = ref<{ fact: string; evidence: string }>({ fact: '', evidence: '' });

/** 运行健康状态（审计聚合，仅运行指标） */
const qualityDays = ref(1);
const qualityLoading = ref(false);
const quality = ref<MemoryQuality>({
  days: 1,
  extractRuns: 0,
  extractEmpty: 0,
  extractKept: 0,
  needsContext: 0,
  lowConfidence: 0,
  extractFailures: 0,
  consolidateRuns: 0,
  consolidateFailures: 0,
  lastConsolidateMs: 0,
  lastConsolidateTokens: 0,
  lastExtractAt: '',
  lastConsolidateAt: ''
});

/** 版本历史状态 */
const versionVisible = ref(false);
const versionLoading = ref(false);
const versions = ref<MemoryVersion[]>([]);
const versionDetailVisible = ref(false);
const versionDetail = ref<MemoryVersion | null>(null);
const restoreVisible = ref(false);
const restoring = ref(false);
const restoreForm = ref<{
  versionId: string;
  mode: 'CONTENT' | 'CONTENT_AND_WATERMARK';
  note: string;
}>({ versionId: '', mode: 'CONTENT', note: '' });

/** 版本来源中文标签 */
const VERSION_SOURCE_LABELS: Record<string, string> = {
  PRE_MANUAL: '人工编辑前',
  MANUAL: '人工编辑',
  PRE_CONSOLIDATE: '合并前',
  CONSOLIDATE: '合并后',
  CONSOLIDATE_PARTIAL: '合并中断',
  PRE_RESTORE: '恢复前',
  RESTORE: '恢复'
};

const sourceLabel = (source: string) => VERSION_SOURCE_LABELS[source] || source;

/** 打开版本历史 */
const handleOpenVersions = async () => {
  if (!query.value.agentId || !query.value.userId) {
    modal.msgWarning('请先选择智能体与用户');
    return;
  }
  versionVisible.value = true;
  await loadVersions();
};

/** 加载版本列表 */
const loadVersions = async () => {
  if (!query.value.agentId || !query.value.userId) {
    versions.value = [];
    return;
  }
  versionLoading.value = true;
  try {
    const res = await listMemoryVersions(query.value.agentId, query.value.userId);
    versions.value = res.data || [];
  } finally {
    versionLoading.value = false;
  }
};

/** 查看版本正文 */
const handleViewVersion = async (row: MemoryVersion) => {
  const res = await getMemoryVersion(query.value.agentId!, query.value.userId!, row.versionId);
  versionDetail.value = res.data || null;
  versionDetailVisible.value = true;
};

/** 打开恢复确认 */
const handleRestoreVersion = (row: MemoryVersion) => {
  restoreForm.value = { versionId: row.versionId, mode: 'CONTENT', note: '' };
  restoreVisible.value = true;
};

/** 执行恢复 */
const confirmRestore = async () => {
  if (!query.value.agentId || !query.value.userId || !restoreForm.value.versionId) {
    return;
  }
  restoring.value = true;
  try {
    const payload: MemoryRestoreForm = {
      agentId: query.value.agentId,
      userId: query.value.userId,
      mode: restoreForm.value.mode,
      note: restoreForm.value.note
    };
    await restoreMemoryVersion(restoreForm.value.versionId, payload);
    modal.msgSuccess('已恢复；恢复前版本已自动保存');
    restoreVisible.value = false;
    await loadVersions();
    await loadFiles();
    if (current.value.path === 'MEMORY.md') {
      await handleSelectFile({ path: 'MEMORY.md' } as MemoryFile);
    }
  } finally {
    restoring.value = false;
  }
};

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
  await Promise.all([loadFiles(), loadDeclared(), loadQuality()]);
};

/** 加载运行健康指标（按天窗口） */
const loadQuality = async () => {
  if (!query.value.userId) {
    return;
  }
  qualityLoading.value = true;
  try {
    const res = await getMemoryQuality(query.value.userId, qualityDays.value);
    if (res.data) {
      quality.value = res.data;
    }
  } finally {
    qualityLoading.value = false;
  }
};

/** 格式化审计时间（ISO → MM-DD HH:mm） */
const formatQualityTime = (value?: string) => {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
};

/** 加载显式记忆（含已撤销条目，便于审计） */
const loadDeclared = async () => {
  if (!query.value.agentId || !query.value.userId) {
    declared.value = [];
    return;
  }
  declaredLoading.value = true;
  try {
    const res = await listDeclaredMemories(query.value.agentId, query.value.userId);
    declared.value = res.data || [];
  } finally {
    declaredLoading.value = false;
  }
};

/** 打开新增显式记忆弹窗 */
const handleOpenDeclared = () => {
  if (!requireScope()) return;
  declaredForm.value = { fact: '', evidence: '' };
  declaredVisible.value = true;
};

/** 保存人工补充的显式记忆 */
const confirmDeclared = async () => {
  if (!requireScope()) return;
  if (!declaredForm.value.fact.trim()) {
    modal.msgWarning('记忆内容不能为空');
    return;
  }
  declaredSaving.value = true;
  try {
    await addDeclaredMemory({
      agentId: query.value.agentId!,
      userId: query.value.userId!,
      fact: declaredForm.value.fact.trim(),
      evidence: declaredForm.value.evidence.trim()
    });
    modal.msgSuccess('显式记忆已保存，下一轮对话起生效');
    declaredVisible.value = false;
    await loadDeclared();
  } finally {
    declaredSaving.value = false;
  }
};

/** 撤销一条显式记忆 */
const handleRevokeDeclared = async (row: MemoryDeclaredEntry) => {
  if (!requireScope()) return;
  try {
    await modal.confirm(`确认撤销「${row.fact}」？撤销后该条不再带入对话。`);
  } catch {
    return;
  }
  await revokeDeclaredMemory(query.value.agentId!, query.value.userId!, row.id);
  modal.msgSuccess('已撤销');
  await loadDeclared();
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
    await Promise.all([loadFiles(), loadQuality()]);
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
    await Promise.all([loadFiles(), loadDeclared()]);
  } finally {
    removing.value = false;
  }
};

onMounted(async () => {
  await Promise.all([loadConfig(), loadOptions()]);
  await Promise.all([loadFiles(), loadDeclared(), loadQuality()]);
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

.quality-panel {
  margin-top: 12px;
}

.quality-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.quality-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.quality-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.quality-value {
  color: var(--el-text-color-primary);
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;

  em {
    margin-left: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-style: normal;
    font-weight: 400;
  }
}

.quality-danger {
  color: var(--el-color-danger);
}

.quality-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.declared-panel {
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

.version-current-tag {
  margin-left: 6px;
}

.version-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.restore-alert {
  margin-bottom: 16px;
}

.restore-tip {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.config-savebar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  border: 1px solid var(--app-surface-border);
  border-radius: var(--app-radius-base);
  background: var(--app-surface-bg);
  box-shadow: var(--app-shadow-md);
}

.config-savebar__tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.config-savebar__actions {
  display: flex;
  gap: 8px;
}
</style>