package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.DeptService;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 删除部门工具（写入类，执行前必须经人工确认）。
 *
 * <p>只删除**空部门**：默认部门、存在下级部门、存在用户或存在岗位的部门一律拒绝，提示改用停用。
 * 确认闸门走 AgentScope 框架原生 HITL（命中 ask 规则时暂停并发 {@code RequireUserConfirmEvent}）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class DeleteDeptTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "delete_dept";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按部门 ID 删除一个部门。只能删除空部门：默认部门、存在下级部门、"
        + "存在用户或存在岗位的部门会被拒绝（这类部门应改为停用）。属于写入类操作，执行前需要用户确认，"
        + "删除后不可恢复，请先与用户确认部门 ID 无误。仅能删除提问用户数据范围内的部门。";

    /**
     * 删除部门所需权限
     */
    private static final String PERM_DEPT_REMOVE = "system:dept:remove";

    private final DeptService deptService;

    private final PermissionService permissionService;

    /**
     * @param deptService       部门服务（跨模块写入）
     * @param permissionService 权限服务（按用户校验部门删除权限）
     */
    public DeleteDeptTool(DeptService deptService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(false)
            .concurrencySafe(false));
        this.deptService = deptService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝删除部门");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_DEPT_REMOVE)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「部门删除」权限，已拒绝删除部门");
        }
        Long deptId = AiToolSupport.parseId(AiToolSupport.input(param).get("dept_id"));
        if (deptId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "部门 ID 不能为空且必须是数字");
        }
        try {
            deptService.deleteDeptByAi(deptId, operatorId);
            return AiToolSupport.ok(param, TOOL_NAME, "部门已删除（dept_id：" + deptId + "）。");
        } catch (ServiceException e) {
            // 业务校验失败不打断整轮对话，交由模型说明情况
            return AiToolSupport.fail(param, TOOL_NAME, e.getMessage());
        } catch (Exception e) {
            log.error("删除部门失败：operatorId={}, deptId={}", operatorId, deptId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "删除部门失败：" + e.getMessage());
        }
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dept_id", Map.of("type", "string", "description", "要删除的部门 ID（数字字符串）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("dept_id"));
        return schema;
    }

}
