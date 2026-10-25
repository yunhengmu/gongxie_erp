/**
 * 首登检查清单类型定义。
 */

/** 清单单项 */
export interface OnboardingItem {
  /** 稳定标识：chat-model / embedding-model / document / first-chat */
  key: string;
  /** 标题 */
  title: string;
  /** 引导说明 */
  description: string;
  /** 是否已完成 */
  done: boolean;
}

/** 清单报告 */
export interface OnboardingReport {
  /** 清单项 */
  items: OnboardingItem[];
  /** 必做项总数 */
  requiredCount: number;
  /** 已完成项数 */
  doneCount: number;
  /** 是否全部完成 */
  allDone: boolean;
}
