import type { OnboardingReport } from '@/api/ai/onboarding/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 首登检查清单（只读；完成状态由服务端现有数据推导）
 */
export const getOnboardingChecklist = (): AxiosPromise<OnboardingReport> => {
  return request({
    url: '/ai/onboarding/checklist',
    method: 'get'
  });
};
