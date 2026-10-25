package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 敏感词库对象 ai_sensitive_word
 *
 * <p>安全合规校验的敏感词配置：支持普通词（子串匹配）与正则表达式，按分类组织、可启停。
 * 输入 / 输出 / 渠道出站三类场景共用同一词库（命中处置见安全合规配置）。与既有治理表一致，
 * 本表为逻辑删除并带完整审计列。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_sensitive_word")
public class AiSensitiveWord extends BaseEntity {

    /**
     * 是否正则：否（普通词，按子串匹配）
     */
    public static final String REGEX_NO = "0";

    /**
     * 是否正则：是
     */
    public static final String REGEX_YES = "1";

    /**
     * 是否启用：启用
     */
    public static final String ENABLED = "1";

    /**
     * 是否启用：禁用
     */
    public static final String DISABLED = "0";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 敏感词或正则表达式
     */
    private String word;

    /**
     * 是否正则：0否（子串匹配） 1是
     */
    private String isRegex;

    /**
     * 分类（可空，供分组管理与统计）
     */
    private String category;

    /**
     * 是否启用：1启用 0禁用
     */
    private String enabled;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}