package org.dromara.ai.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.ai.domain.bo.AiModelConfigBo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.domain.vo.AiModelTestVo;

import java.util.Collection;
import java.util.List;

/**
 * AI 模型配置 Service 接口
 *
 * @author BizBuddy
 */
public interface IAiModelConfigService {

    /**
     * 查询模型配置详情。
     *
     * @param id 主键
     * @return 模型配置视图对象
     */
    AiModelConfigVo queryById(Long id);

    /**
     * 分页查询模型配置列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiModelConfigVo> queryPageList(AiModelConfigBo bo, PageQuery pageQuery);

    /**
     * 查询启用中的模型选项（供知识库等页面下拉选择）。
     *
     * @param modelType 模型类型，为空表示不限
     * @return 模型配置列表
     */
    List<AiModelConfigVo> queryOptions(String modelType);

    /**
     * 新增模型配置。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiModelConfigBo bo);

    /**
     * 复制模型配置：按源配置整行复制既有业务列，名称按「原名 + copy」自动唯一化。
     *
     * <p>口径：密钥 **密文原样搬运**（不解密、不重加密）、状态 **原样复制**。</p>
     *
     * @param id 源配置主键
     * @return 副本视图对象（含自动生成的名称与新主键）
     */
    AiModelConfigVo copyById(Long id);

    /**
     * 修改模型配置。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiModelConfigBo bo);

    /**
     * 按主键集合删除模型配置。
     *
     * @param ids     主键集合
     * @param isValid 是否执行删除前校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 连通性测试：按传入配置（或已保存的密钥）发起一次真实调用。
     *
     * @param bo 业务对象，id 不为空且未填密钥时复用库中密钥
     * @return 测试结果
     */
    AiModelTestVo testConnection(AiModelConfigBo bo);

    /**
     * 解密指定模型的密钥（仅供服务端发起模型调用使用）。
     *
     * @param id 模型配置主键
     * @return 明文密钥，未配置时返回 null
     */
    String decryptApiKey(Long id);

}