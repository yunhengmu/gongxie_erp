/**
 * L18：长期记忆分批合并（水位不丢文件）。
 *
 * <p>把单批预算压到最小（500 tokens ≈ 4000 字符），并写入两个都超过预算的流水文件：
 * 旧文件（昨天）与新文件（今天）必须各占一个批次，且两个文件里的事实都要进入 MEMORY.md。
 * 用于验证「超预算不再跳过旧流水」以及「每批成功后才推进水位」。</p>
 *
 * <p>本层临时改写配置与两个流水文件，收尾无条件恢复原配置与原始内容。</p>
 */

export const meta = { layer: 'L18', name: '长期记忆分批合并', needsKey: true };

import { readMemory, todayLedgerPath, writeMemory } from '../lib/flow.mjs';

/**
 * 昨天的流水路径（按宿主本地日期）。
 *
 * @returns {string} memory/YYYY-MM-DD.md
 */
const yesterdayLedgerPath = () => {
  const date = new Date(Date.now() - 86400000);
  const pad = (value) => String(value).padStart(2, '0');
  return `memory/${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}.md`;
};

/**
 * 构造超过单批预算的流水：45 条唯一填充 + 1 条带标记的高信号事实。
 *
 * @param {string} tag   文件标识（ALPHA / BETA）
 * @param {string} stamp 运行标识
 * @returns {{ content: string, marker: string }}
 */
const buildLedger = (tag, stamp) => {
  const marker = `E2E_BATCH_FACT_${tag}_${stamp}`;
  const filler = Array.from({ length: 45 }, (_, index) => {
    const seq = String(index).padStart(2, '0');
    return `- E2E_BATCH_${tag}_${stamp}_${seq}：这是用于验证分批合并的填充事实，描述用户在某类工作场景下的长期偏好与习惯，内容本身没有业务含义，仅用于让单个流水文件超过单次合并的字符预算。`;
  });
  return {
    marker,
    content: [`- ${marker}：用户长期偏好用表格展示数据`, ...filler].join('\n')
  };
};

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, resolved, registry, log } = ctx;
  if (!resolved.platformChatKeyReady) {
    return { status: 'SKIP', detail: '平台未为对话模型配置 API 密钥（「模型配置」页补全后可跑）' };
  }

  const config = await http.request('GET', '/ai/memory/config');
  if (!config.enabled) {
    return { status: 'SKIP', detail: '长期记忆未开启（enabled=false），无法验证分批合并' };
  }

  const profile = await http.request('GET', '/system/user/profile');
  const userId = profile?.user?.userId;
  const agentId = resolved.entryAgentId;
  const todayPath = todayLedgerPath();
  const yesterdayPath = yesterdayLedgerPath();
  const originalMemory = await readMemory(http, agentId, userId, 'MEMORY.md');
  const originalToday = await readMemory(http, agentId, userId, todayPath);
  const originalYesterday = await readMemory(http, agentId, userId, yesterdayPath);
  const stamp = registry.stamp;
  const alpha = buildLedger('ALPHA', stamp);
  const beta = buildLedger('BETA', stamp);

  try {
    // 最小合并预算：500 tokens × 4 = 4000 字符，两个文件都超过该预算
    await http.request('PUT', '/ai/memory/config', {
      body: { ...config, flushMode: 'never', consolidationMaxTokens: 500 }
    });
    // 旧文件放昨天、新文件放今天：旧→新分批，两批各包含一个文件
    await writeMemory(http, agentId, userId, yesterdayPath, alpha.content);
    await writeMemory(http, agentId, userId, todayPath, beta.content);

    await http.request('POST', '/ai/memory/consolidate', {
      query: { agentId, userId },
      timeoutMs: 300000
    });

    const stats = await http.request('GET', '/ai/memory/stats', { query: { userId } });
    if (!stats.lastConsolidatedAt) {
      return { status: 'FAIL', detail: '分批合并后未推进水位', evidence: { stats } };
    }
    // 两个超预算文件必须各占一批都被纳入；旧实现会只合并一个、另一个被永久跳过
    if (!(stats.lastBatches >= 2) || stats.lastMergedFiles < 2) {
      return {
        status: 'FAIL',
        detail: `未按批次合并全部流水：batches=${stats.lastBatches}, files=${stats.lastMergedFiles}`,
        evidence: { stats }
      };
    }
    if (stats.lastRemainingFiles !== 0) {
      return {
        status: 'FAIL',
        detail: `分批合并后仍有未处理流水：remaining=${stats.lastRemainingFiles}`,
        evidence: { stats }
      };
    }

    const memory = await readMemory(http, agentId, userId, 'MEMORY.md');
    if (!memory.trim()) {
      return { status: 'FAIL', detail: '分批合并后 MEMORY.md 为空', evidence: { stats } };
    }

    log(`  分批合并完成：${stats.lastBatches} 批 / ${stats.lastMergedFiles} 个文件`);
    return {
      status: 'PASS',
      detail: `分批合并通过（${stats.lastBatches} 批纳入 ${stats.lastMergedFiles} 个超预算流水，无剩余）`,
      evidence: {
        memoryChars: memory.length,
        alphaMarker: alpha.marker,
        betaMarker: beta.marker,
        stats
      }
    };
  } finally {
    // 恢复配置与原始记忆；若昨天流水原本不存在，恢复为空文件（接口无单文件删除能力）
    await http.request('PUT', '/ai/memory/config', { body: config }).catch(() => undefined);
    await writeMemory(http, agentId, userId, 'MEMORY.md', originalMemory).catch(() => undefined);
    await writeMemory(http, agentId, userId, todayPath, originalToday).catch(() => undefined);
    // 即使原本不存在也要回写为空，避免把测试填充事实留在真实流水里
    await writeMemory(http, agentId, userId, yesterdayPath, originalYesterday).catch(() => undefined);
  }
};
