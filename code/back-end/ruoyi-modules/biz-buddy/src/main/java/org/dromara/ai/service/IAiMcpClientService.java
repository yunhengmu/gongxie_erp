package org.dromara.ai.service;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.dromara.ai.domain.AiScenarioPackMcpTool;
import org.dromara.ai.domain.bo.AiMcpClientBo;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.dromara.ai.domain.vo.AiMcpClientTestVo;
import org.dromara.ai.domain.vo.AiMcpClientVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 外部 MCP Server 连接配置 Service 接口（REQ-AI-16）。
 *
 * @author BizBuddy
 */
public interface IAiMcpClientService {

    /**
     * 查询连接配置详情。
     *
     * @param id 主键
     * @return 视图对象
     */
    AiMcpClientVo queryById(Long id);

    /**
     * 分页查询连接配置列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiMcpClientVo> queryPageList(AiMcpClientBo bo, PageQuery pageQuery);

    /**
     * 新增连接配置。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiMcpClientBo bo);

    /**
     * 修改连接配置。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiMcpClientBo bo);

    /**
     * 按主键集合删除连接配置。
     *
     * @param ids     主键集合
     * @param isValid 是否执行删除前校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 测试连接并返回该 Server 声明的工具清单（不落库；编辑时请求头留空则复用已配置密文）。
     *
     * @param bo 连接配置（可含主键以复用已存请求头）
     * @return 测试结果
     */
    AiMcpClientTestVo testConnection(AiMcpClientBo bo);

    /**
     * 解析智能体装配所需的 MCP 客户端：需要接入的 Server、按白名单过滤并**归一化后**的工具名、
     * 改名映射、包内授权决策与装配指纹片段。
     *
     * <p><b>按场景包接入（2026-09-30 口径）</b>：入参为会话所选场景包内勾选的 MCP 工具；
     * 未选包（入参为空）时返回空结果 —— 该会话不接入任何 MCP 工具。连接是否启用、以及
     * {@code ai_mcp_client.enable_tools} 白名单仍是前置闸门，包内勾选只能是其子集。</p>
     *
     * <p>工具名通过实际建立连接发现（结果按「配置主键 + 短 TTL」缓存），因为框架的
     * {@code ToolFilter} 会按 {@code ToolsConfig.allow} 裁剪工具，MCP 工具必须显式列入白名单。
     * 归一化只作用于**注册进工具箱的名字**，不改 {@code enable_tools} 白名单语义（白名单始终是远端原始名）。</p>
     *
     * @param selections 场景包内勾选的 MCP 工具（连接 + 远端原始工具名 + 授权决策）；为空表示不接入
     * @return 装配解析结果（无勾选 / 无启用配置时返回空结果）
     */
    McpClientAssembly resolveForAssembly(List<AiScenarioPackMcpTool> selections);

    /**
     * 构建本次装配要接入的 MCP 客户端，并对其工具名做归一化装饰。
     *
     * <p><b>为什么不交给 {@code ToolsConfig.mcpServers}</b>：框架会以远端自声明的工具名原样注册，
     * 这类名字可能不满足 LLM function name 规范（如 {@code weather.search_local}），
     * 在严格校验的模型上会整轮 400。故改为在此处包装为 {@link org.dromara.ai.core.mcp.NormalizingMcpClientWrapper}
     * 后由装配层自行注册，注册路径与框架 {@code McpServerRegistrar} 内部完全一致。</p>
     *
     * <p>返回的客户端**尚未初始化**，由工具箱在注册时初始化；构建失败的 Server 不出现在结果中。</p>
     *
     * @param assembly 装配解析结果
     * @return Server 名 → 待注册的 MCP 客户端（按 {@code assembly} 的 Server 顺序）
     */
    Map<String, McpClientWrapper> openClients(McpClientAssembly assembly);

}