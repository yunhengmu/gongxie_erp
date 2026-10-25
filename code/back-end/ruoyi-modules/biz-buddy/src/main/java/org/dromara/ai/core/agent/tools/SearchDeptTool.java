package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.domain.DeptDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索部门工具（只读）。
 *
 * <p>按部门名称关键词模糊检索（可附状态筛选），返回部门 ID、名称、上级、排序与状态，
 * 用于回答组织架构问题或为「查看 / 修改 / 删除部门」取得部门 ID。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class SearchDeptTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "search_dept";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按关键词搜索部门（对部门名称模糊匹配），可附带状态筛选。"
        + "返回部门 ID、名称、上级部门、显示顺序与状态，用于了解组织架构或取得部门 ID。属于只读操作。"
        + "关键词留空则列出全部部门（受当前提问用户的数据范围限制）。";

    /**
     * 搜索部门所需权限
     */
    private static final String PERM_DEPT_LIST = "system:dept:list";

    /**
     * 单次返回的部门条数上限（超出只回前 N 条并提示缩小范围）
     */
    private static final int MAX_RESULT = 50;

    private final DeptService deptService;

    private final PermissionService permissionService;

    /**
     * @param deptService       部门服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验部门列表权限）
     */
    public SearchDeptTool(DeptService deptService, PermissionService permissionService) {
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
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝搜索部门");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_DEPT_LIST)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「部门列表」权限，已拒绝搜索部门");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        String keyword = StringUtils.trim(AiToolSupport.stringValue(input.get("keyword")));
        String status = StringUtils.trim(AiToolSupport.stringValue(input.get("status")));
        if (!StringUtils.isBlank(status) && !"0".equals(status) && !"1".equals(status)) {
            return AiToolSupport.fail(param, TOOL_NAME, "状态只能是 0（正常）或 1（停用）");
        }
        try {
            List<DeptDetailDTO> depts = deptService.searchDeptsByAi(keyword, status, operatorId);
            return AiToolSupport.ok(param, TOOL_NAME, render(depts, keyword, status));
        } catch (Exception e) {
            log.error("搜索部门失败：keyword={}, status={}", keyword, status, e);
            return AiToolSupport.fail(param, TOOL_NAME, "搜索部门失败：" + e.getMessage());
        }
    }

    /**
     * 渲染搜索结果文本。
     *
     * @param depts   部门列表
     * @param keyword 关键词
     * @param status  状态筛选
     * @return 文本
     */
    private static String render(List<DeptDetailDTO> depts, String keyword, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(depts.size()).append(" 个部门");
        if (StringUtils.isNotBlank(keyword)) {
            sb.append("（关键词：").append(keyword).append("）");
        }
        if (StringUtils.isNotBlank(status)) {
            sb.append("（状态：").append(AiToolSupport.statusText(status)).append("）");
        }
        if (depts.isEmpty()) {
            sb.append("：没有匹配的部门。");
            return sb.toString();
        }
        sb.append("：\n");
        int limit = Math.min(depts.size(), MAX_RESULT);
        for (int i = 0; i < limit; i++) {
            DeptDetailDTO dept = depts.get(i);
            sb.append("- dept_id=").append(dept.getDeptId())
                .append(" | ").append(AiToolSupport.display(dept.getDeptName()))
                .append(" | 上级=").append(AiToolSupport.display(dept.getParentName()))
                .append("（parent_id=").append(AiToolSupport.display(dept.getParentId())).append("）")
                .append(" | 排序=").append(AiToolSupport.display(dept.getOrderNum()))
                .append(" | 状态=").append(AiToolSupport.statusText(dept.getStatus()))
                .append("\n");
        }
        if (depts.size() > limit) {
            sb.append("（仅列出前 ").append(limit).append(" 条，请用更精确的关键词缩小范围）\n");
        }
        return sb.toString().trim();
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", Map.of("type", "string", "description", "部门名称关键词（模糊匹配），留空列出全部"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "部门状态：0 正常 / 1 停用，留空不限"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

}
