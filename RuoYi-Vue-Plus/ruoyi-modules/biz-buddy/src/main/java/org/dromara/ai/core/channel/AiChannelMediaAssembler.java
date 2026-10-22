package org.dromara.ai.core.channel;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.util.AiDocumentParser;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 渠道入站多媒体的内容块装配（REQ-AI-24 扩展）。
 *
 * <p>框架的入站 mapper 只把媒体消息映射为「中性标记文本 + {@link ChannelMediaMetadata} 元数据」，
 * **不下载媒体**。本类据元数据经 {@link AiChannelRuntime#downloadMedia} 取回字节，再装配为
 * AgentScope 内容块：图片 → {@link ImageBlock}（Base64）；文件 → 复用
 * {@link AiDocumentParser} 解析为文本内联；语音 → 平台转写文本（若提供）；其余降级为标记文本。</p>
 *
 * <p>下载失败 / 不支持的类型一律降级为**标记文本**，不阻断回答（与聊天附件的降级口径一致）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChannelMediaAssembler {

    private final AiProperties aiProperties;

    /**
     * 一次入站消息装配出的内容。
     *
     * @param text      用户可见文本（无文本时为 null，表示纯媒体消息）
     * @param storedText 落库文本（无文本时用媒体标记占位，保持会话可读）
     * @param blocks    附件内容块（图片 / 文档）
     */
    public record ChannelContent(String text, String storedText, List<ContentBlock> blocks) {
    }

    /**
     * 把一条归一化入站消息装配为「文本 + 内容块」。
     *
     * @param runtime 渠道运行期装配（提供平台媒体下载）
     * @param inbound 归一化入站消息
     * @return 装配结果；无任何可用内容时返回 null
     */
    public ChannelContent assemble(AiChannelRuntime runtime, InboundMessage inbound) {
        if (inbound == null || inbound.messages() == null || inbound.messages().isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        StringBuilder markers = new StringBuilder();
        List<ContentBlock> blocks = new ArrayList<>();
        for (Msg msg : inbound.messages()) {
            Map<String, Object> meta = msg.getMetadata();
            String kind = meta == null ? null : str(meta.get(ChannelMediaMetadata.KIND));
            String raw = StringUtils.trim(msg.getTextContent());
            if (StringUtils.isBlank(kind)) {
                if (StringUtils.isNotBlank(raw)) {
                    appendLine(text, raw);
                }
                continue;
            }
            String marker = StringUtils.isNotBlank(raw) ? raw
                : ChannelMediaMetadata.markerText(kind, str(meta.get(ChannelMediaMetadata.FILE_NAME)));
            ContentBlock block = buildMediaBlock(runtime, meta, kind);
            if (block != null) {
                blocks.add(block);
            }
            appendLine(markers, marker);
        }
        if (text.length() == 0 && blocks.isEmpty() && markers.length() == 0) {
            return null;
        }
        String visible = text.length() > 0 ? text.toString() : null;
        String stored = text.length() > 0 ? text.toString() : markers.toString();
        return new ChannelContent(visible, stored, blocks);
    }

    /**
     * 按媒体类型装配单个内容块（失败返回 null，由调用方降级为标记文本）。
     */
    private ContentBlock buildMediaBlock(AiChannelRuntime runtime, Map<String, Object> meta, String kind) {
        // 语音 / 视频：优先用平台转写文本（无需下载）
        if (ChannelMediaMetadata.KIND_AUDIO.equals(kind) || ChannelMediaMetadata.KIND_VIDEO.equals(kind)) {
            String recognition = str(meta.get(ChannelMediaMetadata.RECOGNITION));
            if (StringUtils.isNotBlank(recognition)) {
                return TextBlock.builder().text("【语音转写】" + recognition).build();
            }
        }
        String mediaId = str(meta.get(ChannelMediaMetadata.ID));
        String url = str(meta.get(ChannelMediaMetadata.URL));
        String fileName = str(meta.get(ChannelMediaMetadata.FILE_NAME));
        String messageId = str(meta.get("channelMessageId"));
        byte[] bytes = runtime.downloadMedia(kind, messageId, mediaId, url, fileName,
            aiProperties.getChannel().getMediaMaxBytes());
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (ChannelMediaMetadata.KIND_IMAGE.equals(kind)) {
            // 飞书 / 钉钉的图片消息不带文件名，不能只按后缀猜 MIME：
            // 以字节魔数识别真实格式，避免把 JPEG 标成 image/png 导致模型读不出图
            String mediaType = detectImageMediaType(bytes, fileName);
            if (mediaType == null) {
                log.warn("渠道图片内容无法识别，降级为标记文本：channelCode={}, bytes={}",
                    runtime.getChannelCode(), bytes.length);
                return null;
            }
            log.info("渠道图片已装配：channelCode={}, mediaType={}, bytes={}",
                runtime.getChannelCode(), mediaType, bytes.length);
            return ImageBlock.builder()
                .source(Base64Source.builder()
                    .data(Base64.getEncoder().encodeToString(bytes))
                    .mediaType(mediaType)
                    .build())
                .build();
        }
        if (ChannelMediaMetadata.KIND_FILE.equals(kind)) {
            String suffix = suffixOf(fileName);
            if (AiDocumentParser.supported(suffix)) {
                String parsed = AiDocumentParser.extract(bytes, suffix);
                int inlineMax = aiProperties.getChat().getDocumentInlineMaxChars();
                return TextBlock.builder()
                    .text("【文件：" + StringUtils.defaultIfBlank(fileName, "未命名") + "】\n"
                        + truncate(parsed, inlineMax))
                    .build();
            }
        }
        return null;
    }

    /**
     * 安全取值（缺失 / 非字符串时返回 null）。
     */
    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.isBlank(text) ? null : text;
    }

    /**
     * 追加一行（避免空串产生多余空行）。
     */
    private static void appendLine(StringBuilder sb, String line) {
        if (StringUtils.isBlank(line)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(line);
    }

    /**
     * 取文件名后缀（小写，不含点）。
     */
    private static String suffixOf(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 识别图片 MIME：优先按字节魔数，其次按文件名后缀；无法识别时返回 null。
     *
     * @param bytes    图片字节
     * @param fileName 文件名（可空）
     * @return MIME 类型；无法识别时返回 null
     */
    private static String detectImageMediaType(byte[] bytes, String fileName) {
        String byMagic = imageMediaTypeOf(bytes);
        if (byMagic != null) {
            return byMagic;
        }
        return switch (suffixOf(fileName)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            case "heic" -> "image/heic";
            case "heif" -> "image/heif";
            default -> null;
        };
    }

    /**
     * 按文件头魔数识别常见图片格式。
     *
     * @param bytes 文件字节
     * @return MIME 类型；无法识别时返回 null
     */
    private static String imageMediaTypeOf(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(bytes, 0, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F')
            && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        if (startsWith(bytes, 0, 'B', 'M')) {
            return "image/bmp";
        }
        if (startsWith(bytes, 0, 'I', 'I', 0x2A, 0x00)
            || startsWith(bytes, 0, 'M', 'M', 0x00, 0x2A)) {
            return "image/tiff";
        }
        // HEIF / HEIC：ftyp 盒 + 品牌（heic/heix/hevc/mif1/msf1）
        if (startsWith(bytes, 4, 'f', 't', 'y', 'p')) {
            if (startsWith(bytes, 8, 'h', 'e', 'i', 'c')
                || startsWith(bytes, 8, 'h', 'e', 'i', 'x')
                || startsWith(bytes, 8, 'h', 'e', 'v', 'c')
                || startsWith(bytes, 8, 'm', 'i', 'f', '1')
                || startsWith(bytes, 8, 'm', 's', 'f', '1')) {
                return "image/heic";
            }
        }
        return null;
    }

    /**
     * 判断字节序列在指定偏移处是否以给定值开头（越界返回 false）。
     *
     * @param bytes  字节数组
     * @param offset 偏移
     * @param values 期望值
     * @return 是否匹配
     */
    private static boolean startsWith(byte[] bytes, int offset, int... values) {
        if (bytes.length < offset + values.length) {
            return false;
        }
        for (int i = 0; i < values.length; i++) {
            if ((bytes[offset + i] & 0xFF) != (values[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按上限截断内联文本：保留首尾、中段省略（与聊天附件同口径）。
     */
    private static String truncate(String text, int max) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        int head = max / 2;
        int tail = max - head;
        return text.substring(0, head)
            + "\n……（内容过长，中段已省略）……\n"
            + text.substring(text.length() - tail);
    }
}
