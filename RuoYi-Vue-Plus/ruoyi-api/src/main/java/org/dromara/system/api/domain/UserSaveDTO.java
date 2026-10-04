package org.dromara.system.api.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户写入传输对象（供 AI 用户工具新增 / 修改用户）。
 *
 * <p>修改场景下，除 {@code userId} 外均为**可选**：为 {@code null} 表示沿用该用户现有值，
 * 文本字段传空串表示清空；{@code roleIds} / {@code postIds} 为 {@code null} 表示不改绑定，
 * 传空数组表示清空全部绑定。</p>
 *
 * <p><b>不含密码</b>：新增用户的初始密码由平台配置项 {@code sys.user.initPassword} 决定
 * （与「用户导入」同一口径），避免明文口令流经模型上下文与工具调用审计表。</p>
 *
 * @author BizBuddy
 */
@Data
public class UserSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID（修改时必填，新增时留空）
     */
    private Long userId;

    /**
     * 部门ID（新增时可留空，留空取操作人所属部门）
     */
    private Long deptId;

    /**
     * 用户账号
     */
    private String userName;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户邮箱
     */
    private String email;

    /**
     * 手机号码
     */
    private String phoneNumber;

    /**
     * 用户性别（0男 1女 2未知）
     */
    private String gender;

    /**
     * 账号状态（0正常 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 角色组（null＝不改，空数组＝清空）
     */
    private Long[] roleIds;

    /**
     * 岗位组（null＝不改，空数组＝清空）
     */
    private Long[] postIds;

    /**
     * 操作人用户 ID（显式传入，用于落库 create_by 与审计）
     */
    private Long operatorUserId;

}