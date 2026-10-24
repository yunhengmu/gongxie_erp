/**
 * L2 专家提示词辅助生成：`POST /ai/agent/experts/prompt/generate`（仅生成建议值、不落库）。
 *
 * <p>断言「结构存在 + 关键词命中」，不断言精确文案——LLM 输出天然不确定。</p>
 */

export const meta = { layer: 'L2', name: '专家提示词辅助生成', needsKey: true };

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, resolved } = ctx;
  if (!resolved.platformChatKeyReady) {
    return { status: 'SKIP', detail: '平台未为对话模型配置 API 密钥（「模型配置」页补全后可跑）' };
  }

  const data = await http.request('POST', '/ai/agent/experts/prompt/generate', {
    timeoutMs: 120000,
    body: {
      agentName: 'E2E 校验专家',
      domainHint: '端到端校验用的临时专家，负责回答 BizBuddy 平台相关问题',
      toolNames: ['知识库检索'],
      writeToolNames: [],
      kbNames: [resolved.demoKbName],
      writeEnabled: false,
      modelId: resolved.chatModel.id
    }
  });

  const evidence = {
    success: data?.success,
    modelName: data?.modelName,
    elapsedMs: data?.elapsedMs,
    sysPromptLength: data?.sysPrompt?.length ?? 0,
    description: data?.description
  };

  if (!data?.success) {
    return { status: 'FAIL', detail: `生成失败：${data?.message || '（无说明）'}`, evidence };
  }
  if (!data.sysPrompt || data.sysPrompt.length <= 50) {
    return { status: 'FAIL', detail: `系统提示词过短或为空（长度 ${evidence.sysPromptLength}）`, evidence };
  }
  if (!data.modelName) {
    return { status: 'FAIL', detail: '返回体缺少 modelName', evidence };
  }

  return {
    status: 'PASS',
    detail: `生成成功（${data.modelName}，${data.elapsedMs}ms，提示词 ${evidence.sysPromptLength} 字）`,
    evidence
  };
};
