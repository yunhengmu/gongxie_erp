package org.dromara.system.api.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户详情传输对象（供 AI 用户工具读取用户信息）。
 *
 * <p>只承载「回答问题所需」的字段，**不含密码等敏感列**；搜索场景不填充
 * {@code roleNames}（详情接口才填充）。</p>
 *
 * @author BizBuddy
 */
@Data
public class UserDetailDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户账号
     */
    private String userName;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 部门ID
     */
    private Long deptId;

    /**
     * 部门名称
     */
    private String deptName;

    /**
     * 用户类型（sys_user系统用户）
     */
    private String userType;

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
     * 角色名称集合（仅详情接口填充）
     */
    private List<String> roleNames;

    /**
     * 最后登录时间
     */
    private LocalDateTime loginDate;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}