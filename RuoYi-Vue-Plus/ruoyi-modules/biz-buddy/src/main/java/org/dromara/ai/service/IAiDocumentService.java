package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiDocumentBo;
import org.dromara.ai.domain.vo.AiChunkContextVo;
import org.dromara.ai.domain.vo.AiChunkVo;
import org.dromara.ai.domain.vo.AiDocumentBatchStatusVo;
import org.dromara.ai.domain.vo.AiDocumentBatchVo;
import org.dromara.ai.domain.vo.AiDocumentVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

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
     * 解析文档，**跳过「正在解析中」守卫**（调用方已在入队前用条件更新占位）。
     *
     * <p>仅供批量任务线程使用；页面单条操作请用 {@link #parse(Long)}。</p>
     *
     * @param id 文档主键
     * @return 解析后的文档视图对象
     */
    AiDocumentVo parseClaimed(Long id);

    /**
     * 批量提交解析（异步）：立即返回受理与跳过明细，进度由 {@link #queryBatchStatus} 轮询。
     *
     * @param ids 文档主键集合
     * @return 提交回执
     */
    AiDocumentBatchVo submitParseBatch(Collection<Long> ids);

    /**
     * 批量提交向量化（异步）：仅受理已解析完成的文档。
     *
     * @param ids 文档主键集合
     * @return 提交回执
     */
    AiDocumentBatchVo submitVectorizeBatch(Collection<Long> ids);

    /**
     * 批量提交「一键入库」（异步）：逐文档先解析、成功后串接向量化。
     *
     * @param ids 文档主键集合
     * @return 提交回执
     */
    AiDocumentBatchVo submitIngestBatch(Collection<Long> ids);

    /**
     * 查询批量处理进度（轻量回执，不附带文件地址等大字段）。
     *
     * @param ids 文档主键集合
     * @return 进度列表（按入参顺序，已删除的文档会被忽略）
     */
    List<AiDocumentBatchStatusVo> queryBatchStatus(Collection<Long> ids);

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