package org.dromara.ai.service;

import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiScenarioPackMcpTool;
import org.dromara.ai.domain.AiScenarioPackTool;
import org.dromara.ai.domain.bo.AiScenarioPackBo;
import org.dromara.ai.domain.vo.AiScenarioPackMcpOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * AI 场景包 Service 接口
 *
 * <p>场景包按角色 / 部门打包「业务工具 + 外部 MCP 工具 + 技能」，用户在创建任务时选定并**固化到会话**，
 * 包内能力叠加到智能体既有绑定之上以增强对话。</p>
 *
 * @author BizBuddy
 */
public interface IAiScenarioPackService {

    /**
     * 分页查询场景包（管理页全量）
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiScenarioPackVo> queryPageList(AiScenarioPackBo bo, PageQuery pageQuery);

    /**
     * 查询场景包详情（含三类绑定集合）
     *
     * @param id 主键
     * @return 场景包详情
     */
    AiScenarioPackVo queryById(Long id);

    /**
     * 新增场景包（含三类绑定集合）
     *
     * @param bo 业务对象
     * @return 是否成功
     */
    Boolean insertByBo(AiScenarioPackBo bo);

    /**
     * 修改场景包（编码不可修改；绑定集合「先删后插」全量覆盖）
     *
     * @param bo 业务对象
     * @return 是否成功
     */
    Boolean updateByBo(AiScenarioPackBo bo);

    /**
     * 删除场景包（逻辑删除本体 + 物理删除三类绑定；历史会话保留快照，下一次对话自动降级为「无包」）
     *
     * @param ids 主键集合
     * @return 是否成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids);

    /**
     * 查询「当前提问用户可选」的场景包选项（启用中 + 生效范围内），供创建任务时选择
     *
     * @return 选项列表
     */
    List<AiScenarioPackOptionVo> queryOptions();

    /**
     * 查询外部 MCP 工具选项（启用中的连接 + 其工具白名单），供管理页勾选
     *
     * @return 选项列表
     */
    List<AiScenarioPackMcpOptionVo> queryMcpToolOptions();

    /**
     * 取「启用中」的场景包；为空、不存在或已停用时返回 {@code null}（对话链路静默降级为「无包」）。
     *
     * @param id 场景包主键
     * @return 场景包实体；不可用时返回 null
     */
    AiScenarioPack getEnabledPackOrNull(Long id);

    /**
     * 校验场景包可被当前用户选用（存在 + 启用中 + 在当前用户生效范围内），不通过时抛出业务异常。
     *
     * @param id 场景包主键（可空：为空表示不选包，直接通过）
     */
    void validateSelectable(Long id);

    /**
     * 查询包内业务工具绑定（含授权决策）。
     *
     * @param packId 场景包主键
     * @return 绑定集合（按主键升序）
     */
    List<AiScenarioPackTool> queryPackTools(Long packId);

    /**
     * 查询包内外部 MCP 工具绑定（含授权决策）。
     *
     * @param packId 场景包主键
     * @return 绑定集合（按主键升序）
     */
    List<AiScenarioPackMcpTool> queryPackMcpTools(Long packId);

    /**
     * 查询包内技能主键集合。
     *
     * @param packId 场景包主键
     * @return 技能主键集合（按绑定顺序）
     */
    List<Long> queryPackSkillIds(Long packId);

}