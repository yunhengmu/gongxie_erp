#!/usr/bin/env node
/**
 * BizBuddy RAG 检索质量评测脚本（外部驱动）。
 *
 * 对**已运行**的容器栈发真实 HTTP 请求，自建一个评测知识库（编码 `e2e_rag_eval`）：
 * 上传语料 → 登记文档 → 解析 → 向量化 → 按用例集逐条检索 → 统计 hit@k / recall@5 / MRR@5 等指标。
 * 用于后续 RAG 增强的**前后对比基线**。不参与 `mvn test`、不改任何产品代码。
 *
 * <p>用法：
 * <pre>
 *   node e2e/http/eval/eval-rag.mjs                                  # 库已存在则复用，跑完保留
 *   node e2e/http/eval/eval-rag.mjs --rebuild                        # 先删旧评测库再重建
 *   node e2e/http/eval/eval-rag.mjs --corpus d:/some/md --top-k 5    # 指定语料目录与召回条数
 *   node e2e/http/eval/eval-rag.mjs --rewrite                        # 【已失效】P0-② 已回退，仅兼容旧命令，无实际作用
 *   node e2e/http/eval/eval-rag.mjs --clean                          # 跑完删除评测库及其文档
 * </pre></p>
 *
 * <p>接口依据（源码实测）：
 * - 建库 `POST /ai/kb`（body 见 `AiKnowledgeBaseBo`）；列表 `GET /ai/kb/list`；
 * - 上传 `POST /resource/oss/upload`（multipart 字段名 `file`，返回 `{url,fileName,ossId}`）；
 * - 登记 `POST /ai/kb/doc`（body 见 `AiDocumentBo`，`ossId` 必填）；解析 `POST /ai/kb/doc/{id}/parse`；
 *   向量化 `POST /ai/kb/doc/{id}/vectorize`；删除 `DELETE /ai/kb/doc/{ids}`；
 * - 检索 `GET /ai/kb/retrieval/test?kbId&query&topK&threshold`（返回含 `content/score/docName` 的数组）。</p>
 */

import fs from 'node:fs';
import path from 'node:path';
import url from 'node:url';

import { loadConfig } from '../lib/env.mjs';
import { HttpClient, BizError } from '../lib/http.mjs';

const here = path.dirname(url.fileURLToPath(import.meta.url));
const e2eDir = path.resolve(here, '..');
const repoRoot = path.resolve(e2eDir, '..', '..');

/** 评测知识库编码（固定，供幂等复用） */
const KB_CODE = 'e2e_rag_eval';
/** 评测知识库名称 */
const KB_NAME = 'E2E 检索质量评测库';
/** 默认召回条数 */
const DEFAULT_TOP_K = 5;
/** 评测语料上限（避免全量解析过慢） */
const CORPUS_LIMIT = 10;
/** 向量化完成态（AiVectorServiceImpl.STATUS_DONE） */
const VECTOR_DONE = '2';

/** 参数错误 */
class UsageError extends Error {}

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 参数列表
 * @returns {object} 解析结果
 */
const parseArgs = (argv) => {
  const valueFlags = { '--base-url': 'baseUrl', '--corpus': 'corpus', '--top-k': 'topK', '--timeout': 'timeout', '--rerank-mode': 'rerankMode' };
  const boolFlags = { '--rebuild': 'rebuild', '--clean': 'clean', '--keep-kb': 'keepKb', '--verbose': 'verbose', '--help': 'help', '--rewrite': 'rewrite', '--rerank': 'rerank', '--assemble': 'assemble' };
  const args = { rebuild: false, clean: false, keepKb: false, verbose: false, help: false, rewrite: false, rerank: false, assemble: false, topK: DEFAULT_TOP_K };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (boolFlags[token]) {
      args[boolFlags[token]] = true;
      continue;
    }
    if (valueFlags[token]) {
      const value = argv[++i];
      if (value === undefined) {
        throw new UsageError(`参数 ${token} 缺少取值`);
      }
      args[valueFlags[token]] = value;
      continue;
    }
    throw new UsageError(`未知参数：${token}`);
  }
  args.topK = Number(args.topK);
  if (!Number.isInteger(args.topK) || args.topK < 1 || args.topK > 50) {
    throw new UsageError('--top-k 必须是 1~50 的整数');
  }
  args.timeoutMs = args.timeout ? Number(args.timeout) * 1000 : 15000;
  if (!Number.isFinite(args.timeoutMs) || args.timeoutMs <= 0) {
    throw new UsageError('--timeout 必须是正数（秒）');
  }
  return args;
};

const usage = () => `BizBuddy RAG 检索质量评测（e2e/http/eval）

用法：node e2e/http/eval/eval-rag.mjs [选项]

选项：
  --base-url <url>   后端地址（默认 http://localhost:8080）
  --corpus <dir>     语料目录（默认取文档仓 docs/，回退 e2e/http/eval/corpus/；按用例引用挑 ~${CORPUS_LIMIT} 篇）
  --top-k <n>        召回条数（默认 ${DEFAULT_TOP_K}；hit@1/3/5 与 MRR@5 均基于该结果）
  --rewrite          已失效（P0-② 查询改写 + RRF 实测无收益，已整体回退；仅保留该参数以兼容旧命令）
  --rerank           检索时透传 rerank=true（强制开启重排）；**不传则跟随服务端配置**（现已默认开启）
  --rerank-mode <m>  重排融合模式（replace / rank_rrf / score_fusion），透传后端；需配合 --rerank，便于一次评测内 A/B
  --assemble         检索时透传 assemble=true（对命中做 P0-③ 上下文装配：去重 + 相邻合并 + 字符上限）；并输出「装配前 vs 装配后」统计；**不传则跟随服务端配置**（现已默认开启）
  --rebuild          先删除旧评测库（含其文档）再重建
  --clean            跑完删除评测库及其文档（默认保留，便于复核）
  --keep-kb          显式保留评测库（默认行为，便于与 --clean 对照）
  --timeout <秒>     单次 HTTP 超时（默认 15；解析/向量化另有更长的固定超时）
  --verbose          打印细节
  --help             显示本帮助

指标：hit@1 / hit@3 / hit@5、recall@5、MRR@5、平均 top1 分数、平均检索耗时；并按 category（fact/term/synonym/colloquial/crossdoc）分组输出。
判定：topK 命中中存在 docName ∈ expectDocs 且其 content 含任一 expectKeywords 记为命中。
装配（--assemble）：额外输出「装配前 → 装配后」的片段条数 / 总字符数 / 估算 Token 数 / 期望关键词覆盖率 / 冗余率（整体 + 分类别）。
报告：e2e/http/.e2e-report-rag-<时间戳>.json（已被 .gitignore 忽略，含 byCategory / assembleSummary）`;

/**
 * 解析语料目录与文件清单。
 *
 * <p>默认取文档仓 `docs/`（从 `e2e/http/` 看是 `../docs`）；优先挑选**被用例引用的**文档，
 * 保证「语料 = 评测目标」一致；不足时按字母序补齐；目录不存在则回退 `e2e/http/eval/corpus/`。</p>
 *
 * @param {string|null} cliDir 命令行指定的语料目录
 * @param {Set<string>} caseDocs 用例中引用到的期望文档名集合
 * @returns {{ dir: string, files: string[] }} 语料目录与文件绝对路径（已排序）
 */
const resolveCorpus = (cliDir, caseDocs) => {
  // 文档仓在 `biz-buddy/` 的上一层仓库根（从 `e2e/http/` 看是 `../../docs`）
  const candidates = cliDir
    ? [path.resolve(cliDir)]
    : [path.join(repoRoot, '..', 'docs'), path.join(e2eDir, 'docs'), path.join(here, 'corpus')];
  for (const dir of candidates) {
    if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
      continue;
    }
    const all = fs
      .readdirSync(dir)
      .filter((name) => name.toLowerCase().endsWith('.md'))
      .sort();
    if (!all.length) {
      continue;
    }
    const referenced = all.filter((name) => caseDocs.has(name));
    const selected = referenced.length >= Math.min(CORPUS_LIMIT, caseDocs.size) ? referenced : all.slice(0, CORPUS_LIMIT);
    return { dir, files: selected.slice(0, CORPUS_LIMIT).map((name) => path.join(dir, name)) };
  }
  throw new BizError(
    `未找到可用语料目录（尝试：${candidates.join(' / ')}）。` +
      `可用 --corpus <dir> 指定包含 .md 文件的目录。`
  );
};

/**
 * 上传单个文件到框架 OSS，返回 ossId 与文件名。
 *
 * <p>框架 OSS 上传端点 `POST /resource/oss/upload`（multipart/form-data，字段名 `file`）。</p>
 *
 * @param {object} cfg 配置
 * @param {HttpClient} http 已登录的 HTTP 客户端
 * @param {string} filePath 本地文件路径
 * @returns {Promise<{ ossId: string, fileName: string }>} OSS 主键与原始文件名
 */
const uploadFile = async (cfg, http, filePath) => {
  const buffer = fs.readFileSync(filePath);
  const fileName = path.basename(filePath);
  const form = new FormData();
  form.append('file', new Blob([buffer]), fileName);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 120000);
  let response;
  try {
    response = await fetch(`${cfg.baseUrl}/resource/oss/upload`, {
      method: 'POST',
      headers: { clientid: cfg.clientId, Authorization: `Bearer ${http.token}` },
      body: form,
      signal: controller.signal
    });
  } catch (error) {
    throw new BizError(`上传 ${fileName} 失败：${error.name === 'AbortError' ? '超时（120s）' : error.message}`);
  } finally {
    clearTimeout(timer);
  }
  const text = await response.text();
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    throw new BizError(`上传 ${fileName} 响应不是 JSON（HTTP ${response.status}）：${text.slice(0, 200)}`);
  }
  if (payload.code !== 200) {
    throw new BizError(`上传 ${fileName} 业务失败（code=${payload.code}）：${payload.msg || ''}`);
  }
  const data = payload.data ?? {};
  if (!data.ossId) {
    throw new BizError(`上传 ${fileName} 未返回 ossId：${JSON.stringify(data).slice(0, 200)}`);
  }
  return { ossId: String(data.ossId), fileName: data.fileName || data.originalName || fileName };
};

/**
 * 查询评测知识库（按编码精确匹配）。
 *
 * @param {HttpClient} http HTTP 客户端
 * @returns {Promise<object|null>} 知识库视图对象或 null
 */
const findKb = async (http) => {
  const page = await http.request('GET', '/ai/kb/list', { query: { kbCode: KB_CODE, pageNum: 1, pageSize: 20 } });
  return (page?.rows ?? []).find((item) => item.kbCode === KB_CODE) ?? null;
};

/**
 * 查询知识库下的全部文档。
 *
 * @param {HttpClient} http HTTP 客户端
 * @param {string|number} kbId 知识库主键
 * @returns {Promise<object[]>} 文档列表
 */
const listDocs = async (http, kbId) => {
  const page = await http.request('GET', '/ai/kb/doc/list', { query: { kbId, pageNum: 1, pageSize: 100 } });
  return page?.rows ?? [];
};

/**
 * 删除知识库及其全部文档（先删文档，再删库；接口要求库下无文档才能删库）。
 *
 * @param {HttpClient} http HTTP 客户端
 * @param {object} kb 知识库视图对象
 * @param {Function} log 日志
 * @returns {Promise<void>}
 */
const deleteKb = async (http, kb, log) => {
  const docs = await listDocs(http, kb.id);
  if (docs.length) {
    await http.request('DELETE', `/ai/kb/doc/${docs.map((item) => item.id).join(',')}`, { timeoutMs: 60000 });
    log(`  ✓ 删除评测库文档 ${docs.length} 篇`);
  }
  await http.request('DELETE', `/ai/kb/${kb.id}`, { timeoutMs: 60000 });
  log(`  ✓ 删除评测库 ${KB_CODE}`);
};

/**
 * 确保知识库存在，返回知识库视图对象。
 *
 * @param {HttpClient} http HTTP 客户端
 * @param {object} args 参数
 * @param {object} embeddingModel 向量模型视图对象
 * @param {Function} log 日志
 * @returns {Promise<object>} 知识库视图对象
 */
const ensureKb = async (http, args, embeddingModel, log) => {
  let kb = await findKb(http);
  if (kb && args.rebuild) {
    log('· --rebuild：删除旧评测库后重建');
    await deleteKb(http, kb, log);
    kb = null;
  }
  if (kb) {
    log(`· 复用已存在评测库 ${KB_CODE}（id=${kb.id}，chunkSize=${kb.chunkSize}，向量模型=${kb.embeddingModelName}）`);
    return kb;
  }
  await http.request('POST', '/ai/kb', {
    timeoutMs: 30000,
    body: {
      kbName: KB_NAME,
      kbCode: KB_CODE,
      description: 'RAG 检索质量评测库（e2e/http/eval/eval-rag.mjs 自动创建）',
      embeddingModelId: embeddingModel.id,
      // dimension 留空由后端取向量模型配置的维度（种子为 1024）
      chunkSize: 500,
      chunkOverlap: 50,
      retrievalTopK: DEFAULT_TOP_K,
      similarityThreshold: 0,
      visibleScope: '0',
      status: '0'
    }
  });
  kb = await findKb(http);
  if (!kb) {
    throw new BizError('创建评测库后仍查询不到（/ai/kb/list）——请检查数据库或权限');
  }
  log(`· 已创建评测库 ${KB_CODE}（id=${kb.id}，dimension=${kb.dimension}）`);
  return kb;
};

/**
 * 确保单篇文档已入库、解析并向量化；已向量化则复用。
 *
 * @param {object} cfg 配置
 * @param {HttpClient} http HTTP 客户端
 * @param {object} kb 知识库视图对象
 * @param {string} filePath 语料文件路径
 * @param {Function} log 日志
 * @returns {Promise<{ docName: string, docId: number, chunkCount: number|null, reused: boolean }>} 入库结果
 */
const ingestDoc = async (cfg, http, kb, filePath, log) => {
  const docName = path.basename(filePath);
  let doc = (await listDocs(http, kb.id)).find((item) => item.docName === docName) ?? null;

  if (doc && doc.vectorStatus === VECTOR_DONE) {
    log(`  · 复用已向量化文档 ${docName}（切片 ${doc.chunkCount}）`);
    return { docName, docId: doc.id, chunkCount: doc.chunkCount, reused: true };
  }

  if (!doc) {
    const oss = await uploadFile(cfg, http, filePath);
    await http.request('POST', '/ai/kb/doc', {
      timeoutMs: 30000,
      body: {
        kbId: kb.id,
        docName,
        fileSuffix: 'md',
        // 雪花 ossId 为 19 位，超出 JS Number 安全整数范围，必须以字符串下发（Jackson 可解析为 Long）
        ossId: oss.ossId,
        fileSize: fs.statSync(filePath).size
      }
    });
    doc = (await listDocs(http, kb.id)).find((item) => item.docName === docName) ?? null;
    if (!doc) {
      throw new BizError(`登记文档 ${docName} 后仍查询不到（/ai/kb/doc/list）`);
    }
    log(`  · 已上传并登记 ${docName}（docId=${doc.id}）`);
  } else {
    log(`  · 文档已存在但未向量化，继续处理 ${docName}（docId=${doc.id}）`);
  }

  const parsed = await http.request('POST', `/ai/kb/doc/${doc.id}/parse`, { timeoutMs: 300000 });
  if (!parsed?.chunkCount || parsed.chunkCount <= 0) {
    throw new BizError(`解析 ${docName} 后切片为空（chunkCount=${parsed?.chunkCount ?? '未知'}）——源文件可能不可读或格式不支持`);
  }

  const vectorized = await http.request('POST', `/ai/kb/doc/${doc.id}/vectorize`, { timeoutMs: 600000 });
  if (vectorized?.vectorStatus !== VECTOR_DONE) {
    throw new BizError(
      `向量化 ${docName} 未完成（vectorStatus=${vectorized?.vectorStatus ?? '未知'}）` +
        `${vectorized?.vectorErrorMsg ? `：${vectorized.vectorErrorMsg}` : ''}——请确认平台已为向量模型配置 API 密钥`
    );
  }
  log(`  · 解析+向量化完成 ${docName}（切片 ${parsed.chunkCount}）`);
  return { docName, docId: doc.id, chunkCount: parsed.chunkCount, reused: false };
};

/**
 * 发起单条检索请求，返回命中列表与耗时。
 *
 * @param {HttpClient} http HTTP 客户端
 * @param {object} kb 知识库视图对象
 * @param {object} testCase 用例（query）
 * @param {number} topK 召回条数
 * @param {boolean} rewrite 是否透传 rewrite=true
 * @param {boolean} rerank 是否透传 rerank=true
 * @param {string|undefined} rerankMode 透传 rerankMode（融合模式）
 * @param {boolean} assemble 是否透传 assemble=true（对命中做上下文装配）
 * @returns {Promise<{ list: object[], elapsedMs: number }>} 命中列表与耗时
 */
const fetchHits = async (http, kb, testCase, topK, rewrite, rerank, rerankMode, assemble) => {
  const started = Date.now();
  const hits = await http.request('GET', '/ai/kb/retrieval/test', {
    timeoutMs: 60000,
    query: {
      kbId: kb.id,
      query: testCase.query,
      topK,
      threshold: 0,
      rewrite: rewrite ? true : undefined,
      rerank: rerank ? true : undefined,
      rerankMode: rerank && rerankMode ? rerankMode : undefined,
      assemble: assemble ? true : undefined
    }
  });
  return { list: Array.isArray(hits) ? hits : [], elapsedMs: Date.now() - started };
};

/**
 * 对一组命中列表打分（排序类指标）。
 *
 * @param {object[]} list 命中列表
 * @param {object} testCase 用例（query / expectDocs / expectKeywords）
 * @param {number} topK 召回条数
 * @param {number} elapsedMs 检索耗时
 * @returns {object} 逐条结果
 */
const scoreList = (list, testCase, topK, elapsedMs) => {
  const expectDocs = testCase.expectDocs ?? [];
  const keywords = testCase.expectKeywords ?? [];
  // 相关命中 = docName 命中期望文档 且 切片正文含任一期望关键词
  const relevantRanks = [];
  list.forEach((hit, index) => {
    const docOk = expectDocs.includes(hit.docName);
    const text = String(hit.content ?? '');
    const keywordOk = keywords.length === 0 || keywords.some((word) => text.includes(word));
    if (docOk && keywordOk) {
      relevantRanks.push(index + 1);
    }
  });
  const rank = relevantRanks.length ? Math.min(...relevantRanks) : null;

  // recall@5：期望文档中被 topK 结果按 docName 召回的比例
  const topKNames = new Set(list.slice(0, topK).map((hit) => hit.docName));
  const recalled = expectDocs.filter((name) => topKNames.has(name));
  const recall5 = expectDocs.length ? recalled.length / expectDocs.length : 0;

  return {
    category: testCase.category ?? 'uncategorized',
    query: testCase.query,
    expectDocs,
    expectKeywords: keywords,
    rank,
    hit1: rank !== null && rank <= 1,
    hit3: rank !== null && rank <= 3,
    hit5: rank !== null && rank <= 5,
    recall5,
    mrr5: rank !== null && rank <= 5 ? 1 / rank : 0,
    top1Score: list[0]?.score ?? null,
    elapsedMs,
    top3: list.slice(0, 3).map((hit) => ({ docName: hit.docName ?? null, score: hit.score ?? null, preview: String(hit.content ?? '').slice(0, 40) }))
  };
};

/**
 * 估算文本 Token 数（脚本侧口径，与后端 {@code AiTokenEstimator} 一致）。
 *
 * <p>CJK 字符（统一表意文字及扩展 / 兼容区、CJK 标点、全角字符）≈ 1 token / 字符；
 * 其它字符 ≈ 4 字符 / token（向上取整）。仅为近似值，用于前后对比报告。</p>
 *
 * @param {string} text 文本
 * @returns {number} 估算 Token 数（非负）
 */
const estimateTokens = (text) => {
  const source = String(text ?? '');
  let cjk = 0;
  let other = 0;
  for (const ch of source) {
    const code = ch.codePointAt(0);
    const isCjk =
      (code >= 0x4e00 && code <= 0x9fff) ||
      (code >= 0x3400 && code <= 0x4dbf) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0x3000 && code <= 0x303f) ||
      (code >= 0xff00 && code <= 0xffef);
    if (isCjk) {
      cjk++;
    } else {
      other++;
    }
  }
  return cjk + Math.ceil(other / 4);
};

/**
 * 计算单个命中列表的「上下文装配」相关统计（条数 / 总字符数 / 估算 Token / 期望关键词覆盖率 / 冗余率）。
 *
 * <p>冗余率口径＝字符二元组重复占比（1 − 去重后二元组数 / 二元组总数），越低越精简。
 * 估算 Token 为**脚本侧估算**，口径与后端 {@code AiTokenEstimator} 一致（CJK≈1 token/字符，其它≈4 字符/token）。</p>
 *
 * @param {object[]} list 命中列表
 * @param {object} testCase 用例（expectKeywords）
 * @returns {object} 统计
 */
const contentStatOf = (list, testCase) => {
  const contents = list.map((hit) => String(hit.content ?? ''));
  const totalChars = contents.reduce((sum, text) => sum + text.length, 0);
  const keywords = testCase.expectKeywords ?? [];
  const joined = contents.join('\n');
  const covered = keywords.filter((word) => joined.includes(word)).length;
  const coverage = keywords.length ? covered / keywords.length : 1;
  const tokens = estimateTokens(joined);
  const grams = [];
  for (const text of contents) {
    for (let i = 0; i + 2 <= text.length; i++) {
      grams.push(text.slice(i, i + 2));
    }
  }
  const uniqueGrams = new Set(grams).size;
  const redundancy = grams.length ? 1 - uniqueGrams / grams.length : 0;
  return { fragments: list.length, totalChars, tokens, coverage, redundancy };
};

/**
 * 执行单条检索用例并打分；开启 --assemble 时额外返回「装配前 vs 装配后」统计。
 *
 * @param {HttpClient} http HTTP 客户端
 * @param {object} kb 知识库视图对象
 * @param {object} testCase 用例
 * @param {number} topK 召回条数
 * @param {boolean} rewrite 是否透传 rewrite=true
 * @param {boolean} rerank 是否透传 rerank=true
 * @param {string|undefined} rerankMode 透传 rerankMode（融合模式）
 * @param {boolean} assemble 是否透传 assemble=true
 * @returns {Promise<{ result: object, assemble: object|null }>} 逐条结果与装配统计
 */
const runCase = async (http, kb, testCase, topK, rewrite, rerank, rerankMode, assemble) => {
  const primary = await fetchHits(http, kb, testCase, topK, rewrite, rerank, rerankMode, assemble);
  const result = scoreList(primary.list, testCase, topK, primary.elapsedMs);
  if (!assemble) {
    return { result, assemble: null };
  }
  // 装配开启：再取一次「未装配」命中，用于前后对比（向量检索确定性，无需重排）
  const raw = await fetchHits(http, kb, testCase, topK, rewrite, rerank, rerankMode, false);
  return { result, assemble: { before: contentStatOf(raw.list, testCase), after: contentStatOf(primary.list, testCase) } };
};

/**
 * 对一组结果计算指标。
 *
 * @param {object[]} results 结果列表
 * @returns {object} 指标
 */
const metricsOf = (results) => {
  const total = results.length || 1;
  const hit = (key) => results.filter((item) => item[key]).length / total;
  const mean = (pick) => results.reduce((sum, item) => sum + pick(item), 0) / total;
  const scored = results.filter((item) => typeof item.top1Score === 'number');
  return {
    cases: results.length,
    hitAt1: hit('hit1'),
    hitAt3: hit('hit3'),
    hitAt5: hit('hit5'),
    recallAt5: mean((item) => item.recall5),
    mrrAt5: mean((item) => item.mrr5),
    avgTop1Score: scored.length ? scored.reduce((sum, item) => sum + item.top1Score, 0) / scored.length : null,
    avgLatencyMs: Math.round(mean((item) => item.elapsedMs))
  };
};

/**
 * 汇总指标：整体 + 按 category 分组。
 *
 * @param {object[]} results 逐条结果
 * @returns {object} 汇总
 */
const summarize = (results) => {
  const byCategory = {};
  for (const item of results) {
    const category = item.category ?? 'uncategorized';
    (byCategory[category] ??= []).push(item);
  }
  const grouped = {};
  for (const [category, list] of Object.entries(byCategory)) {
    grouped[category] = metricsOf(list);
  }
  return { ...metricsOf(results), byCategory: grouped };
};

/**
 * 汇总「上下文装配」统计：整体 + 按 category 分组（装配前 vs 装配后）。
 *
 * @param {object[]} results 逐条结果（含 assemble 统计）
 * @returns {object|null} 汇总；无装配数据时返回 null
 */
const summarizeAssemble = (results) => {
  const rows = results.filter((item) => item.assemble).map((item) => ({ category: item.category ?? 'uncategorized', ...item.assemble }));
  if (!rows.length) {
    return null;
  }
  const mean = (list, pick) => (list.length ? list.reduce((sum, item) => sum + pick(item), 0) / list.length : null);
  const reduceGroup = (list) => ({
    cases: list.length,
    before: {
      fragments: mean(list, (x) => x.before.fragments),
      totalChars: mean(list, (x) => x.before.totalChars),
      tokens: mean(list, (x) => x.before.tokens),
      coverage: mean(list, (x) => x.before.coverage),
      redundancy: mean(list, (x) => x.before.redundancy)
    },
    after: {
      fragments: mean(list, (x) => x.after.fragments),
      totalChars: mean(list, (x) => x.after.totalChars),
      tokens: mean(list, (x) => x.after.tokens),
      coverage: mean(list, (x) => x.after.coverage),
      redundancy: mean(list, (x) => x.after.redundancy)
    }
  });
  const byCategory = {};
  for (const row of rows) {
    (byCategory[row.category] ??= []).push(row);
  }
  const grouped = {};
  for (const [category, list] of Object.entries(byCategory)) {
    grouped[category] = reduceGroup(list);
  }
  return { ...reduceGroup(rows), byCategory: grouped };
};

const pct = (value) => `${(value * 100).toFixed(1)}%`;
const num = (value) => (value === null || value === undefined ? '-' : Number(value).toFixed(3));
const fixed = (value, digits = 1) => (value === null || value === undefined ? '-' : Number(value).toFixed(digits));

const main = async () => {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(`✗ ${error.message}\n`);
    console.error(usage());
    process.exit(3);
  }
  if (args.help) {
    console.log(usage());
    process.exit(0);
  }

  const cases = JSON.parse(fs.readFileSync(path.join(here, 'rag-cases.json'), 'utf8'));
  if (!Array.isArray(cases) || cases.length < 20) {
    throw new BizError(`用例集 rag-cases.json 至少需 20 条（当前 ${Array.isArray(cases) ? cases.length : 0} 条）`);
  }
  const caseDocs = new Set(cases.flatMap((item) => item.expectDocs ?? []));

  const cfg = loadConfig(args);
  const log = (message) => console.log(message);
  const http = new HttpClient(cfg, { timeoutMs: args.timeoutMs, verbose: args.verbose, log: args.verbose ? log : () => {} });

  log(`BizBuddy RAG 评测｜${cfg.baseUrl}｜用例 ${cases.length} 条｜topK ${args.topK}｜检索重排 ${args.rerank ? '开(强制)' : '跟随服务端配置'}${args.rerank && args.rerankMode ? `(${args.rerankMode})` : ''}｜上下文装配 ${args.assemble ? '开(强制)' : '跟随服务端配置'}\n`);
  await http.login();
  log('· 登录成功');

  // 向量模型（modelType=embedding 的种子模型）
  const modelPage = await http.request('GET', '/ai/model/list', { query: { pageNum: 1, pageSize: 100 } });
  const models = modelPage?.rows ?? [];
  const embeddingModel =
    models.find((item) => item.modelType === 'embedding' && item.configName === cfg.embeddingModelName) ??
    models.find((item) => item.modelType === 'embedding');
  if (!embeddingModel) {
    throw new BizError('未找到向量模型（/ai/model/list 中 modelType=embedding），请先在「模型配置」维护');
  }
  if (!embeddingModel.apiKeyMask) {
    throw new BizError(`向量模型「${embeddingModel.configName}」未配置 API 密钥——向量化与检索会失败，请先在「模型配置」补全`);
  }
  log(`· 向量模型＝${embeddingModel.configName}（id=${embeddingModel.id}，dimension=${embeddingModel.dimension}）`);

  const corpus = resolveCorpus(args.corpus, caseDocs);
  log(`· 语料目录＝${corpus.dir}（${corpus.files.length} 篇）`);

  const kb = await ensureKb(http, args, embeddingModel, log);

  log('\n· 入库（上传→登记→解析→向量化）');
  const corpusManifest = [];
  for (const file of corpus.files) {
    const result = await ingestDoc(cfg, http, kb, file, log);
    corpusManifest.push({ file: path.basename(file), docId: result.docId, chunkCount: result.chunkCount, reused: result.reused });
  }

  log(`\n· 检索评测（${cases.length} 条用例）`);
  const results = [];
  for (const testCase of cases) {
    let result;
    let assembleStat = null;
    try {
      const outcome = await runCase(http, kb, testCase, args.topK, args.rewrite, args.rerank, args.rerankMode, args.assemble);
      result = outcome.result;
      assembleStat = outcome.assemble;
    } catch (error) {
      result = {
        category: testCase.category ?? 'uncategorized',
        query: testCase.query,
        expectDocs: testCase.expectDocs ?? [],
        expectKeywords: testCase.expectKeywords ?? [],
        rank: null,
        hit1: false,
        hit3: false,
        hit5: false,
        recall5: 0,
        mrr5: 0,
        top1Score: null,
        elapsedMs: 0,
        top3: [],
        error: error.message
      };
    }
    result.assemble = assembleStat;
    results.push(result);
    const badge = result.rank !== null ? `命中@${result.rank}` : '未命中';
    const actual = result.top3.map((item) => `${item.docName ?? '?'}(${num(item.score)})`).join(' / ') || '无召回';
    console.log(`  [${badge}] ${result.query}`);
    console.log(`        期望 ${result.expectDocs.join(',')} → top3: ${actual}`);
    if (result.error) {
      console.log(`        ⚠ 检索异常：${result.error}`);
    }
  }

  const summary = summarize(results);
  log('\n===== 汇总（整体） =====');
  log(`用例数 ${summary.cases}｜语料 ${corpus.files.length} 篇｜检索重排 ${args.rerank ? '开(强制)' : '跟随服务端配置'}${args.rerank && args.rerankMode ? `(${args.rerankMode})` : ''}｜上下文装配 ${args.assemble ? '开(强制)' : '跟随服务端配置'}`);
  log(`hit@1 ${pct(summary.hitAt1)}｜hit@3 ${pct(summary.hitAt3)}｜hit@5 ${pct(summary.hitAt5)}`);
  log(`recall@5 ${pct(summary.recallAt5)}｜MRR@5 ${num(summary.mrrAt5)}`);
  log(`平均 top1 分数 ${num(summary.avgTop1Score)}｜平均检索耗时 ${summary.avgLatencyMs}ms`);

  log('\n===== 汇总（按类别） =====');
  log('category      n   hit@1   hit@3   hit@5   recall@5  MRR@5   耗时(ms)');
  for (const [category, metric] of Object.entries(summary.byCategory)) {
    log(
      `${category.padEnd(12)}${String(metric.cases).padStart(3)}` +
        `  ${pct(metric.hitAt1).padStart(6)}  ${pct(metric.hitAt3).padStart(6)}  ${pct(metric.hitAt5).padStart(6)}` +
        `  ${pct(metric.recallAt5).padStart(7)}  ${num(metric.mrrAt5).padStart(6)}  ${String(metric.avgLatencyMs).padStart(7)}`
    );
  }
  if (args.rewrite) {
    log('注：改写开启时命中项的 score 为 RRF 融合分（非余弦），故仅排序类指标可与「改写关」对比。');
  }
  if (args.rerank) {
    log('注：重排开启时命中项的 score 为重排分（relevance_score，非余弦），故仅排序类指标可与「重排关」对比。');
  }

  // 上下文装配统计（仅 --assemble 时输出）：装配前 vs 装配后
  const assembleSummary = summarizeAssemble(results);
  if (assembleSummary) {
    log('\n===== 上下文装配统计（装配前 → 装配后） =====');
    log('scope          n   片段数(前→后)      总字符(前→后)      估算token(前→后)   关键词覆盖(前→后)     冗余率(前→后)');
    const line = (name, metric) => {
      const b = metric.before;
      const a = metric.after;
      return (
        `${name.padEnd(14)}${String(metric.cases).padStart(3)}` +
        `  ${fixed(b.fragments)}→${fixed(a.fragments)}`.padEnd(18) +
        `  ${fixed(b.totalChars)}→${fixed(a.totalChars)}`.padEnd(20) +
        `  ${fixed(b.tokens)}→${fixed(a.tokens)}`.padEnd(18) +
        `  ${pct(b.coverage)}→${pct(a.coverage)}`.padEnd(20) +
        `  ${pct(b.redundancy)}→${pct(a.redundancy)}`
      );
    };
    log(line('整体', assembleSummary));
    for (const [category, metric] of Object.entries(assembleSummary.byCategory)) {
      log(line(category, metric));
    }
    log('注：装配只做返回集后处理（去重 + 相邻合并 + 字符上限），不改排序；覆盖率不降即可视为无损精简。');
    log('注：估算 token 为**脚本侧估算**（CJK≈1 token/字符，其它≈4 字符/token），口径与后端 AiTokenEstimator 一致。');
  }

  const stamp = String(Date.now());
  const reportPath = path.join(e2eDir, `.e2e-report-rag-${stamp}.json`);
  fs.writeFileSync(
    reportPath,
    JSON.stringify(
      {
        baseUrl: cfg.baseUrl,
        startedAt: stamp,
        topK: args.topK,
        rewrite: args.rewrite,
        rerank: args.rerank,
        rerankMode: args.rerank ? (args.rerankMode ?? null) : null,
        assemble: args.assemble,
        kb: { id: kb.id, code: kb.kbCode, name: kb.kbName, chunkSize: kb.chunkSize, chunkOverlap: kb.chunkOverlap, dimension: kb.dimension, embeddingModel: embeddingModel.configName },
        corpusDir: corpus.dir,
        corpus: corpusManifest,
        summary,
        assembleSummary,
        results
      },
      null,
      2
    ),
    'utf8'
  );
  log(`报告已写入：${reportPath}`);

  if (args.clean) {
    log('\n· --clean：清理评测库');
    const current = await findKb(http);
    if (current) {
      await deleteKb(http, current, log);
    }
  } else {
    log(`\n· 评测库已保留（${KB_CODE}，id=${kb.id}）——复核后可用 --rebuild 重建或 --clean 清理`);
  }

  process.exit(0);
};

main().catch((error) => {
  console.error(`\n✗ ${error?.message || error}`);
  if (process.argv.includes('--verbose')) {
    console.error(error?.stack);
  }
  process.exit(1);
});