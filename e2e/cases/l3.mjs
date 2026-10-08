/**
 * L3 知识库链路：文档解析（按对象键从 OSS 取源文件）→ 向量化 → 检索命中。
 *
 * <p>**解析必须在前**：演示文档在种子中只写了 `content`（`parse_status='2'`）但**没有切片**
 * （`chunk_count=0`），直接向量化会报「文档切片为空，请先重新解析文档」。先解析还能顺带验证
 * 「按对象键从 OSS 读取源文件」这条链路（服务端内部地址）。</p>
 *
 * <p>`parse` / `vectorize` 均为**同步**返回（直接给 `AiDocumentVo`），无需轮询。</p>
 */

export const meta = { layer: 'L3', name: '知识库解析、向量化与检索命中', needsKey: true };

/** 向量化完成态（AiVectorServiceImpl.STATUS_DONE） */
const VECTOR_DONE = '2';

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, resolved } = ctx;
  if (!resolved.platformEmbeddingKeyReady) {
    return { status: 'SKIP', detail: '平台未为向量模型配置 API 密钥（向量化需要 embedding 密钥）' };
  }

  const parsed = await http.request('POST', `/ai/kb/doc/${resolved.demoDocId}/parse`, { timeoutMs: 180000 });
  const evidence = {
    docName: resolved.demoDocName,
    parseStatus: parsed?.parseStatus,
    chunkCount: parsed?.chunkCount,
    charCount: parsed?.charCount
  };
  if (!parsed?.chunkCount || parsed.chunkCount <= 0) {
    return {
      status: 'FAIL',
      detail: `解析后切片仍为空（chunkCount=${parsed?.chunkCount ?? '未知'}）——通常是源文件在 OSS 中不可读`,
      evidence
    };
  }

  const doc = await http.request('POST', `/ai/kb/doc/${resolved.demoDocId}/vectorize`, { timeoutMs: 180000 });
  evidence.vectorStatus = doc?.vectorStatus;
  evidence.vectorErrorMsg = doc?.vectorErrorMsg;
  if (doc?.vectorStatus !== VECTOR_DONE) {
    return {
      status: 'FAIL',
      detail: `向量化未完成（vectorStatus=${doc?.vectorStatus ?? '未知'}）${doc?.vectorErrorMsg ? `：${doc.vectorErrorMsg}` : ''}`,
      evidence
    };
  }

  const hits = await http.request('GET', '/ai/kb/retrieval/test', {
    timeoutMs: 60000,
    query: { kbId: resolved.demoKbId, query: 'BizBuddy 是什么', topK: 5, threshold: 0.3 }
  });
  evidence.hitCount = Array.isArray(hits) ? hits.length : 0;
  evidence.topHit = Array.isArray(hits) && hits[0] ? { score: hits[0].score, preview: String(hits[0].content ?? '').slice(0, 80) } : null;

  if (!Array.isArray(hits) || hits.length === 0) {
    return { status: 'FAIL', detail: '检索无命中（返回空数组）——请确认文档已向量化且相似度阈值合适', evidence };
  }

  return {
    status: 'PASS',
    detail: `解析出 ${evidence.chunkCount} 切片 → 向量化完成 → 检索命中 ${evidence.hitCount} 条（最高分 ${evidence.topHit?.score ?? '-'}）`,
    evidence
  };
};
