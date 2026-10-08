package org.dromara.ai.core.agent;

import io.agentscope.core.permission.PermissionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

/**
 * 全局工具权限模式的解析与运行期切换（REQ-AI-23）。
 *
 * <p>默认值来自配置 {@code bizbuddy.ai.chat.permission-mode}；运行期切换后写入 Redis 覆盖默认值，
 * 立即对所有已装配的智能体生效（共享单例，故由 {@link AiChatFacade} 每轮通过
 * {@code RuntimeContext} 级 API 应用，而非重建实例）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPermissionModeStore {

    /**
     * 运行期覆盖值所在的 Redis 键
     */
    private static final String REDIS_KEY = "bizbuddy:ai:perm:mode:global";

    private final AiProperties aiProperties;

    /**
     * 解析当前生效的权限模式：Redis 覆盖值优先，其次配置默认值。
     *
     * @return 权限模式
     */
    public PermissionMode resolve() {
        String configured = RedisUtils.getCacheObject(REDIS_KEY);
        String name = StringUtils.isBlank(configured)
            ? aiProperties.getChat().getPermissionMode() : configured;
        PermissionMode mode = parse(name);
        return mode == null ? PermissionMode.DEFAULT : mode;
    }

    /**
     * 切换全局权限模式（写入 Redis 覆盖值）。
     *
     * @param name 模式名称
     */
    public void update(String name) {
        PermissionMode mode = parse(StringUtils.trim(name));
        if (mode == null) {
            throw new ServiceException("不支持的权限模式：" + name);
        }
        RedisUtils.setCacheObject(REDIS_KEY, mode.name());
        log.info("全局工具权限模式已切换为：{}", mode.name());
    }

    /**
     * 解析模式名称。
     *
     * @param name 模式名称（大小写不敏感）
     * @return 权限模式；非法名称返回 null
     */
    private PermissionMode parse(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        try {
            return PermissionMode.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}