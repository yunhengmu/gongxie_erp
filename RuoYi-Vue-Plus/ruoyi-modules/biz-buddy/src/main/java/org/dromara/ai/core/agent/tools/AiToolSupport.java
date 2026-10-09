package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 业务工具共用支撑（内置只读工具与部门 / 用户工具的公共解析、权限判定与结果封装）。
 *
 * <p>工具运行在 Reactor 线程，Sa-Token 的线程上下文不可用：操作人一律取自
 * {@link ToolCallParam#getRuntimeContext()}，功能权限使用不依赖线程上下文的
 * {@link PermissionService}，返回结果统一用 {@link ToolResultBlock} 回喂模型
 * （业务校验失败不打断整轮对话）。</p>
 *
 * @author BizBuddy
 */
final class AiToolSupport {

    /**
     * 超级管理员通配权限
     */
    static final String PERM_ALL = "*:*:*";

    /**
     * 未设置占位文案
     */
    static final String UNSET = "（未设置）";

    private AiToolSupport() {
    }

    /**
     * 从运行时上下文解析操作人。
     *
     * @param param 工具调用参数
     * @return 操作人用户 ID；无法解析时返回 null
     */
    static Long operatorId(ToolCallParam param) {
        return parseId(param == null || param.getRuntimeContext() == null
            ? null : param.getRuntimeContext().getUserId());
    }

    /**
     * 取工具调用入参（可能为空 Map，不会返回 null）。
     *
     * @param param 工具调用参数
     * @return 入参 Map
     */
    static Map<String, Object> input(ToolCallParam param) {
        return param == null || param.getInput() == null ? Map.of() : param.getInput();
    }

    /**
     * 校验操作人是否具备指定菜单权限（含超级管理员通配）。
     *
     * @param permissionService 权限服务
     * @param operatorId        操作人用户 ID
     * @param perm              所需权限编码
     * @return 是否具备权限
     */
    static boolean hasPermission(PermissionService permissionService, Long operatorId, String perm) {
        Set<String> perms = permissionService.getMenuPermission(operatorId);
        if (perms == null || perms.isEmpty()) {
            return false;
        }
        return perms.contains(PERM_ALL) || perms.contains(perm);
    }

    /**
     * 解析数字 ID。
     *
     * @param raw 原始值
     * @return 数字 ID；非法时返回 null
     */
    static Long parseId(Object raw) {
        String value = StringUtils.trim(raw == null ? null : String.valueOf(raw));
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全转字符串（null 转空串）。
     *
     * @param value 原始值
     * @return 字符串
     */
    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 展示用文本（去空白，空值显示占位）。
     *
     * @param value 原始值
     * @return 展示文本
     */
    static String display(Object value) {
        String text = StringUtils.trim(stringValue(value));
        return StringUtils.isBlank(text) ? UNSET : text;
    }

    /**
     * 状态展示文案（0正常 1停用）。
     *
     * @param status 状态值
     * @return 展示文案
     */
    static String statusText(String status) {
        if ("0".equals(status)) {
            return "正常";
        }
        if ("1".equals(status)) {
            return "停用";
        }
        return display(status);
    }

    /**
     * 性别展示文案（0男 1女 2未知）。
     *
     * @param gender 性别值
     * @return 展示文案
     */
    static String genderText(String gender) {
        if ("0".equals(gender)) {
            return "男";
        }
        if ("1".equals(gender)) {
            return "女";
        }
        if ("2".equals(gender)) {
            return "未知";
        }
        return display(gender);
    }

    /**
     * 公告类型展示文案（1通知 2公告）。
     *
     * @param noticeType 公告类型值
     * @return 展示文案
     */
    static String noticeTypeText(String noticeType) {
        if ("1".equals(noticeType)) {
            return "通知";
        }
        if ("2".equals(noticeType)) {
            return "公告";
        }
        return display(noticeType);
    }

    /**
     * 公告状态展示文案（0正常 1关闭）。
     *
     * @param status 公告状态值
     * @return 展示文案
     */
    static String noticeStatusText(String status) {
        if ("0".equals(status)) {
            return "正常";
        }
        if ("1".equals(status)) {
            return "关闭";
        }
        return display(status);
    }

    /**
     * 菜单类型展示文案（M目录 C菜单 F按钮）。
     *
     * @param menuType 菜单类型值
     * @return 展示文案
     */
    static String menuTypeText(String menuType) {
        if ("M".equals(menuType)) {
            return "目录";
        }
        if ("C".equals(menuType)) {
            return "菜单";
        }
        if ("F".equals(menuType)) {
            return "按钮";
        }
        return display(menuType);
    }

    /**
     * 角色数据范围展示文案。
     *
     * @param dataScope 数据范围值（1 全部 / 2 自定义 / 3 本部门 / 4 本部门及以下 / 5 仅本人 / 6 部门及以下或本人）
     * @return 展示文案
     */
    static String dataScopeText(String dataScope) {
        if (dataScope == null) {
            return UNSET;
        }
        return switch (dataScope) {
            case "1" -> "全部数据权限";
            case "2" -> "自定义数据权限";
            case "3" -> "本部门数据权限";
            case "4" -> "本部门及以下数据权限";
            case "5" -> "仅本人数据权限";
            case "6" -> "部门及以下或本人数据权限";
            default -> display(dataScope);
        };
    }

    /**
     * 解析 ID 列表入参（支持 JSON 数组与逗号分隔串）。
     *
     * <p>三态语义：{@code null}＝**未提供**（调用方据此「不改绑定」）；空数组＝**清空**；
     * 其余为去重后的 ID 数组。非法项由 {@link #validateIds(Object)} 先行拦截，不会静默丢弃。</p>
     *
     * @param raw 原始值
     * @return ID 数组；未提供时返回 null
     */
    static Long[] parseIds(Object raw) {
        if (raw == null) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : toList(raw)) {
            Long id = parseId(item);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids.toArray(new Long[0]);
    }

    /**
     * 校验 ID 列表入参（每一项都必须是数字）。
     *
     * @param raw 原始值
     * @return 错误说明；合法或未提供时返回 null
     */
    static String validateIds(Object raw) {
        if (raw == null) {
            return null;
        }
        for (Object item : toList(raw)) {
            if (parseId(item) == null) {
                return "ID 必须是数字，收到：" + stringValue(item);
            }
        }
        return null;
    }

    /**
     * 把入参归一化为列表（JSON 数组或逗号分隔串，空白视为空列表）。
     *
     * @param raw 原始值
     * @return 列表
     */
    private static List<?> toList(Object raw) {
        if (raw instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        String text = StringUtils.trim(stringValue(raw));
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        return Arrays.asList(text.split(","));
    }

    /**
     * 成功的工具结果。
     *
     * @param param        工具调用参数
     * @param fallbackName 兜底工具名
     * @param message      结果文本
     * @return 工具结果流
     */
    static Mono<ToolResultBlock> ok(ToolCallParam param, String fallbackName, String message) {
        return Mono.just(ToolResultBlock.text(message == null ? "" : message)
            .withIdAndName(useId(param), useName(param, fallbackName)));
    }

    /**
     * 失败的工具结果（回喂模型，不打断整轮对话）。
     *
     * @param param        工具调用参数
     * @param fallbackName 兜底工具名
     * @param message      错误说明
     * @return 工具结果流
     */
    static Mono<ToolResultBlock> fail(ToolCallParam param, String fallbackName, String message) {
        return Mono.just(ToolResultBlock.error(StringUtils.isBlank(message) ? "操作未成功" : message)
            .withIdAndName(useId(param), useName(param, fallbackName)));
    }

    /**
     * 取工具调用 ID。
     *
     * @param param 工具调用参数
     * @return 调用 ID
     */
    private static String useId(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        return use == null ? null : use.getId();
    }

    /**
     * 取工具调用名（缺失时用兜底名）。
     *
     * @param param        工具调用参数
     * @param fallbackName 兜底工具名
     * @return 工具名
     */
    private static String useName(ToolCallParam param, String fallbackName) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        return use == null || StringUtils.isBlank(use.getName()) ? fallbackName : use.getName();
    }

}
