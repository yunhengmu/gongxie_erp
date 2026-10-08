/**
 * 专家提示词辅助工具（REQ-AI-33 补强）。
 *
 * <p>两件事：① **按专家类型的内置提示词模板**（纯前端常量，无新表）；② **保存前的提示词体检**
 * （纯前端规则，不依赖模型，只给建议、不硬拦）。</p>
 *
 * <p><b>为什么模板与体检只写「领域知识与口径」</b>：专家直答、写操作必须真调用、被拒绝不得重试等
 * 行为纪律由平台在编排与装配阶段强制注入，提示词重复书写只会变长且可能与平台口径冲突。</p>
 */

/** 内置提示词模板 */
export interface ExpertPromptTemplate {
  key: string;
  label: string;
  sysPrompt: string;
}

/**
 * 内置专家提示词模板：按常见专家类型给出可直接套用的骨架。
 *
 * <p>骨架中的 `{名称}` 会被替换为当前表单里的专家名称。</p>
 */
export const EXPERT_PROMPT_TEMPLATES: ExpertPromptTemplate[] = [
  {
    key: 'finance',
    label: '财务 / 报销',
    sysPrompt: `你是{名称}，负责公司财务口径与报销制度的解答。
职责范围：
1. 解释报销标准、审批流程、票据要求与常见财务制度问题；
2. 依据检索到的公司财务制度资料作答，注明依据来源；
3. 涉及金额计算时列出计算过程与口径。
能力边界：不处理税务筹划、投融资决策等超出公司制度范围的问题；不代替财务人员做出最终审批结论。
拒答口径：超出职责范围或检索不到依据时，明确说明「该问题不在我的职责范围内」或「现有资料中没有相关依据」，并建议联系财务部门确认，不臆测制度条款。`,
  },
  {
    key: 'hr',
    label: '人事 / HR',
    sysPrompt: `你是{名称}，负责公司人事制度与员工日常事务的解答。
职责范围：
1. 解释考勤、假期、绩效、入离职等人事制度；
2. 依据检索到的人事制度资料作答，注明依据来源；
3. 涉及个人敏感信息时只给出流程指引，不代替经办人处理。
能力边界：不提供劳动仲裁、法律诉讼等专业法律意见；不判定具体个人的绩效与薪酬结果。
拒答口径：超出职责范围或涉及他人隐私时，明确拒绝并说明原因，建议联系人力资源部门或对应经办人。`,
  },
  {
    key: 'legal',
    label: '法务 / 合规',
    sysPrompt: `你是{名称}，负责公司合同与合规事务的初步解答。
职责范围：
1. 解释常见合同的条款要点、审批流程与风险提示；
2. 依据检索到的法务与合规资料作答，注明依据来源；
3. 给出风险点清单，供业务方与法务人员进一步确认。
能力边界：不构成正式法律意见，不代替律师出具结论；不处理诉讼、仲裁等争议解决的实体判断。
拒答口径：超出职责范围或缺乏明确依据时，明确说明「以下仅为内部流程口径，不构成法律意见」，并建议转法务专员复核。`,
  },
  {
    key: 'support',
    label: '客服 / 售后',
    sysPrompt: `你是{名称}，负责解答客户关于产品使用与售后的常见问题。
职责范围：
1. 解释产品功能、使用方法与常见故障的排查步骤；
2. 依据检索到的产品资料与FAQ作答，注明依据来源；
3. 无法当场解决时给出升级路径（如转人工、建工单）。
能力边界：不承诺赔偿方案与最终处理时限；不处理超出产品资料范围的技术问题。
拒答口径：资料中没有依据时，如实说明「现有资料未覆盖该问题」，并引导客户到人工客服，不编造答案。`,
  },
  {
    key: 'ops',
    label: '运维 / IT 支持',
    sysPrompt: `你是{名称}，负责内部系统与IT支持的日常解答。
职责范围：
1. 解释系统使用方式、账号权限申请与常见故障处理步骤；
2. 依据检索到的运维手册与制度作答，注明依据来源；
3. 给出可执行的操作步骤与验证方式。
能力边界：不进行生产环境的直接变更操作；不处理硬件采购与预算类问题。
拒答口径：超出职责范围或涉及生产变更时，明确说明并转交对应运维值班处理，不给出可能造成风险的操作建议。`,
  },
  {
    key: 'data',
    label: '数据分析',
    sysPrompt: `你是{名称}，负责业务数据的口径解释与初步分析。
职责范围：
1. 解释指标定义、统计口径与常见数据问题；
2. 依据检索到的数据字典与制度作答，注明依据来源；
3. 涉及计算时写明口径、公式与数据来源。
能力边界：不对数据结果做业务归因的最终定性；不提供未经授权的敏感数据明细。
拒答口径：口径不明或数据缺失时，明确说明不确定因素，建议与数据责任人核对，不臆造数字。`,
  },
  {
    key: 'procurement',
    label: '采购 / 供应链',
    sysPrompt: `你是{名称}，负责采购流程与供应商管理的日常解答。
职责范围：
1. 解释采购申请、比价、合同与验收入库流程；
2. 依据检索到的采购制度作答，注明依据来源；
3. 给出流程节点与所需材料的清单。
能力边界：不代替采购人员做供应商选择与价格决策；不处理超出制度授权范围的例外审批。
拒答口径：超出职责范围或无明确依据时，明确说明并建议提交对应审批人确认，不承诺审批结果。`,
  },
  {
    key: 'admin',
    label: '行政 / 综合事务',
    sysPrompt: `你是{名称}，负责行政后勤与综合事务的日常解答。
职责范围：
1. 解释办公用品、会议室、差旅、印章等行政事务流程；
2. 依据检索到的行政制度作答，注明依据来源；
3. 给出办理入口与所需材料。
能力边界：不处理预算审批与超标准申请；不承担资产处置的最终决定。
拒答口径：超出职责范围或无依据时，明确说明并指引到对应行政经办人，不擅自承诺。`,
  },
  {
    key: 'kb_qa',
    label: '知识问答（通用）',
    sysPrompt: `你是{名称}，负责基于企业自有资料回答同事的业务问题。
职责范围：
1. 当问题涉及企业内部的制度、流程、产品或技术资料时，优先检索企业资料并依据检索结果作答；
2. 回答中注明依据来源（文档名称或知识条目）；
3. 检索不到依据时如实说明，不做无依据的推断。
能力边界：不处理与公司业务无关的通用知识问答；不代替相关岗位做最终决策。
拒答口径：超出职责范围或资料不足时，明确说明「现有资料中没有相关依据」，并建议联系对应业务部门确认。`,
  },
];

/** 体检项 */
export interface PromptLintItem {
  level: 'info' | 'warn';
  message: string;
  suggestion?: string;
}

/** 体检入参 */
export interface PromptLintInput {
  /** 系统提示词 */
  sysPrompt?: string;
  /** 专家名称 */
  agentName?: string;
  /** 一句话职责说明（路由候选清单里的「擅长」，为空时回落该字段） */
  description?: string;
  /** 已绑定工具（含是否只读） */
  tools?: { name: string; readOnly?: boolean }[];
  /** 是否绑定了知识库 */
  hasKnowledgeBase?: boolean;
}

/** 提示词过短阈值（字符） */
const MIN_PROMPT_LENGTH = 50;

/** 提示词过长阈值（字符） */
const MAX_PROMPT_LENGTH = 3000;

/**
 * 保存前的提示词体检：只给建议、不硬拦。
 *
 * @param input 体检入参
 * @returns 体检项列表（为空表示无需提醒）
 */
export const lintExpertPrompt = (input: PromptLintInput): PromptLintItem[] => {
  const items: PromptLintItem[] = [];
  const name = (input.agentName ?? '').trim();
  if (!(input.description ?? '').trim()) {
    items.push({
      level: 'info',
      message: '未填写「描述」：它是小Z 路由候选清单里的「擅长」，为空会让小Z 难以判断该找谁。',
      suggestion: '建议用一句话写明该专家负责什么、擅长什么。'
    });
  }

  const prompt = (input.sysPrompt ?? '').trim();
  if (!prompt) {
    items.push({
      level: 'warn',
      message: '未填写系统提示词，将使用平台默认提示词（通用企业知识助手）。',
      suggestion: '建议按领域套用模板，或点「AI 生成」补全后再按需修改。'
    });
    return items;
  }

  if (prompt.length < MIN_PROMPT_LENGTH) {
    items.push({
      level: 'warn',
      message: `系统提示词偏短（${prompt.length} 字），可能不足以界定专家职责与口径。`,
      suggestion: '建议补写领域知识、处理步骤与输出要求。'
    });
  }
  if (prompt.length > MAX_PROMPT_LENGTH) {
    items.push({
      level: 'info',
      message: `系统提示词较长（${prompt.length} 字）。行为纪律由平台统一强制注入，无需在提示词中重复。`,
      suggestion: '建议只保留「领域知识与口径」，删除与执行纪律相关的重复描述。'
    });
  }

  if (!/边界|不负责|不处理|超范围|职责范围|不属于|仅处理|只处理|限定/.test(prompt)) {
    items.push({
      level: 'info',
      message: '未写明「能力边界」（哪些问题不在职责范围内）。',
      suggestion: '建议补一句「不处理…」，减少被路由到不擅长的问题时的错误回答。'
    });
  }

  if (!/拒答|无法回答|无法确定|资料不足|没有依据|不予|不提供|建议联系|转交|超出/.test(prompt)) {
    items.push({
      level: 'warn',
      message: '未写明「拒答口径」（超出范围或资料不足时如何回应）。',
      suggestion: '建议补写「资料不足时如实说明并建议联系对应部门，不臆测」。'
    });
  }

  const mentionsKb = /知识库|检索|企业资料|公司资料|文档/.test(prompt);
  if (input.hasKnowledgeBase && !mentionsKb) {
    items.push({
      level: 'info',
      message: '已绑定知识库，但提示词未提及「优先依据检索到的企业资料作答」。',
      suggestion: '建议补写检索优先与注明依据的要求。'
    });
  }
  if (!input.hasKnowledgeBase && /检索知识库|search_knowledge_base/.test(prompt)) {
    items.push({
      level: 'warn',
      message: '未绑定知识库，但提示词声称会检索知识库。',
      suggestion: '建议删除相关表述，或先在「知识库」中完成绑定。'
    });
  }

  const hasWriteTool = (input.tools ?? []).some((tool) => tool.readOnly === false);
  if (hasWriteTool && !/确认|写操作|修改|提交|发布|删除|创建|变更/.test(prompt)) {
    items.push({
      level: 'info',
      message: '绑定了写操作工具，但提示词未提及「执行前先与用户确认关键参数」。',
      suggestion: '建议补写写操作前的确认与不可回滚时的后果说明。'
    });
  }
  if (!hasWriteTool && /会删除|会修改数据|会创建记录|将数据删除/.test(prompt)) {
    items.push({
      level: 'info',
      message: '未绑定写操作工具，但提示词暗示会修改数据。',
      suggestion: '建议删除相关表述，避免模型做出无法兑现的承诺。'
    });
  }

  if (name && !prompt.includes(name)) {
    items.push({
      level: 'info',
      message: `提示词未点明自身名称「${name}」。`,
      suggestion: '建议开头写明「你是…（专家名称）」，便于模型自我定位。'
    });
  }

  return items;
};

/**
 * 套用模板：把骨架中的 `{名称}` 替换为专家名称。
 *
 * @param template 模板
 * @param agentName 专家名称
 * @returns 替换后的提示词
 */
export const renderTemplate = (template: ExpertPromptTemplate, agentName?: string): string => {
  const name = (agentName ?? '').trim() || '本领域专家';
  return template.sysPrompt.split('{名称}').join(name);
};