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
            return ImageBlock.builder()
                .source(Base64Source.builder()
                    .data(Base64.getEncoder().encodeToString(bytes))
                    .mediaType(guessImageMediaType(fileName))
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
     * 按后缀猜图片 MIME（未知时用 image/png）。
     */
    private static String guessImageMediaType(String fileName) {
        return switch (suffixOf(fileName)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            case "heif" -> "image/heif";
            default -> "image/png";
        };
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
