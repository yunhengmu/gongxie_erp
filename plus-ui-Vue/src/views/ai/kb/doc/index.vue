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
            <el-select v-model="queryParams.kbId" placeholder="请选择知识库" style="width: 240px" @change="handleKbChange">
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
            <p>支持 PDF / docx / xlsx / pptx 与常见文本格式；上传后先「解析」提取文本并切片，再「向量化」写入向量库。</p>
          </div>
          <div class="toolbar-actions">
            <el-upload
              ref="uploadRef"
              :action="uploadUrl"
              :headers="uploadHeaders"
              :limit="1"
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

      <el-table v-loading="loading" border class="data-table" :data="docList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
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
          <div class="chunk-content">{{ item.content }}</div>
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
  </div>
</template>

<script setup name="AiKbDoc" lang="ts">
import {
  addDocument,
  delDocument,
  getDocumentContent,
  listDocument,
  listDocumentChunk,
  parseDocument,
  vectorizeDocument
} from '@/api/ai/kb/doc';
import type { ChunkVO, DocumentQuery, DocumentVO } from '@/api/ai/kb/doc/types';
import { optionKnowledgeBase } from '@/api/ai/kb/base';
import type { KnowledgeBaseVO } from '@/api/ai/kb/base/types';
import { useLoading } from '@/hooks/async/useLoading';
import { useSearchReset } from '@/hooks/form/useSearchReset';
import { useSearchToggle } from '@/hooks/form/useSearchToggle';
import { useTableSelection } from '@/hooks/table/useTableSelection';
import modal from '@/plugins/modal';
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

const { ids, multiple, handleSelectionChange } = useTableSelection<DocumentVO>((item) => item.id);

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

/** 查询文档列表 */
const getList = async () => {
  if (!queryParams.value.kbId) {
    docList.value = [];
    total.value = 0;
    return;
  }
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

/** 上传前校验 */
const handleBeforeUpload = () => {
  if (!queryParams.value.kbId) {
    modal.msgWarning('请先选择知识库');
    return false;
  }
  modal.loading('正在上传文件，请稍候...');
  return true;
};

/** 上传失败 */
const handleUploadError = () => {
  modal.closeLoading();
  modal.msgError('文件上传失败');
};

/** 上传成功：登记文档并提示解析 */
const handleUploadSuccess = async (res: any, file: any) => {
  modal.closeLoading();
  if (res.code !== 200) {
    modal.msgError(res.msg || '文件上传失败');
    return;
  }
  const oss = res.data;
  const originalName: string = oss.originalName || oss.fileName || file?.name || '未命名文档';
  const suffix = (oss.fileSuffix || originalName.split('.').pop() || '').toLowerCase();
  await addDocument({
    kbId: queryParams.value.kbId,
    docName: originalName,
    fileSuffix: suffix,
    ossId: oss.ossId,
    fileSize: file?.size
  });
  modal.msgSuccess('上传成功，请点击「解析」提取文本与切片');
  await getList();
};

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

.chunk-content {
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>