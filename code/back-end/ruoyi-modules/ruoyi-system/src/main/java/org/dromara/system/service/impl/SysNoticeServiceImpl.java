package org.dromara.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.enums.PushSourceEnum;
import org.dromara.common.core.enums.PushTypeEnum;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.DictService;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.ObjectUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.LambdaQueryBuilder;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.system.api.MessageService;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.domain.NoticeDTO;
import org.dromara.system.api.domain.NoticeDetailDTO;
import org.dromara.system.api.domain.NoticeSaveDTO;
import org.dromara.system.api.domain.PushPayloadDTO;
import org.dromara.system.domain.SysNotice;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.bo.SysNoticeBo;
import org.dromara.system.domain.vo.SysNoticeVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysNoticeMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.ISysNoticeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 公告 服务层实现
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@Service
public class SysNoticeServiceImpl implements ISysNoticeService, NoticeService {

    /**
     * 公告状态：正常
     */
    private static final String STATUS_NORMAL = "0";

    private final SysNoticeMapper noticeMapper;
    private final SysUserMapper userMapper;
    private final DictService dictService;
    private final MessageService messageService;

    /**
     * 分页查询通知公告列表
     *
     * @param notice    查询条件
     * @param pageQuery 分页参数
     * @return 通知公告分页列表
     */
    @Override
    public PageResult<SysNoticeVo> selectPageNoticeList(SysNoticeBo notice, PageQuery pageQuery) {
        LambdaQueryWrapper<SysNotice> lqw = buildQueryWrapper(notice);
        Page<SysNoticeVo> page = noticeMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 查询公告信息
     *
     * @param noticeId 公告ID
     * @return 公告信息
     */
    @Override
    public SysNoticeVo selectNoticeById(Long noticeId) {
        return noticeMapper.selectVoById(noticeId);
    }

    /**
     * 查询公告列表
     *
     * @param notice 公告信息
     * @return 公告集合
     */
    @Override
    public List<SysNoticeVo> selectNoticeList(SysNoticeBo notice) {
        LambdaQueryWrapper<SysNotice> lqw = buildQueryWrapper(notice);
        return noticeMapper.selectVoList(lqw);
    }

    /**
     * 构造公告列表查询条件。
     *
     * @param bo 公告筛选条件
     * @return 包含标题、类型、创建人和排序条件的查询包装器
     */
    private LambdaQueryWrapper<SysNotice> buildQueryWrapper(SysNoticeBo bo) {
        LambdaQueryBuilder<SysNotice> builder = QueryBuilder.lambda(SysNotice.class)
            .likeIfText(SysNotice::getNoticeTitle, bo.getNoticeTitle())
            .eqIfText(SysNotice::getNoticeType, bo.getNoticeType());
        if (StringUtils.isNotBlank(bo.getCreateByName())) {
            SysUserVo sysUser = userMapper.lambda().eq(SysUser::getUserName, bo.getCreateByName()).voOne();
            builder.eq(SysNotice::getCreateBy, ObjectUtils.notNullGetter(sysUser, SysUserVo::getUserId));
        }
        return builder.orderByAsc(SysNotice::getNoticeId).build();
    }

    /**
     * 新增公告
     *
     * @param bo 公告信息
     * @return 结果
     */
    @Override
    public int insertNotice(SysNoticeBo bo) {
        SysNotice notice = MapstructUtils.convert(bo, SysNotice.class);
        int rows = noticeMapper.insert(notice);
        bo.setNoticeId(notice.getNoticeId());
        return rows;
    }

    /**
     * 发布公告：向在线用户广播公告摘要。
     *
     * @param bo 公告信息
     */
    @Override
    public void publishNotice(SysNoticeBo bo) {
        String type = dictService.getDictLabel("sys_notice_type", bo.getNoticeType());
        Map<String, Object> data = new HashMap<>(8);
        data.put("noticeType", bo.getNoticeType());
        data.put("noticeTypeLabel", type);
        data.put("noticeTitle", bo.getNoticeTitle());
        data.put("noticeId", bo.getNoticeId());
        data.put("noticeContent", bo.getNoticeContent());
        data.put("status", bo.getStatus());
        messageService.publishAll(PushPayloadDTO.of(
            PushTypeEnum.NOTICE,
            PushSourceEnum.NOTICE,
            "[" + type + "] " + bo.getNoticeTitle(),
            data,
            "/system/notice?noticeId=" + bo.getNoticeId()
        ));
    }

    /**
     * 发布公告（落库 + 广播），操作人由调用方显式传入。
     *
     * <p>AI 工具执行在 Reactor 线程，无法通过线程上下文拿到登录用户，因此这里显式写入 create_by，
     * 保证公告归属与审计链路正确（MyBatis-Plus 的注入处理器仅在 create_by 为空时才会用登录态填充）。</p>
     *
     * @param dto 公告内容与操作人
     * @return 公告主键
     */
    @Override
    public Long createNoticeByAi(NoticeDTO dto) {
        SysNoticeBo bo = new SysNoticeBo();
        bo.setNoticeTitle(dto.getNoticeTitle());
        bo.setNoticeType(StringUtils.isBlank(dto.getNoticeType()) ? "1" : dto.getNoticeType());
        bo.setNoticeContent(dto.getNoticeContent());
        bo.setStatus(StringUtils.isBlank(dto.getStatus()) ? STATUS_NORMAL : dto.getStatus());
        SysNotice notice = MapstructUtils.convert(bo, SysNotice.class);
        if (dto.getOperatorUserId() != null) {
            notice.setCreateBy(dto.getOperatorUserId());
        }
        noticeMapper.insert(notice);
        bo.setNoticeId(notice.getNoticeId());
        publishNotice(bo);
        return notice.getNoticeId();
    }

    /**
     * 修改公告
     *
     * @param bo 公告信息
     * @return 结果
     */
    @Override
    public int updateNotice(SysNoticeBo bo) {
        SysNotice notice = MapstructUtils.convert(bo, SysNotice.class);
        return noticeMapper.updateById(notice);
    }

    /**
     * 删除公告对象
     *
     * @param noticeId 公告ID
     * @return 结果
     */
    @Override
    public int deleteNoticeById(Long noticeId) {
        return noticeMapper.deleteById(noticeId);
    }

    /**
     * 批量删除公告信息
     *
     * @param noticeIds 需要删除的公告ID
     * @return 结果
     */
    @Override
    public int deleteNoticeByIds(Long[] noticeIds) {
        return noticeMapper.deleteByIds(Arrays.asList(noticeIds));
    }

    /**
     * 搜索公告（标题模糊匹配，可按类型与状态筛选），按公告 ID 倒序（最新的在前）。
     *
     * <p>AI 工具链路：{@code sys_notice} 无数据权限注解，故直接按条件查全表；
     * 越权面由工具侧的菜单权限（{@code system:notice:list}）收敛。</p>
     *
     * @param keyword    标题关键词（可空）
     * @param noticeType 公告类型（1通知 2公告，可空不限）
     * @param status     公告状态（0正常 1关闭，可空不限）
     * @return 公告列表
     */
    @Override
    public List<NoticeDetailDTO> searchNoticesByAi(String keyword, String noticeType, String status) {
        LambdaQueryWrapper<SysNotice> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(keyword), SysNotice::getNoticeTitle, keyword);
        lqw.eq(StringUtils.isNotBlank(noticeType), SysNotice::getNoticeType, noticeType);
        lqw.eq(StringUtils.isNotBlank(status), SysNotice::getStatus, status);
        lqw.orderByDesc(SysNotice::getNoticeId);
        return toDetails(noticeMapper.selectList(lqw));
    }

    /**
     * 按公告 ID 查询详情（AI 工具链路）。
     *
     * @param noticeId 公告 ID
     * @return 公告详情；不存在时返回 null
     */
    @Override
    public NoticeDetailDTO selectNoticeDetailByAi(Long noticeId) {
        if (noticeId == null) {
            return null;
        }
        SysNotice notice = noticeMapper.selectById(noticeId);
        if (notice == null) {
            return null;
        }
        List<NoticeDetailDTO> details = toDetails(List.of(notice));
        return details.isEmpty() ? null : details.get(0);
    }

    /**
     * 修改公告（未提供的字段保持原值；不重新向在线用户广播）。
     *
     * <p>与页面「修改」保持一致：页面修改也不广播（只有新增会推送）。字段为 {@code null} 表示不修改，
     * 非 null（含空串）表示按该值覆盖，故空白值分别回落到原记录。</p>
     *
     * @param dto 公告修改入参
     * @return 是否修改成功
     */
    @Override
    public boolean updateNoticeByAi(NoticeSaveDTO dto) {
        if (dto == null || dto.getNoticeId() == null) {
            throw new ServiceException("公告 ID 不能为空");
        }
        SysNotice exists = noticeMapper.selectById(dto.getNoticeId());
        if (exists == null) {
            throw new ServiceException("公告不存在或已被删除（notice_id=" + dto.getNoticeId() + "）");
        }
        SysNoticeBo bo = new SysNoticeBo();
        bo.setNoticeId(dto.getNoticeId());
        bo.setNoticeTitle(keepIfBlank(dto.getNoticeTitle(), exists.getNoticeTitle()));
        bo.setNoticeType(keepIfBlank(dto.getNoticeType(), exists.getNoticeType()));
        bo.setNoticeContent(keepIfNull(dto.getNoticeContent(), exists.getNoticeContent()));
        bo.setStatus(keepIfBlank(dto.getStatus(), exists.getStatus()));
        bo.setRemark(keepIfNull(dto.getRemark(), exists.getRemark()));
        return updateNotice(bo) > 0;
    }

    /**
     * 按公告 ID 删除（物理删除，与页面一致）。
     *
     * @param noticeId 公告 ID
     * @return 是否删除成功
     */
    @Override
    public boolean deleteNoticeByAi(Long noticeId) {
        if (noticeId == null) {
            throw new ServiceException("公告 ID 不能为空");
        }
        SysNotice exists = noticeMapper.selectById(noticeId);
        if (exists == null) {
            throw new ServiceException("公告不存在或已被删除（notice_id=" + noticeId + "）");
        }
        return deleteNoticeById(noticeId) > 0;
    }

    /**
     * 把公告实体转换为传输对象并补齐创建人昵称。
     *
     * @param list 公告实体列表
     * @return 传输对象列表
     */
    private List<NoticeDetailDTO> toDetails(List<SysNotice> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = list.stream()
            .map(SysNotice::getCreateBy)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> nickNames = new HashMap<>(8);
        if (!userIds.isEmpty()) {
            for (SysUser user : userMapper.selectByIds(userIds)) {
                nickNames.put(user.getUserId(), user.getNickName());
            }
        }
        List<NoticeDetailDTO> rows = new ArrayList<>(list.size());
        for (SysNotice notice : list) {
            NoticeDetailDTO dto = new NoticeDetailDTO();
            dto.setNoticeId(notice.getNoticeId());
            dto.setNoticeTitle(notice.getNoticeTitle());
            dto.setNoticeType(notice.getNoticeType());
            dto.setNoticeContent(notice.getNoticeContent());
            dto.setStatus(notice.getStatus());
            dto.setRemark(notice.getRemark());
            dto.setCreateBy(notice.getCreateBy());
            dto.setCreateByName(nickNames.get(notice.getCreateBy()));
            dto.setCreateTime(notice.getCreateTime());
            dto.setUpdateTime(notice.getUpdateTime());
            rows.add(dto);
        }
        return rows;
    }

    /**
     * 文本字段回填：入参为空白时保持原值。
     *
     * @param provided 入参值
     * @param original 原值
     * @return 生效值
     */
    private static String keepIfBlank(String provided, String original) {
        return StringUtils.isBlank(provided) ? original : provided;
    }

    /**
     * 文本字段回填：入参为 null 时保持原值（空串视为显式清空）。
     *
     * @param provided 入参值
     * @param original 原值
     * @return 生效值
     */
    private static String keepIfNull(String provided, String original) {
        return provided == null ? original : provided;
    }
}
