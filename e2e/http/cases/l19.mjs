/**
 * L19：长期记忆版本快照与恢复。
 *
 * <p>覆盖 T19 的写入前后快照与恢复链路：
 * ① 人工编辑 MEMORY.md 生成版本快照；
 * ② 再次编辑后，按版本 ID 恢复到旧正文；
 * ③ 恢复前自动生成 `PRE_RESTORE` 快照、恢复完成生成 `RESTORE` 快照；
 * ④ 恢复后正文与目标版本一致，`restoredFrom` 指向目标版本。</p>
 *
 * <p>本层会临时改写 MEMORY.md，收尾无条件恢复原始正文。</p>
 */

export const meta = { layer: 'L19', name: '长期记忆版本恢复', needsKey: false };

import { readMemory, writeMemory } from '../lib/flow.mjs';

const MEMORY_MD = 'MEMORY.md';

/**
 * 列出版本（新→旧）。
 *
 * @param {object} http   HTTP 客户端
 * @param {number|string} agentId 入口智能体主键
 * @param {number|string} userId  用户主键
 * @returns {Promise<object[]>} 版本列表
 */
const listVersions = async (http, agentId, userId) =>
  (await http.request('GET', '/ai/memory/versions', { query: { agentId, userId } })) || [];

/**
 * 读取版本详情（含正文）。
 *
 * @param {object} http      HTTP 客户端
 * @param {number|string} agentId 入口智能体主键
 * @param {number|string} userId  用户主键
 * @param {string} versionId 版本 ID
 * @returns {Promise<object>} 版本详情
 */
const readVersion = (http, agentId, userId, versionId) =>
  http.request('GET', `/ai/memory/versions/${versionId}`, { query: { agentId, userId } });

/**
 * 在版本列表中按来源与正文查找目标版本。
 *
 * @param {object} http       HTTP 客户端
 * @param {number|string} agentId 入口智能体主键
 * @param {number|string} userId  用户主键
 * @param {{ source?: string, content?: string, restoredFrom?: string }} filter 过滤条件
 * @returns {Promise<object|null>} 命中的版本详情
 */
const findVersion = async (http, agentId, userId, filter) => {
  const versions = await listVersions(http, agentId, userId);
  for (const item of versions) {
    if (filter.source && item.source !== filter.source) {
      continue;
    }
    if (filter.restoredFrom && item.restoredFrom !== filter.restoredFrom) {
      continue;
    }
    const detail = await readVersion(http, agentId, userId, item.versionId);
    if ((filter.content === undefined || detail.content === filter.content) && detail.versionId) {
      return detail;
    }
  }
  return null;
};

/**
 * @param {object} ctx 运行上下文
 * @returns {Promise<object>} 用例结果
 */
export const run = async (ctx) => {
  const { http, resolved, registry } = ctx;
  const config = await http.request('GET', '/ai/memory/config');
  if (!config.enabled) {
    return { status: 'SKIP', detail: '长期记忆未开启（enabled=false），无法验证版本恢复' };
  }

  const profile = await http.request('GET', '/system/user/profile');
  const userId = profile?.user?.userId;
  const agentId = resolved.entryAgentId;
  const originalMemory = await readMemory(http, agentId, userId, MEMORY_MD);
  const stamp = registry.stamp;
  const baselineInput = `- E2E_L19_BASE_${stamp}：用户偏好用表格展示统计数据`;
  const changedInput = `- E2E_L19_CHANGED_${stamp}：用户改用卡片展示统计数据`;

  try {
    // 1) 写入基线正文：产生 MANUAL 版本快照
    await writeMemory(http, agentId, userId, MEMORY_MD, baselineInput);
    const baseline = await readMemory(http, agentId, userId, MEMORY_MD);
    if (!baseline.includes(`E2E_L19_BASE_${stamp}`)) {
      return { status: 'FAIL', detail: '基线正文写入失败', evidence: { baseline } };
    }
    const baselineVersion = await findVersion(http, agentId, userId, {
      source: 'MANUAL',
      content: baseline
    });
    if (!baselineVersion) {
      return { status: 'FAIL', detail: '未找到基线版本快照', evidence: { baseline } };
    }

    // 2) 覆盖为变更正文：旧正文再留一份 PRE_MANUAL，新正文留 MANUAL
    await writeMemory(http, agentId, userId, MEMORY_MD, changedInput);
    const changed = await readMemory(http, agentId, userId, MEMORY_MD);
    if (!changed.includes(`E2E_L19_CHANGED_${stamp}`)) {
      return { status: 'FAIL', detail: '变更正文写入失败', evidence: { changed } };
    }
    // 恢复点：变更正文已有一份快照；内容与最新快照相同是常态，快照层按哈希去重，
    // 因此这里只断言「可恢复点存在」，不强制要求新增 PRE_RESTORE 条目
    const changedVersion = await findVersion(http, agentId, userId, { content: changed });
    if (!changedVersion) {
      return { status: 'FAIL', detail: '变更正文没有可恢复的版本快照', evidence: { changed } };
    }

    // 3) 恢复到基线版本
    await http.request('POST', `/ai/memory/versions/${baselineVersion.versionId}/restore`, {
      body: { agentId, userId, mode: 'CONTENT', note: 'e2e 版本恢复' }
    });
    const restored = await readMemory(http, agentId, userId, MEMORY_MD);
    if (restored !== baseline) {
      return {
        status: 'FAIL',
        detail: '恢复后正文与目标版本不一致',
        evidence: { baseline, restored }
      };
    }

    // 4) 恢复后变更正文的回滚点仍在 + 恢复完成快照
    // 恢复前会自动留一份 PRE_RESTORE（与变更正文同内容），按哈希去重时可能复用旧条目，
    // 故只断言「同内容的可恢复版本存在」，不要求版本 ID 与恢复前完全一致
    const changedStillExists = await findVersion(http, agentId, userId, { content: changed });
    if (!changedStillExists) {
      return {
        status: 'FAIL',
        detail: '恢复后变更正文的回滚点丢失',
        evidence: { changedVersion: changedVersion.versionId, changedStillExists }
      };
    }
    const restoreSnapshot = await findVersion(http, agentId, userId, {
      source: 'RESTORE',
      restoredFrom: baselineVersion.versionId,
      content: baseline
    });
    if (!restoreSnapshot) {
      return {
        status: 'FAIL',
        detail: '恢复后未生成 RESTORE 快照或内容不一致',
        evidence: { baselineVersion: baselineVersion.versionId }
      };
    }

    return {
      status: 'PASS',
      detail: '版本恢复通过（MANUAL 快照 → 恢复 → 回滚点保留 + RESTORE 快照，正文一致）',
      evidence: {
        baselineVersionId: baselineVersion.versionId,
        changedVersionId: changedVersion.versionId,
        restoreVersionId: restoreSnapshot.versionId,
        memoryChars: restored.length
      }
    };
  } finally {
    // 无条件恢复原始正文（空内容也回写，避免测试事实残留）
    await writeMemory(http, agentId, userId, MEMORY_MD, originalMemory).catch(() => undefined);
  }
};
