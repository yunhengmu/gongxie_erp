/**
 * L3 知识库生命周期（原 L3 + L14 合并）：单文档解析 → 批量解析/状态轮询 →
 * 批量向量化 → 单文档向量化 → 检索命中 → 重排字段透出。
 *
 * <p>合并理由：两层操作同一演示库/演示文档，重复的后台准备与轮询可复用；
 * 判定仍按「解析 → 向量化 → 检索」分段返回，失败原因不混淆。</p>
 *
 * <p>**解析必须在前**：演示文档种子只写 `content`、没有切片，直接向量化会报
 * 「文档切片为空」；解析同时验证「按对象键从 OSS 取源文件」。</p>
 */

export const meta = { layer: 'L3', name: '知识库生命周期（解析 / 批量 / 向量化 / 检索）', needsKey: true };

/** 向量化完成态（AiVectorServiceImpl.STATUS_DONE） */
const VECTOR_DONE = '2';

/** 解析/向量化终态：2 完成 / 3 失败 */
const TERMINAL = ['2', '3'];

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, resolved } = ctx;
  if (!resolved.platformEmbeddingKeyReady) {
    return { status: 'SKIP', detail: '平台未为向量模型配置 API 密钥（解析/向量化需要 embedding 密钥）' };
  }

  const docId = resolved.demoDocId;
  const missingId = 999999999;
  const evidence = { docName: resolved.demoDocName, docId };

  // ① 单文档解析（同步）：验证 OSS 取源文件 + 切片
  const parsed = await http.request('POST', `/ai/kb/doc/${docId}/parse`, { timeoutMs: 180000 });
  evidence.chunkCount = parsed?.chunkCount;
  evidence.charCount = parsed?.charCount;
  if (!parsed?.chunkCount || parsed.chunkCount <= 0) {
    return {
      status: 'FAIL',
      detail: `解析后切片仍为空（chunkCount=${parsed?.chunkCount ?? '未知'}）——通常是源文件在 OSS 中不可读`,
      evidence
    };
  }

  // ② 批量解析：受理演示文档 + 不存在文档必须归入跳过，并轮询到终态
  const submitted = await http.request('POST', '/ai/kb/doc/batch/parse', {
    body: { ids: [docId, missingId] }
  });
  evidence.accepted = (submitted?.accepted ?? []).map(String);
  evidence.skipped = (submitted?.skipped ?? []).map((item) => ({ id: item.id, reason: item.reason }));
  if (!evidence.accepted.includes(String(docId))) {
    return { status: 'FAIL', detail: '批量解析未受理演示文档', evidence };
  }
  if (!evidence.skipped.some((item) => String(item.id) === String(missingId))) {
    return { status: 'FAIL', detail: '不存在的文档未归入跳过明细', evidence };
  }
  const batchParsed = await pollStatus(http, [docId], (item) => TERMINAL.includes(item.parseStatus), 120_000);
  evidence.batchParseStatus = batchParsed?.parseStatus;
  if (batchParsed?.parseStatus !== '2') {
    return {
      status: 'FAIL',
      detail: `批量解析未完成（parseStatus=${batchParsed?.parseStatus ?? '未知'}，${batchParsed?.errorMsg ?? '无错误信息'}）`,
      evidence
    };
  }

  // ③ 批量向量化 + 轮询
  const vectorSubmitted = await http.request('POST', '/ai/kb/doc/batch/vectorize', { body: { ids: [docId] } });
  evidence.vectorAccepted = (vectorSubmitted?.accepted ?? []).map(String);
  if (!evidence.vectorAccepted.includes(String(docId))) {
    return {
      status: 'FAIL',
      detail: `批量向量化未受理演示文档（跳过：${(vectorSubmitted?.skipped ?? []).map((i) => i.reason).join('；') || '无'}）`,
      evidence
    };
  }
  const vectorized = await pollStatus(http, [docId], (item) => TERMINAL.includes(item.vectorStatus), 300_000);
  evidence.vectorStatus = vectorized?.vectorStatus;
  evidence.vectorError = vectorized?.vectorErrorMsg;
  if (vectorized?.vectorStatus !== '2') {
    return {
      status: 'FAIL',
      detail: `批量向量化未完成（vectorStatus=${vectorized?.vectorStatus ?? '未知'}，${vectorized?.vectorErrorMsg ?? '无错误信息'}）`,
      evidence
    };
  }

  // ④ 单文档向量化（同步接口仍须可用；已向量化时返回当前状态）
  const singleVector = await http.request('POST', `/ai/kb/doc/${docId}/vectorize`, { timeoutMs: 180000 });
  evidence.singleVectorStatus = singleVector?.vectorStatus;
  if (singleVector?.vectorStatus !== VECTOR_DONE) {
    return {
      status: 'FAIL',
      detail: `单文档向量化接口未返回完成态（vectorStatus=${singleVector?.vectorStatus ?? '未知'}）`,
      evidence
    };
  }

  // ⑤ 检索命中
  const hits = await http.request('GET', '/ai/kb/retrieval/test', {
    timeoutMs: 60000,
    query: { kbId: resolved.demoKbId, query: 'BizBuddy 是什么', topK: 5, threshold: 0.3 }
  });
  evidence.hitCount = Array.isArray(hits) ? hits.length : 0;
  evidence.topHit = Array.isArray(hits) && hits[0]
    ? { score: hits[0].score, preview: String(hits[0].content ?? '').slice(0, 80) }
    : null;
  if (!Array.isArray(hits) || hits.length === 0) {
    return { status: 'FAIL', detail: '检索无命中（返回空数组）——请确认文档已向量化且相似度阈值合适', evidence };
  }

  // ⑥ 知识库级重排字段装配（含存量库迁移核验）
  const kbList = await http.request('GET', '/ai/kb/list', { query: { pageNum: 1, pageSize: 50 } });
  const demoKb = (kbList?.rows ?? []).find((row) => String(row.id) === String(resolved.demoKbId));
  evidence.rerankEnabled = demoKb?.rerankEnabled;
  if (!demoKb || demoKb.rerankEnabled === undefined) {
    return {
      status: 'FAIL',
      detail: '知识库接口未透出 rerankEnabled 字段——请确认两方言建表已加列、且存量库已执行 ALTER',
      evidence
    };
  }
  evidence.retrievalMode = demoKb.retrievalMode;
  if (demoKb.retrievalMode === undefined || demoKb.bm25IndexStatus === undefined) {
    return {
      status: 'FAIL',
      detail: '知识库接口未透出 retrievalMode / bm25IndexStatus 字段——请确认两方言建表已加列、且存量库已执行 ALTER',
      evidence
    };
  }

  // ⑦ 混合检索：切换为「向量 + BM25」→ 等待 BM25 索引就绪 → 检索命中 → 还原为向量
  const kbDetail = await http.request('GET', `/ai/kb/${resolved.demoKbId}`);
  const updateBody = { ...kbDetail, retrievalMode: 'hybrid' };
  await http.request('PUT', '/ai/kb', { body: updateBody });
  const bm25Ready = await pollKb(http, resolved.demoKbId, (row) => row.bm25IndexStatus === 'ready', 180_000);
  evidence.hybridStatus = bm25Ready?.bm25IndexStatus;
  if (bm25Ready?.bm25IndexStatus !== 'ready') {
    await restoreVectorMode(http, updateBody);
    return {
      status: 'FAIL',
      detail: `切换混合检索后 BM25 索引未就绪（bm25IndexStatus=${bm25Ready?.bm25IndexStatus ?? '未知'}）`,
      evidence
    };
  }
  const hybridHits = await http.request('GET', '/ai/kb/retrieval/test', {
    timeoutMs: 60000,
    query: { kbId: resolved.demoKbId, query: 'BizBuddy 是什么', topK: 5, threshold: 0.3 }
  });
  evidence.hybridHitCount = Array.isArray(hybridHits) ? hybridHits.length : 0;
  evidence.hybridScore = hybridHits?.[0]?.score;
  await restoreVectorMode(http, updateBody);
  if (!Array.isArray(hybridHits) || hybridHits.length === 0) {
    return { status: 'FAIL', detail: '混合检索无命中（BM25 已就绪仍返回空数组）', evidence };
  }

  return {
    status: 'PASS',
    detail: `解析 ${evidence.chunkCount} 切片 → 批量解析/向量化完成 → 检索命中 ${evidence.hitCount} 条`
      + `（最高分 ${evidence.topHit?.score ?? '-'}）；混合检索命中 ${evidence.hybridHitCount} 条；`
      + `rerankEnabled=${evidence.rerankEnabled}`,
    evidence
  };
};

/**
 * 轮询知识库详情，直到满足完成判据。
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {string|number} kbId 知识库主键
 * @param {(row: object) => boolean} done 完成判据
 * @param {number} timeoutMs 超时
 * @returns {Promise<object|null>} 最后一次观测到的知识库
 */
const pollKb = async (http, kbId, done, timeoutMs) => {
  const deadline = Date.now() + timeoutMs;
  let latest = null;
  for (;;) {
    latest = await http.request('GET', `/ai/kb/${kbId}`).catch(() => null);
    if (latest && done(latest)) {
      return latest;
    }
    if (Date.now() >= deadline) {
      return latest;
    }
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
};

/**
 * 还原知识库为纯向量检索（避免污染后续用例）。
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {object} updateBody 切换混合检索时的知识库请求体
 */
const restoreVectorMode = async (http, updateBody) => {
  await http.request('PUT', '/ai/kb', { body: { ...updateBody, retrievalMode: 'vector' } }).catch(() => undefined);
};

/**
 * 轮询批量进度直到目标项满足完成判据。
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {Array<string|number>} ids 文档主键
 * @param {(item: object) => boolean} done 完成判据
 * @param {number} timeoutMs 超时
 * @returns {Promise<object|null>} 最后一次观测到的进度
 */
const pollStatus = async (http, ids, done, timeoutMs) => {
  const deadline = Date.now() + timeoutMs;
  let latest = null;
  for (;;) {
    const rows = (await http.request('GET', '/ai/kb/doc/batch/status', {
      query: { ids: ids.join(',') }
    })) ?? [];
    latest = rows.find((item) => String(item.id) === String(ids[0])) ?? null;
    if (latest && done(latest)) {
      return latest;
    }
    if (Date.now() >= deadline) {
      return latest;
    }
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
};
