package org.dromara.system.api.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 部门写入传输对象（供 AI 部门工具新增 / 修改部门）。
 *
 * <p>修改场景下，除 {@code deptId} 外均为**可选**：为空表示沿用该部门现有值。</p>
 *
 * @author BizBuddy
 */
@Data
public class DeptSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 部门ID（修改时必填，新增时留空）
     */
    private Long deptId;

    /**
     * 父部门ID（新增时必填，修改时留空表示不改上级）
     */
    private Long parentId;

    /**
     * 部门名称
     */
    private String deptName;

    /**
     * 部门类别编码
     */
    private String deptCategory;

    /**
     * 显示顺序
     */
    private Integer orderNum;

    /**
     * 负责人用户ID
     */
    private Long leader;

    /**
     * 联系电话
     */
    private String phone;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 部门状态（0正常 1停用）
     */
    private String status;

    /**
     * 操作人用户 ID（显式传入，用于落库 create_by 与审计）
     */
    private Long operatorUserId;

}
