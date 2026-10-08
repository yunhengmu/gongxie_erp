package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiSecretManager;
import org.dromara.ai.core.mcp.McpToolNameNormalizer;
import org.dromara.ai.core.mcp.NormalizingMcpClientWrapper;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.AiScenarioPackMcpTool;
import org.dromara.ai.domain.bo.AiMcpClientBo;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.dromara.ai.domain.vo.AiMcpClientTestVo;
import org.dromara.ai.domain.vo.AiMcpClientVo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.ai.service.IAiMcpClientService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 MCP Server 连接配置 Service 业务层处理（REQ-AI-16）。
 *
 * <p>仅支持 {@code sse} / {@code http}（streamable）两种 HTTP 传输；连接配置为平台级，但
 * <b>接入改为按场景包</b>（2026-09-30）：只有会话所选场景包内勾选的 MCP 工具才会装配，
 * 未选包的会话不接入任何 MCP 工具；连接是否启用、以及工具白名单 {@code enable_tools} 仍是前置闸门。</p>
 *
 * <p>装配链路<b>不走</b> {@code ToolsConfig.mcpServers}：框架的 {@code McpServerRegistrar} 会以远端
 * 自声明的工具名原样注册，而这类名字可能不满足 LLM function name 规范。改为由本服务
 * {@link #openClients(McpClientAssembly)} 构建「归一化名装饰」后的客户端，交给装配层自行注册
 * （见 {@code AgentRegistry#registerMcpClients}）；框架的 {@code ToolFilter} 会把不在
 * {@code ToolsConfig.allow} 的工具裁掉，故本服务需要**先发现工具名**再交给装配层入白名单。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMcpClientServiceImpl implements IAiMcpClientService {

    /**
     * 连接与工具发现的超时（与框架默认初始化超时同量级，避免页面「测试连接」长时间挂起）
     */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 工具发现结果的缓存时长：配置未变更时避免每次装配都去外连
     */
    private static final long DISCOVERY_CACHE_MILLIS = Duration.ofMinutes(5).toMillis();

    /**
     * 支持的传输方式（不放开 stdio）
     */
    private static final Set<String> SUPPORTED_TRANSPORTS = Set.of(
        AiMcpClient.TRANSPORT_SSE, AiMcpClient.TRANSPORT_HTTP);

    private final AiMcpClientMapper baseMapper;

    private final AiSecretManager secretManager;

    /**
     * 工具发现缓存：配置主键 → 发现时间 + 工具名（配置变更后由更新时间驱动指纹变化，缓存按 TTL 过期）
     */
    private final ConcurrentHashMap<Long, DiscoveryCache> discoveryCache = new ConcurrentHashMap<>();

    @Override
    public AiMcpClientVo queryById(Long id) {
        return toVo(baseMapper.selectById(id));
    }

    @Override
    public PageResult<AiMcpClientVo> queryPageList(AiMcpClientBo bo, PageQuery pageQuery) {
        Page<AiMcpClient> page = baseMapper.selectPage(pageQuery.build(), buildQueryWrapper(bo));
        List<AiMcpClientVo> records = page.getRecords().stream().map(this::toVo).toList();
        return PageResult.build(records, page.getTotal());
    }

    @Override
    public Boolean insertByBo(AiMcpClientBo bo) {
        AiMcpClient add = MapstructUtils.convert(bo, AiMcpClient.class);
        applyNormalize(add);
        add.setHeadersJson(secretManager.encrypt(StringUtils.trim(bo.getHeadersJson())));
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            discoveryCache.remove(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(AiMcpClientBo bo) {
        AiMcpClient exists = baseMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("外部 MCP 连接不存在或已被删除");
        }
        AiMcpClient update = MapstructUtils.convert(bo, AiMcpClient.class);
        applyNormalize(update);
        // 请求头为密文不可回显：留空表示「保持不变」，避免编辑其他字段时把凭据清空
        if (StringUtils.isBlank(bo.getHeadersJson())) {
            update.setHeadersJson(exists.getHeadersJson());
        } else {
            update.setHeadersJson(secretManager.encrypt(StringUtils.trim(bo.getHeadersJson())));
        }
        validEntityBeforeSave(update);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            discoveryCache.remove(update.getId());
        }
        return flag;
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的外部 MCP 连接");
        }
        boolean flag = baseMapper.deleteByIds(ids) > 0;
        if (flag) {
            ids.forEach(discoveryCache::remove);
        }
        return flag;
    }

    @Override
    public AiMcpClientTestVo testConnection(AiMcpClientBo bo) {
        AiMcpClientTestVo vo = new AiMcpClientTestVo();
        AiMcpClient probe = buildProbe(bo);
        vo.setServerName(probe.getServerName());
        vo.setTools(List.of());
        if (StringUtils.isBlank(probe.getServerName()) || StringUtils.isBlank(probe.getUrl())
            || StringUtils.isBlank(probe.getTransport())) {
            vo.setSuccess(false);
            vo.setMessage("请先填写 Server 名称、传输方式与地址");
            return vo;
        }
        McpClientWrapper wrapper = null;
        try {
            wrapper = buildClient(probe);
            // 必须用 Mono.defer 延迟求值：listTools() 在装配期就会校验「已初始化」，
            // 直接 .then(wrapper.listTools()) 会在 initialize() 完成前求值而报 not initialized
            List<McpSchema.Tool> tools = wrapper.initialize()
                .then(Mono.defer(wrapper::listTools))
                .block(DISCOVERY_TIMEOUT);
            List<String> names = tools == null ? List.of()
                : tools.stream().map(McpSchema.Tool::name).toList();
            vo.setTools(names);
            vo.setSuccess(true);
            vo.setMessage("连接成功，发现 " + names.size() + " 个工具");
            if (probe.getId() != null) {
                discoveryCache.put(probe.getId(), new DiscoveryCache(System.currentTimeMillis(), names));
            }
            log.info("外部 MCP 连接测试成功：server={}, url={}, tools={}",
                probe.getServerName(), probe.getUrl(), names);
        } catch (Exception e) {
            vo.setSuccess(false);
            vo.setMessage("连接失败：" + e.getMessage());
            log.warn("外部 MCP 连接测试失败：server={}, url={}, 原因={}",
                probe.getServerName(), probe.getUrl(), e.getMessage());
        } finally {
            closeQuietly(wrapper);
        }
        return vo;
    }

    @Override
    public McpClientAssembly resolveForAssembly(List<AiScenarioPackMcpTool> selections) {
        if (selections == null || selections.isEmpty()) {
            // 未选场景包（或包内未勾选任何 MCP 工具）：该会话不接入任何 MCP 工具
            return McpClientAssembly.empty();
        }
        // 连接主键 → (远端原始工具名 → 授权决策)，保持勾选顺序
        Map<Long, Map<String, String>> selectedByClient = new LinkedHashMap<>();
        for (AiScenarioPackMcpTool selection : selections) {
            if (selection == null || selection.getMcpClientId() == null
                || StringUtils.isBlank(selection.getToolName())) {
                continue;
            }
            selectedByClient.computeIfAbsent(selection.getMcpClientId(), key -> new LinkedHashMap<>())
                .put(StringUtils.trim(selection.getToolName()), selection.getDecision());
        }
        if (selectedByClient.isEmpty()) {
            return McpClientAssembly.empty();
        }
        List<String> serverNames = new ArrayList<>();
        List<String> toolNames = new ArrayList<>();
        Map<String, Map<String, String>> aliases = new LinkedHashMap<>();
        Map<String, String> toolDecisions = new LinkedHashMap<>();
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        StringBuilder signature = new StringBuilder();
        // 连接「启用中」仍是一道前置闸门：仅遍历启用中的连接（按主键升序，决定归一化名的分配顺序）
        for (AiMcpClient client : queryEnabled()) {
            Map<String, String> selected = selectedByClient.get(client.getId());
            if (selected == null) {
                continue;
            }
            List<String> whitelist = splitTools(client.getEnableTools());
            List<String> allowed = applyWhitelistAndSelection(discoverTools(client), whitelist,
                selected.keySet());
            if (allowed.isEmpty()) {
                log.warn("场景包勾选的 MCP 工具在连接侧不可用（未声明 / 不在白名单），已忽略：server={}, selected={}",
                    client.getServerName(), selected.keySet());
                continue;
            }
            serverNames.add(client.getServerName());
            Map<String, String> aliasByOriginal = new LinkedHashMap<>();
            for (String rawName : allowed) {
                String alias = normalizer.allocate(rawName);
                if (alias == null) {
                    log.warn("跳过无法归一化的 MCP 工具名：server={}, toolName={}",
                        client.getServerName(), rawName);
                    continue;
                }
                if (!alias.equals(rawName)) {
                    aliasByOriginal.put(rawName, alias);
                }
                if (!toolNames.contains(alias)) {
                    toolNames.add(alias);
                }
                toolDecisions.put(alias, selected.get(rawName));
            }
            if (!aliasByOriginal.isEmpty()) {
                aliases.put(client.getServerName(), aliasByOriginal);
            }
            // 指纹不含请求头（凭据）内容：凭据变更会体现为 updateTime 变化
            signature.append(client.getServerName()).append('=').append(client.getUpdateTime())
                .append(':').append(client.getTransport()).append(':').append(client.getUrl())
                .append(':').append(String.join(",", whitelist)).append(':')
                .append(String.join(",", allowed)).append(':')
                .append(renderAliases(aliasByOriginal)).append(':')
                .append(renderDecisions(selected)).append(';');
        }
        return new McpClientAssembly(serverNames, toolNames, aliases, toolDecisions, signature.toString());
    }

    @Override
    public Map<String, McpClientWrapper> openClients(McpClientAssembly assembly) {
        if (assembly.serverNames().isEmpty()) {
            return Map.of();
        }
        Map<String, AiMcpClient> byName = new LinkedHashMap<>();
        for (AiMcpClient client : queryEnabled()) {
            byName.put(client.getServerName(), client);
        }
        Map<String, McpClientWrapper> result = new LinkedHashMap<>();
        for (String serverName : assembly.serverNames()) {
            AiMcpClient client = byName.get(serverName);
            if (client == null) {
                log.warn("外部 MCP 连接不存在或已停用，本轮跳过接入：server={}", serverName);
                continue;
            }
            try {
                McpClientWrapper wrapper = buildClient(client);
                Map<String, String> aliasByOriginal = assembly.aliasesOf(serverName);
                result.put(serverName, aliasByOriginal.isEmpty()
                    ? wrapper : new NormalizingMcpClientWrapper(wrapper, aliasByOriginal));
            } catch (Exception e) {
                log.warn("外部 MCP 客户端构建失败，该 Server 本轮不接入：server={}, 原因={}",
                    serverName, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 渲染改名映射（仅用于日志与指纹）：{@code 原始名>归一化名,原始名>归一化名}。
     *
     * @param aliasByOriginal 改名映射
     * @return 渲染结果；无改名时为空串
     */
    private String renderAliases(Map<String, String> aliasByOriginal) {
        if (aliasByOriginal.isEmpty()) {
            return "";
        }
        StringBuilder rendered = new StringBuilder();
        aliasByOriginal.forEach((original, alias) -> {
            if (rendered.length() > 0) {
                rendered.append(',');
            }
            rendered.append(original).append('>').append(alias);
        });
        return rendered.toString();
    }

    /**
     * 构造探测用实体：入参未填的字段回落到已存配置（列表页「测试连接」仅传主键）。
     *
     * @param bo 业务对象
     * @return 探测实体
     */
    private AiMcpClient buildProbe(AiMcpClientBo bo) {
        AiMcpClient exists = bo.getId() == null ? null : baseMapper.selectById(bo.getId());
        AiMcpClient probe = new AiMcpClient();
        probe.setId(bo.getId());
        probe.setServerName(firstNonBlank(bo.getServerName(), exists == null ? null : exists.getServerName()));
        probe.setTransport(normalizeTransport(firstNonBlank(bo.getTransport(),
            exists == null ? null : exists.getTransport())));
        probe.setUrl(firstNonBlank(bo.getUrl(), exists == null ? null : exists.getUrl()));
        probe.setTimeoutMs(bo.getTimeoutMs() != null ? bo.getTimeoutMs()
            : (exists == null ? null : exists.getTimeoutMs()));
        probe.setInitTimeoutMs(bo.getInitTimeoutMs() != null ? bo.getInitTimeoutMs()
            : (exists == null ? null : exists.getInitTimeoutMs()));
        probe.setHeadersJson(resolveCipherHeaders(bo, exists));
        return probe;
    }

    /**
     * 解析请求头密文：入参已填则加密，未填则复用已存密文（编辑 / 行内测试场景）。
     *
     * @param bo     业务对象
     * @param exists 已存实体（可为空）
     * @return 请求头密文；均无值时返回 null
     */
    private String resolveCipherHeaders(AiMcpClientBo bo, AiMcpClient exists) {
        String plain = StringUtils.trim(bo.getHeadersJson());
        if (StringUtils.isNotBlank(plain)) {
            return secretManager.encrypt(plain);
        }
        return exists == null ? null : exists.getHeadersJson();
    }

    /**
     * 取第一个非空文本。
     *
     * @param primary   首选值
     * @param fallback  兜底值
     * @return 首个非空值（均空时返回 null）
     */
    private String firstNonBlank(String primary, String fallback) {
        if (StringUtils.isNotBlank(primary)) {
            return StringUtils.trim(primary);
        }
        return StringUtils.isBlank(fallback) ? null : fallback;
    }

    /**
     * 由连接配置构造 AgentScope MCP 连接配置。
     *
     * @param client 连接配置实体
     * @return AgentScope MCP 连接配置
     */
    private McpServerConfig toConfig(AiMcpClient client) {
        McpServerConfig config = new McpServerConfig();
        config.setTransport(normalizeTransport(client.getTransport()));
        config.setUrl(client.getUrl());
        Map<String, String> headers = parseHeaders(secretManager.decrypt(client.getHeadersJson()));
        if (!headers.isEmpty()) {
            config.setHeaders(headers);
        }
        List<String> whitelist = splitTools(client.getEnableTools());
        if (!whitelist.isEmpty()) {
            config.setEnableTools(whitelist);
        }
        if (client.getTimeoutMs() != null && client.getTimeoutMs() > 0) {
            config.setTimeout(Duration.ofMillis(client.getTimeoutMs()));
        }
        if (client.getInitTimeoutMs() != null && client.getInitTimeoutMs() > 0) {
            config.setInitializationTimeout(Duration.ofMillis(client.getInitTimeoutMs()));
        }
        return config;
    }

    /**
     * 按连接配置构造 MCP 客户端（与框架 McpServerRegistrar 的构造方式保持一致）。
     *
     * @param client 连接配置实体
     * @return MCP 客户端包装
     */
    private McpClientWrapper buildClient(AiMcpClient client) {
        McpServerConfig config = toConfig(client);
        McpClientBuilder builder = McpClientBuilder.create(client.getServerName());
        if (AiMcpClient.TRANSPORT_SSE.equals(normalizeTransport(client.getTransport()))) {
            builder.sseTransport(config.getUrl());
        } else {
            builder.streamableHttpTransport(config.getUrl());
        }
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            builder.headers(config.getHeaders());
        }
        if (config.getTimeout() != null) {
            builder.timeout(config.getTimeout());
        }
        if (config.getInitializationTimeout() != null) {
            builder.initializationTimeout(config.getInitializationTimeout());
        }
        return builder.buildSync();
    }

    /**
     * 发现外部 Server 的实际工具名（带短 TTL 缓存）。
     *
     * @param client 连接配置实体
     * @return 工具名列表；连接失败时为空列表
     */
    private List<String> discoverTools(AiMcpClient client) {
        DiscoveryCache cached = discoveryCache.get(client.getId());
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < DISCOVERY_CACHE_MILLIS) {
            return cached.tools();
        }
        List<String> tools = listRemoteTools(client);
        discoveryCache.put(client.getId(), new DiscoveryCache(now, tools));
        return tools;
    }

    /**
     * 实际建立连接并列出工具（失败不抛异常，返回空列表并告警）。
     *
     * @param client 连接配置实体
     * @return 工具名列表
     */
    private List<String> listRemoteTools(AiMcpClient client) {
        McpClientWrapper wrapper = null;
        try {
            wrapper = buildClient(client);
            // 同 testConnection：listTools 必须延迟到 initialize 完成后求值
            List<McpSchema.Tool> tools = wrapper.initialize()
                .then(Mono.defer(wrapper::listTools))
                .block(DISCOVERY_TIMEOUT);
            if (tools == null) {
                log.warn("发现外部 MCP 工具超时：server={}, url={}", client.getServerName(), client.getUrl());
                return List.of();
            }
            List<String> names = tools.stream().map(McpSchema.Tool::name).toList();
            log.info("发现外部 MCP 工具：server={}, url={}, tools={}", client.getServerName(), client.getUrl(), names);
            return names;
        } catch (Exception e) {
            log.warn("发现外部 MCP 工具失败（该 Server 本轮不接入任何工具）：server={}, url={}, 原因={}",
                client.getServerName(), client.getUrl(), e.getMessage());
            return List.of();
        } finally {
            closeQuietly(wrapper);
        }
    }

    /**
     * 按白名单过滤发现到的工具名。
     *
     * @param discovered 发现到的工具名
     * @param whitelist  白名单（为空表示不过滤）
     * @return 实际放行的工具名
     */
    private List<String> applyWhitelist(List<String> discovered, List<String> whitelist) {
        if (whitelist.isEmpty()) {
            return discovered;
        }
        List<String> allowed = discovered.stream().filter(whitelist::contains).toList();
        List<String> missing = whitelist.stream().filter(name -> !discovered.contains(name)).toList();
        if (!missing.isEmpty()) {
            log.warn("MCP 工具白名单包含该 Server 未声明的工具，已忽略：{}", missing);
        }
        return allowed;
    }

    /**
     * 在「发现到的工具 ∩ 连接白名单」基础上再取场景包勾选的子集。
     *
     * @param discovered 发现到的工具名
     * @param whitelist  连接的工具白名单（enable_tools）
     * @param selected   场景包勾选的远端原始工具名
     * @return 实际放行的工具名
     */
    private List<String> applyWhitelistAndSelection(List<String> discovered, List<String> whitelist,
                                                    Set<String> selected) {
        List<String> base = applyWhitelist(discovered, whitelist);
        List<String> allowed = base.stream().filter(selected::contains).toList();
        List<String> missing = selected.stream().filter(name -> !base.contains(name)).toList();
        if (!missing.isEmpty()) {
            log.warn("场景包勾选的 MCP 工具不在该连接的白名单内或未被声明，已忽略：{}", missing);
        }
        return allowed;
    }

    /**
     * 渲染包内决策（仅用于日志与指纹）：{@code 工具名=决策,工具名=决策}。
     *
     * @param decisions 远端原始工具名 → 决策
     * @return 渲染结果；无决策时为空串
     */
    private String renderDecisions(Map<String, String> decisions) {
        if (decisions.isEmpty()) {
            return "";
        }
        StringBuilder rendered = new StringBuilder();
        decisions.forEach((name, decision) -> {
            if (rendered.length() > 0) {
                rendered.append(',');
            }
            rendered.append(name).append('=').append(decision);
        });
        return rendered.toString();
    }

    /**
     * 解析请求头 JSON 文本。
     *
     * @param headersJson 明文 JSON（可为空）
     * @return 请求头映射
     */
    private Map<String, String> parseHeaders(String headersJson) {
        if (StringUtils.isBlank(headersJson) || !JsonUtils.isJsonObject(headersJson)) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonUtils.parseObject(headersJson, new TypeReference<Map<String, Object>>() {
        });
        if (parsed == null || parsed.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        parsed.forEach((key, value) -> {
            if (value != null) {
                headers.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return headers;
    }

    /**
     * 拆分工具白名单文本。
     *
     * @param enableTools 逗号分隔文本
     * @return 工具名列表（去重、保序）
     */
    private List<String> splitTools(String enableTools) {
        if (StringUtils.isBlank(enableTools)) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : enableTools.split(",")) {
            String name = StringUtils.trim(part);
            if (StringUtils.isNotBlank(name)) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * 规范化传输方式并校验（仅 sse / http）。
     *
     * @param transport 原始传输方式
     * @return 规范化后的传输方式
     */
    private String normalizeTransport(String transport) {
        String value = StringUtils.trim(transport).toLowerCase(Locale.ROOT);
        String normalized = switch (value) {
            case "streamable-http", "streamablehttp" -> AiMcpClient.TRANSPORT_HTTP;
            default -> value;
        };
        if (StringUtils.isNotBlank(normalized) && !SUPPORTED_TRANSPORTS.contains(normalized)) {
            throw new ServiceException("仅支持 sse 与 http（streamable）两种传输方式，不支持：" + transport);
        }
        return normalized;
    }

    /**
     * 规范化字段（传输方式、空串转 null）。
     *
     * @param entity 实体
     */
    private void applyNormalize(AiMcpClient entity) {
        entity.setServerName(StringUtils.trim(entity.getServerName()));
        entity.setUrl(StringUtils.trim(entity.getUrl()));
        entity.setTransport(normalizeTransport(entity.getTransport()));
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus(AiMcpClient.STATUS_ENABLED);
        }
    }

    /**
     * 查询启用中的连接配置。
     *
     * @return 连接配置列表
     */
    private List<AiMcpClient> queryEnabled() {
        LambdaQueryWrapper<AiMcpClient> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiMcpClient::getStatus, AiMcpClient.STATUS_ENABLED);
        lqw.orderByAsc(AiMcpClient::getId);
        return baseMapper.selectList(lqw);
    }

    /**
     * 实体转视图对象，并填充「是否已配置请求头」标识（凭据不回显）。
     *
     * @param entity 实体
     * @return 视图对象；实体为空时返回 null
     */
    private AiMcpClientVo toVo(AiMcpClient entity) {
        if (entity == null) {
            return null;
        }
        AiMcpClientVo vo = MapstructUtils.convert(entity, AiMcpClientVo.class);
        if (vo != null) {
            vo.setHeadersConfigured(StringUtils.isNotBlank(entity.getHeadersJson()));
        }
        return vo;
    }

    /**
     * 静默关闭 MCP 客户端。
     *
     * @param wrapper 客户端包装
     */
    private void closeQuietly(McpClientWrapper wrapper) {
        if (wrapper == null) {
            return;
        }
        try {
            wrapper.close();
        } catch (Exception e) {
            log.debug("关闭 MCP 客户端失败：{}", e.getMessage());
        }
    }

    /**
     * 构建动态查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiMcpClient> buildQueryWrapper(AiMcpClientBo bo) {
        LambdaQueryWrapper<AiMcpClient> lqw = Wrappers.lambdaQuery();
        lqw.and(StringUtils.isNotBlank(bo.getKeyword()), w -> w
            .like(AiMcpClient::getServerName, bo.getKeyword())
            .or().like(AiMcpClient::getUrl, bo.getKeyword()));
        lqw.eq(StringUtils.isNotBlank(bo.getTransport()), AiMcpClient::getTransport, bo.getTransport());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiMcpClient::getStatus, bo.getStatus());
        lqw.orderByAsc(AiMcpClient::getServerName);
        return lqw;
    }

    /**
     * 保存前的数据校验。
     *
     * @param entity 实体
     */
    private void validEntityBeforeSave(AiMcpClient entity) {
        LambdaQueryWrapper<AiMcpClient> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiMcpClient::getServerName, entity.getServerName());
        lqw.ne(entity.getId() != null, AiMcpClient::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("Server 名称已存在：" + entity.getServerName());
        }
    }

    /**
     * 工具发现缓存项。
     *
     * @param at    发现时间（毫秒）
     * @param tools 工具名列表
     */
    private record DiscoveryCache(long at, List<String> tools) {
    }

}