package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.domain.DeptDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查看部门详情工具（只读）。
 *
 * <p>按键查询单个部门的名称、上级、类别、排序、负责人、联系方式与状态；
 * 需要「某部门的上级是谁」「某部门是否停用」等事实时调用，不要凭猜测回答。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class GetDeptTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_dept";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按部门 ID 查询部门的详细信息（名称、上级部门、类别编码、显示顺序、"
        + "负责人用户 ID、联系电话、邮箱、状态、创建时间）。属于只读操作。部门 ID 可从「搜索部门」结果中获取。"
        + "仅能查看当前提问用户数据范围内的部门。";

    /**
     * 查看部门所需权限
     */
    private static final String PERM_DEPT_QUERY = "system:dept:query";

    private final DeptService deptService;

    private final PermissionService permissionService;

    /**
     * @param deptService       部门服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验部门查询权限）
     */
    public GetDeptTool(DeptService deptService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.deptService = deptService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝查询部门");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_DEPT_QUERY)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「部门查询」权限，已拒绝查询部门");
        }
        Long deptId = AiToolSupport.parseId(AiToolSupport.input(param).get("dept_id"));
        if (deptId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "部门 ID 不能为空且必须是数字");
        }
        try {
            DeptDetailDTO dept = deptService.selectDeptDetailByAi(deptId, operatorId);
            if (dept == null) {
                return AiToolSupport.ok(param, TOOL_NAME,
                    "未找到部门（dept_id=" + deptId + "），可能不存在或已被删除。");
            }
            return AiToolSupport.ok(param, TOOL_NAME, render(dept));
        } catch (Exception e) {
            log.error("查询部门详情失败：deptId={}", deptId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "查询部门失败：" + e.getMessage());
        }
    }

    /**
     * 渲染部门详情文本。
     *
     * @param dept 部门详情
     * @return 文本
     */
    private static String render(DeptDetailDTO dept) {
        return "部门详情：\n"
            + "- 部门 ID：" + dept.getDeptId() + "\n"
            + "- 部门名称：" + AiToolSupport.display(dept.getDeptName()) + "\n"
            + "- 上级部门：" + AiToolSupport.display(dept.getParentName())
            + "（parent_id=" + AiToolSupport.display(dept.getParentId()) + "）\n"
            + "- 部门类别编码：" + AiToolSupport.display(dept.getDeptCategory()) + "\n"
            + "- 显示顺序：" + AiToolSupport.display(dept.getOrderNum()) + "\n"
            + "- 负责人用户 ID：" + AiToolSupport.display(dept.getLeader()) + "\n"
            + "- 联系电话：" + AiToolSupport.display(dept.getPhone()) + "\n"
            + "- 邮箱：" + AiToolSupport.display(dept.getEmail()) + "\n"
            + "- 状态：" + AiToolSupport.statusText(dept.getStatus()) + "\n"
            + "- 创建时间：" + AiToolSupport.display(dept.getCreateTime());
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dept_id", Map.of("type", "string", "description", "部门 ID（数字字符串）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("dept_id"));
        return schema;
    }

}
