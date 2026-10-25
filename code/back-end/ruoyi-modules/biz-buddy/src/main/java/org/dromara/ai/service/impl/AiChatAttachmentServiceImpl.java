package org.dromara.ai.service.impl;

import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AiVisionGuard;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.domain.bo.AiChatAttachmentBo;
import org.dromara.ai.domain.vo.AiChatAttachmentVo;
import org.dromara.ai.mapper.AiMessageAttachmentMapper;
import org.dromara.ai.service.IAiChatAttachmentService;
import org.dromara.ai.util.AiDocumentParser;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 对话附件 Service 实现
 *
 * @author BizBuddy
 */
@Service
@RequiredArgsConstructor
public class AiChatAttachmentServiceImpl implements IAiChatAttachmentService {

    /**
     * 允许的图片后缀（与 AgentScope 多模态图片格式对齐）
     */
    private static final Set<String> IMAGE_SUFFIXES = Set.of("png", "jpg", "jpeg", "gif", "webp", "heic", "heif");

    /**
     * 图片后缀 → MIME 类型
     */
    private static final Map<String, String> IMAGE_MIME = Map.of(
        "png", "image/png",
        "jpg", "image/jpeg",
        "jpeg", "image/jpeg",
        "gif", "image/gif",
        "webp", "image/webp",
        "heic", "image/heic",
        "heif", "image/heif");

    private final AiMessageAttachmentMapper baseMapper;

    private final OssService ossService;

    private final AiProperties aiProperties;

    /**
     * 图片附件的「支持视觉」能力校验（多模态易踩坑：非视觉模型会白屏式报错）
     */
    private final AiVisionGuard visionGuard;

    @Override
    public AiChatAttachmentVo register(Long sessionId, AiChatAttachmentBo bo) {
        OssDTO oss = resolveOss(bo.getOssId());
        String suffix = resolveSuffix(bo, oss);
        String fileName = resolveFileName(bo, oss, suffix);
        boolean image = IMAGE_SUFFIXES.contains(suffix);
        boolean document = AiDocumentParser.supported(suffix);
        if (!image && !document) {
            throw new ServiceException("不支持的文件类型：." + suffix
                + "，图片仅支持 png / jpg / jpeg / gif / webp / heic / heif；文档仅支持 PDF / docx / xlsx / pptx / 文本类文件");
        }
        // 实际字节交由服务端下载后校验（OSS 元信息不含大小，且不信任客户端声明）
        byte[] bytes = downloadBytes(bo.getOssId());
        long limit = image ? aiProperties.getChat().getImageMaxBytes() : aiProperties.getChat().getDocumentMaxBytes();
        if (bytes.length > limit) {
            throw new ServiceException((image ? "图片" : "文档") + "大小超过限制（最大 " + humanSize(limit) + "）");
        }
        if (image) {
            // 图片走多模态 ImageBlock，非视觉模型会报错或被忽略：在此按「支持视觉」声明提前拦下并给出明确提示
            visionGuard.assertImageSupported(sessionId);
        }
        AiMessageAttachment entity = new AiMessageAttachment();
        entity.setSessionId(sessionId);
        entity.setFileType(image ? AiMessageAttachment.TYPE_IMAGE : AiMessageAttachment.TYPE_DOCUMENT);
        entity.setFileName(fileName);
        entity.setFileSuffix(suffix);
        entity.setFileSize((long) bytes.length);
        entity.setMimeType(image ? IMAGE_MIME.get(suffix) : null);
        entity.setOssId(bo.getOssId());
        if (document) {
            String text = AiDocumentParser.extract(bytes, suffix);
            if (StringUtils.isBlank(text)) {
                throw new ServiceException("未从文档中解析出文本内容，可能是扫描件或空文档");
            }
            entity.setParsedContent(text);
        }
        baseMapper.insert(entity);
        return toVo(entity, resolveUrlQuietly(entity.getOssId()));
    }

    @Override
    public List<AiMessageAttachment> bindToMessage(Long sessionId, Long messageId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(attachmentIds.stream().filter(Objects::nonNull).toList()));
        if (distinct.isEmpty()) {
            return List.of();
        }
        int max = aiProperties.getChat().getMaxAttachmentsPerMessage();
        if (distinct.size() > max) {
            throw new ServiceException("单条消息最多携带 " + max + " 个附件");
        }
        List<AiMessageAttachment> list = baseMapper.selectByIds(distinct);
        if (list.size() != distinct.size()) {
            throw new ServiceException("附件不存在或已被删除，请重新上传");
        }
        for (AiMessageAttachment attachment : list) {
            if (!sessionId.equals(attachment.getSessionId())) {
                throw new ServiceException("附件不属于该会话，请重新上传");
            }
            if (attachment.getMessageId() != null && !attachment.getMessageId().equals(messageId)) {
                throw new ServiceException("附件已被其他消息使用，请重新上传");
            }
        }
        for (AiMessageAttachment attachment : list) {
            if (messageId.equals(attachment.getMessageId())) {
                continue;
            }
            AiMessageAttachment update = new AiMessageAttachment();
            update.setId(attachment.getId());
            update.setMessageId(messageId);
            baseMapper.updateById(update);
        }
        return list;
    }

    @Override
    public List<AiMessageAttachment> listByMessageId(Long messageId) {
        if (messageId == null) {
            return List.of();
        }
        return baseMapper.selectList(Wrappers.<AiMessageAttachment>lambdaQuery()
            .eq(AiMessageAttachment::getMessageId, messageId)
            .orderByAsc(AiMessageAttachment::getId));
    }

    @Override
    public Map<Long, List<AiChatAttachmentVo>> listVoMapByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        List<AiMessageAttachment> list = baseMapper.selectList(Wrappers.<AiMessageAttachment>lambdaQuery()
            .in(AiMessageAttachment::getMessageId, messageIds)
            .orderByAsc(AiMessageAttachment::getId));
        Map<Long, List<AiChatAttachmentVo>> result = new LinkedHashMap<>();
        for (AiMessageAttachment attachment : list) {
            result.computeIfAbsent(attachment.getMessageId(), key -> new ArrayList<>())
                .add(toVo(attachment, resolveUrlQuietly(attachment.getOssId())));
        }
        return result;
    }

    @Override
    public byte[] downloadBytes(Long ossId) {
        String url = resolveUrl(ossId);
        byte[] bytes;
        try {
            bytes = HttpUtil.downloadBytes(url);
        } catch (Exception e) {
            throw new ServiceException("文件下载失败：" + e.getMessage());
        }
        if (bytes == null || bytes.length == 0) {
            throw new ServiceException("文件下载失败，请检查对象存储配置与文件是否仍然存在");
        }
        return bytes;
    }

    @Override
    public AiChatAttachmentVo refreshUrl(Long sessionId, Long attachmentId) {
        AiMessageAttachment attachment = attachmentId == null ? null : baseMapper.selectById(attachmentId);
        if (attachment == null || !Objects.equals(sessionId, attachment.getSessionId())) {
            throw new ServiceException("附件不存在或不属于该会话");
        }
        return toVo(attachment, resolveUrlQuietly(attachment.getOssId()));
    }

    @Override
    public void deletePending(Long sessionId, Long attachmentId) {
        AiMessageAttachment attachment = attachmentId == null ? null : baseMapper.selectById(attachmentId);
        if (attachment == null) {
            return;
        }
        if (!sessionId.equals(attachment.getSessionId())) {
            throw new ServiceException("附件不属于该会话");
        }
        if (attachment.getMessageId() != null) {
            throw new ServiceException("附件已随消息发送，无法删除");
        }
        baseMapper.deleteById(attachmentId);
    }

    /**
     * 取 OSS 文件元信息（不存在即拒绝）。
     *
     * @param ossId 对象存储主键
     * @return OSS 元信息
     */
    private OssDTO resolveOss(Long ossId) {
        if (ossId == null) {
            throw new ServiceException("文件未上传，请重新上传后再提交");
        }
        List<OssDTO> list = ossService.selectByIds(String.valueOf(ossId));
        if (list == null || list.isEmpty() || list.get(0) == null) {
            throw new ServiceException("文件不存在或已被删除，请重新上传");
        }
        return list.get(0);
    }

    /**
     * 解析文件后缀（优先 OSS 记录，其次原始文件名）。
     *
     * @param bo  登记参数
     * @param oss OSS 元信息
     * @return 小写后缀（不含点）
     */
    private String resolveSuffix(AiChatAttachmentBo bo, OssDTO oss) {
        String suffix = normalizeSuffix(oss.getFileSuffix());
        if (suffix.isEmpty()) {
            suffix = normalizeSuffix(nameOf(bo.getFileName()));
        }
        if (suffix.isEmpty()) {
            suffix = normalizeSuffix(oss.getOriginalName());
        }
        if (suffix.isEmpty()) {
            throw new ServiceException("无法识别文件类型，请重新上传");
        }
        return suffix;
    }

    /**
     * 解析展示用文件名（登记参数 → OSS 原名 → OSS 存储名）。
     *
     * @param bo     登记参数
     * @param oss    OSS 元信息
     * @param suffix 已解析的后缀
     * @return 文件名
     */
    private String resolveFileName(AiChatAttachmentBo bo, OssDTO oss, String suffix) {
        if (StringUtils.isNotBlank(bo.getFileName())) {
            return bo.getFileName();
        }
        if (StringUtils.isNotBlank(oss.getOriginalName())) {
            return oss.getOriginalName();
        }
        return "attachment." + suffix;
    }

    /**
     * 附件实体 → 视图对象。
     *
     * @param entity 附件实体
     * @param url    访问地址（可空）
     * @return 视图对象
     */
    private AiChatAttachmentVo toVo(AiMessageAttachment entity, String url) {
        AiChatAttachmentVo vo = new AiChatAttachmentVo();
        vo.setId(entity.getId());
        vo.setFileType(entity.getFileType());
        vo.setFileName(entity.getFileName());
        vo.setFileSuffix(entity.getFileSuffix());
        vo.setFileSize(entity.getFileSize());
        vo.setUrl(url);
        vo.setParsedCharCount(StringUtils.isBlank(entity.getParsedContent()) ? 0 : entity.getParsedContent().length());
        return vo;
    }

    /**
     * 解析 OSS 服务端读写地址（下载用，忽略对外域名，不存在即报错）。
     */
    private String resolveUrl(Long ossId) {
        String url = resolveInternalUrlQuietly(ossId);
        if (StringUtils.isBlank(url)) {
            throw new ServiceException("文件不存在或已被删除，请重新上传");
        }
        return url;
    }

    /**
     * 解析 OSS 服务端读写地址（下载用，忽略对外域名，不存在时返回 null 而不报错）。
     */
    private String resolveInternalUrlQuietly(Long ossId) {
        if (ossId == null) {
            return null;
        }
        String urls = ossService.selectInternalUrlByIds(String.valueOf(ossId));
        if (StringUtils.isBlank(urls)) {
            return null;
        }
        int index = urls.indexOf(',');
        return index > 0 ? urls.substring(0, index) : urls;
    }

    /**
     * 解析 OSS 访问地址（历史回显用，对外展示地址，不存在时返回 null 而不报错）。
     */
    private String resolveUrlQuietly(Long ossId) {
        if (ossId == null) {
            return null;
        }
        String urls = ossService.selectUrlByIds(String.valueOf(ossId));
        if (StringUtils.isBlank(urls)) {
            return null;
        }
        int index = urls.indexOf(',');
        return index > 0 ? urls.substring(0, index) : urls;
    }

    /**
     * 取文件名（容错空值）。
     */
    private String nameOf(String fileName) {
        return StringUtils.isBlank(fileName) ? "" : fileName;
    }

    /**
     * 规范化后缀。
     *
     * @param suffix 原始后缀或文件名
     * @return 小写且不含点的后缀；无法识别时为空串
     */
    private String normalizeSuffix(String suffix) {
        if (StringUtils.isBlank(suffix)) {
            return "";
        }
        String normalized = suffix.trim().toLowerCase(Locale.ROOT);
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex >= 0) {
            normalized = normalized.substring(dotIndex + 1);
        }
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * 人类可读的大小文案。
     *
     * @param bytes 字节数
     * @return 如 {@code 512KB} / {@code 5MB}
     */
    private String humanSize(long bytes) {
        long mb = 1024L * 1024L;
        if (bytes >= mb && bytes % mb == 0) {
            return (bytes / mb) + "MB";
        }
        return (bytes / 1024L) + "KB";
    }

}