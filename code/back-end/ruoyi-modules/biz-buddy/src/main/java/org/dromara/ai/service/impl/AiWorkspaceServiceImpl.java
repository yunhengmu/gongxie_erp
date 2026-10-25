package org.dromara.ai.service.impl;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceContentBo;
import org.dromara.ai.domain.vo.AiWorkspaceContentVo;
import org.dromara.ai.domain.vo.AiWorkspaceFileVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiWorkspaceService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AI 工作区文件 Service 业务层处理（REQ-AI-08）。
 *
 * <p>工作区文件由 AgentScope 存放在智能体工作区内，并走「两层读」：工作区根为共享默认，
 * 用户目录（{@code <userId>/}）为按用户覆盖。本实现对两类文件做受管读写：</p>
 *
 * <ul>
 *   <li><b>共享默认层</b>：工作区根下的本地文件（{@code <workspace>/AGENTS.md} 等），
 *       由框架在无用户覆盖时作为兜底读入；</li>
 *   <li><b>用户覆盖层</b>：经框架文件系统的 {@code IsolationScope.USER} 命名空间写入
 *       {@code <workspace>/<userId>/AGENTS.md} 等，与框架读取路径一致。</li>
 * </ul>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiWorkspaceServiceImpl implements IAiWorkspaceService {

    /**
     * 人格与行为约定文件名
     */
    private static final String AGENTS_MD = "AGENTS.md";

    /**
     * 文件类型：人格与行为约定
     */
    private static final String TYPE_AGENTS = "AGENTS";

    /**
     * 文件类型：附加上下文文件
     */
    private static final String TYPE_EXTRA = "EXTRA";

    /**
     * 层来源：共享默认（工作区根）
     */
    private static final String SCOPE_SHARED = "SHARED";

    /**
     * 层来源：用户覆盖（用户目录）
     */
    private static final String SCOPE_USER = "USER";

    /**
     * 生效来源：两处均无内容
     */
    private static final String EFFECTIVE_NONE = "NONE";

    /**
     * 工作区控制台使用的固定会话标识（用户级隔离下与会话无关）
     */
    private static final String CONSOLE_SESSION = "workspace-console";

    /**
     * 允许的工作区相对路径：仅 Markdown 文件，禁止 {@code ..} 与绝对路径，避免越权访问工作区其它文件
     */
    private static final Pattern SAFE_PATH =
        Pattern.compile("^(?!.*\\.\\.)[A-Za-z0-9][A-Za-z0-9._/-]{0,126}\\.md$");

    private final IAiAgentService agentService;

    private final AgentRegistry agentRegistry;

    private final AiRuntimeConfigStore runtimeConfigStore;

    @Override
    public List<AiWorkspaceFileVo> listFiles(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        List<AiWorkspaceFileVo> files = new ArrayList<>();
        for (String path : allowedPaths()) {
            String shared = readShared(workspace, path);
            String user = readUser(workspace, ctx, path);
            files.add(buildFile(path, shared, user));
        }
        return files;
    }

    @Override
    public AiWorkspaceContentVo readContent(Long agentId, Long userId, String path, String scope) {
        validatePath(path);
        String layer = normalizeScope(scope);
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        String content = SCOPE_USER.equals(layer)
            ? readUser(workspace, contextOf(userId), path)
            : readShared(workspace, path);
        AiWorkspaceContentVo vo = new AiWorkspaceContentVo();
        vo.setPath(path);
        vo.setScope(layer);
        vo.setContent(content == null ? "" : content);
        return vo;
    }

    @Override
    public void writeContent(AiWorkspaceContentBo bo) {
        validatePath(bo.getPath());
        String layer = normalizeScope(bo.getScope());
        AiAgent definition = agentService.getEnabledAgent(bo.getAgentId());
        WorkspaceManager workspace = workspaceOf(definition);
        String content = bo.getContent() == null ? "" : bo.getContent();
        if (SCOPE_USER.equals(layer)) {
            workspace.writeUtf8WorkspaceRelative(contextOf(bo.getUserId()), bo.getPath(), content);
        } else {
            writeShared(workspace, bo.getPath(), content);
        }
        log.info("工作区文件已更新：agent={}, userId={}, path={}, scope={}, chars={}",
            definition.getAgentCode(), bo.getUserId(), bo.getPath(), layer, content.length());
    }

    @Override
    public void resetUserOverride(Long agentId, Long userId, String path) {
        validatePath(path);
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        Path target = workspace.resolveRuntimeDataPath(contextOf(userId), path).normalize();
        try {
            boolean deleted = Files.deleteIfExists(target);
            log.info("工作区文件用户覆盖已清除：agent={}, userId={}, path={}, deleted={}",
                definition.getAgentCode(), userId, path, deleted);
        } catch (IOException e) {
            throw new ServiceException("清除用户覆盖失败：" + e.getMessage());
        }
    }

    /**
     * 构造受管文件清单：固定的 {@code AGENTS.md} 加运行期登记的附加上下文文件。
     *
     * @return 去重后的工作区相对路径列表（AGENTS.md 恒在首位）
     */
    private List<String> allowedPaths() {
        Set<String> paths = new LinkedHashSet<>();
        paths.add(AGENTS_MD);
        AiWorkspaceConfigBo policy = runtimeConfigStore.workspace();
        if (policy.getAdditionalContextFiles() != null) {
            for (String file : policy.getAdditionalContextFiles()) {
                if (StringUtils.isNotBlank(file) && SAFE_PATH.matcher(file.trim()).matches()) {
                    paths.add(file.trim());
                }
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * 读取共享默认层内容（工作区根下的本地文件）。
     *
     * @param workspace 工作区管理器
     * @param path      工作区相对路径
     * @return 文件正文；不存在时返回 null
     */
    private String readShared(WorkspaceManager workspace, String path) {
        Path file = sharedFile(workspace, path);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取共享默认工作区文件失败：path={}, {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 读取用户覆盖层内容（经框架文件系统按用户命名空间读取）。
     *
     * @param workspace 工作区管理器
     * @param ctx       运行上下文
     * @param path      工作区相对路径
     * @return 文件正文；该层不存在时返回 null
     */
    private String readUser(WorkspaceManager workspace, RuntimeContext ctx, String path) {
        AbstractFilesystem filesystem = workspace.getFilesystem();
        if (filesystem == null) {
            return null;
        }
        try {
            ReadResult result = filesystem.read(ctx, path, 0, 0);
            if (result != null && result.isSuccess() && result.fileData() != null) {
                return result.fileData().content();
            }
        } catch (Exception e) {
            log.warn("读取用户覆盖工作区文件失败：path={}, {}", path, e.getMessage());
        }
        return null;
    }

    /**
     * 写入共享默认层（工作区根下的本地文件，原子覆盖）。
     *
     * @param workspace 工作区管理器
     * @param path      工作区相对路径
     * @param content   文件正文
     */
    private void writeShared(WorkspaceManager workspace, String path, String content) {
        Path file = sharedFile(workspace, path);
        if (file == null) {
            throw new ServiceException("非法的共享默认文件路径：" + path);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp." + UUID.randomUUID());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 忽略临时文件清理失败
            }
            throw new ServiceException("保存共享默认工作区文件失败：" + e.getMessage());
        }
    }

    /**
     * 解析共享默认层文件路径（限定在工作区根之下）。
     *
     * @param workspace 工作区管理器
     * @param path      工作区相对路径
     * @return 绝对路径；越界时返回 null
     */
    private Path sharedFile(WorkspaceManager workspace, String path) {
        Path root = workspace.getWorkspace().normalize();
        Path target = root.resolve(path).normalize();
        return target.startsWith(root) ? target : null;
    }

    /**
     * 解析智能体工作区管理器。
     *
     * @param definition 智能体定义
     * @return 工作区管理器
     */
    private WorkspaceManager workspaceOf(AiAgent definition) {
        return agentRegistry.get(definition).getWorkspaceManager();
    }

    /**
     * 构造工作区控制台使用的运行上下文。
     *
     * @param userId 归属用户主键
     * @return 运行上下文
     */
    private RuntimeContext contextOf(Long userId) {
        return RuntimeContext.builder()
            .userId(String.valueOf(userId))
            .sessionId(CONSOLE_SESSION)
            .build();
    }

    /**
     * 构造工作区文件视图对象。
     *
     * @param path   工作区相对路径
     * @param shared 共享默认层正文
     * @param user   用户覆盖层正文
     * @return 视图对象
     */
    private AiWorkspaceFileVo buildFile(String path, String shared, String user) {
        AiWorkspaceFileVo vo = new AiWorkspaceFileVo();
        vo.setPath(path);
        vo.setName(fileNameOf(path));
        vo.setType(AGENTS_MD.equals(path) ? TYPE_AGENTS : TYPE_EXTRA);
        vo.setHasShared(StringUtils.isNotEmpty(shared));
        vo.setSharedChars(shared == null ? 0 : shared.length());
        vo.setHasUser(StringUtils.isNotEmpty(user));
        vo.setUserChars(user == null ? 0 : user.length());
        if (vo.isHasUser()) {
            vo.setEffective(SCOPE_USER);
        } else if (vo.isHasShared()) {
            vo.setEffective(SCOPE_SHARED);
        } else {
            vo.setEffective(EFFECTIVE_NONE);
        }
        return vo;
    }

    /**
     * 取路径中的文件名。
     *
     * @param path 工作区相对路径
     * @return 文件名
     */
    private String fileNameOf(String path) {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    /**
     * 校验工作区文件路径：必须为 {@code AGENTS.md} 或已登记的附加上下文文件，且不含越界写法。
     *
     * @param path 工作区相对路径
     */
    private void validatePath(String path) {
        if (StringUtils.isBlank(path) || !SAFE_PATH.matcher(path).matches()) {
            throw new ServiceException("非法的工作区文件路径：" + path);
        }
        if (!allowedPaths().contains(path)) {
            throw new ServiceException("该文件不在工作区受管清单内：" + path);
        }
    }

    /**
     * 规范化层来源。
     *
     * @param scope 层来源
     * @return SHARED 或 USER
     */
    private String normalizeScope(String scope) {
        if (SCOPE_USER.equalsIgnoreCase(StringUtils.trim(scope))) {
            return SCOPE_USER;
        }
        if (SCOPE_SHARED.equalsIgnoreCase(StringUtils.trim(scope))) {
            return SCOPE_SHARED;
        }
        throw new ServiceException("非法的写入层：" + scope);
    }

}