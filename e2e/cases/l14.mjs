/**
 * L14：知识库文档批量链路（批量解析 → 状态轮询 → 批量向量化）。
 *
 * <p>覆盖新增的异步批量接口：
 * ① `POST /ai/kb/doc/batch/parse` 立即返回「受理 + 跳过」回执（不存在的文档必须归入跳过）；
 * ② `GET /ai/kb/doc/batch/status` 能按文档主键拿到处理进度，直到解析进入终态；
 * ③ `POST /ai/kb/doc/batch/vectorize` 受理已解析文档，并最终落到「已向量化」。</p>
 *
 * <p><b>SKIP 口径</b>：演示文档不可用、或平台未配向量模型密钥时 SKIP 而非 FAIL——
 * 该层要真正跑完向量化，缺密钥属于运行期前置未满足。</p>
 */

export const meta = { layer: 'L14', name: '知识库批量解析与向量化', needsKey: true };

/** 解析/向量化终态：2 完成 / 3 失败 */
const TERMINAL = ['2', '3'];

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, resolved } = ctx;
  if (!resolved.demoDocId) {
    return { status: 'SKIP', detail: '未解析到演示文档（批量链路没有可操作对象）' };
  }
  if (!resolved.platformEmbeddingKeyReady) {
    return { status: 'SKIP', detail: '平台未为向量模型配置 API 密钥（批量向量化需要 embedding 密钥）' };
  }

  const docId = resolved.demoDocId;
  const missingId = 999999999;
  const evidence = { docName: resolved.demoDocName, docId };

  // ① 批量解析：受理演示文档，并把不存在的文档归入跳过
  const submitted = await http.request('POST', '/ai/kb/doc/batch/parse', {
    body: { ids: [docId, missingId] }
  });
  const accepted = (submitted?.accepted ?? []).map(String);
  const skipped = submitted?.skipped ?? [];
  evidence.accepted = accepted;
  evidence.skipped = skipped.map((item) => ({ id: item.id, reason: item.reason }));
  if (!accepted.includes(String(docId))) {
    return { status: 'FAIL', detail: '批量解析未受理演示文档', evidence };
  }
  if (!skipped.some((item) => String(item.id) === String(missingId))) {
    return { status: 'FAIL', detail: '不存在的文档未归入跳过明细', evidence };
  }

  // ② 轮询解析进度
  const parsed = await pollStatus(http, [docId], (item) => TERMINAL.includes(item.parseStatus), 120_000);
  evidence.parseStatus = parsed?.parseStatus;
  evidence.parseError = parsed?.errorMsg;
  if (parsed?.parseStatus !== '2') {
    return {
      status: 'FAIL',
      detail: `批量解析未完成（parseStatus=${parsed?.parseStatus ?? '未知'}，${parsed?.errorMsg ?? '无错误信息'}）`,
      evidence
    };
  }

  // ③ 批量向量化 + 轮询
  const vectorSubmitted = await http.request('POST', '/ai/kb/doc/batch/vectorize', {
    body: { ids: [docId] }
  });
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

  return {
    status: 'PASS',
    detail: `批量解析受理 ${evidence.accepted.length} 个、跳过 ${evidence.skipped.length} 个；`
      + `解析 → 向量化均进入完成态（chunk 链路可用）`,
    evidence
  };
};

/**
 * 轮询批量进度直到目标项满足完成判据。
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {Array<string|number>} ids 文档主键
 * @param {(item: object) => boolean} done 完成判据
 * @param {number} timeoutMs 超时
 * @returns {Promise<object|null>} 最后一次观测到的进度；超时返回最后一次结果
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
