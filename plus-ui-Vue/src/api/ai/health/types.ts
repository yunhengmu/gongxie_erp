/**
 * 平台健康自检类型定义（REQ-AI-35）
 */

/** 体检项状态：通过 / 告警 / 失败 / 待探测（本地判定无结论，需点「测试」） */
export type HealthStatus = 'PASS' | 'WARN' | 'FAIL' | 'UNKNOWN';

/** 单个体检项结果 */
export interface HealthCheckItem {
  /** 体检项标识（稳定不变） */
  key: string;
  /** 体检项名称 */
  name: string;
  /** 分类（基础设施 / 模型与向量 / 集成 / 安全与运行时 / 智能体 / 存储） */
  category: string;
  /** 状态 */
  status: HealthStatus;
  /** 判定说明（含实际观测值） */
  detail: string;
  /** 修复建议（通过时为空串） */
  suggestion: string;
  /** 是否支持「测试」做真实连接探测 */
  probeSupported: boolean;
  /** 探测目标：model / milvus / mcp-client */
  probeTarget?: string;
  /** 探测对象主键（模型 / MCP 连接） */
  probeId?: string | number;
  /** 探测耗时（毫秒，仅探测结果有值） */
  elapsedMs?: number;
}

/** 体检报告 */
export interface HealthReport {
  /** 整体结论：PASS / WARN / FAIL */
  overall: HealthStatus;
  /** 体检项总数 */
  total: number;
  /** 通过项数 */
  passCount: number;
  /** 告警项数 */
  warnCount: number;
  /** 失败项数 */
  failCount: number;
  /** 待探测项数 */
  unknownCount: number;
  /** 自检时刻 */
  checkedAt: string;
  /** 体检项明细 */
  items: HealthCheckItem[];
}

/** 探测请求 */
export interface HealthProbePayload {
  /** 探测目标：model / milvus / mcp-client */
  target: string;
  /** 探测对象主键（model / mcp-client 必填） */
  id?: string | number;
}
