package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiWorkspaceContentBo;
import org.dromara.ai.domain.vo.AiWorkspaceContentVo;
import org.dromara.ai.domain.vo.AiWorkspaceFileVo;

import java.util.List;

/**
 * AI 工作区文件 Service 接口（REQ-AI-08）。
 *
 * <p>工作区文件（AGENTS.md 与附加上下文文件）按用户覆盖存放于智能体工作区，
 * 本接口提供文件清单、分层内容读取与写入、以及清除用户覆盖（回落共享默认）。</p>
 *
 * @author BizBuddy
 */
public interface IAiWorkspaceService {

    /**
     * 查询指定智能体与用户下的工作区文件清单（AGENTS.md + 已登记的附加上下文文件）。
     *
     * @param agentId 智能体主键
     * @param userId  用户主键
     * @return 文件清单（含共享默认与用户覆盖各自的存在与字符数）
     */
    List<AiWorkspaceFileVo> listFiles(Long agentId, Long userId);

    /**
     * 读取指定层的工作区文件内容。
     *
     * @param agentId 智能体主键
     * @param userId  用户主键
     * @param path    工作区相对路径
     * @param scope   层来源（SHARED 共享默认 / USER 用户覆盖）
     * @return 文件内容
     */
    AiWorkspaceContentVo readContent(Long agentId, Long userId, String path, String scope);

    /**
     * 保存指定层的工作区文件内容。
     *
     * @param bo 写入参数
     */
    void writeContent(AiWorkspaceContentBo bo);

    /**
     * 清除用户覆盖，使该文件回落共享默认。
     *
     * @param agentId 智能体主键
     * @param userId  用户主键
     * @param path    工作区相对路径
     */
    void resetUserOverride(Long agentId, Long userId, String path);

}