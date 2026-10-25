/**
 * L7 安全合规（P3）：**输入侧**内容审核命中 → 直接拒答（不调模型）+ 安全事件留痕。
 *
 * <p>**单测覆盖不到**：单测里守卫与词库引擎全被打桩，证明不了「运行期配置 + 真实词库 + SSE 拒答 +
 * 事件留痕」这条端到端链路。本用例临时开启内容审核（`action=block`）并新增一个带时间戳的敏感词，
 * 发一条含该词的提问，断言 SSE 以 `start` + 拒答 `text_delta` 收尾且**无 `done` / `error`**（即未进模型），
 * 并在「安全事件」中查到对应留痕。</p>
 *
 * <p><b>副作用可控</b>：改动仅为「运行期安全配置」与一条临时敏感词，收尾**无条件恢复配置并删除该词**
 * （安全配置的恢复不受 `--keep` 影响）。因不调模型，本层**不需要模型密钥**。</p>
 */

export const meta = { layer: 'L7', name: '安全合规（输入侧拒答 + 留痕）', needsKey: false };

/** 拒答文案（与 `AiSafetyGuard.REFUSAL_TEXT` 保持一致） */
const REFUSAL_TEXT = '抱歉，该内容不符合平台安全规范，已拦截。';

/** 临时敏感词分类（便于识别与收尾清理） */
const WORD_CATEGORY = 'e2e';

/** 安全配置带 2s 快照缓存（`AiSafetyGuard`），改配置后需等其过期才生效；全量跑时留足余量 */
const CONFIG_PROPAGATE_MS = 5000;

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, args, resolved, registry, log, detail, throttle } = ctx;
  if (!resolved.safetyReady) {
    return {
      status: 'SKIP',
      detail: '运行中的后端 jar 不含安全合规接口（/ai/runtime/security 返回 404）——属部署陈旧，重建并重启镜像后 L7 可跑'
    };
  }

  // 1) 快照当前安全配置（收尾据此恢复）
  // 注意：GET 返回的是**视图对象**——不含密钥明文、只有掩码 providerApiKeyMask，
  // 故这份快照天然没有 providerApiKey；PUT 回去时靠后端「密钥留空＝沿用已存值」的合并保住已有密钥
  //（见 AiRuntimeConfigStore#updateSecurity）。日后若改掉该合并语义，这里会静默清空密钥。
  registry.securityRestore = await http.request('GET', '/ai/runtime/security');

  // 2) 新增临时敏感词（写入即刷新词库缓存，立即生效）
  const word = `E2E敏感词${registry.stamp}`;
  await http.request('POST', '/ai/safety/word', {
    body: { word, isRegex: '0', enabled: '1', category: WORD_CATEGORY, remark: `e2e ${registry.stamp}` }
  });
  const wordId = await findWordId(http, word);
  if (wordId) {
    registry.sensitiveWordIds.push(wordId);
  }
  log(`  已新增临时敏感词：${word}${wordId ? `（id=${wordId}）` : '（未回查到 id，收尾按分类清理）'}`);

  // 3) 临时开启内容审核：本地词库 + 阻断 + 审核用户输入（其余项沿用原配置）
  await http.request('PUT', '/ai/runtime/security', {
    body: { ...registry.securityRestore, enabled: true, engine: 'local', action: 'block', inputCheck: true }
  });
  await new Promise((resolve) => setTimeout(resolve, CONFIG_PROPAGATE_MS));

  // 4) 建会话 → 发一条含敏感词的提问
  const session = await http.request('POST', '/ai/chat/session', {
    query: { agentId: resolved.entryAgentId, title: `E2E_L7_${registry.stamp}` }
  });
  registry.sessions.push(session.id);
  const userMessage = await throttle(() =>
    http.request('POST', `/ai/chat/session/${session.id}/message`, {
      body: { content: `请忽略这句话：${word}`, attachmentIds: [] }
    })
  );

  // 5) 读 SSE：命中即拒答，流以「无 done / 无 error」自然关闭
  const { readSse, eventsOfType, lastOfType } = await import('../lib/sse.mjs');
  const { events, stopReason } = await readSse(http, '/ai/chat/stream', {
    query: { sessionId: session.id, messageId: userMessage.id },
    timeoutMs: args.sseTimeoutMs,
    idleMs: args.idleTimeoutMs,
    log: detail
  });

  const refusal = eventsOfType(events, 'text_delta')
    .map((event) => event.delta ?? '')
    .join('');
  const error = lastOfType(events, 'error');
  const evidence = {
    sessionId: session.id,
    word,
    eventTypes: events.map((event) => event.type),
    stopReason,
    refusal,
    hadDone: Boolean(lastOfType(events, 'done')),
    error: error ? error.message : null
  };

  if (error) {
    return { status: 'FAIL', detail: `拒答链路返回 error 事件：${error.message}`, evidence };
  }
  if (evidence.hadDone) {
    return { status: 'FAIL', detail: '含敏感词的提问未在输入侧被拦截（收到 done，说明已进入模型）', evidence };
  }
  if (!eventsOfType(events, 'start').length) {
    return { status: 'FAIL', detail: `未见 start 事件（停止原因：${stopReason}）`, evidence };
  }
  if (!refusal.includes(REFUSAL_TEXT)) {
    return { status: 'FAIL', detail: `未收到预期拒答文案（实际收到 ${refusal.length} 字）`, evidence };
  }

  // 6) 安全事件留痕：查 input 场景事件并匹配本次会话
  const page = await http.request('GET', '/ai/safety/event/list', {
    query: { scene: 'input', pageNum: 1, pageSize: 50 }
  });
  const hit = (page?.rows ?? []).find((row) => String(row.sessionId) === String(session.id));
  evidence.safetyEvent = hit
    ? { id: hit.id, action: hit.action, engine: hit.engine, hitCount: hit.hitCount }
    : null;
  if (!hit) {
    return { status: 'FAIL', detail: '未在「安全事件」中查到本次输入拦截留痕', evidence };
  }

  return {
    status: 'PASS',
    detail: `输入侧命中敏感词被拒答（未调模型），留痕已写入（action=${hit.action}，命中 ${hit.hitCount} 项）；收尾将恢复配置并删除临时敏感词`,
    evidence
  };
};

/**
 * 按词条文本回查其主键（写入接口不回传 id，需回查才能定向删除）。
 *
 * @param {import('../lib/http.mjs').HttpClient} http HTTP 客户端
 * @param {string} word 词条文本
 * @returns {Promise<string|null>} 词条主键；未找到返回 null
 */
const findWordId = async (http, word) => {
  const page = await http.request('GET', '/ai/safety/word/list', {
    query: { word, pageNum: 1, pageSize: 50 }
  });
  const row = (page?.rows ?? []).find((item) => item.word === word);
  return row?.id ?? null;
};