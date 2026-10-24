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
          <el-form-item label="敏感词" prop="word">
            <el-input
              v-model="queryParams.word"
              placeholder="敏感词 / 正则"
              clearable
              style="width: 220px"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item label="分类" prop="category">
            <el-input v-model="queryParams.category" placeholder="分类" clearable style="width: 180px" @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="状态" prop="enabled">
            <el-select v-model="queryParams.enabled" placeholder="全部" clearable style="width: 140px">
              <el-option label="启用" value="1" />
              <el-option label="禁用" value="0" />
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
            <h3>敏感词库</h3>
            <p>
              本地引擎的敏感词与正则规则；命中后按「安全合规配置」中的动作记录 / 脱敏 / 阻断。
              正则词以 <code class="inline-code">isRegex</code> 标记，匹配失败会跳过并记录。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:safety:word:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button
              v-hasPermi="['ai:safety:word:edit']"
              type="success"
              plain
              icon="Edit"
              :disabled="single"
              @click="handleUpdate()"
            >
              修改
            </el-button>
            <el-button
              v-hasPermi="['ai:safety:word:remove']"
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

      <el-table v-loading="loading" border class="data-table" :data="wordList" @selection-change="handleSelectionChange">
        <template #empty>
          <el-empty description="暂无敏感词数据" :image-size="80" />
        </template>
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="敏感词" align="left" prop="word" :show-overflow-tooltip="true" />
        <el-table-column label="是否正则" align="center" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.isRegex === '1' ? 'warning' : 'info'" size="small" effect="plain">
              {{ scope.row.isRegex === '1' ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" align="center" prop="category" width="140" :show-overflow-tooltip="true" />
        <el-table-column label="启用" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.enabled === '1' ? 'success' : 'info'">
              {{ scope.row.enabled === '1' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备注" align="left" prop="remark" :show-overflow-tooltip="true" />
        <el-table-column label="创建时间" align="center" prop="createTime" width="170" />
        <el-table-column label="操作" align="center" width="130" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="修改" placement="top">
              <el-button
                v-hasPermi="['ai:safety:word:edit']"
                link
                type="primary"
                icon="Edit"
                @click="handleUpdate(scope.row)"
              ></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-hasPermi="['ai:safety:word:remove']"
                link
                type="danger"
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

    <!-- 敏感词表单 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="620px" append-to-body>
      <el-form ref="wordFormRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="敏感词" prop="word">
          <el-input v-model="form.word" :placeholder="form.isRegex === '1' ? '请输入正则表达式' : '请输入敏感词'" />
        </el-form-item>
        <el-form-item label="是否正则" prop="isRegex">
          <el-radio-group v-model="form.isRegex">
            <el-radio value="0">否</el-radio>
            <el-radio value="1">是</el-radio>
          </el-radio-group>
          <span class="form-tip">选「是」时，敏感词按正则表达式匹配</span>
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-input v-model="form.category" placeholder="如：政治敏感 / 广告 / 辱骂" />
        </el-form-item>
        <el-form-item label="状态" prop="enabled">
          <el-radio-group v-model="form.enabled">
            <el-radio value="1">启用</el-radio>
            <el-radio value="0">禁用</el-radio>
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
  </div>
</template>

<script setup name="AiSafetyWord" lang="ts">
import { addWord, delWord, listWord, queryWord, updateWord } from '@/api/ai/safety';
import type { SafetyWordForm, SafetyWordQuery, SafetyWordVO } from '@/api/ai/safety/types';
import modal from '@/plugins/modal';

const wordList = ref<SafetyWordVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const buttonLoading = ref(false);
const queryFormRef = ref<ElFormInstance>();
const wordFormRef = ref<ElFormInstance>();

const queryParams = ref<SafetyWordQuery>({
  pageNum: 1,
  pageSize: 10,
  word: undefined,
  category: undefined,
  enabled: undefined
});

const initFormData: SafetyWordForm = {
  id: undefined,
  word: undefined,
  isRegex: '0',
  category: undefined,
  enabled: '1',
  remark: undefined
};

const dialog = reactive<{ visible: boolean; title: string }>({
  visible: false,
  title: ''
});

const form = ref<SafetyWordForm>({ ...initFormData });

const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);

const rules: ElFormRules = {
  word: [{ required: true, message: '敏感词不能为空', trigger: 'blur' }],
  isRegex: [{ required: true, message: '请选择是否正则', trigger: 'change' }],
  enabled: [{ required: true, message: '请选择状态', trigger: 'change' }]
};

/** 查询敏感词列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listWord(queryParams.value);
    wordList.value = res.data?.rows ?? [];
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
  queryParams.value.word = undefined;
  queryParams.value.category = undefined;
  queryParams.value.enabled = undefined;
  queryParams.value.pageNum = 1;
  getList();
};

const handleSelectionChange = (selection: SafetyWordVO[]) => {
  ids.value = selection.map((item) => item.id as string | number);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};

/** 重置表单为初始值 */
const reset = () => {
  form.value = { ...initFormData };
  wordFormRef.value?.resetFields();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.title = '新增敏感词';
  dialog.visible = true;
};

/** 修改按钮操作 */
const handleUpdate = async (row?: Partial<SafetyWordVO>) => {
  reset();
  const id = row?.id ?? ids.value[0];
  if (id === undefined || id === null) {
    return;
  }
  const res = await queryWord(id);
  const detail = res.data;
  form.value = {
    id: detail.id,
    word: detail.word,
    isRegex: detail.isRegex ?? '0',
    category: detail.category,
    enabled: detail.enabled ?? '1',
    remark: detail.remark
  };
  dialog.title = '修改敏感词';
  dialog.visible = true;
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = () => {
  wordFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    buttonLoading.value = true;
    try {
      const payload: SafetyWordForm = { ...form.value };
      if (payload.id) {
        await updateWord(payload);
        modal.msgSuccess('修改成功');
      } else {
        delete payload.id;
        await addWord(payload);
        modal.msgSuccess('新增成功');
      }
      dialog.visible = false;
      await getList();
    } finally {
      buttonLoading.value = false;
    }
  });
};

/** 删除按钮操作 */
const handleDelete = async (row?: Partial<SafetyWordVO>) => {
  const target = row?.id ?? ids.value;
  await modal.confirm('是否确认删除选中的敏感词？删除后本地引擎将不再命中该规则。');
  await delWord(target);
  modal.msgSuccess('删除成功');
  await getList();
};

getList();
</script>

<style scoped lang="scss">
.inline-code {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
  font-size: 12px;
}

.form-tip {
  margin-left: 10px;
  color: var(--app-text-muted);
  font-size: 12px;
}
</style>