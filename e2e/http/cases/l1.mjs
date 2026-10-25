/**
 * L1 模型连通性：`POST /ai/model/test`。
 *
 * <p>apiKey 作为**瞬时入参**下发（服务端只测试、不落库），因此不会污染「模型配置」里的种子模型。</p>
 */

export const meta = { layer: 'L1', name: '模型连通性（chat + embedding）', needsKey: true };

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { cfg, http, resolved } = ctx;
  if (!cfg.llmApiKey) {
    return { status: 'SKIP', detail: '未提供 BIZBUDDY_LLM_API_KEY' };
  }

  const probe = async (model, label, extra) => {
    const data = await http.request('POST', '/ai/model/test', {
      timeoutMs: 60000,
      body: {
        configName: `e2e-${label}`,
        provider: model.provider || 'dashscope',
        modelType: model.modelType,
        modelCode: model.modelCode,
        baseUrl: cfg.llmBaseUrl || model.baseUrl,
        apiKey: cfg.llmApiKey,
        timeoutSeconds: 30,
        ...extra
      }
    });
    return data;
  };

  const chat = await probe(resolved.chatModel, 'chat');
  if (!chat?.success) {
    return { status: 'FAIL', detail: `对话模型连通失败：${chat?.message || '（无说明）'}`, evidence: { chat } };
  }

  const evidence = { chat: { message: chat.message, elapsedMs: chat.elapsedMs } };
  if (!resolved.embeddingModel) {
    evidence.embedding = { skipped: '未找到 embedding 模型' };
    return { status: 'PASS', detail: `对话模型连通正常（${chat.elapsedMs}ms）；未配置向量模型，跳过其连通性检查`, evidence };
  }

  const embedding = await probe(resolved.embeddingModel, 'embedding', { dimension: resolved.embeddingModel.dimension });
  evidence.embedding = { message: embedding?.message, elapsedMs: embedding?.elapsedMs };
  if (!embedding?.success) {
    return { status: 'FAIL', detail: `向量模型连通失败：${embedding?.message || '（无说明）'}`, evidence };
  }

  return {
    status: 'PASS',
    detail: `对话模型 ${chat.elapsedMs}ms、向量模型 ${embedding.elapsedMs}ms 均连通`,
    evidence
  };
};
