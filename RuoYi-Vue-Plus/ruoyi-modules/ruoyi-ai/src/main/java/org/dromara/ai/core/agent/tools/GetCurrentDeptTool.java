package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.DeptDetailDTO;
import org.dromara.system.api.domain.UserDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 获取当前用户所属部门信息工具（只读、无入参）。
 *
 * <p>先按提问用户查出其 {@code deptId}，再取该部门的详情——只返回**本人所属部门**，
 * 因此与「查看 / 搜索部门」工具不同，本工具**没有参数、无法指向其他部门**，无越权面，
 * 可列入 {@code AgentRegistry.BUILTIN_READONLY_TOOL_CODES}（无需逐智能体绑定即装配、未绑定时默认放行）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class GetCurrentDeptTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_current_dept";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "获取当前提问用户本人所属部门的信息（部门 ID、名称、上级部门、"
        + "部门类别编码、显示顺序、负责人用户 ID、联系电话、邮箱、状态、创建时间）。"
        + "当问题涉及「我在哪个部门」「我们部门的联系方式 / 负责人」时调用，不要凭猜测回答。"
        + "只读操作，无入参，只返回提问用户本人所属的部门；查询其他部门请用 get_dept 或 search_dept。";

    private final UserService userService;

    private final DeptService deptService;

    /**
     * @param userService 通用用户服务（取提问用户的所属部门 ID）
     * @param deptService 通用部门服务（取部门详情）
     */
    public GetCurrentDeptTool(UserService userService, DeptService deptService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.userService = userService;
        this.deptService = deptService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前提问用户的平台身份，已拒绝查询所属部门");
        }
        try {
            UserDTO user = userService.selectById(operatorId);
            if (user == null) {
                return AiToolSupport.fail(param, TOOL_NAME, "未查到当前用户信息（该用户可能已被删除或停用）");
            }
            if (user.getDeptId() == null) {
                return AiToolSupport.ok(param, TOOL_NAME, "当前提问用户未归属任何部门。");
            }
            DeptDetailDTO dept = deptService.selectDeptDetailByAi(user.getDeptId(), operatorId);
            if (dept == null) {
                return AiToolSupport.ok(param, TOOL_NAME,
                    "当前提问用户所属的部门（dept_id=" + user.getDeptId() + "）不存在或已被删除。");
            }
            return AiToolSupport.ok(param, TOOL_NAME, render(dept));
        } catch (Exception e) {
            log.error("查询当前用户所属部门失败：userId={}", operatorId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "查询当前用户所属部门失败：" + e.getMessage());
        }
    }

    /**
     * 渲染部门信息文本。
     *
     * @param dept 部门详情
     * @return 文本
     */
    private static String render(DeptDetailDTO dept) {
        return "当前提问用户所属部门的信息：\n"
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
            + "- 创建时间：" + AiToolSupport.display(dept.getCreateTime()) + "\n"
            + "说明：以上为提问用户本人所属部门的信息。";
    }

    /**
     * 构造参数 JSON Schema（本工具无入参）。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

}
