package org.dromara.ai.service;

import org.dromara.ai.domain.vo.AiMcpServerToolVo;
import org.dromara.ai.domain.vo.AiMcpServerVo;

import java.util.List;

/**
 * MCP 出口（服务端）Service 接口（REQ-AI-15）。
 *
 * <p>对外暴露的工具清单复用业务工具注册表 {@code ai_tool}（新增 {@code mcp_exposed} 开关列），
 * 只允许暴露「启用中的只读工具」；清单变更后对同一个 {@code McpStatelessSyncServer} Bean
 * 调 {@code addTool / removeTool} **热生效**，无需重启。</p>
 *
 * @author BizBuddy
 */
public interface IAiMcpServerService {

    /**
     * 查询出口状态：端点路径、口令校验开关与口令、当前出口实际挂载的工具名。
     *
     * @return 出口状态
     */
    AiMcpServerVo queryServer();

    /**
     * 查询候选工具清单（启用中的只读工具），并标注是否已对外暴露、实现是否已登记。
     *
     * @return 候选工具列表
     */
    List<AiMcpServerToolVo> queryTools();

    /**
     * 保存对外暴露清单：写回 {@code ai_tool.mcp_exposed} 并热同步出口工具。
     *
     * @param toolCodes 需要暴露的工具编码集合（未列出的已暴露工具将被撤销）
     */
    void updateExposed(List<String> toolCodes);

    /**
     * 按数据库当前状态重整出口工具（热挂载新增 / 刷新变更 / 撤销不再暴露的工具）。
     */
    void reconcile();

}