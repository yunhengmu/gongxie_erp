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
          <el-form-item label="名称" prop="nameKeyword">
            <el-input
              v-model="queryParams.nameKeyword"
              placeholder="请输入专家名称"
              clearable
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="编码" prop="agentCode">
            <el-input
              v-model="queryParams.agentCode"
              placeholder="请输入专家编码"
              clearable
              @keyup.enter="handleQuery"
            />
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
            <h3>专家配置</h3>
            <p>维护可被小Z 路由的专家：绑定模型、业务工具与知识库范围；停用后不再出现在候选与「指定专家」中。</p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:agent:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:agent:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:agent:remove']"
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

      <el-table v-loading="loading" border class="data-table" :data="agentList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="名称" align="center" prop="agentName" :show-overflow-tooltip="true" />
        <el-table-column label="编码" align="center" prop="agentCode" width="160" :show-overflow-tooltip="true" />
        <el-table-column label="绑定模型" align="center" width="160" :show-overflow-tooltip="true">
          <template #default="scope">{{ modelLabel(scope.row) }}</template>
        </el-table-column>
        <el-table-column label="归属部门" align="center" width="140" :show-overflow-tooltip="true">
          <template #default="scope">{{ deptLabel(scope.row.ownerDeptId) }}</template>
        </el-table-column>
        <el-table-column label="可见范围" align="center" width="110">
          <template #default="scope">{{ visibleScopeLabel(scope.row.visibleScope) }}</template>
        </el-table-column>
        <el-table-column label="自动路由" align="center" width="120">
          <template #default="scope">
            <el-tag :type="scope.row.autoRoute === '0' ? 'info' : 'success'" effect="plain" size="small">
              {{ autoRouteLabel(scope.row.autoRoute) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="90">
          <template #default="scope">
            <el-switch
              v-model="scope.row.status"
              active-value="0"
              inactive-value="1"
              :loading="statusLoadingId === scope.row.id"
              @change="handleStatusChange(scope.row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" align="center" prop="createTime" width="170" />
        <el-table-column label="操作" align="center" width="230" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="查看" placement="top">
              <el-button
                v-hasPermi="['ai:agent:query']"
                link
                type="primary"
                icon="View"
                @click="handleView(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:agent:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="复制" placement="top">
              <el-button
                v-hasPermi="['ai:agent:add']"
                link
                type="primary"
                icon="CopyDocument"
                @click="handleClone(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:agent:remove']"
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
      <el-form ref="agentFormRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="名称" prop="agentName">
          <el-input v-model="form.agentName" placeholder="如：财务专家" />
        </el-form-item>
        <el-form-item label="编码" prop="agentCode">
          <template #label>
            <span class="label-tip">
              编码
              <el-tooltip v-if="form.id" placement="top">
                <template #content>
                  <div class="tip-content">编码创建后不可修改（已作为专家编码与路由 key 使用）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.agentCode" :disabled="!!form.id" placeholder="如：finance_expert（小写字母开头，可含数字与下划线）" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话说明该专家的职责（也作为「AI 生成」与路由判断「擅长」的依据）" />
        </el-form-item>
        <el-form-item label="快速生成">
          <div class="gen-row">
            <el-select
              v-model="templateKey"
              placeholder="套用提示词模板"
              clearable
              style="width: 190px"
              @change="handleApplyTemplate"
            >
              <el-option v-for="tpl in EXPERT_PROMPT_TEMPLATES" :key="tpl.key" :label="tpl.label" :value="tpl.key" />
            </el-select>
            <el-button type="primary" plain icon="MagicStick" :loading="generating" @click="handleGenerate">
              AI 生成
            </el-button>
            <span class="text-muted">按名称 / 描述 / 已选工具与知识库，生成「描述 + 系统提示词」</span>
          </div>
        </el-form-item>
        <el-form-item label="系统提示词" prop="sysPrompt">
          <el-input
            v-model="form.sysPrompt"
            type="textarea"
            :rows="8"
            placeholder="留空将使用平台默认提示词；建议只写「领域知识与口径」——执行纪律由平台统一注入"
          />
        </el-form-item>
        <el-form-item label="绑定模型" prop="modelId">
          <el-select v-model="form.modelId" placeholder="请选择对话模型" style="width: 100%">
            <el-option v-for="item in modelOptions" :key="item.id" :label="item.configName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="自动路由" prop="autoRoute">
          <template #label>
            <span class="label-tip">
              自动路由
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    开启：进入小Z 的**自动候选清单**，用户提问时可被自动路由到；<br />
                    关闭：不进自动候选，**只能被用户在任务工作台手动指定**（仍可见、仍可正常执行）。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-radio-group v-model="form.autoRoute">
            <el-radio value="1">开启（自动候选）</el-radio>
            <el-radio value="0">关闭（仅手动指定）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="工具集" prop="toolBindings">
          <el-select v-model="selectedToolCodes" multiple clearable placeholder="请选择业务工具" style="width: 100%">
            <el-option v-for="item in toolOptions" :key="item.toolCode" :label="item.toolName" :value="item.toolCode">
              <span>{{ item.toolName }}</span>
              <span class="option-tip">{{ item.readOnly ? '只读' : '读写' }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.toolBindings?.length" label="工具授权">
          <template #label>
            <span class="label-tip">
              工具授权
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    「需人工确认」的工具在执行前会暂停，等待你在对话页确认后才产生业务副作用。平台内置的无害只读工具（如「获取当前时间」）
                    <b>无需在此绑定即对所有智能体生效</b>；若显式绑定并配为「需确认 / 禁止」，则以该决策为准。
                    <br />
                    注意：工具授权决策的改动需<b>新建会话</b>后完全生效——同一会话会沿用已有的权限判定。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <div class="binding-list">
            <div v-for="bind in form.toolBindings" :key="bind.toolCode" class="binding-row">
              <span class="binding-name">{{ toolLabel(bind.toolCode) }}</span>
              <el-select v-model="bind.decision" size="small" style="width: 150px">
                <el-option v-for="item in DECISION_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </div>
          </div>
        </el-form-item>
        <el-form-item v-if="form.id" label="已记住的工具">
          <template #label>
            <span class="label-tip">
              已记住的工具
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    「记住该工具」是<b>用户级</b>信任：仅对该条记录里的用户免人工确认，同一专家下<b>其他用户仍会弹确认</b>；
                    外部 MCP 等「平台级、不在此页绑定」的工具同样记录在这里，并覆盖上面的「需确认」档位。
                    <br />
                    撤销后该用户的该工具恢复按上面的授权决策判定（其<b>下一次提问</b>即生效）。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <div v-if="trustedTools.length" class="binding-list">
            <div v-for="trust in trustedTools" :key="`${trust.toolCode}-${trust.userId}`" class="binding-row">
              <span class="binding-name">{{ toolLabel(trust.toolCode) }}</span>
              <span class="text-muted">{{ trust.toolCode }} · {{ trust.userName || trust.userId }}</span>
              <el-button
                v-hasPermi="['ai:agent:edit']"
                link
                type="danger"
                size="small"
                @click="handleRevokeTrust(trust)"
              >
                撤销
              </el-button>
            </div>
          </div>
          <span v-else class="text-muted">暂无（用户在选择「记住该工具」后在此显示）</span>
        </el-form-item>
        <el-form-item label="知识库" prop="kbIds">
          <el-select v-model="form.kbIds" multiple clearable placeholder="请选择可检索的知识库" style="width: 100%">
            <el-option v-for="item in kbOptions" :key="item.id" :label="item.kbName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="技能" prop="skillIds">
          <template #label>
            <span class="label-tip">
              技能
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    技能清单会注入 system prompt，模型按需加载；仅放行被绑定的技能，实际生效还会按提问用户的技能生效范围过滤。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select v-model="form.skillIds" multiple clearable placeholder="请选择要装配的技能" style="width: 100%">
            <el-option v-for="item in skillOptions" :key="item.id" :label="item.name" :value="item.id">
              <span>{{ item.name }}</span>
              <span class="option-desc">{{ item.description }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="子智能体" prop="subagentIds">
          <template #label>
            <span class="label-tip">
              子智能体
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    子智能体由主智能体按需委派（框架内置 agent_spawn 工具）；<b>仅只读工具可下放</b>——子智能体侧没有人工确认闸门，且子智能体只能用到主智能体已绑定的只读工具。绑定改动需新建会话后生效。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select
            v-model="form.subagentIds"
            multiple
            clearable
            filterable
            collapse-tags
            placeholder="请选择要绑定的子智能体"
            style="width: 100%"
          >
            <el-option v-for="item in subagentList" :key="item.id" :label="item.name" :value="item.id">
              <span>{{ item.name }}</span>
              <span class="option-desc">{{ item.description }}</span>
            </el-option>
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
                  <div class="tip-content">填 0 表示不限；超限后该专家对提问方直接拒绝（按用户维度统计）。</div>
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
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="buttonLoading" type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="detail.visible" title="专家详情" width="860px" append-to-body>
      <template v-if="detail.record">
        <el-descriptions :column="2" border size="small" class="detail-summary">
          <el-descriptions-item label="名称">{{ detail.record.agentName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="编码">{{ detail.record.agentCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.record.status === '0' ? 'success' : 'info'">
              {{ detail.record.status === '0' ? '正常' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="归属部门">{{ deptLabel(detail.record.ownerDeptId) }}</el-descriptions-item>
          <el-descriptions-item label="可见范围">{{ visibleScopeLabel(detail.record.visibleScope) }}</el-descriptions-item>
          <el-descriptions-item label="自动路由">{{ autoRouteLabel(detail.record.autoRoute) }}</el-descriptions-item>
          <el-descriptions-item label="最大迭代次数">{{ detail.record.maxIters ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="描述" :span="2">{{ detail.record.description || '-' }}</el-descriptions-item>
          <el-descriptions-item label="绑定模型">{{ modelLabel(detail.record) }}</el-descriptions-item>
          <el-descriptions-item label="配额">
            日 Token：{{ quotaLabel(detail.record.dailyTokenQuota) }}；日请求数：{{ quotaLabel(detail.record.dailyRequestQuota) }}；月 Token：{{ quotaLabel(detail.record.monthlyTokenQuota) }}
          </el-descriptions-item>
          <el-descriptions-item label="角色模型" :span="2">
            压缩摘要：{{ modelLabelById(detail.record.summaryModelId) }}；记忆抽取：{{ modelLabelById(detail.record.memoryModelId) }}；子智能体：{{ modelLabelById(detail.record.subagentModelId) }}
            <span class="text-muted">（留空或所选模型不可用时回退主模型）</span>
          </el-descriptions-item>
          <el-descriptions-item label="工具集" :span="2">
            <template v-if="detail.record.toolBindings?.length">
              <el-tag
                v-for="bind in detail.record.toolBindings"
                :key="bind.toolCode"
                class="cell-tag"
                :type="decisionTagType(bind.decision)"
                effect="plain"
                size="small"
              >
                {{ toolLabel(bind.toolCode) }} · {{ decisionLabel(bind.decision) }}
              </el-tag>
            </template>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="知识库" :span="2">
            <template v-if="detail.record.kbIds?.length">
              <el-tag v-for="kbId in detail.record.kbIds" :key="String(kbId)" class="cell-tag" type="warning" effect="plain" size="small">
                {{ kbLabel(kbId) }}
              </el-tag>
            </template>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="技能" :span="2">
            <template v-if="detail.record.skillIds?.length">
              <el-tag v-for="skillId in detail.record.skillIds" :key="String(skillId)" class="cell-tag" type="success" effect="plain" size="small">
                {{ skillLabel(skillId) }}
              </el-tag>
            </template>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="子智能体" :span="2">
            <template v-if="detail.record.subagentIds?.length">
              <el-tag v-for="subId in detail.record.subagentIds" :key="String(subId)" class="cell-tag" effect="plain" size="small">
                {{ subagentLabel(subId) }}
              </el-tag>
            </template>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.record.remark || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.record.createTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detail.record.updateTime || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-block">
          <div class="detail-label">系统提示词</div>
          <pre v-if="detail.record.sysPrompt" class="detail-plain">{{ detail.record.sysPrompt }}</pre>
          <el-empty v-else description="留空，使用平台默认提示词" :image-size="60" />
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

<script setup name="AiAgentExpert" lang="ts">
import {
  addAgent,
  delAgent,
  generateExpertPrompt,
  getAgent,
  listAgent,
  listTrustedTools,
  optionAgentTool,
  revokeTrustedTool,
  updateAgent
} from '@/api/ai/agent';
import type {
  AgentForm,
  AgentQuery,
  AgentToolTrustVO,
  AgentVO,
  ExpertPromptGenerateForm,
  ToolOptionVO
} from '@/api/ai/agent/types';
import { visibleKnowledgeBaseOptions } from '@/api/ai/kb/base';
import type { KnowledgeBaseVO } from '@/api/ai/kb/base/types';
import { optionModelConfig } from '@/api/ai/model/config';
import type { ModelConfigVO } from '@/api/ai/model/config/types';
import { visibleSkillOptions } from '@/api/ai/skill';
import type { SkillOptionVO } from '@/api/ai/skill/types';
import { subagentOptions } from '@/api/ai/subagent';
import type { SubagentOptionVO } from '@/api/ai/subagent/types';
import type { DeptTreeVO } from '@/api/system/dept/types';
import { deptTreeSelect } from '@/api/system/user';
import { useLoading } from '@/hooks/async/useLoading';
import { useFormDialog } from '@/hooks/dialog/useFormDialog';
import { useSearchReset } from '@/hooks/form/useSearchReset';
import { useSearchToggle } from '@/hooks/form/useSearchToggle';
import { useTableSelection } from '@/hooks/table/useTableSelection';
import modal from '@/plugins/modal';
import { EXPERT_PROMPT_TEMPLATES, lintExpertPrompt, renderTemplate } from './expertPromptHelper';

const VISIBLE_SCOPE_OPTIONS = [
  { label: '全员', value: '0' },
  { label: '本部门', value: '1' },
  { label: '仅创建人', value: '2' }
];

/** 工具授权决策选项 */
const DECISION_OPTIONS = [
  { label: '允许', value: '0' },
  { label: '需确认', value: '1' },
  { label: '禁止', value: '2' }
];

const visibleScopeOptions = VISIBLE_SCOPE_OPTIONS;

const agentList = ref<AgentVO[]>([]);
const modelOptions = ref<ModelConfigVO[]>([]);
const toolOptions = ref<ToolOptionVO[]>([]);
const kbOptions = ref<KnowledgeBaseVO[]>([]);
const skillOptions = ref<SkillOptionVO[]>([]);
const subagentList = ref<SubagentOptionVO[]>([]);
const deptOptions = ref<DeptTreeVO[]>([]);
/** 该专家已记住的工具（「记住该工具」沉淀的免确认决策） */
const trustedTools = ref<AgentToolTrustVO[]>([]);
const buttonLoading = ref(false);
/** 「AI 生成」进行中 */
const generating = ref(false);
/** 当前选中的提示词模板（套用后即重置） */
const templateKey = ref<string | undefined>(undefined);
const statusLoadingId = ref<string | number | undefined>(undefined);
const { loading, withLoading } = useLoading(true);
const { showSearch } = useSearchToggle();
const total = ref(0);

const queryFormRef = ref<ElFormInstance>();
const agentFormRef = ref<ElFormInstance>();

/** 专家表单：在后端既有字段基础上补充「子智能体绑定」集合 */
type AgentFormWithSubagent = AgentForm & { subagentIds?: (string | number)[] };

/** 带子智能体绑定回显的行数据（后端详情/列表返回 subagentIds） */
type AgentRowWithSubagent = Partial<AgentVO> & { subagentIds?: (string | number)[] };

const initFormData: AgentFormWithSubagent = {
  id: undefined,
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
  remark: undefined,
  /** 默认开启自动路由（REQ-AI-33） */
  autoRoute: '1',
  /** 专家写工具开关已从表单移除（REQ-AI-30 简化）：恒为「允许」，不再由用户配置 */
  expertWriteEnabled: '1',
  toolBindings: [],
  kbIds: [],
  skillIds: [],
  subagentIds: []
};

const data = reactive<PageData<AgentFormWithSubagent, AgentQuery>>({
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    nameKeyword: undefined,
    agentCode: undefined,
    status: undefined
  },
  rules: {}
});

const { queryParams, form } = toRefs(data);

/** 工具选择的读写代理：界面按编码多选，表单按「编码 + 授权决策」保存 */
const selectedToolCodes = computed<string[]>({
  get: () => (form.value.toolBindings ?? []).map((bind) => bind.toolCode),
  set: (codes: string[]) => {
    const exists = new Map((form.value.toolBindings ?? []).map((bind) => [bind.toolCode, bind]));
    form.value.toolBindings = codes.map((code) => exists.get(code) ?? { toolCode: code, decision: '0' });
  }
});

/** 表单校验规则 */
const rules = computed<ElFormRules>(() => ({
  agentName: [{ required: true, message: '专家名称不能为空', trigger: 'blur' }],
  agentCode: [
    { required: true, message: '专家编码不能为空', trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9_]{1,60}$/,
      message: '小写字母开头，仅含小写字母、数字与下划线，长度 2~61 位',
      trigger: 'blur'
    }
  ],
  modelId: [{ required: true, message: '请选择绑定模型', trigger: 'change' }],
  maxIters: [{ type: 'number', min: 1, max: 50, message: '最大迭代次数需在 1~50 之间', trigger: 'blur' }]
}));

/** 部门 ID → 名称 */
const deptMap = computed(() => {
  const map = new Map<string | number, string>();
  const walk = (list: DeptTreeVO[]) => {
    list.forEach((item) => {
      map.set(item.id, item.label);
      if (item.children?.length) {
        walk(item.children);
      }
    });
  };
  walk(deptOptions.value);
  return map;
});

/** 工具编码 → 名称 */
const toolNameMap = computed(() => new Map(toolOptions.value.map((item) => [item.toolCode, item.toolName])));

/** 知识库 ID → 名称 */
const kbNameMap = computed(() => new Map(kbOptions.value.map((item) => [String(item.id), item.kbName])));

/** 技能 ID → 标识 */
const skillNameMap = computed(() => new Map(skillOptions.value.map((item) => [String(item.id), item.name])));

const { ids, single, multiple, handleSelectionChange } = useTableSelection<AgentVO>((item) => item.id);

/** 详情弹窗（查看专家完整信息，只读） */
const detail = reactive<{ visible: boolean; record: AgentRowWithSubagent | null }>({
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
  formRef: agentFormRef,
  initialFormData: initFormData
});

/** 列表展示：绑定模型名称 */
const modelLabel = (row: Partial<AgentVO>) => {
  if (row.modelName) {
    return row.modelName;
  }
  const matched = modelOptions.value.find((item) => String(item.id) === String(row.modelId));
  return matched?.configName ?? '-';
};

/** 模型 ID → 名称（用于仅有 ID 的角色模型槽位；找不到时回显原值以便定位失效配置） */
const modelLabelById = (id?: string | number) => {
  if (id == null || id === '') {
    return '-';
  }
  return modelOptions.value.find((item) => String(item.id) === String(id))?.configName ?? String(id);
};

/** 配额展示：0 表示不限 */
const quotaLabel = (value?: number) => (value ? String(value) : '不限');

/** 列表展示：工具名称 */
const toolLabel = (code: string) => toolNameMap.value.get(code) ?? code;

/** 工具授权决策：标签颜色 */
const decisionTagType = (decision?: string): ElTagType => {
  if (decision === '1') {
    return 'warning';
  }
  if (decision === '2') {
    return 'danger';
  }
  return 'success';
};

/** 工具授权决策：文案 */
const decisionLabel = (decision?: string) => DECISION_OPTIONS.find((item) => item.value === decision)?.label ?? '允许';

/** 列表展示：知识库名称 */
const kbLabel = (id: string | number) => kbNameMap.value.get(String(id)) ?? String(id);

/** 技能标签 */
const skillLabel = (id: string | number) => skillNameMap.value.get(String(id)) ?? String(id);

/** 子智能体 ID → 名称 */
const subagentNameMap = computed(() => new Map(subagentList.value.map((item) => [String(item.id), item.name])));

/** 子智能体标签 */
const subagentLabel = (id: string | number) => subagentNameMap.value.get(String(id)) ?? String(id);

/** 列表展示：归属部门名称 */
const deptLabel = (id?: string | number) => (id == null ? '-' : deptMap.value.get(id) ?? '-');

/** 列表展示：可见范围名称 */
const visibleScopeLabel = (value?: string) =>
  visibleScopeOptions.find((item) => item.value === value)?.label ?? value ?? '-';

/** 自动路由文案（REQ-AI-33）：关闭＝只允许用户手动指定 */
const autoRouteLabel = (value?: string) => (value === '0' ? '仅手动指定' : '自动候选');

/** 行数据 → 完整表单（后端 PUT 为全量覆盖，提交须带全字段） */
const toForm = (row: AgentRowWithSubagent): AgentFormWithSubagent => ({
  id: row.id,
  agentName: row.agentName,
  agentCode: row.agentCode,
  description: row.description,
  sysPrompt: row.sysPrompt,
  modelId: row.modelId,
  summaryModelId: row.summaryModelId,
  memoryModelId: row.memoryModelId,
  subagentModelId: row.subagentModelId,
  dailyTokenQuota: row.dailyTokenQuota ?? 0,
  dailyRequestQuota: row.dailyRequestQuota ?? 0,
  monthlyTokenQuota: row.monthlyTokenQuota ?? 0,
  ownerDeptId: row.ownerDeptId,
  visibleScope: row.visibleScope,
  maxIters: row.maxIters,
  status: row.status,
  remark: row.remark,
  autoRoute: row.autoRoute ?? '1',
  /** 已从表单移除（简化）：恒为「允许」 */
  expertWriteEnabled: '1',
  toolBindings: row.toolBindings ? row.toolBindings.map((bind) => ({ ...bind })) : [],
  kbIds: row.kbIds ? [...row.kbIds] : [],
  skillIds: row.skillIds ? [...row.skillIds] : [],
  subagentIds: row.subagentIds ? [...row.subagentIds] : []
});

/** 查询专家列表 */
const getList = async () => {
  await withLoading(async () => {
    const res = await listAgent(queryParams.value);
    agentList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  });
};

/**
 * 加载下拉选项（模型 / 工具 / 知识库 / 技能 / 子智能体 / 部门树）
 *
 * <p>逐项降级：用 allSettled 而非 all，任一选项失败（如权限不足）只置空该项并汇总提示，
 * 不影响其余选项与列表本身。</p>
 */
const loadOptions = async () => {
  const [modelRes, toolRes, kbRes, skillRes, subagentRes, deptRes] = await Promise.allSettled([
    optionModelConfig('chat'),
    optionAgentTool(),
    visibleKnowledgeBaseOptions(),
    visibleSkillOptions(),
    subagentOptions(),
    deptTreeSelect()
  ]);
  const failed: string[] = [];
  const resolveOption = <T,>(res: PromiseSettledResult<{ data?: T }>, label: string): T[] => {
    if (res.status === 'fulfilled') {
      return res.value.data ?? [];
    }
    failed.push(label);
    return [];
  };
  modelOptions.value = resolveOption<ModelConfigVO>(modelRes, '模型');
  toolOptions.value = resolveOption<ToolOptionVO>(toolRes, '工具');
  kbOptions.value = resolveOption<KnowledgeBaseVO>(kbRes, '知识库');
  skillOptions.value = resolveOption<SkillOptionVO>(skillRes, '技能');
  subagentList.value = resolveOption<SubagentOptionVO>(subagentRes, '子智能体');
  deptOptions.value = resolveOption<DeptTreeVO>(deptRes, '部门树');
  if (failed.length) {
    ElMessage.warning(`${failed.join(' / ')} 选项加载失败，对应下拉暂不可用`);
  }
};

/** 取消按钮 */
const cancel = () => {
  reset();
  trustedTools.value = [];
  templateKey.value = undefined;
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
  trustedTools.value = [];
  templateKey.value = undefined;
  openDialog('新增专家');
};

/** 查看按钮操作（只读详情，取详情接口以保证全字段回显） */
const handleView = async (row: AgentRowWithSubagent) => {
  const res = await getAgent(row.id as string | number);
  detail.record = (res.data ?? null) as AgentRowWithSubagent | null;
  detail.visible = true;
};

/** 修改按钮操作 */
const handleUpdate = async (row?: AgentRowWithSubagent) => {
  reset();
  templateKey.value = undefined;
  const id = row?.id ?? ids.value[0];
  const res = await getAgent(id);
  Object.assign(form.value, toForm(res.data ?? {}));
  form.value.toolBindings = form.value.toolBindings ?? [];
  form.value.kbIds = form.value.kbIds ?? [];
  form.value.skillIds = form.value.skillIds ?? [];
  form.value.subagentIds = form.value.subagentIds ?? [];
  await loadTrustedTools(id);
  showDialog('修改专家');
};

/**
 * 复制按钮操作：按既有专家预填新增表单。
 *
 * <p>清空主键与编码（编码唯一）、名称加「-副本」，其余（模型 / 工具 / 知识库 / 技能 / 提示词 / 配额）
 * 原样带入，保存走新增接口。</p>
 */
const handleClone = async (row: AgentRowWithSubagent) => {
  trustedTools.value = [];
  templateKey.value = undefined;
  const res = await getAgent(row.id as string | number);
  const cloned = toForm(res.data ?? {});
  cloned.id = undefined;
  cloned.agentCode = undefined;
  cloned.agentName = row.agentName ? `${row.agentName}-副本` : row.agentName;
  reset();
  Object.assign(form.value, cloned);
  form.value.toolBindings = form.value.toolBindings ?? [];
  form.value.kbIds = form.value.kbIds ?? [];
  form.value.skillIds = form.value.skillIds ?? [];
  form.value.subagentIds = form.value.subagentIds ?? [];
  showDialog('复制专家');
};

/** 套用内置提示词模板（覆盖前二次确认） */
const handleApplyTemplate = async (key?: string) => {
  templateKey.value = undefined;
  if (!key) {
    return;
  }
  const template = EXPERT_PROMPT_TEMPLATES.find((tpl) => tpl.key === key);
  if (!template) {
    return;
  }
  if ((form.value.sysPrompt ?? '').trim()) {
    const confirmed = await modal
      .confirm('套用模板将覆盖当前系统提示词，是否继续？')
      .then(() => true)
      .catch(() => false);
    if (!confirmed) {
      return;
    }
  }
  form.value.sysPrompt = renderTemplate(template, form.value.agentName);
  modal.msgSuccess(`已套用模板：${template.label}`);
};

/**
 * 「AI 生成」：按名称 / 描述 / 已选工具与知识库，补全「描述 + 能力描述 + 系统提示词」。
 *
 * <p>生成结果为**建议值**，回填后仍可编辑；失败时如实提示原因（如模型未配置密钥）。</p>
 */
const handleGenerate = async () => {
  if (!form.value.agentName?.trim()) {
    modal.msgWarning('请先填写专家名称');
    return;
  }
  if (!form.value.description?.trim()) {
    modal.msgWarning('请先填写「描述」，它同时作为生成的领域依据');
    return;
  }
  if ((form.value.sysPrompt ?? '').trim()) {
    const confirmed = await modal
      .confirm('生成将覆盖当前的「描述 / 能力描述 / 系统提示词」，是否继续？')
      .then(() => true)
      .catch(() => false);
    if (!confirmed) {
      return;
    }
  }
  const toolNames: string[] = [];
  const writeToolNames: string[] = [];
  (form.value.toolBindings ?? []).forEach((bind) => {
    const name = toolLabel(bind.toolCode);
    toolNames.push(name);
    if (toolOptions.value.find((item) => item.toolCode === bind.toolCode)?.readOnly === false) {
      writeToolNames.push(name);
    }
  });
  const payload: ExpertPromptGenerateForm = {
    agentName: form.value.agentName,
    domainHint: form.value.description,
    toolNames,
    writeToolNames,
    kbNames: (form.value.kbIds ?? []).map((id) => kbLabel(id)),
    writeEnabled: form.value.expertWriteEnabled !== '0',
    modelId: form.value.modelId
  };
  generating.value = true;
  try {
    const res = await generateExpertPrompt(payload);
    const data = res.data;
    if (!data?.success) {
      modal.msgError(data?.message || '生成失败，请稍后重试');
      return;
    }
    if (data.description) {
      form.value.description = data.description;
    }
    if (data.sysPrompt) {
      form.value.sysPrompt = data.sysPrompt;
    }
    modal.msgSuccess(`生成成功（${data.modelName || '模型'}，${data.elapsedMs ?? 0}ms），请核对后按需修改`);
  } finally {
    generating.value = false;
  }
};

/**
 * 加载「已记住的工具」（信任决策）。
 *
 * <p>失败时置空且不阻断弹窗：该列表只是展示与撤销入口，不应影响专家配置本身的编辑。</p>
 */
const loadTrustedTools = async (agentId: string | number) => {
  try {
    trustedTools.value = (await listTrustedTools(agentId)).data ?? [];
  } catch {
    trustedTools.value = [];
  }
};

/** 撤销某条「记住该工具」信任决策（按用户维度，只影响该用户） */
const handleRevokeTrust = async (trust: AgentToolTrustVO) => {
  const agentId = form.value.id as string | number;
  const who = trust.userName || trust.userId;
  await modal.confirm(
    `是否确认撤销用户「${who}」对工具「${toolLabel(trust.toolCode)}」的记住？撤销后该用户的该工具恢复按授权决策判定。`
  );
  await revokeTrustedTool(agentId, trust.userId as string | number, trust.toolCode);
  modal.msgSuccess('已撤销');
  await loadTrustedTools(agentId);
  await getList();
};

/** 状态开关：直接按当前行组装完整表单提交 */
const handleStatusChange = async (row: AgentRowWithSubagent) => {
  statusLoadingId.value = row.id;
  try {
    await updateAgent(toForm(row));
    modal.msgSuccess(row.status === '0' ? '已启用' : '已停用');
  } catch {
    row.status = row.status === '0' ? '1' : '0';
  } finally {
    statusLoadingId.value = undefined;
  }
};

/**
 * 保存前提示词体检：只给建议、不硬拦（用户可选择「仍然保存」）。
 *
 * @returns 是否继续保存
 */
const confirmPromptLint = async (): Promise<boolean> => {
  const items = lintExpertPrompt({
    sysPrompt: form.value.sysPrompt,
    agentName: form.value.agentName,
    description: form.value.description,
    tools: (form.value.toolBindings ?? []).map((bind) => ({
      name: toolLabel(bind.toolCode),
      readOnly: toolOptions.value.find((item) => item.toolCode === bind.toolCode)?.readOnly !== false
    })),
    hasKnowledgeBase: (form.value.kbIds ?? []).length > 0
  });
  if (!items.length) {
    return true;
  }
  const escapeHtml = (text: string) =>
    text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const html = items
    .map(
      (item) =>
        `${item.level === 'warn' ? '⚠' : '·'} ${escapeHtml(item.message)}` +
        (item.suggestion ? `<br/><span style="color:#909399">建议：${escapeHtml(item.suggestion)}</span>` : '')
    )
    .join('<br/><br/>');
  try {
    await ElMessageBox.confirm(html, '提示词体检（仅建议，不影响保存）', {
      confirmButtonText: '仍然保存',
      cancelButtonText: '返回修改',
      type: 'warning',
      dangerouslyUseHTMLString: true
    });
    return true;
  } catch {
    return false;
  }
};

/** 提交按钮 */
const submitForm = () => {
  agentFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    if (!(await confirmPromptLint())) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: AgentFormWithSubagent = { ...form.value };
      if (payload.id) {
        await updateAgent(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addAgent(payload);
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
const handleDelete = async (row?: Partial<AgentVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm(
    row
      ? `是否确认删除专家「${row.agentName || row.agentCode || row.id}」？`
      : `是否确认删除选中的 ${ids.value.length} 个专家？`
  );
  await delAgent(target);
  modal.msgSuccess('删除成功');
  await getList();
};

getList();
loadOptions();
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

.cell-tag {
  margin: 2px 4px 2px 0;
}

.option-tip {
  float: right;
  color: var(--app-text-muted);
  font-size: 12px;
}

/* 快速生成行：模板下拉 + AI 生成 + 说明 */
.gen-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

/* 工具授权 / 角色模型 / 配额：与 el-form-item 内容区对齐的窄列表 */
.binding-list,
.role-model-list,
.quota-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.binding-row,
.role-model-row,
.quota-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 工具名（工具授权 / 已记住的工具）：宽度至少容纳 6 个汉字 */
.binding-name {
  flex: 0 0 96px;
  color: var(--app-text-title);
  font-size: 13px;
}

.role-model-label,
.quota-label {
  flex: 0 0 72px;
  color: var(--app-text-title);
  font-size: 13px;
}

/* 详情弹窗 */
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
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

</style>
