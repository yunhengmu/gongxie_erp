<template>
  <div class="p-2 app-container">
    <el-card v-loading="loading" shadow="hover" class="form-panel">
      <template #header>
        <div class="table-heading">
          <h3>小Z 配置</h3>
          <p>
            小Z 是平台唯一的入口智能体：负责理解用户提问、决定自答还是路由给专家，并整合各专家结论。
            此处只维护其基础与模型配置；其可用工具、知识库、技能与 MCP 均来自**会话资源集**（在任务工作台选择）。
          </p>
        </div>
      </template>

      <el-empty v-if="!loaded" description="未配置入口智能体（请联系管理员初始化小Z）" :image-size="80" />

      <el-form v-else ref="entryFormRef" :model="form" :rules="rules" label-width="130px" class="entry-form">
        <el-form-item label="名称" prop="agentName">
          <el-input v-model="form.agentName" placeholder="如：小Z" />
        </el-form-item>
        <el-form-item label="编码" prop="agentCode">
          <template #label>
            <span class="label-tip">
              编码
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">入口编码创建后不可修改（已作为 AgentScope 的 agent name 使用）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.agentCode" disabled placeholder="entry_agent" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话说明小Z 的职责" />
        </el-form-item>
        <el-form-item label="系统提示词" prop="sysPrompt">
          <template #label>
            <span class="label-tip">
              系统提示词
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    小Z 的路由纪律与整合纪律（只归纳、不改写专家事实）已内置；此处可补充业务口径。
                    修改后下一轮对话即生效。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.sysPrompt" type="textarea" :rows="6" placeholder="留空将使用平台默认提示词" />
        </el-form-item>
        <el-form-item label="绑定模型" prop="modelId">
          <el-select v-model="form.modelId" placeholder="请选择对话模型" style="width: 100%">
            <el-option v-for="item in modelOptions" :key="item.id" :label="item.configName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色模型">
          <template #label>
            <span class="label-tip">
              角色模型
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    三个槽位均已接入装配：压缩摘要用于上下文压缩、记忆抽取用于长期记忆的抽取与合并、子智能体用于子智能体未单独指定模型时的默认值；留空或所选模型不可用时统一回退主模型。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <div class="role-model-list">
            <div class="role-model-row">
              <span class="role-model-label">压缩摘要</span>
              <el-select v-model="form.summaryModelId" clearable placeholder="默认不指定" style="width: 100%">
                <el-option v-for="item in modelOptions" :key="item.id" :label="item.configName" :value="item.id" />
              </el-select>
            </div>
            <div class="role-model-row">
              <span class="role-model-label">记忆抽取</span>
              <el-select v-model="form.memoryModelId" clearable placeholder="默认不指定" style="width: 100%">
                <el-option v-for="item in modelOptions" :key="item.id" :label="item.configName" :value="item.id" />
              </el-select>
            </div>
            <div class="role-model-row">
              <span class="role-model-label">子智能体</span>
              <el-select v-model="form.subagentModelId" clearable placeholder="默认不指定" style="width: 100%">
                <el-option v-for="item in modelOptions" :key="item.id" :label="item.configName" :value="item.id" />
              </el-select>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="配额">
          <template #label>
            <span class="label-tip">
              配额
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">填 0 表示不限；超限后小Z 对提问方直接拒绝（按用户维度统计）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <div class="quota-list">
            <div class="quota-row">
              <span class="quota-label">日 Token</span>
              <el-input-number v-model="form.dailyTokenQuota" :min="0" :step="1000" controls-position="right" />
            </div>
            <div class="quota-row">
              <span class="quota-label">日请求数</span>
              <el-input-number v-model="form.dailyRequestQuota" :min="0" :step="10" controls-position="right" />
            </div>
            <div class="quota-row">
              <span class="quota-label">月 Token</span>
              <el-input-number v-model="form.monthlyTokenQuota" :min="0" :step="10000" controls-position="right" />
            </div>
          </div>
        </el-form-item>
        <el-form-item label="归属部门" prop="ownerDeptId">
          <el-tree-select
            v-model="form.ownerDeptId"
            :data="deptOptions"
            :props="{ value: 'id', label: 'label', children: 'children' } as any"
            value-key="id"
            placeholder="请选择归属部门"
            check-strictly
            clearable
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="可见范围" prop="visibleScope">
          <el-radio-group v-model="form.visibleScope">
            <el-radio v-for="item in visibleScopeOptions" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="最大迭代次数" prop="maxIters">
          <template #label>
            <span class="label-tip">
              最大迭代次数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">单轮对话中模型可自行决定的最大轮次。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.maxIters" :min="1" :max="50" controls-position="right" />
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
        <el-form-item>
          <el-button v-hasPermi="['ai:agent:entry:edit']" :loading="buttonLoading" type="primary" @click="submitForm">
            保 存
          </el-button>
          <el-button @click="loadEntry">重 置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup name="AiAgentEntry" lang="ts">
import { getEntryAgent, updateEntryAgent } from '@/api/ai/agent';
import type { EntryAgentForm } from '@/api/ai/agent/types';
import { optionModelConfig } from '@/api/ai/model/config';
import type { ModelConfigVO } from '@/api/ai/model/config/types';
import type { DeptTreeVO } from '@/api/system/dept/types';
import { deptTreeSelect } from '@/api/system/user';
import { useLoading } from '@/hooks/async/useLoading';
import modal from '@/plugins/modal';

const VISIBLE_SCOPE_OPTIONS = [
  { label: '全员', value: '0' },
  { label: '本部门', value: '1' },
  { label: '仅创建人', value: '2' }
];

const visibleScopeOptions = VISIBLE_SCOPE_OPTIONS;

const modelOptions = ref<ModelConfigVO[]>([]);
const deptOptions = ref<DeptTreeVO[]>([]);
const buttonLoading = ref(false);
const loaded = ref(false);
const { loading, withLoading } = useLoading(true);

const entryFormRef = ref<ElFormInstance>();

const initFormData: EntryAgentForm = {
  agentName: undefined,
  agentCode: undefined,
  description: undefined,
  sysPrompt: undefined,
  modelId: undefined,
  summaryModelId: undefined,
  memoryModelId: undefined,
  subagentModelId: undefined,
  dailyTokenQuota: 0,
  dailyRequestQuota: 0,
  monthlyTokenQuota: 0,
  ownerDeptId: undefined,
  visibleScope: '0',
  maxIters: 10,
  status: '0',
  remark: undefined
};

const form = ref<EntryAgentForm>({ ...initFormData });

/** 表单校验规则 */
const rules = computed<ElFormRules>(() => ({
  agentName: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
  modelId: [{ required: true, message: '请选择绑定模型', trigger: 'change' }],
  maxIters: [{ type: 'number', min: 1, max: 50, message: '最大迭代次数需在 1~50 之间', trigger: 'blur' }]
}));

/**
 * 加载下拉选项（模型 / 部门树）。
 *
 * <p>逐项降级：任一选项失败（如权限不足）只置空该项并提示，不影响表单本身。</p>
 */
const loadOptions = async () => {
  const [modelRes, deptRes] = await Promise.allSettled([optionModelConfig('chat'), deptTreeSelect()]);
  const failed: string[] = [];
  if (modelRes.status === 'fulfilled') {
    modelOptions.value = modelRes.value.data ?? [];
  } else {
    failed.push('模型');
  }
  if (deptRes.status === 'fulfilled') {
    deptOptions.value = deptRes.value.data ?? [];
  } else {
    failed.push('部门树');
  }
  if (failed.length) {
    ElMessage.warning(`${failed.join(' / ')} 选项加载失败，对应下拉暂不可用`);
  }
};

/** 加载入口智能体配置 */
const loadEntry = async () => {
  await withLoading(async () => {
    try {
      const res = await getEntryAgent();
      const data = res.data as (EntryAgentForm & { id?: string | number }) | null;
      if (!data) {
        loaded.value = false;
        return;
      }
      form.value = {
        ...initFormData,
        ...data,
        dailyTokenQuota: data.dailyTokenQuota ?? 0,
        dailyRequestQuota: data.dailyRequestQuota ?? 0,
        monthlyTokenQuota: data.monthlyTokenQuota ?? 0
      };
      loaded.value = true;
    } catch {
      loaded.value = false;
    }
  });
};

/** 提交按钮 */
const submitForm = () => {
  entryFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      await updateEntryAgent({ ...form.value });
      modal.msgSuccess('保存成功');
      await loadEntry();
    } finally {
      buttonLoading.value = false;
    }
  });
};

loadOptions();
loadEntry();
</script>

<style scoped lang="scss">
.table-heading h3 {
  margin: 0;
}

.table-heading p {
  margin: 6px 0 0;
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.entry-form {
  max-width: 860px;
}

/* 角色模型 / 配额：与 el-form-item 内容区对齐的窄列表 */
.role-model-list,
.quota-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.role-model-row,
.quota-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.role-model-label,
.quota-label {
  flex: 0 0 72px;
  color: var(--app-text-title);
  font-size: 13px;
}
</style>
