<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="configLoading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>MCP 服务端</h3>
            <p>
              对外暴露给外部 AI 的工具入口：出口端点与主应用共用端口，调用方携带平台口令访问。
              暴露清单复用「业务工具注册」表，仅「启用中的只读工具」可勾选——写入类工具在出口没有人工确认闸门，一律不可暴露。
              保存后对同一个出口服务端热挂载/撤销工具，无需重启。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Refresh" @click="loadAll">刷新</el-button>
          </div>
        </div>
      </template>

      <el-descriptions :column="2" border class="server-desc">
        <el-descriptions-item label="出口端点">
          <span class="mono">{{ status.endpoint || '—' }}</span>
          <span class="form-tip">（完整地址 = 平台服务地址 + 该路径）</span>
        </el-descriptions-item>
        <el-descriptions-item label="当前实际挂载">
          <template v-if="status.liveTools.length">
            <el-tag v-for="name in status.liveTools" :key="name" class="live-tag" effect="plain">{{ name }}</el-tag>
          </template>
          <span v-else class="form-tip">出口当前未挂载任何工具</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-form :model="configForm" label-width="150px" class="config-form">
        <el-form-item label="启用访问口令校验">
          <template #label>
            <span class="label-tip">
              启用访问口令校验
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    开启后调用方须携带 Authorization: Bearer &lt;口令&gt; 或 X-MCP-Token：&lt;口令&gt;；关闭则出口完全开放（仅建议内网调试时临时关闭）。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="configForm.authEnabled" />
        </el-form-item>
        <el-form-item label="访问口令">
          <template #label>
            <span class="label-tip">
              访问口令
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    口令以明文存于运行期配置（Redis），用于回显与交付；开启校验但口令为空时，出口对所有请求返回 401。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input
            v-model="configForm.token"
            class="token-input"
            placeholder="对外交付给调用方的口令"
            :disabled="!configForm.authEnabled"
            show-password
          />
          <el-button class="token-btn" icon="Refresh" @click="handleGenerateToken">随机生成</el-button>
        </el-form-item>
        <el-form-item>
          <el-button
            v-hasPermi="['ai:mcp:server:edit']"
            type="primary"
            icon="Check"
            :loading="configSaving"
            @click="handleSaveConfig"
          >
            保存配置
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel tools-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>对外暴露清单</h3>
            <p>仅列出启用中的只读工具；「实现未登记」的工具勾选后也无法执行，已禁用勾选。</p>
          </div>
          <div class="toolbar-actions">
            <el-button
              v-hasPermi="['ai:mcp:server:edit']"
              type="primary"
              icon="Check"
              :loading="toolsSaving"
              :disabled="!toolList.length"
              @click="handleSaveTools"
            >
              保存暴露清单
            </el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="toolsLoading" border class="data-table" :data="toolList">
        <el-table-column label="对外暴露" align="center" width="110">
          <template #default="scope">
            <el-switch
              v-model="scope.row.exposed"
              :disabled="!scope.row.registered || !canEdit"
            />
          </template>
        </el-table-column>
        <el-table-column label="工具编码" align="center" prop="toolCode" width="220" :show-overflow-tooltip="true" />
        <el-table-column label="工具名称" align="center" prop="toolName" width="160" :show-overflow-tooltip="true" />
        <el-table-column label="实现登记" align="center" width="110">
          <template #default="scope">
            <el-tag :type="scope.row.registered ? 'success' : 'danger'" effect="plain">
              {{ scope.row.registered ? '已登记' : '未登记' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" align="left" prop="description" :show-overflow-tooltip="true" />
      </el-table>
      <el-empty v-if="!toolsLoading && !toolList.length" description="暂无启用中的只读工具，请先到「业务工具注册」新增" />
    </el-card>

    <el-card shadow="hover" class="table-panel example-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>调用示例</h3>
            <p>
              出口是「无状态 HTTP + JSON-RPC 2.0」：调用方 POST 到出口端点即可，三步之间无需保持会话。
              下面的地址与口令取自本页当前配置，可直接复制交付给外部调用方。
            </p>
          </div>
        </div>
      </template>

      <el-descriptions :column="1" border class="server-desc">
        <el-descriptions-item label="出口完整地址">
          <span class="mono">{{ mcpUrl }}</span>
          <el-button link type="primary" icon="CopyDocument" class="copy-btn" @click="handleCopy(mcpUrl)">复制</el-button>
          <div class="form-tip">
            也可直连后端：<span class="mono">http://&lt;后端主机&gt;:8080{{ status.endpoint || '/mcp' }}</span>（容器内为 ruoyi-admin:8080）
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="请求头">
          必带 <span class="mono">Content-Type: application/json</span> 与
          <span class="mono">Accept: application/json, text/event-stream</span>（无状态传输，响应可能以 SSE 帧回包）
        </el-descriptions-item>
        <el-descriptions-item label="认证">
          <span class="mono">Authorization: Bearer &lt;访问口令&gt;</span> 或
          <span class="mono">X-MCP-Token: &lt;访问口令&gt;</span>
          <div class="form-tip">
            {{
              status.authEnabled
                ? '当前已开启口令校验：缺失或错误将返回 HTTP 401 + JSON-RPC error.code=-32001'
                : '当前未开启口令校验：认证头可省略（仅建议内网调试时）'
            }}
          </div>
        </el-descriptions-item>
      </el-descriptions>

      <div class="example-block">
        <div class="example-title">
          <span>curl 三步调用</span>
          <el-button link type="primary" icon="CopyDocument" @click="handleCopy(curlExample)">复制</el-button>
        </div>
        <pre class="code-block">{{ curlExample }}</pre>
      </div>

      <div class="example-block">
        <div class="example-title">
          <span>MCP 客户端（Streamable HTTP）配置片段</span>
          <el-button link type="primary" icon="CopyDocument" @click="handleCopy(clientExample)">复制</el-button>
        </div>
        <pre class="code-block">{{ clientExample }}</pre>
        <div class="form-tip">
          填在支持 Streamable HTTP 的 MCP 客户端配置里（<span class="mono">type</span> 字段名因客户端而异，可能是
          <span class="mono">"http"</span> 或 <span class="mono">"streamable-http"</span>）；未开启口令校验时删掉
          <span class="mono">headers</span>。
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup name="AiMcpServer" lang="ts">
import { getMcpServer, listMcpServerTools, updateMcpServer, updateMcpServerTools } from '@/api/ai/mcp/server';
import type { McpServerConfigForm, McpServerStatus, McpServerTool } from '@/api/ai/mcp/server/types';
import auth from '@/plugins/auth';
import modal from '@/plugins/modal';

/** 是否具备出口编辑权限（无权限时开关置灰，避免误以为可保存） */
const canEdit = computed(() => auth.hasPermi('ai:mcp:server:edit'));

const configLoading = ref(true);
const configSaving = ref(false);
const toolsLoading = ref(false);
const toolsSaving = ref(false);

const status = ref<McpServerStatus>({
  endpoint: '',
  authEnabled: true,
  token: '',
  liveTools: []
});

const configForm = ref<McpServerConfigForm>({
  authEnabled: true,
  token: ''
});

const toolList = ref<McpServerTool[]>([]);

/** 出口完整地址：站点源 + 前端 API 前缀 + 出口路径（供外部调用方直接复制） */
const mcpUrl = computed(() => {
  const base = String(import.meta.env.VITE_APP_BASE_API || '').replace(/\/$/, '');
  return `${window.location.origin}${base}${status.value.endpoint || '/mcp'}`;
});

/** 拼一条 curl：按当前是否开启口令校验决定是否带认证头 */
const curlOf = (id: number, method: string, params: string) => {
  const lines = [
    `curl -s -X POST "${mcpUrl.value}" \\`,
    '  -H "Content-Type: application/json" \\',
    '  -H "Accept: application/json, text/event-stream" \\'
  ];
  if (status.value.authEnabled) {
    lines.push(`  -H "Authorization: Bearer ${status.value.token || '<访问口令>'}" \\`);
  }
  lines.push(`  -d '{"jsonrpc":"2.0","id":${id},"method":"${method}","params":${params}}'`);
  return lines.join('\n');
};

/** curl 三步调用示例（内插当前地址与口令） */
const curlExample = computed(() =>
  [
    '# ① 握手：确认地址与口令可用，返回 serverInfo',
    curlOf(1, 'initialize', '{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"my-client","version":"1.0.0"}}'),
    '',
    '# ② 列出当前挂载的工具（清单来自「对外暴露清单」）',
    curlOf(2, 'tools/list', '{}'),
    '',
    '# ③ 调用工具：系统信息（平台内置只读工具；若已在暴露清单里撤销，请换成清单内的其它工具）',
    curlOf(3, 'tools/call', '{"name":"get_system_info","arguments":{}}')
  ].join('\n')
);

/** MCP 客户端配置片段（Streamable HTTP） */
const clientExample = computed(() => {
  const headers = status.value.authEnabled
    ? `,\n      "headers": { "Authorization": "Bearer ${status.value.token || '<访问口令>'}" }`
    : '';
  return `{\n  "mcpServers": {\n    "bizbuddy": {\n      "type": "http",\n      "url": "${mcpUrl.value}"${headers}\n    }\n  }\n}`;
});

/** 复制到剪贴板（浏览器未放行时提示手动复制） */
const handleCopy = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text);
    modal.msgSuccess('已复制');
  } catch {
    modal.msgWarning('浏览器未放行剪贴板，请手动选择复制');
  }
};

/** 加载出口状态与访问配置 */
const loadServer = async () => {
  configLoading.value = true;
  try {
    const res = await getMcpServer();
    if (res.data) {
      status.value = {
        endpoint: res.data.endpoint || '',
        authEnabled: res.data.authEnabled,
        token: res.data.token || '',
        liveTools: res.data.liveTools || []
      };
      configForm.value = {
        authEnabled: res.data.authEnabled,
        token: res.data.token || ''
      };
    }
  } finally {
    configLoading.value = false;
  }
};

/** 加载候选工具清单 */
const loadTools = async () => {
  toolsLoading.value = true;
  try {
    const res = await listMcpServerTools();
    toolList.value = res.data || [];
  } finally {
    toolsLoading.value = false;
  }
};

const loadAll = async () => {
  await Promise.all([loadServer(), loadTools()]);
};

/** 生成随机口令（32 位十六进制，便于直接交付） */
const handleGenerateToken = () => {
  const bytes = new Uint8Array(16);
  window.crypto.getRandomValues(bytes);
  configForm.value.token = Array.from(bytes)
    .map((item) => item.toString(16).padStart(2, '0'))
    .join('');
};

/** 保存访问配置 */
const handleSaveConfig = async () => {
  if (configForm.value.authEnabled && !configForm.value.token) {
    modal.msgWarning('开启口令校验时必须配置访问口令');
    return;
  }
  configSaving.value = true;
  try {
    await updateMcpServer({ ...configForm.value });
    modal.msgSuccess('保存成功，出口立即按新口令校验');
    await loadServer();
  } finally {
    configSaving.value = false;
  }
};

/** 保存暴露清单（热生效） */
const handleSaveTools = async () => {
  toolsSaving.value = true;
  try {
    const exposed = toolList.value.filter((item) => item.exposed).map((item) => item.toolCode);
    await updateMcpServerTools(exposed);
    modal.msgSuccess(exposed.length ? `已暴露 ${exposed.length} 个工具，出口已热更新` : '已清空暴露清单，出口工具已撤销');
    await loadAll();
  } finally {
    toolsSaving.value = false;
  }
};

onMounted(loadAll);
</script>

<style scoped lang="scss">
.table-heading p {
  margin: 6px 0 0;
  color: var(--app-text-muted);
  font-size: 13px;
}

.form-tip {
  margin-left: 12px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.server-desc {
  margin-bottom: 12px;
}

.live-tag {
  margin-right: 6px;
}

.token-input {
  width: 360px;
}

.token-btn {
  margin-left: 8px;
}

.config-form {
  max-width: 1000px;
  padding-top: 4px;
}

.tools-panel {
  margin-top: 12px;
}

.mono {
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, Monaco, monospace;
}

.copy-btn {
  margin-left: 8px;
}

.example-panel {
  margin-top: 12px;
}

.example-block + .example-block {
  margin-top: 12px;
}

.example-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.code-block {
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre;
}
</style>