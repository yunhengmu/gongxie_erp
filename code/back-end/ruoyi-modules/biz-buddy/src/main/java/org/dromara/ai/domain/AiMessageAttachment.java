package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 会话消息附件对象 ai_message_attachment
 *
 * <p>用户与小Z 聊天时上传的图片 / 文档：图片以 Base64 data URI 下发给模型，文档解析为纯文本内联进本轮对话。
 * 上传后先落一行（{@code messageId} 为空＝未发送的暂存件），发送消息时绑定到用户消息。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_message_attachment")
public class AiMessageAttachment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 附件类型：图片
     */
    public static final String TYPE_IMAGE = "image";

    /**
     * 附件类型：文档
     */
    public static final String TYPE_DOCUMENT = "document";

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属会话（ai_session.id）
     */
    private Long sessionId;

    /**
     * 所属用户消息（ai_message.id）；为空表示上传后尚未发送的暂存件
     */
    private Long messageId;

    /**
     * 附件类型（image 图片 / document 文档）
     */
    private String fileType;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件后缀（小写，不含点）
     */
    private String fileSuffix;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * MIME 类型（图片为 image/*；文档可空）
     */
    private String mimeType;

    /**
     * 对象存储主键（sys_oss.oss_id）
     */
    private Long ossId;

    /**
     * 文档解析后的纯文本（图片为空）
     */
    private String parsedContent;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}