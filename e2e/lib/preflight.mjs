/**
 * 前置检查：栈可达 + 登录成功 + 演示种子与模型存在。
 *
 * <p>全部 id 都**动态解析**（不硬编码雪花 id）：入口智能体取自 `/ai/agent/entry`，
 * 专家取自 `/ai/agent/experts`，知识库取自 `/ai/kb/visible-options`，
 * 模型取自 `/ai/model/list`。缺任何一项都给出可操作的报错，而不是抛堆栈。</p>
 */

import { wslDistro } from './env.mjs';

/** 前置检查失败（对应退出码 2） */
export class PreflightError extends Error {}

/**
 * 执行前置检查并解析所需主键。
 *
 * @param {import('./http.mjs').HttpClient} client HTTP 客户端
 * @param {object} cfg 配置
 * @param {{ log?: Function }} options 选项
 * @returns {Promise<object>} 解析结果
 */
export const preflight = async (client, cfg, options = {}) => {
  const log = options.log ?? (() => {});

  log(`· 登录 ${cfg.baseUrl}（账号 ${cfg.username}）`);
  try {
    await client.login();
  } catch (error) {
    throw new PreflightError(
      `${error.message}\n` +
        `  排查建议：\n` +
        `  1) 栈是否在跑：wsl -d ${wslDistro()} -- docker ps（应看到 bizbuddy-admin）\n` +
        `  2) 地址是否可达：浏览器打开 ${cfg.baseUrl}/swagger-ui.html\n` +
        `  3) 账号密码是否正确（默认 admin/admin123，可用 BIZBUDDY_USERNAME/PASSWORD 覆盖）\n` +
        `  4) 接口加密密钥是否与后端一致（默认读 code/front-end/.env.development）`
    );
  }
  log('· 登录成功');

  const entry = await client.request('GET', '/ai/agent/entry');
  if (!entry?.id) {
    throw new PreflightError('未取到入口智能体（/ai/agent/entry 为空）——请确认建库脚本已执行且入口智能体种子存在');
  }

  const experts = (await client.request('GET', '/ai/agent/experts')) ?? [];
  const introExpert = experts.find((item) => item.agentKey === cfg.demoExpertCode);
  if (!introExpert) {
    throw new PreflightError(
      `未找到演示专家「${cfg.demoExpertCode}」（/ai/agent/experts）。` +
        `该专家随建库脚本种子写入，仅全新库初始化时插入；存量库请手工创建或改用 BIZBUDDY_DEMO_EXPERT_CODE 指定其它专家`
    );
  }
  const noticeExpert = experts.find((item) => item.agentKey === cfg.noticeExpertCode);
  if (!noticeExpert) {
    throw new PreflightError(`未找到内置专家「${cfg.noticeExpertCode}」（/ai/agent/experts），HITL 用例（L5）依赖它`);
  }

  const knowledgeBases = (await client.request('GET', '/ai/kb/visible-options')) ?? [];
  const demoKb = knowledgeBases.find((item) => item.kbCode === cfg.demoKbCode);
  if (!demoKb) {
    throw new PreflightError(
      `未找到演示知识库「${cfg.demoKbCode}」（/ai/kb/visible-options）。` +
        `它随建库脚本种子写入，仅全新库初始化时插入；存量库请用 BIZBUDDY_DEMO_KB_CODE 指定其它可见知识库`
    );
  }

  const docPage = await client.request('GET', '/ai/kb/doc/list', {
    query: { kbId: demoKb.id, pageNum: 1, pageSize: 50 }
  });
  const docs = docPage?.rows ?? [];
  if (!docs.length) {
    throw new PreflightError(`演示知识库「${cfg.demoKbCode}」下没有文档，无法验证向量化与检索（L3）`);
  }
  const demoDoc = docs.find((item) => item.docName === cfg.demoDocName) ?? docs[0];

  const modelPage = await client.request('GET', '/ai/model/list', { query: { pageNum: 1, pageSize: 100 } });
  const models = modelPage?.rows ?? [];
  const chatModel = models.find((item) => item.modelType === 'chat' && item.configName === cfg.chatModelName) ?? models.find((item) => item.modelType === 'chat');
  const embeddingModel = models.find((item) => item.modelType === 'embedding' && item.configName === cfg.embeddingModelName) ?? models.find((item) => item.modelType === 'embedding');
  if (!chatModel) {
    throw new PreflightError('未找到可用的对话模型（/ai/model/list 中 modelType=chat），请先在「模型配置」维护一个对话模型');
  }

  log(`· 种子解析完成：入口=${entry.agentName}｜演示专家=${introExpert.agentName}｜公告专家=${noticeExpert.agentName}`);
  log(`· 知识库=${demoKb.kbName}｜文档=${demoDoc.docName}｜对话模型=${chatModel.configName}｜向量模型=${embeddingModel?.configName ?? '(缺失)'}`);
  // 「平台侧是否已配置密钥」看掩码：`******` 表示已配置、空串表示未配置。
  // L2~L6 走服务端已保存的模型配置（不需要环境变量密钥），故据此决定 SKIP 还是真跑。
  const platformChatKeyReady = Boolean(chatModel.apiKeyMask);
  const platformEmbeddingKeyReady = Boolean(embeddingModel?.apiKeyMask);
  log(`· 平台密钥：对话模型 ${platformChatKeyReady ? '已配置' : '未配置'}｜向量模型 ${embeddingModel ? (platformEmbeddingKeyReady ? '已配置' : '未配置') : '无向量模型'}`);

  // 安全合规接口是否在**运行中的 jar** 内（P1~P3 新增）：容器陈旧时该端点 404，L7 据此 SKIP
  // 而不是误报 FAIL（与「未配密钥 → SKIP」同一口径）。
  const securityProbe = await client.requestRaw('GET', '/ai/runtime/security');
  const safetyReady = securityProbe.status === 200 && securityProbe.payload?.code === 200;
  log(`· 安全合规接口：${safetyReady ? '可用' : '不可用（运行中的 jar 早于该功能，L7 将 SKIP）'}`);

  return {
    entryAgentId: entry.id,
    entryAgentName: entry.agentName,
    introExpertId: introExpert.agentId,
    introExpertName: introExpert.agentName,
    noticeExpertId: noticeExpert.agentId,
    noticeExpertName: noticeExpert.agentName,
    demoKbId: demoKb.id,
    demoKbName: demoKb.kbName,
    demoDocId: demoDoc.id,
    demoDocName: demoDoc.docName,
    demoDocVectorStatus: demoDoc.vectorStatus,
    chatModel,
    embeddingModel,
    platformChatKeyReady,
    platformEmbeddingKeyReady,
    safetyReady
  };
};
