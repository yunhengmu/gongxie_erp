package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索用户工具（只读）。
 *
 * <p>按关键词（账号 / 昵称 / 手机号）模糊检索（可附部门与状态筛选），返回用户 ID、账号、昵称、
 * 所属部门与状态，用于回答「谁负责这件事」或为「查看 / 修改 / 删除用户」取得用户 ID。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class SearchUserTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "search_user";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按关键词搜索用户（对账号、昵称、手机号模糊匹配），"
        + "可附带部门与账号状态筛选。返回用户 ID、账号、昵称、所属部门、手机号与状态，"
        + "用于了解「有哪些人」或取得用户 ID。属于只读操作。关键词留空则列出用户（最多 200 条）。"
        + "部门 ID 可用「搜索部门」取得，指定部门时包含其所有下级部门。"
        + "结果仅包含当前提问用户数据范围内的用户。";

    /**
     * 搜索用户所需权限
     */
    private static final String PERM_USER_LIST = "system:user:list";

    /**
     * 单次返回的用户条数上限（超出只回前 N 条并提示缩小范围）
     */
    private static final int MAX_RESULT = 50;

    private final UserService userService;

    private final PermissionService permissionService;

    /**
     * @param userService       用户服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验用户列表权限）
     */
    public SearchUserTool(UserService userService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝搜索用户");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_USER_LIST)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「用户列表」权限，已拒绝搜索用户");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        String keyword = StringUtils.trim(AiToolSupport.stringValue(input.get("keyword")));
        String status = StringUtils.trim(AiToolSupport.stringValue(input.get("status")));
        Long deptId = AiToolSupport.parseId(input.get("dept_id"));
        if (input.get("dept_id") != null && deptId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "部门 ID 必须是数字");
        }
        if (!StringUtils.isBlank(status) && !"0".equals(status) && !"1".equals(status)) {
            return AiToolSupport.fail(param, TOOL_NAME, "账号状态只能是 0（正常）或 1（停用）");
        }
        try {
            List<UserDetailDTO> users = userService.searchUsersByAi(keyword, deptId, status, operatorId);
            return AiToolSupport.ok(param, TOOL_NAME, render(users, keyword, deptId, status));
        } catch (Exception e) {
            log.error("搜索用户失败：keyword={}, deptId={}, status={}", keyword, deptId, status, e);
            return AiToolSupport.fail(param, TOOL_NAME, "搜索用户失败：" + e.getMessage());
        }
    }

    /**
     * 渲染搜索结果文本。
     *
     * @param users   用户列表
     * @param keyword 关键词
     * @param deptId  部门筛选
     * @param status  状态筛选
     * @return 文本
     */
    private static String render(List<UserDetailDTO> users, String keyword, Long deptId, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(users.size()).append(" 个用户");
        if (StringUtils.isNotBlank(keyword)) {
            sb.append("（关键词：").append(keyword).append("）");
        }
        if (deptId != null) {
            sb.append("（部门 dept_id：").append(deptId).append("，含下级）");
        }
        if (StringUtils.isNotBlank(status)) {
            sb.append("（状态：").append(AiToolSupport.statusText(status)).append("）");
        }
        if (users.isEmpty()) {
            sb.append("：没有匹配的用户。");
            return sb.toString();
        }
        sb.append("：\n");
        int limit = Math.min(users.size(), MAX_RESULT);
        for (int i = 0; i < limit; i++) {
            UserDetailDTO user = users.get(i);
            sb.append("- user_id=").append(user.getUserId())
                .append(" | ").append(AiToolSupport.display(user.getUserName()))
                .append(" | 昵称=").append(AiToolSupport.display(user.getNickName()))
                .append(" | 部门=").append(AiToolSupport.display(user.getDeptName()))
                .append(" | 手机号=").append(AiToolSupport.display(user.getPhoneNumber()))
                .append(" | 状态=").append(AiToolSupport.statusText(user.getStatus()))
                .append("\n");
        }
        if (users.size() > limit) {
            sb.append("（仅列出前 ").append(limit).append(" 条，请用更精确的关键词或部门缩小范围）\n");
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
        properties.put("keyword", Map.of("type", "string", "description", "关键词（匹配账号 / 昵称 / 手机号），留空列出用户"));
        properties.put("dept_id", Map.of("type", "string", "description", "部门 ID（数字字符串），指定时含其所有下级部门，可空"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "账号状态：0 正常 / 1 停用，留空不限"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

}