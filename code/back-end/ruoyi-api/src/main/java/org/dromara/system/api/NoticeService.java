package org.dromara.system.api;

import org.dromara.system.api.domain.NoticeDTO;
import org.dromara.system.api.domain.NoticeDetailDTO;
import org.dromara.system.api.domain.NoticeSaveDTO;

import java.util.List;

/**
 * 通用 公告服务
 *
 * <p>供其它模块（如 AI 工具）以「业务服务」方式发布系统公告，内部复用公告落库与在线广播逻辑，
 * 并由调用方显式传入操作人，避免依赖线程上下文（AI 工具执行在 Reactor 线程）。</p>
 *
 * @author BizBuddy
 */
public interface NoticeService {

    /**
     * 发布系统公告（落库 + 在线广播）。
     *
     * @param dto 公告内容与操作人
     * @return 公告主键
     */
    Long createNoticeByAi(NoticeDTO dto);

    /**
     * 搜索公告（标题模糊匹配，可按类型与状态筛选），按公告 ID 倒序（最新的在前）。
     *
     * @param keyword    标题关键词（可空）
     * @param noticeType 公告类型（1通知 2公告，可空不限）
     * @param status     公告状态（0正常 1关闭，可空不限）
     * @return 公告列表
     */
    List<NoticeDetailDTO> searchNoticesByAi(String keyword, String noticeType, String status);

    /**
     * 按公告 ID 查询详情。
     *
     * @param noticeId 公告 ID
     * @return 公告详情；不存在时返回 null
     */
    NoticeDetailDTO selectNoticeDetailByAi(Long noticeId);

    /**
     * 修改公告（未提供的字段保持原值；不重新向在线用户广播）。
     *
     * @param dto 公告修改入参
     * @return 是否修改成功
     */
    boolean updateNoticeByAi(NoticeSaveDTO dto);

    /**
     * 按公告 ID 删除（物理删除，与页面一致）。
     *
     * @param noticeId 公告 ID
     * @return 是否删除成功
     */
    boolean deleteNoticeByAi(Long noticeId);

}