package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiDocumentBo;
import org.dromara.ai.domain.vo.AiChunkContextVo;
import org.dromara.ai.domain.vo.AiChunkVo;
import org.dromara.ai.domain.vo.AiDocumentVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;

/**
 * AI 知识库文档 Service 接口
 *
 * @author BizBuddy
 */
public interface IAiDocumentService {

    /**
     * 分页查询文档列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiDocumentVo> queryPageList(AiDocumentBo bo, PageQuery pageQuery);

    /**
     * 查询文档详情。
     *
     * @param id 主键
     * @return 文档视图对象
     */
    AiDocumentVo queryById(Long id);

    /**
     * 登记文档（文件已由前端上传至对象存储）。
     *
     * @param bo 业务对象
     * @return 是否登记成功
     */
    Boolean insertByBo(AiDocumentBo bo);

    /**
     * 解析文档：提取文本、按知识库切分策略切片并重建切片记录。
     *
     * @param id 文档主键
     * @return 解析后的文档视图对象
     */
    AiDocumentVo parse(Long id);

    /**
     * 按主键集合删除文档（同时清理切片）。
     *
     * @param ids     主键集合
     * @param isValid 是否执行删除前校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 分页查询文档切片。
     *
     * @param docId     文档主键
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiChunkVo> queryChunkPage(Long docId, PageQuery pageQuery);

    /**
     * 查询文档解析后的纯文本（在线预览用）。
     *
     * @param docId 文档主键
     * @return 纯文本内容
     */
    String queryContent(Long docId);

    /**
     * 按切片序号定位切片，并带出前后相邻切片（回答「参考来源」点击用）。
     *
     * <p>访问前校验该文档所属知识库对当前用户可见，避免仅凭 docId 越权读取正文。</p>
     *
     * @param docId      文档主键
     * @param chunkIndex 切片序号（从 0 开始）
     * @param radius     前后各取多少条相邻切片（null 或越界时按 1 处理，上限 3）
     * @return 命中切片及其上下文；序号不存在时 {@code target} 为 null
     */
    AiChunkContextVo queryChunkContext(Long docId, Integer chunkIndex, Integer radius);

}