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
          <el-form-item label="知识库" prop="kbId">
            <el-select
              v-model="queryParams.kbId"
              placeholder="全部知识库（不选即查全部）"
              clearable
              style="width: 240px"
              @change="handleKbChange"
            >
              <el-option v-for="item in kbOptions" :key="item.id" :label="item.kbName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="文档名称" prop="docName">
            <el-input v-model="queryParams.docName" placeholder="请输入文档名称" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="解析状态" prop="parseStatus">
            <el-select v-model="queryParams.parseStatus" placeholder="全部" clearable style="width: 140px">
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="向量化状态" prop="vectorStatus">
            <el-select v-model="queryParams.vectorStatus" placeholder="全部" clearable style="width: 140px">
              <el-option v-for="item in vectorStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
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
            <h3>文档与切片</h3>
            <p>支持 PDF / docx / xlsx / pptx 与常见文本格式；可多选批量「解析 / 向量化」，或上传后由「自动入库」一次走完。</p>
          </div>
          <div class="toolbar-actions">
            <el-tooltip v-if="canIngest" content="上传后自动执行「解析 + 向量化」，无需再逐条点击" placement="top">
              <el-switch
                v-model="autoIngest"
                class="auto-ingest-switch"
                active-text="自动入库"
                :disabled="!queryParams.kbId"
              />
            </el-tooltip>
            <el-upload
              ref="uploadRef"
              :action="uploadUrl"
              :headers="uploadHeaders"
              :multiple="true"
              :accept="acceptExt"
              :show-file-list="false"
              :on-success="handleUploadSuccess"
              :on-error="handleUploadError"
              :before-upload="handleBeforeUpload"
            >
              <el-button v-hasPermi="['ai:kb:doc:upload']" type="primary" plain icon="Upload" :disabled="!queryParams.kbId">
                上传文档
              </el-button>
            </el-upload>
            <el-dropdown
              v-hasPermi="['ai:kb:doc:parse']"
              trigger="click"
              :disabled="!ids.length"
              @command="handleBatchCommand"
            >
              <el-button type="primary" plain icon="Operation" :disabled="!ids.length">
                批量处理
                <el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="ingest" icon="MagicStick">一键入库（解析 + 向量化）</el-dropdown-item>
                  <el-dropdown-item command="parse" icon="Document">批量解析</el-dropdown-item>
                  <el-dropdown-item command="vectorize" icon="Cpu">批量向量化</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button
              v-hasPermi="['ai:kb:doc:remove']"
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

      <el-table
        ref="docTableRef"
        v-loading="loading"
        border
        class="data-table"
        :data="docList"
        row-key="id"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="55" align="center" reserve-selection />
        <el-table-column label="文档名称" align="center" prop="docName" :show-overflow-tooltip="true" />
        <el-table-column label="格式" align="center" width="90">
          <template #default="scope">{{ scope.row.fileSuffix || '-' }}</template>
        </el-table-column>
        <el-table-column label="大小" align="center" width="100">
          <template #default="scope">{{ formatSize(scope.row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="字符数" align="center" prop="charCount" width="100" />
        <el-table-column label="切片数" align="center" width="90">
          <template #default="scope">
            <el-button link type="primary" :disabled="!scope.row.chunkCount" @click="handleChunks(scope.row)">
              {{ scope.row.chunkCount ?? 0 }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="解析状态" align="center" width="130">
          <template #default="scope">
            <el-tooltip :content="scope.row.errorMsg || statusLabel(scope.row.parseStatus)" placement="top">
              <el-tag :type="statusType(scope.row.parseStatus)">{{ statusLabel(scope.row.parseStatus) }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="向量化状态" align="center" width="140">
          <template #default="scope">
            <el-tooltip :content="scope.row.vectorErrorMsg || vectorStatusLabel(scope.row.vectorStatus)" placement="top">
              <el-tag :type="statusType(scope.row.vectorStatus)">{{ vectorStatusLabel(scope.row.vectorStatus) }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" align="center" prop="createTime" width="170" />
        <el-table-column label="操作" align="center" width="260" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="预览" placement="top">
              <el-button
                v-hasPermi="['ai:kb:doc:preview']"
                link
                type="primary"
                icon="View"
                @click="handlePreview(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="解析（提取文本并重建切片）" placement="top">
              <el-button
                v-hasPermi="['ai:kb:doc:parse']"
                link
                type="warning"
                icon="MagicStick"
                :disabled="scope.row.parseStatus === '1'"
                @click="handleParse(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip :content="scope.row.vectorStatus === '2' ? '重建索引（重新向量化）' : '向量化（写入向量库）'" placement="top">
              <el-button
                v-hasPermi="['ai:kb:doc:vector']"
                link
                type="success"
                icon="Cpu"
                :disabled="scope.row.parseStatus !== '2' || scope.row.vectorStatus === '1'"
                @click="handleVectorize(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="下载原文件" placement="top">
              <el-button link type="primary" icon="Download" :disabled="!scope.row.fileUrl" @click="openFile(scope.row)"></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:kb:doc:remove']"
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

    <!-- 在线预览 -->
    <el-dialog v-model="preview.visible" :title="'预览：' + preview.docName" width="900px" append-to-body top="6vh">
      <el-tabs v-model="preview.tab">
        <el-tab-pane label="原始文件" name="raw" :disabled="!canNativePreview">
          <div class="preview-raw">
            <iframe v-if="isPdf" :src="preview.fileUrl" class="preview-frame"></iframe>
            <img v-else-if="isImage" :src="preview.fileUrl" class="preview-img" alt="预览" />
            <el-empty v-else description="该格式浏览器无法直接渲染，请查看「解析文本」或下载原文件" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="解析文本" name="text">
          <div v-loading="preview.loading" class="preview-text">
            <pre v-if="preview.content">{{ preview.content }}</pre>
            <el-empty v-else description="暂无解析文本，请先点「解析」" />
          </div>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="preview.visible = false">关 闭</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 切片查看 -->
    <el-dialog v-model="chunkDialog.visible" :title="'切片：' + chunkDialog.docName" width="800px" append-to-body top="8vh">
      <div v-loading="chunkDialog.loading" class="chunk-list">
        <div v-for="item in chunkDialog.list" :key="item.id" class="chunk-item">
          <div class="chunk-index">#{{ item.chunkIndex }}</div>
          <div class="chunk-body">
            <div v-if="item.headingPath || (item.pageNo !== undefined && item.pageNo !== null)" class="chunk-meta">
              <span v-if="item.headingPath">§{{ item.headingPath }}</span>
              <span v-if="item.pageNo !== undefined && item.pageNo !== null">p.{{ item.pageNo }}</span>
            </div>
            <div class="chunk-content">{{ item.content }}</div>
          </div>
        </div>
      </div>
      <pagination
        v-show="chunkDialog.total > 0"
        v-model:page="chunkDialog.query.pageNum"
        v-model:limit="chunkDialog.query.pageSize"
        :total="chunkDialog.total"
        @pagination="loadChunks"
      />
    </el-dialog>

    <!-- 批量处理进度（异步任务轮询回执） -->
    <el-drawer v-model="batchDrawer.visible" title="批量处理进度" size="520px" append-to-body>
      <div class="batch-summary">
        <span>{{ batchDrawer.label }}</span>
        <el-tag :type="batchDrawer.running ? 'warning' : 'success'" size="small">
          {{ batchDrawer.running ? '处理中' : '已完成' }}
        </el-tag>
      </div>
      <div class="batch-list">
        <div v-for="item in batchDrawer.items" :key="item.id" class="batch-item">
          <div class="batch-item__head">
            <span class="batch-item__name">{{ item.docName || '#' + item.id }}</span>
            <div class="batch-item__tags">
              <el-tag :type="statusType(item.parseStatus)" size="small">
                解析：{{ statusLabel(item.parseStatus) }}
              </el-tag>
              <el-tag :type="statusType(item.vectorStatus)" size="small">
                向量化：{{ vectorStatusLabel(item.vectorStatus) }}
              </el-tag>
            </div>
          </div>
          <div v-if="item.errorMsg" class="batch-item__error">解析失败：{{ item.errorMsg }}</div>
          <div v-if="item.vectorErrorMsg" class="batch-item__error">向量化失败：{{ item.vectorErrorMsg }}</div>
        </div>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="batchDrawer.visible = false">关 闭</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup name="AiKbDoc" lang="ts">
import {
  addDocument,
  batchIngestDocument,
  batchParseDocument,
  batchVectorizeDocument,
  delDocument,
  getBatchStatus,
  getDocumentContent,
  listDocument,
  listDocumentChunk,
  parseDocument,
  vectorizeDocument
} from '@/api/ai/kb/doc';
import type { ChunkVO, DocumentBatchStatusVO, DocumentBatchVO, DocumentQuery, DocumentVO } from '@/api/ai/kb/doc/types';
import { optionKnowledgeBase } from '@/api/ai/kb/base';
import type { KnowledgeBaseVO } from '@/api/ai/kb/base/types';
import { useLoading } from '@/hooks/async/useLoading';
import { useSearchReset } from '@/hooks/form/useSearchReset';
import { useSearchToggle } from '@/hooks/form/useSearchToggle';
import { useTableSelection } from '@/hooks/table/useTableSelection';
import modal from '@/plugins/modal';
import { checkPermi } from '@/utils/permission';
import { globalHeaders } from '@/utils/request';

const route = useRoute();

const STATUS_OPTIONS = [
  { label: '待解析', value: '0' },
  { label: '解析中', value: '1' },
  { label: '已解析', value: '2' },
  { label: '解析失败', value: '3' }
];

const VECTOR_STATUS_OPTIONS = [
  { label: '待向量化', value: '0' },
  { label: '向量化中', value: '1' },
  { label: '已向量化', value: '2' },
  { label: '向量化失败', value: '3' }
];

const ACCEPT_EXT = '.pdf,.docx,.xlsx,.pptx,.txt,.md,.csv,.json,.xml,.html,.htm,.yml,.yaml,.log,.sql,.properties';

/** 批量进度轮询间隔（毫秒） */
const BATCH_POLL_INTERVAL = 3000;
/** 单次批量提交上限（与后端 MAX_BATCH_SIZE 对齐，超出时分片提交） */
const BATCH_LIMIT = 50;

/**
 * 批量动作定义：命令 → 展示名 + 接口 + 完成判据。
 *
 * 完成判据必须按动作区分：批量解析只看解析状态，批量向量化只看向量化状态，
 * 一键入库则「解析失败」即终止，否则等向量化进入终态。
 */
const BATCH_ACTIONS = {
  ingest: {
    label: '一键入库',
    tip: '将逐文档解析，成功后自动向量化',
    run: batchIngestDocument,
    done: (item: DocumentBatchStatusVO) => item.parseStatus === '3' || ['2', '3'].includes(item.vectorStatus ?? '')
  },
  parse: {
    label: '批量解析',
    tip: '将重新提取文本并重建切片',
    run: batchParseDocument,
    done: (item: DocumentBatchStatusVO) => ['2', '3'].includes(item.parseStatus ?? '')
  },
  vectorize: {
    label: '批量向量化',
    tip: '将切片写入向量库，已向量化过的文档会重建索引',
    run: batchVectorizeDocument,
    done: (item: DocumentBatchStatusVO) => ['2', '3'].includes(item.vectorStatus ?? '')
  }
} as const;

type BatchCommand = keyof typeof BATCH_ACTIONS;

const statusOptions = STATUS_OPTIONS;
const vectorStatusOptions = VECTOR_STATUS_OPTIONS;
const uploadUrl = import.meta.env.VITE_APP_BASE_API + '/resource/oss/upload';
// 上传请求头在页面初始化时取一次即可，避免响应式对象每次求值都产生新引用
const uploadHeaders = globalHeaders();
const acceptExt = ACCEPT_EXT;

const docList = ref<DocumentVO[]>([]);
const kbOptions = ref<KnowledgeBaseVO[]>([]);
const { loading, withLoading } = useLoading(true);
const { showSearch } = useSearchToggle();
const total = ref(0);
const queryFormRef = ref<ElFormInstance>();
const docTableRef = ref();
/** 上传组件实例：多文件上传结束后统一清空内部文件列表，避免长期累积 */
const uploadRef = ref();
/** 是否具备「一键入库」所需的两项权限（缺少任一项就隐藏自动开关，避免上传后必然 403） */
const canIngest = computed(() => checkPermi(['ai:kb:doc:parse']) && checkPermi(['ai:kb:doc:vector']));
/** 上传后是否自动执行「解析 + 向量化」 */
const autoIngest = ref(canIngest.value);

const data = reactive<PageData<any, DocumentQuery>>({
  form: {},
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    kbId: undefined,
    docName: undefined,
    parseStatus: undefined,
    vectorStatus: undefined
  },
  rules: {}
});
const { queryParams } = toRefs(data);

const preview = reactive({
  visible: false,
  tab: 'text',
  docName: '',
  fileUrl: '',
  suffix: '',
  content: '',
  loading: false
});

const chunkDialog = reactive({
  visible: false,
  loading: false,
  docName: '',
  docId: '' as string | number,
  list: [] as ChunkVO[],
  total: 0,
  query: { pageNum: 1, pageSize: 5 }
});

/** 批量处理进度面板 */
const batchDrawer = reactive({
  visible: false,
  label: '',
  running: false,
  items: [] as DocumentBatchStatusVO[],
  command: 'ingest' as BatchCommand,
  targetIds: [] as Array<string | number>
});

/** 批量进度轮询定时器（单实例，切换批次时先清旧） */
let batchTimer: ReturnType<typeof setTimeout> | null = null;
/** 本次上传已登记、待提交自动入库的文档主键 */
const pendingUploadIds: Array<string | number> = [];
let uploadTimer: ReturnType<typeof setTimeout> | null = null;
/** 在途上传数：归零后统一清空 el-upload 内部列表 */
const uploadPending = ref(0);

const { ids, multiple, handleSelectionChange, clearSelection } = useTableSelection<DocumentVO>((item) => item.id);

const isPdf = computed(() => preview.suffix === 'pdf');
const isImage = computed(() => ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp'].includes(preview.suffix));
const canNativePreview = computed(() => isPdf.value || isImage.value);

/** 解析状态名称 */
const statusLabel = (value?: string) => statusOptions.find((item) => item.value === value)?.label ?? '-';

/** 向量化状态名称 */
const vectorStatusLabel = (value?: string) => vectorStatusOptions.find((item) => item.value === value)?.label ?? '-';

/** 解析状态标签样式 */
const statusType = (value?: string) => {
  switch (value) {
    case '2':
      return 'success';
    case '1':
      return 'warning';
    case '3':
      return 'danger';
    default:
      return 'info';
  }
};

/** 文件大小格式化 */
const formatSize = (size?: number) => {
  if (!size) {
    return '-';
  }
  if (size < 1024) {
    return size + ' B';
  }
  if (size < 1024 * 1024) {
    return (size / 1024).toFixed(1) + ' KB';
  }
  return (size / 1024 / 1024).toFixed(2) + ' MB';
};

/** 查询知识库下拉 */
const loadKbOptions = async () => {
  const res = await optionKnowledgeBase();
  kbOptions.value = res.data ?? [];
  const routeKbId = route.query.kbId as string | undefined;
  if (routeKbId) {
    queryParams.value.kbId = routeKbId;
  } else if (!queryParams.value.kbId && kbOptions.value.length === 1) {
    queryParams.value.kbId = kbOptions.value[0].id;
  }
};

/** 查询文档列表（未选知识库时查询全部：后端 kbId 为条件过滤，不传即全量） */
const getList = async () => {
  await withLoading(async () => {
    const res = await listDocument(queryParams.value);
    docList.value = res.data?.rows ?? [];
    total.value = res.data?.total ?? 0;
  });
};

/** 切换知识库 */
const handleKbChange = () => {
  queryParams.value.pageNum = 1;
  getList();
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
  afterReset: async () => {
    await loadKbOptions();
    handleQuery();
  }
});

/** 上传前校验（多文件时每个文件各触发一次） */
const handleBeforeUpload = () => {
  if (!queryParams.value.kbId) {
    modal.msgWarning('请先选择知识库');
    return false;
  }
  uploadPending.value += 1;
  return true;
};

/** 上传结束（成功 / 失败都调用）：在途归零后清空 el-upload 内部列表 */
const finishUpload = () => {
  uploadPending.value = Math.max(0, uploadPending.value - 1);
  if (!uploadPending.value) {
    uploadRef.value?.clearFiles();
  }
};

/** 上传失败 */
const handleUploadError = (error: Error) => {
  finishUpload();
  modal.msgError(error?.message ? `文件上传失败：${error.message}` : '文件上传失败');
};

/** 上传成功：登记文档；开启「自动入库」时按批提交流水线 */
const handleUploadSuccess = async (res: any, file: any) => {
  try {
    if (res.code !== 200) {
      modal.msgError(res.msg || '文件上传失败');
      return;
    }
    const oss = res.data ?? {};
    const originalName: string = oss.originalName || oss.fileName || file?.name || '未命名文档';
    const suffix = (oss.fileSuffix || originalName.split('.').pop() || '').toLowerCase();
    const added = await addDocument({
      kbId: queryParams.value.kbId,
      docName: originalName,
      fileSuffix: suffix,
      ossId: oss.ossId,
      fileSize: file?.size
    });
    const docId = added?.data;
    if (docId !== undefined && docId !== null) {
      queueUploaded(docId);
    }
  } catch (e) {
    // 登记失败（同名文档 / 文件不存在等）由请求层统一提示，这里只保证上传计数归零
  } finally {
    finishUpload();
  }
};

/**
 * 上传登记结果入队，并在「最后一次登记后」合并提交。
 *
 * 多文件上传是逐个回调的，逐个提交会把一个批次拆成 N 个请求；
 * 这里用 800ms 防抖把同一个选择批次压成一次「一键入库」。
 */
const queueUploaded = (docId: string | number) => {
  pendingUploadIds.push(docId);
  if (uploadTimer) {
    clearTimeout(uploadTimer);
  }
  uploadTimer = setTimeout(flushUploaded, 800);
};

/** 提交本批上传结果 */
const flushUploaded = async () => {
  uploadTimer = null;
  const targetIds = pendingUploadIds.splice(0, pendingUploadIds.length);
  if (!targetIds.length) {
    return;
  }
  if (autoIngest.value) {
    await submitBatch('ingest', targetIds);
  } else {
    modal.msgSuccess(`已登记 ${targetIds.length} 个文档，请点击「解析」提取文本与切片`);
    await getList();
  }
};

/** 批量操作入口 */
const handleBatchCommand = async (command: BatchCommand) => {
  if (!ids.value.length) {
    return;
  }
  const action = BATCH_ACTIONS[command];
  try {
    await modal.confirm(`已选 ${ids.value.length} 个文档，确认执行「${action.label}」？${action.tip}。`);
  } catch {
    return;
  }
  await submitBatch(command, [...ids.value]);
};

/**
 * 提交批量任务并打开进度面板。
 *
 * @param command   批量动作
 * @param targetIds 文档主键
 */
const submitBatch = async (command: BatchCommand, targetIds: Array<string | number>) => {
  const action = BATCH_ACTIONS[command];
  const accepted: Array<string | number> = [];
  const skipped: DocumentBatchVO['skipped'] = [];
  // 后端单次上限 50：超出时分片提交，避免整批被拒
  for (let offset = 0; offset < targetIds.length; offset += BATCH_LIMIT) {
    const segment = targetIds.slice(offset, offset + BATCH_LIMIT);
    const res = await action.run(segment);
    accepted.push(...(res.data?.accepted ?? []));
    skipped.push(...(res.data?.skipped ?? []));
  }
  if (accepted.length) {
    openBatchDrawer(command, accepted);
    modal.msgSuccess(`「${action.label}」已受理 ${accepted.length} 个文档，进度见右侧面板`);
  }
  if (skipped.length) {
    const preview = skipped
      .slice(0, 3)
      .map((item) => `${item.docName || '#' + item.id}：${item.reason}`)
      .join('；');
    modal.msgWarning(`已跳过 ${skipped.length} 个文档（${preview}${skipped.length > 3 ? ' 等' : ''}）`);
  }
  if (!accepted.length && !skipped.length) {
    modal.msgWarning('没有可处理的文档');
  }
  await getList();
};

/** 打开进度面板并开始轮询 */
const openBatchDrawer = (command: BatchCommand, targetIds: Array<string | number>) => {
  batchDrawer.command = command;
  batchDrawer.label = BATCH_ACTIONS[command].label;
  batchDrawer.targetIds = targetIds;
  batchDrawer.items = targetIds.map((id) => ({ id }) as DocumentBatchStatusVO);
  batchDrawer.running = true;
  batchDrawer.visible = true;
  scheduleBatchPoll();
};

/** 轮询批量进度（setTimeout 链，避免请求重叠） */
const scheduleBatchPoll = async () => {
  if (batchTimer) {
    clearTimeout(batchTimer);
    batchTimer = null;
  }
  try {
    const res = await getBatchStatus(batchDrawer.targetIds);
    batchDrawer.items = res.data ?? batchDrawer.items;
  } catch {
    // 单次轮询失败不打断：保持当前面板，下一轮继续
  }
  const action = BATCH_ACTIONS[batchDrawer.command];
  batchDrawer.running = !batchDrawer.items.every(action.done);
  if (batchDrawer.running) {
    batchTimer = setTimeout(scheduleBatchPoll, BATCH_POLL_INTERVAL);
    return;
  }
  // 全部进入终态：刷新列表并清空选择，避免「已完成」的文档仍处于勾选态
  await getList();
  docTableRef.value?.clearSelection();
  clearSelection();
};

/** 页面卸载时清理定时器（避免离开后仍持续请求） */
onBeforeUnmount(() => {
  if (batchTimer) {
    clearTimeout(batchTimer);
    batchTimer = null;
  }
  if (uploadTimer) {
    clearTimeout(uploadTimer);
    uploadTimer = null;
  }
});

/** 解析文档 */
const handleParse = async (row: DocumentVO) => {
  modal.loading('正在解析文档，请稍候...');
  try {
    await parseDocument(row.id);
    modal.closeLoading();
    modal.msgSuccess('解析完成，可继续执行「向量化」');
  } catch (e) {
    modal.closeLoading();
  }
  await getList();
};

/** 向量化文档（已向量化时为重建索引） */
const handleVectorize = async (row: DocumentVO) => {
  const rebuild = row.vectorStatus === '2';
  if (rebuild) {
    await modal.confirm(`「${row.docName}」已向量化，是否重建索引？（将重新生成并覆盖其切片向量）`);
  }
  modal.loading('正在向量化文档，请稍候...');
  try {
    await vectorizeDocument(row.id);
    modal.closeLoading();
    modal.msgSuccess(rebuild ? '索引已重建' : '向量化完成，可到「检索测试」验证召回');
  } catch (e) {
    modal.closeLoading();
  }
  await getList();
};

/** 预览 */
const handlePreview = async (row: DocumentVO) => {
  preview.docName = row.docName;
  preview.fileUrl = row.fileUrl || '';
  preview.suffix = (row.fileSuffix || '').toLowerCase();
  preview.content = '';
  preview.tab = canNativePreview.value || !row.charCount ? (canNativePreview.value ? 'raw' : 'text') : 'text';
  preview.visible = true;
  preview.loading = true;
  try {
    const res = await getDocumentContent(row.id);
    preview.content = res.data ?? '';
  } catch (e) {
    preview.content = '';
  } finally {
    preview.loading = false;
  }
};

/** 新窗口打开原文件 */
const openFile = (row: DocumentVO) => {
  if (row.fileUrl) {
    window.open(row.fileUrl, '_blank');
  }
};

/** 查看切片 */
const handleChunks = async (row: DocumentVO) => {
  chunkDialog.docId = row.id;
  chunkDialog.docName = row.docName;
  chunkDialog.query.pageNum = 1;
  chunkDialog.visible = true;
  await loadChunks();
};

/** 加载切片分页 */
const loadChunks = async () => {
  chunkDialog.loading = true;
  try {
    const res = await listDocumentChunk(chunkDialog.docId, chunkDialog.query);
    chunkDialog.list = res.data?.rows ?? [];
    chunkDialog.total = res.data?.total ?? 0;
  } finally {
    chunkDialog.loading = false;
  }
};

/** 删除按钮操作 */
const handleDelete = async (row?: Partial<DocumentVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm('是否确认删除选中的文档？删除后其切片一并清除。');
  await delDocument(target);
  modal.msgSuccess('删除成功');
  await getList();
};

onMounted(async () => {
  await loadKbOptions();
  await getList();
});
</script>

<style scoped lang="scss">
.table-heading p {
  margin: 6px 0 0;
  color: var(--app-text-muted);
  font-size: 13px;
}

.preview-raw,
.preview-text {
  min-height: 240px;
  max-height: 62vh;
  overflow: auto;
  border-radius: 12px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);
}

.preview-frame {
  width: 100%;
  height: 62vh;
  border: 0;
}

.preview-img {
  display: block;
  max-width: 100%;
  margin: 0 auto;
}

.preview-text pre {
  margin: 0;
  padding: 16px;
  color: var(--app-text-body, #333);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.chunk-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 60vh;
  overflow: auto;
}

.chunk-item {
  display: flex;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);
}

.chunk-index {
  flex: none;
  color: var(--app-accent-strong);
  font-weight: 700;
  font-size: 13px;
}

.chunk-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
  min-width: 0;
}

.chunk-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: var(--app-accent-strong);
  font-size: 12px;
}

.chunk-content {
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.auto-ingest-switch {
  margin-right: 4px;
}

.batch-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  color: var(--app-text-muted);
  font-size: 13px;
}

.batch-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: calc(100vh - 180px);
  overflow: auto;
}

.batch-item {
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);
}

.batch-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.batch-item__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
}

.batch-item__tags {
  display: flex;
  flex: none;
  gap: 6px;
}

.batch-item__error {
  margin-top: 6px;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}
</style>