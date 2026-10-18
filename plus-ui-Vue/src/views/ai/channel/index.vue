<template>
  <div class="p-2 app-container">
    <el-tabs v-model="activeTab" class="channel-tabs">
      <!-- ========== 渠道配置 ========== -->
      <el-tab-pane label="渠道配置" name="channel">
        <div class="search-wrap">
          <el-card shadow="hover" class="search-panel" :class="{ 'is-collapsed': !showSearch }">
            <template #header>
              <div class="panel-heading search-panel-toggle" @click.stop="showSearch = !showSearch">
                <div><h3>筛选条件</h3></div>
              </div>
            </template>
            <el-form ref="queryFormRef" :model="queryParams" :inline="true" class="query-form">
              <el-form-item label="渠道" prop="keyword">
                <el-input
                  v-model="queryParams.keyword"
                  placeholder="渠道名称 / 编码"
                  clearable
                  style="width: 220px"
                  @keyup.enter="handleQuery"
                />
              </el-form-item>
              <el-form-item label="平台" prop="platformType">
                <el-select v-model="queryParams.platformType" placeholder="全部" clearable style="width: 160px">
                  <el-option label="飞书" value="feishu" />
                  <el-option label="企业微信" value="wecom" />
                  <el-option label="GitHub" value="github" />
                  <el-option label="钉钉" value="dingtalk" />
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
                <h3>渠道接入</h3>
                <p>
                  接入飞书 / 企业微信 / GitHub / 钉钉：企业微信 / GitHub 为 Webhook（平台回调），
                  飞书可选 Webhook 或长连接，钉钉为长连接（应用主动建连、无需公网 IP 与回调地址）。
                  外部身份需在「外部身份映射」中绑定平台账号，消息线程按「会话映射」续接同一条会话。
                  执行走无人值守通道（写工具一律拒绝），产出落在会话管理页并标记来源为「渠道接入」。
                </p>
              </div>
              <div class="toolbar-actions">
                <el-button v-hasPermi="['ai:channel:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
                <el-button
                  v-hasPermi="['ai:channel:edit']"
                  type="success"
                  plain
                  icon="Edit"
                  :disabled="single"
                  @click="handleUpdate()"
                >
                  修改
                </el-button>
                <el-button
                  v-hasPermi="['ai:channel:remove']"
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

          <el-table v-loading="loading" border class="data-table" :data="channelList" @selection-change="handleSelectionChange">
            <el-table-column type="selection" width="55" align="center" />
            <el-table-column label="渠道名称" align="left" prop="channelName" :show-overflow-tooltip="true" />
            <el-table-column label="渠道编码" align="center" prop="channelCode" width="150" :show-overflow-tooltip="true" />
            <el-table-column label="平台" align="center" width="110">
              <template #default="scope">
                <el-tag size="small" effect="plain">{{ platformLabel(scope.row.platformType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="默认智能体" align="center" width="180" :show-overflow-tooltip="true">
              <template #default="scope">
                <span>{{ scope.row.agentName || '-' }}</span>
                <el-tag v-if="scope.row.agentStatus === '1'" type="danger" size="small" effect="plain" class="inline-tag">
                  已停用
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="接入地址 / 方式" align="left" :show-overflow-tooltip="true">
              <template #default="scope">
                <span v-if="isLongConnection(scope.row)" class="text-muted">长连接订阅（无需回调地址）</span>
                <code v-else class="callback-path">{{ callbackPath(scope.row) }}</code>
              </template>
            </el-table-column>
            <el-table-column label="连接状态" align="center" width="120">
              <template #default="scope">
                <template v-if="isLongConnection(scope.row)">
                  <el-tooltip
                    :disabled="scope.row.streamStatus !== 'failed' || !scope.row.streamStatusDetail"
                    :content="scope.row.streamStatusDetail || ''"
                    placement="top"
                  >
                    <el-tag :type="streamTagType(scope.row.streamStatus)" size="small" effect="plain">
                      {{ streamLabel(scope.row.streamStatus) }}
                    </el-tag>
                  </el-tooltip>
                </template>
                <span v-else class="text-muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" align="center" width="90">
              <template #default="scope">
                <el-tag :type="scope.row.status === '0' ? 'success' : 'info'">
                  {{ scope.row.status === '0' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" align="center" width="130" class-name="small-padding fixed-width">
              <template #default="scope">
                <el-tooltip content="修改" placement="top">
                  <el-button
                    v-hasPermi="['ai:channel:edit']"
                    link
                    type="primary"
                    icon="Edit"
                    @click="handleUpdate(scope.row)"
                  ></el-button>
                </el-tooltip>
                <el-tooltip content="删除" placement="top">
                  <el-button
                    v-hasPermi="['ai:channel:remove']"
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
      </el-tab-pane>

      <!-- ========== 外部身份映射 ========== -->
      <el-tab-pane label="外部身份映射" name="identity">
        <el-card shadow="hover" class="table-panel">
          <template #header>
            <div class="toolbar-shell">
              <div class="table-heading">
                <h3>外部身份映射</h3>
                <p>
                  把外部平台的用户标识（飞书 open_id / 企微 UserID / GitHub login）绑定到平台账号。
                  <b>未绑定的外部身份一律拒绝执行</b>并回一条提示，不会回退到任何默认身份。
                </p>
              </div>
              <div class="toolbar-actions">
                <el-select
                  v-model="identityQuery.channelId"
                  placeholder="全部渠道"
                  clearable
                  style="width: 220px"
                  @change="handleIdentityQuery"
                >
                  <el-option v-for="item in channelOptions" :key="item.id" :label="item.channelName" :value="item.id" />
                </el-select>
                <el-button v-hasPermi="['ai:channel:add']" type="primary" plain icon="Plus" @click="handleIdentityAdd">
                  新增
                </el-button>
                <el-button
                  v-hasPermi="['ai:channel:edit']"
                  type="success"
                  plain
                  icon="Edit"
                  :disabled="identitySingle"
                  @click="handleIdentityUpdate()"
                >
                  修改
                </el-button>
                <el-button
                  v-hasPermi="['ai:channel:remove']"
                  type="danger"
                  plain
                  icon="Delete"
                  :disabled="identityMultiple"
                  @click="handleIdentityDelete()"
                >
                  删除
                </el-button>
              </div>
            </div>
          </template>

          <el-table
            v-loading="identityLoading"
            border
            class="data-table"
            :data="identityList"
            @selection-change="handleIdentitySelectionChange"
          >
            <el-table-column type="selection" width="55" align="center" />
            <el-table-column label="渠道" align="left" width="200" prop="channelName" :show-overflow-tooltip="true" />
            <el-table-column label="外部用户标识" align="left" prop="externalUserId" :show-overflow-tooltip="true" />
            <el-table-column label="平台账号" align="center" width="200" :show-overflow-tooltip="true">
              <template #default="scope">
                <span v-if="scope.row.platformUserName">{{ scope.row.platformUserName }}</span>
                <span v-else class="text-muted">{{ scope.row.platformUserId ?? '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" align="center" width="90">
              <template #default="scope">
                <el-tag :type="scope.row.status === '0' ? 'success' : 'info'">
                  {{ scope.row.status === '0' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="备注" align="left" prop="remark" :show-overflow-tooltip="true" />
            <el-table-column label="操作" align="center" width="130" class-name="small-padding fixed-width">
              <template #default="scope">
                <el-tooltip content="修改" placement="top">
                  <el-button
                    v-hasPermi="['ai:channel:edit']"
                    link
                    type="primary"
                    icon="Edit"
                    @click="handleIdentityUpdate(scope.row)"
                  ></el-button>
                </el-tooltip>
                <el-tooltip content="删除" placement="top">
                  <el-button
                    v-hasPermi="['ai:channel:remove']"
                    link
                    type="primary"
                    icon="Delete"
                    @click="handleIdentityDelete(scope.row)"
                  ></el-button>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>

          <pagination
            v-show="identityTotal > 0"
            v-model:page="identityQuery.pageNum"
            v-model:limit="identityQuery.pageSize"
            :total="identityTotal"
            @pagination="getIdentityList"
          />
        </el-card>
      </el-tab-pane>

      <!-- ========== 会话映射 ========== -->
      <el-tab-pane label="会话映射" name="thread">
        <el-card shadow="hover" class="table-panel">
          <template #header>
            <div class="toolbar-shell">
              <div class="table-heading">
                <h3>消息线程 → 会话</h3>
                <p>
                  同一线程（飞书 chat_id / 企微 UserID / GitHub owner/repo#number）的后续消息续接同一条会话；
                  会话结束后再次发言会自动新建并重新绑定。
                </p>
              </div>
              <div class="toolbar-actions">
                <el-select
                  v-model="threadQuery.channelId"
                  placeholder="全部渠道"
                  clearable
                  style="width: 220px"
                  @change="handleThreadQuery"
                >
                  <el-option v-for="item in channelOptions" :key="item.id" :label="item.channelName" :value="item.id" />
                </el-select>
                <el-button
                  v-hasPermi="['ai:channel:remove']"
                  type="danger"
                  plain
                  icon="Delete"
                  :disabled="threadMultiple"
                  @click="handleThreadDelete()"
                >
                  删除映射
                </el-button>
              </div>
            </div>
          </template>

          <el-table
            v-loading="threadLoading"
            border
            class="data-table"
            :data="threadList"
            @selection-change="handleThreadSelectionChange"
          >
            <el-table-column type="selection" width="55" align="center" />
            <el-table-column label="渠道" align="left" width="180" prop="channelName" :show-overflow-tooltip="true" />
            <el-table-column label="线程标识" align="left" prop="threadKey" :show-overflow-tooltip="true" />
            <el-table-column label="会话标题" align="left" :show-overflow-tooltip="true">
              <template #default="scope">
                <span v-if="scope.row.sessionTitle">{{ scope.row.sessionTitle }}</span>
                <span v-else class="text-muted">{{ scope.row.sessionId ?? '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="会话状态" align="center" width="100">
              <template #default="scope">
                <el-tag :type="scope.row.sessionStatus === '0' ? 'success' : 'info'" size="small" effect="plain">
                  {{ scope.row.sessionStatus === '0' ? '进行中' : '已结束' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最后活跃" align="center" prop="lastActiveTime" width="170" />
            <el-table-column label="操作" align="center" width="90" class-name="small-padding fixed-width">
              <template #default="scope">
                <el-tooltip content="查看会话明细" placement="top">
                  <el-button
                    v-hasPermi="['ai:channel:query']"
                    link
                    type="primary"
                    icon="View"
                    :disabled="!scope.row.sessionId"
                    @click="goSession(scope.row)"
                  ></el-button>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>

          <pagination
            v-show="threadTotal > 0"
            v-model:page="threadQuery.pageNum"
            v-model:limit="threadQuery.pageSize"
            :total="threadTotal"
            @pagination="getThreadList"
          />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 渠道表单 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="720px" append-to-body>
      <el-form ref="channelFormRef" :model="form" :rules="rules" label-width="180px">
        <el-form-item label="渠道名称" prop="channelName">
          <el-input v-model="form.channelName" placeholder="如：飞书知识问答机器人" />
        </el-form-item>
        <el-form-item label="渠道编码" prop="channelCode">
          <template #label>
            <span class="label-tip">
              渠道编码
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    <template v-if="formIsLongConnection">仅字母 / 数字 / 下划线 / 短横线；该渠道为长连接，无需回调地址。</template>
                    <template v-else>
                      仅字母 / 数字 / 下划线 / 短横线；回调地址为
                      <code>{{ callbackPath(form) }}</code>
                    </template>
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.channelCode" placeholder="回调地址中的路径标识，如 feishu_kb" />
        </el-form-item>
        <el-form-item label="平台类型" prop="platformType">
          <template #label>
            <span class="label-tip">
              平台类型
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">平台类型保存后不可修改；如需更换请新建渠道。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-radio-group v-model="form.platformType" :disabled="!!form.id">
            <el-radio value="feishu">飞书</el-radio>
            <el-radio value="wecom">企业微信</el-radio>
            <el-radio value="github">GitHub</el-radio>
            <el-radio value="dingtalk">钉钉</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="默认智能体" prop="agentId">
          <el-select v-model="form.agentId" placeholder="请选择启用中的智能体" style="width: 100%">
            <el-option v-for="item in agentOptions" :key="item.id" :label="item.agentName" :value="item.id" />
          </el-select>
        </el-form-item>

        <template v-if="form.platformType === 'feishu'">
          <el-form-item label="接入形态" prop="mode">
            <template #label>
              <span class="label-tip">
                接入形态
                <el-tooltip placement="top">
                  <template #content>
                    <div class="tip-content">须与飞书后台「事件订阅方式」保持一致；选「长连接」则无需公网 IP 与回调地址。</div>
                  </template>
                  <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <el-radio-group v-model="form.mode">
              <el-radio value="webhook">Webhook 回调</el-radio>
              <el-radio value="ws">长连接</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="App ID" prop="appId">
            <el-input v-model="form.appId" placeholder="飞书自建应用 appId（cli_xxx）" />
          </el-form-item>
          <el-form-item label="App Secret" prop="appSecret">
            <el-input v-model="form.appSecret" type="password" show-password :placeholder="secretPlaceholder(form.appSecretSet)" />
          </el-form-item>
          <template v-if="formIsLongConnection">
            <div class="form-tip form-tip-block">
              飞书为长连接：应用主动外连，<b>无需公网 IP 与回调地址</b>；请在飞书后台把事件订阅方式选为「长连接」
              并开通接收消息事件 im.message.receive_v1。建连时即完成鉴权，故不需要 Encrypt Key / Verification Token。
            </div>
          </template>
          <template v-else>
            <el-form-item label="Encrypt Key" prop="encryptKey">
              <template #label>
                <span class="label-tip">
                  Encrypt Key
                  <el-tooltip placement="top">
                    <template #content>
                      <div class="tip-content">选填；配置后回调报文体为 AES 密文，且校验 X-Lark-Signature。</div>
                    </template>
                    <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                  </el-tooltip>
                </span>
              </template>
              <el-input v-model="form.encryptKey" type="password" show-password :placeholder="secretPlaceholder(form.encryptKeySet)" />
            </el-form-item>
            <el-form-item label="Verification Token" prop="verificationToken">
              <template #label>
                <span class="label-tip">
                  Verification Token
                  <el-tooltip placement="top">
                    <template #content>
                      <div class="tip-content">选填；配置后 URL 校验握手会比对报文中的 token。</div>
                    </template>
                    <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                  </el-tooltip>
                </span>
              </template>
              <el-input
                v-model="form.verificationToken"
                type="password"
                show-password
                :placeholder="secretPlaceholder(form.verificationTokenSet)"
              />
            </el-form-item>
          </template>
        </template>

        <template v-else-if="form.platformType === 'wecom'">
          <el-form-item label="Corp ID" prop="appId">
            <el-input v-model="form.appId" placeholder="企业微信企业 ID" />
          </el-form-item>
          <el-form-item label="Secret" prop="appSecret">
            <el-input v-model="form.appSecret" type="password" show-password :placeholder="secretPlaceholder(form.appSecretSet)" />
          </el-form-item>
          <el-form-item label="Agent ID" prop="platformAgentId">
            <el-input v-model="form.platformAgentId" placeholder="自建应用 AgentId（正整数）" />
          </el-form-item>
          <el-form-item label="Token" prop="verificationToken">
            <el-input
              v-model="form.verificationToken"
              type="password"
              show-password
              :placeholder="secretPlaceholder(form.verificationTokenSet)"
            />
          </el-form-item>
          <el-form-item label="EncodingAESKey" prop="encryptKey">
            <template #label>
              <span class="label-tip">
                EncodingAESKey
                <el-tooltip placement="top">
                  <template #content>
                    <div class="tip-content">43 位字符（Base64，无填充）。</div>
                  </template>
                  <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <el-input v-model="form.encryptKey" type="password" show-password :placeholder="secretPlaceholder(form.encryptKeySet)" />
          </el-form-item>
        </template>

        <template v-else-if="form.platformType === 'github'">
          <el-form-item label="机器人账号登录名" prop="platformAgentId">
            <el-input v-model="form.platformAgentId" placeholder="选填，如 my-bot；用于丢弃机器人自评避免自环" />
          </el-form-item>
          <el-form-item label="Webhook Secret" prop="webhookSecret">
            <template #label>
              <span class="label-tip">
                Webhook Secret
                <el-tooltip placement="top">
                  <template #content>
                    <div class="tip-content">必填；用于校验 X-Hub-Signature-256（HMAC-SHA256）。</div>
                  </template>
                  <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <el-input v-model="form.webhookSecret" type="password" show-password :placeholder="secretPlaceholder(form.webhookSecretSet)" />
          </el-form-item>
          <el-form-item label="访问令牌（PAT）" prop="apiToken">
            <template #label>
              <span class="label-tip">
                访问令牌（PAT）
                <el-tooltip placement="top">
                  <template #content>
                    <div class="tip-content">必填；用于把回答以评论形式回推到 Issue / PR。</div>
                  </template>
                  <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <el-input v-model="form.apiToken" type="password" show-password :placeholder="secretPlaceholder(form.apiTokenSet)" />
          </el-form-item>
        </template>

        <template v-else-if="form.platformType === 'dingtalk'">
          <el-form-item label="AppKey" prop="appId">
            <el-input v-model="form.appId" placeholder="钉钉企业内部应用 AppKey（长连接的 clientId）" />
          </el-form-item>
          <el-form-item label="AppSecret" prop="appSecret">
            <el-input v-model="form.appSecret" type="password" show-password :placeholder="secretPlaceholder(form.appSecretSet)" />
          </el-form-item>
          <el-form-item label="机器人编码" prop="platformAgentId">
            <el-input v-model="form.platformAgentId" placeholder="RobotCode，出站回复的发送者身份" />
          </el-form-item>
          <div class="form-tip form-tip-block">
            钉钉为长连接（DingTalk Stream）：保存后应用会立即建立连接，无需配置回调地址；
            断线由框架自动重连。请确保应用已开通「机器人」能力并配置好消息接收模式为 Stream。
          </div>
        </template>

        <el-form-item :label="form.platformType === 'dingtalk' ? 'OpenAPI 基地址' : 'API 基地址'" prop="apiBase">
          <template #label>
            <span class="label-tip">
              {{ form.platformType === 'dingtalk' ? 'OpenAPI 基地址' : 'API 基地址' }}
              <el-tooltip v-if="form.platformType === 'dingtalk'" placement="top">
                <template #content>
                  <div class="tip-content">
                    留空使用 https://api.dingtalk.com；长连接注册地址默认由它派生为
                    &lt;基地址&gt;/v1.0/gateway/connections/open（企业内网代理或沙箱联调时改写）。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.apiBase" :placeholder="apiBasePlaceholder" />
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

    <!-- 外部身份映射表单 -->
    <el-dialog v-model="identityDialog.visible" :title="identityDialog.title" width="620px" append-to-body>
      <el-form ref="identityFormRef" :model="identityForm" :rules="identityRules" label-width="130px">
        <el-form-item label="所属渠道" prop="channelId">
          <el-select v-model="identityForm.channelId" placeholder="请选择渠道" style="width: 100%">
            <el-option v-for="item in channelOptions" :key="item.id" :label="item.channelName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="外部用户标识" prop="externalUserId">
          <el-input v-model="identityForm.externalUserId" placeholder="飞书 open_id / 企微 UserID / GitHub login" />
        </el-form-item>
        <el-form-item label="平台账号" prop="platformUserId">
          <template #label>
            <span class="label-tip">
              平台账号
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">外部消息将以该用户的身份执行，可见范围与数据权限均按该用户。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="platformUserLabel"
            placeholder="请选择平台用户"
            readonly
            clearable
            @click="handleSelectPlatformUser"
            @clear="handleClearPlatformUser"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="identityForm.status">
            <el-radio value="0">启用</el-radio>
            <el-radio value="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="identityForm.remark" type="textarea" :rows="2" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="buttonLoading" type="primary" @click="submitIdentityForm">确 定</el-button>
          <el-button @click="identityCancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <UserSelect ref="userSelectRef" :multiple="false" @confirm-call-back="handlePlatformUserSelected"></UserSelect>
  </div>
</template>

<script setup name="AiChannel" lang="ts">
import type { AgentVO } from '@/api/ai/agent/types';
import type {
  ChannelForm,
  ChannelIdentityForm,
  ChannelIdentityQuery,
  ChannelIdentityVO,
  ChannelQuery,
  ChannelThreadVO,
  ChannelVO
} from '@/api/ai/channel/types';
import {
  addChannel,
  addIdentity,
  delChannel,
  delIdentity,
  delThread,
  getChannel,
  listChannel,
  listIdentity,
  listThread,
  optionChannelAgent,
  updateChannel,
  updateIdentity
} from '@/api/ai/channel';
import type { UserVO } from '@/api/system/user/types';
import UserSelect from '@/components/UserSelect/index.vue';
import modal from '@/plugins/modal';

const router = useRouter();

const activeTab = ref('channel');

// ------------------------------------------------------------------
//  渠道配置
// ------------------------------------------------------------------
const channelList = ref<ChannelVO[]>([]);
const agentOptions = ref<AgentVO[]>([]);
const channelOptions = ref<ChannelVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const buttonLoading = ref(false);
const queryFormRef = ref<ElFormInstance>();
const channelFormRef = ref<ElFormInstance>();

const queryParams = ref<ChannelQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  platformType: undefined,
  status: undefined
});

const initFormData: ChannelForm = {
  id: undefined,
  channelName: undefined,
  channelCode: undefined,
  platformType: 'feishu',
  mode: 'webhook',
  agentId: undefined,
  appId: undefined,
  appSecret: undefined,
  platformAgentId: undefined,
  encryptKey: undefined,
  verificationToken: undefined,
  webhookSecret: undefined,
  apiToken: undefined,
  apiBase: undefined,
  status: '0',
  remark: undefined
};

const dialog = reactive<{ visible: boolean; title: string }>({
  visible: false,
  title: ''
});

const form = ref<ChannelForm>({ ...initFormData });

const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);

/** 平台相关的必填项按所选平台动态校验 */
const rules = computed<ElFormRules>(() => {
  const base: ElFormRules = {
    channelName: [{ required: true, message: '渠道名称不能为空', trigger: 'blur' }],
    channelCode: [
      { required: true, message: '渠道编码不能为空', trigger: 'blur' },
      { pattern: /^[A-Za-z0-9_-]+$/, message: '只能包含字母、数字、下划线与短横线', trigger: 'blur' }
    ],
    platformType: [{ required: true, message: '请选择平台类型', trigger: 'change' }],
    agentId: [{ required: true, message: '请选择默认智能体', trigger: 'change' }]
  };
  if (form.value.platformType === 'feishu') {
    base.appId = [{ required: true, message: '飞书 appId 不能为空', trigger: 'blur' }];
    base.appSecret = [{ required: !form.value.appSecretSet, message: '飞书 appSecret 不能为空', trigger: 'blur' }];
  } else if (form.value.platformType === 'wecom') {
    base.appId = [{ required: true, message: '企业微信 corpId 不能为空', trigger: 'blur' }];
    base.appSecret = [{ required: !form.value.appSecretSet, message: '企业微信 secret 不能为空', trigger: 'blur' }];
    base.platformAgentId = [{ required: true, message: 'AgentId 不能为空', trigger: 'blur' }];
    base.verificationToken = [
      { required: !form.value.verificationTokenSet, message: 'Token 不能为空', trigger: 'blur' }
    ];
    base.encryptKey = [
      { required: !form.value.encryptKeySet, message: 'EncodingAESKey 不能为空', trigger: 'blur' },
      {
        validator: (_rule: any, value: string, callback: (error?: Error) => void) => {
          if (!value || value.length === 43) {
            callback();
          } else {
            callback(new Error('EncodingAESKey 必须为 43 位字符'));
          }
        },
        trigger: 'blur'
      }
    ];
  } else if (form.value.platformType === 'github') {
    base.webhookSecret = [{ required: !form.value.webhookSecretSet, message: 'Webhook Secret 不能为空', trigger: 'blur' }];
    base.apiToken = [{ required: !form.value.apiTokenSet, message: '访问令牌不能为空', trigger: 'blur' }];
  } else if (form.value.platformType === 'dingtalk') {
    base.appId = [{ required: true, message: '钉钉 AppKey 不能为空', trigger: 'blur' }];
    base.appSecret = [{ required: !form.value.appSecretSet, message: '钉钉 AppSecret 不能为空', trigger: 'blur' }];
    base.platformAgentId = [{ required: true, message: '机器人编码 RobotCode 不能为空', trigger: 'blur' }];
  }
  return base;
});

/** 平台切换后清空既有校验提示（字段集合随平台变化，避免残留红字） */
watch(
  () => form.value.platformType,
  () => channelFormRef.value?.clearValidate()
);

/** 平台展示名 */
const platformLabel = (type?: string) => {
  if (type === 'feishu') return '飞书';
  if (type === 'wecom') return '企业微信';
  if (type === 'github') return 'GitHub';
  if (type === 'dingtalk') return '钉钉';
  return type || '-';
};

/** 是否长连接渠道（钉钉恒为长连接；飞书按 mode=ws） */
const isLongConnection = (row: Partial<ChannelVO>) =>
  row.platformType === 'dingtalk' || (row.platformType === 'feishu' && row.mode === 'ws');

/** 当前表单是否为「长连接」形态（仅飞书有此开关，钉钉走独立分支） */
const formIsLongConnection = computed(() => form.value.platformType === 'feishu' && form.value.mode === 'ws');

/** API 基地址输入提示（钉钉为 OpenAPI + 长连接注册地址前缀；飞书长连接为 domain） */
const apiBasePlaceholder = computed(() => {
  if (form.value.platformType === 'dingtalk') {
    return '留空使用 https://api.dingtalk.com';
  }
  if (formIsLongConnection.value) {
    return '长连接 domain，留空使用 https://open.feishu.cn';
  }
  return '留空使用各平台默认地址（企业内网 / 私有部署时填写）';
});

/** 长连接状态展示名（长连接渠道有值） */
const streamLabel = (status?: string) => {
  if (status === 'running') return '运行中';
  if (status === 'failed') return '启动失败';
  return '未启动';
};

/** 长连接状态标签样式 */
const streamTagType = (status?: string) => {
  if (status === 'running') return 'success';
  if (status === 'failed') return 'danger';
  return 'info';
};

/** 回调地址（与后端回调节点一致） */
const callbackPath = (row: Partial<ChannelVO>) => {
  const platform = row.platformType || '{平台}';
  const code = row.channelCode || '{渠道编码}';
  return `/ai/channel/callback/${platform}/${code}`;
};

/** 已配置的凭据留空提示 */
const secretPlaceholder = (configured?: boolean) => (configured ? '已配置，留空保持不变' : '请输入');

/** 查询渠道列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listChannel(queryParams.value);
    channelList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  } finally {
    loading.value = false;
  }
};

/** 查询启用中的智能体选项 */
const getAgentOptions = async () => {
  const res = await optionChannelAgent();
  agentOptions.value = res.data ?? [];
};

/** 查询渠道下拉（全部渠道，供映射页筛选与表单选择） */
const getChannelOptions = async () => {
  const res = await listChannel({ pageNum: 1, pageSize: 200 });
  channelOptions.value = res.data?.rows ?? [];
};

const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryFormRef.value?.resetFields();
  queryParams.value.keyword = '';
  queryParams.value.platformType = undefined;
  queryParams.value.status = undefined;
  queryParams.value.pageNum = 1;
  getList();
};

const handleSelectionChange = (selection: ChannelVO[]) => {
  ids.value = selection.map((item) => item.id);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};

/** 重置表单为初始值 */
const reset = () => {
  form.value = { ...initFormData };
  channelFormRef.value?.resetFields();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.title = '新增渠道';
  dialog.visible = true;
};

/** 修改按钮操作（凭据不回显，留空表示保持原值） */
const handleUpdate = async (row?: Partial<ChannelVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getChannel(id);
  const detail = res.data;
  form.value = {
    id: detail.id,
    channelName: detail.channelName,
    channelCode: detail.channelCode,
    platformType: detail.platformType,
    mode: detail.mode ?? 'webhook',
    agentId: detail.agentId,
    appId: detail.appId,
    appSecret: undefined,
    platformAgentId: detail.platformAgentId,
    encryptKey: undefined,
    verificationToken: undefined,
    webhookSecret: undefined,
    apiToken: undefined,
    apiBase: detail.apiBase,
    status: detail.status ?? '0',
    remark: detail.remark,
    appSecretSet: detail.appSecretSet,
    encryptKeySet: detail.encryptKeySet,
    verificationTokenSet: detail.verificationTokenSet,
    webhookSecretSet: detail.webhookSecretSet,
    apiTokenSet: detail.apiTokenSet
  };
  dialog.title = '修改渠道';
  dialog.visible = true;
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = () => {
  channelFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: ChannelForm = { ...form.value };
      if (payload.id) {
        await updateChannel(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addChannel(payload);
        modal.msgSuccess('新增成功');
      }
      dialog.visible = false;
      await getList();
      await getChannelOptions();
    } finally {
      buttonLoading.value = false;
    }
  });
};

/** 删除按钮操作 */
const handleDelete = async (row?: Partial<ChannelVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm('是否确认删除该渠道？其外部身份映射与线程映射将一并清理，历史会话保留。');
  await delChannel(target);
  modal.msgSuccess('删除成功');
  await getList();
  await getChannelOptions();
};

// ------------------------------------------------------------------
//  外部身份映射
// ------------------------------------------------------------------
const identityList = ref<ChannelIdentityVO[]>([]);
const identityLoading = ref(false);
const identityTotal = ref(0);
const identityFormRef = ref<ElFormInstance>();
const userSelectRef = ref<InstanceType<typeof UserSelect>>();
const platformUserLabel = ref('');

const identityQuery = ref<ChannelIdentityQuery>({
  pageNum: 1,
  pageSize: 10,
  channelId: undefined,
  keyword: ''
});

const initIdentityForm: ChannelIdentityForm = {
  id: undefined,
  channelId: undefined,
  externalUserId: undefined,
  platformUserId: undefined,
  status: '0',
  remark: undefined
};

const identityDialog = reactive<{ visible: boolean; title: string }>({
  visible: false,
  title: ''
});

const identityForm = ref<ChannelIdentityForm>({ ...initIdentityForm });
const identityIds = ref<Array<string | number>>([]);
const identitySingle = ref(true);
const identityMultiple = ref(true);

const identityRules: ElFormRules = {
  channelId: [{ required: true, message: '请选择所属渠道', trigger: 'change' }],
  externalUserId: [{ required: true, message: '外部用户标识不能为空', trigger: 'blur' }],
  platformUserId: [{ required: true, message: '请选择平台账号', trigger: 'change' }]
};

const getIdentityList = async () => {
  identityLoading.value = true;
  try {
    const res = await listIdentity(identityQuery.value);
    identityList.value = res.data?.rows ?? [];
    identityTotal.value = res.data?.total ?? 0;
  } finally {
    identityLoading.value = false;
  }
};

const handleIdentityQuery = () => {
  identityQuery.value.pageNum = 1;
  getIdentityList();
};

const handleIdentitySelectionChange = (selection: ChannelIdentityVO[]) => {
  identityIds.value = selection.map((item) => item.id);
  identitySingle.value = selection.length !== 1;
  identityMultiple.value = !selection.length;
};

const resetIdentityForm = () => {
  identityForm.value = { ...initIdentityForm };
  platformUserLabel.value = '';
  identityFormRef.value?.resetFields();
};

const handleIdentityAdd = () => {
  resetIdentityForm();
  identityForm.value.channelId = identityQuery.value.channelId;
  identityDialog.title = '新增外部身份映射';
  identityDialog.visible = true;
};

const handleIdentityUpdate = async (row?: Partial<ChannelIdentityVO>) => {
  resetIdentityForm();
  const target = row ?? identityList.value.find((item) => item.id === identityIds.value[0]);
  if (!target) {
    return;
  }
  identityForm.value = {
    id: target.id,
    channelId: target.channelId,
    externalUserId: target.externalUserId,
    platformUserId: target.platformUserId,
    status: target.status ?? '0',
    remark: target.remark
  };
  platformUserLabel.value = target.platformUserName ?? (target.platformUserId ? String(target.platformUserId) : '');
  identityDialog.title = '修改外部身份映射';
  identityDialog.visible = true;
};

const handleSelectPlatformUser = () => {
  userSelectRef.value?.open();
};

const handlePlatformUserSelected = (users: UserVO[]) => {
  const user = users?.[0];
  if (!user) {
    modal.msgWarning('请选择用户');
    return;
  }
  identityForm.value.platformUserId = user.userId;
  platformUserLabel.value = user.nickName || user.userName;
};

const handleClearPlatformUser = () => {
  identityForm.value.platformUserId = undefined;
  platformUserLabel.value = '';
};

const identityCancel = () => {
  resetIdentityForm();
  identityDialog.visible = false;
};

const submitIdentityForm = () => {
  identityFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: ChannelIdentityForm = { ...identityForm.value };
      if (payload.id) {
        await updateIdentity(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addIdentity(payload);
        modal.msgSuccess('新增成功');
      }
      identityDialog.visible = false;
      await getIdentityList();
    } finally {
      buttonLoading.value = false;
    }
  });
};

const handleIdentityDelete = async (row?: Partial<ChannelIdentityVO>) => {
  const target = row?.id ?? identityIds.value;
  await modal.confirm('是否确认删除选中的外部身份映射？删除后该外部身份将无法执行。');
  await delIdentity(target);
  modal.msgSuccess('删除成功');
  await getIdentityList();
};

// ------------------------------------------------------------------
//  会话映射
// ------------------------------------------------------------------
const threadList = ref<ChannelThreadVO[]>([]);
const threadLoading = ref(false);
const threadTotal = ref(0);
const threadQuery = ref<PageQuery & { channelId?: string | number }>({
  pageNum: 1,
  pageSize: 10,
  channelId: undefined
});
const threadIds = ref<Array<string | number>>([]);
const threadMultiple = ref(true);

const getThreadList = async () => {
  threadLoading.value = true;
  try {
    const res = await listThread(threadQuery.value);
    threadList.value = res.data?.rows ?? [];
    threadTotal.value = res.data?.total ?? 0;
  } finally {
    threadLoading.value = false;
  }
};

const handleThreadQuery = () => {
  threadQuery.value.pageNum = 1;
  getThreadList();
};

const handleThreadSelectionChange = (selection: ChannelThreadVO[]) => {
  threadIds.value = selection.map((item) => item.id);
  threadMultiple.value = !selection.length;
};

const handleThreadDelete = async () => {
  await modal.confirm('是否确认删除选中的线程映射？删除后同一线程的下一条消息将新建会话。');
  await delThread(threadIds.value);
  modal.msgSuccess('删除成功');
  await getThreadList();
};

/** 跳转到会话管理页查看该线程的完整对话 */
const goSession = (row: ChannelThreadVO) => {
  if (!row.sessionId) {
    return;
  }
  router.push({
    path: '/bizbuddy/agent/session',
    query: { sessionId: String(row.sessionId), source: 'channel' }
  });
};

getAgentOptions();
getChannelOptions();
getList();
getIdentityList();
getThreadList();
</script>

<style scoped lang="scss">
.text-muted {
  color: var(--app-text-muted, #909399);
}

.inline-tag {
  margin-left: 4px;
}

.channel-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 12px;
  }
}

.table-heading h3 {
  margin: 0;
}

.table-heading p {
  margin: 6px 0 0;
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.form-tip {
  margin-top: 4px;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.7;
}

.form-tip-block {
  margin: 0 0 18px 130px;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.callback-path {
  color: var(--el-color-primary);
  font-size: 12px;
}
</style>