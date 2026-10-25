package org.dromara.ai.core.mcp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiSecretManager;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.agent.tools.GetSystemInfoTool;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * MCP 自演示引导：应用启动时确保「平台系统信息」外部 MCP 连接存在（幂等）。
 *
 * <p>目的：串起「MCP 服务端 ↔ MCP 客户端」的演示闭环——平台把只读工具
 * {@link GetSystemInfoTool}（{@code ai_tool.mcp_exposed=1}）经 {@code /mcp} 出口对外暴露，
 * 本引导再登记一个连回同一出口的客户端连接，白名单只放行该工具。工具**不会**自动进入任何会话，
 * 只有对话时经会话资源选择器勾选才装配。</p>
 *
 * <p>两个刻意约束：① 连回地址固定为 {@code http://localhost:8080/mcp}（容器内自连），
 * 跨容器部署请在「MCP 客户端」页改地址；② 若已存在同名连接则**不再改动**（避免覆盖人工编辑）。
 * 失败只告警、不阻断启动。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpSelfBootstrap implements ApplicationRunner {

    /**
     * 客户端连接名（唯一）
     */
    private static final String SERVER_NAME = "platform_system_info";

    /**
     * 连回本应用出口的地址（容器内自连；跨容器请到页面改）
     */
    private static final String SELF_URL = "http://localhost:8080/mcp";

    /**
     * 用途说明
     */
    private static final String DESCRIPTION = "平台内置演示：连回本应用 /mcp 出口，白名单仅系统信息工具";

    private final AiMcpClientMapper mcpClientMapper;

    private final AiSecretManager secretManager;

    private final AiRuntimeConfigStore runtimeConfigStore;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long exists = mcpClientMapper.selectCount(Wrappers.<AiMcpClient>lambdaQuery()
                .eq(AiMcpClient::getServerName, SERVER_NAME));
            if (exists != null && exists > 0) {
                return;
            }
            AiMcpClient client = new AiMcpClient();
            client.setId(IdGeneratorUtil.nextLongId());
            client.setServerName(SERVER_NAME);
            client.setTransport(AiMcpClient.TRANSPORT_HTTP);
            client.setUrl(SELF_URL);
            client.setHeadersJson(secretManager.encrypt(buildAuthHeader()));
            client.setEnableTools(GetSystemInfoTool.TOOL_NAME);
            client.setDescription(DESCRIPTION);
            client.setStatus(AiMcpClient.STATUS_ENABLED);
            mcpClientMapper.insert(client);
            log.info("已初始化平台系统信息 MCP 客户端：server={}, url={}, tools={}",
                SERVER_NAME, SELF_URL, GetSystemInfoTool.TOOL_NAME);
        } catch (Exception e) {
            log.warn("初始化平台系统信息 MCP 客户端失败（不影响启动）：{}", e.getMessage());
        }
    }

    /**
     * 按当前出口口令构造 {@code Authorization} 头 JSON（口令为空时不带凭据）。
     *
     * @return 请求头 JSON；口令为空时返回 null
     */
    private String buildAuthHeader() {
        AiMcpServerConfigBo config = runtimeConfigStore.mcpServer();
        String token = config == null ? null : StringUtils.trim(config.getToken());
        if (StringUtils.isBlank(token)) {
            return null;
        }
        return "{\"Authorization\":\"Bearer " + token + "\"}";
    }

}