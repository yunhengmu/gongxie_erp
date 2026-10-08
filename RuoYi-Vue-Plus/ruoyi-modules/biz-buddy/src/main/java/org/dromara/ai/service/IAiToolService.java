package org.dromara.ai.service;

import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiToolBo;
import org.dromara.ai.domain.vo.AiToolVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 业务工具注册 Service 接口
 *
 * @author BizBuddy
 */
public interface IAiToolService {

    /**
     * 查询工具详情。
     *
     * @param id 主键
     * @return 工具视图对象
     */
    AiToolVo queryById(Long id);

    /**
     * 分页查询工具列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiToolVo> queryPageList(AiToolBo bo, PageQuery pageQuery);

    /**
     * 查询启用中的工具选项（供智能体定义页绑定工具集）。
     *
     * @return 工具列表
     */
    List<AiToolVo> queryOptions();

    /**
     * 新增工具注册。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiToolBo bo);

    /**
     * 修改工具注册。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiToolBo bo);

    /**
     * 按主键集合删除工具注册（被智能体绑定时拒绝删除）。
     *
     * @param ids     主键集合
     * @param isValid 是否执行删除前校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 按编码获取工具实体（不校验状态，供智能体定义校验使用）。
     *
     * @param toolCode 工具编码
     * @return 工具实体，不存在时返回 null
     */
    AiTool queryEntity(String toolCode);

    /**
     * 判断工具编码是否已登记。
     *
     * @param toolCode 工具编码
     * @return 是否已登记
     */
    boolean exists(String toolCode);

    /**
     * 按编码集合查询启用中的工具实体（供装配层使用）。
     *
     * @param toolCodes 工具编码集合
     * @return 工具实体列表
     */
    List<AiTool> queryEnabledByCodes(Collection<String> toolCodes);

}
