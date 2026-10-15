package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiUsageStat;
import org.dromara.ai.domain.bo.AiUsageStatBo;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.domain.vo.AiUsageOverviewVo;
import org.dromara.ai.domain.vo.AiUsageStatExportVo;
import org.dromara.ai.domain.vo.AiUsageStatVo;
import org.dromara.ai.domain.vo.AiUsageSummaryVo;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.mapper.AiUsageStatMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.system.api.UserService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 用量统计 Service 业务层处理
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiUsageStatServiceImpl implements IAiUsageStatService {

    /**
     * Redis 当日 Token 计数键前缀
     */
    private static final String KEY_DAY_TOKENS = "bizbuddy:ai:usage:day:tokens:";

    /**
     * Redis 当日请求计数键前缀
     */
    private static final String KEY_DAY_REQUESTS = "bizbuddy:ai:usage:day:requests:";

    /**
     * Redis 当月 Token 计数键前缀
     */
    private static final String KEY_MONTH_TOKENS = "bizbuddy:ai:usage:month:tokens:";

    /**
     * 计数键保留时长（跨天后仍需支撑日终校对）
     */
    private static final Duration COUNTER_TTL = Duration.ofDays(3);

    /**
     * 月计数键保留时长
     */
    private static final Duration MONTH_COUNTER_TTL = Duration.ofDays(40);

    /**
     * 费用精度
     */
    private static final int COST_SCALE = 6;

    /**
     * 千 Token 基数
     */
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    private final AiUsageStatMapper usageStatMapper;

    private final IAiAgentService agentService;

    private final AiModelConfigMapper modelConfigMapper;

    private final UserService userService;

    private final RedissonClient redissonClient;

    /**
     * 项目数据源：仅用于探测数据库产品名，以选择用量 upsert 的方言实现
     */
    private final DataSource dataSource;

    /**
     * 数据源是否为 MySQL（延迟探测并缓存；探测失败按 PostgreSQL 处理）
     */
    private volatile Boolean mysqlDialect;

    @Override
    public void accumulate(Long userId, Long agentId, Long modelId, ChatTokenUsage tokens, int toolCallCount,
                           int requestCount) {
        if (userId == null || agentId == null || tokens == null) {
            return;
        }
        long prompt = tokens.promptTokens() == null ? 0L : tokens.promptTokens();
        long completion = tokens.completionTokens() == null ? 0L : tokens.completionTokens();
        long total = tokens.totalTokens() == null ? prompt + completion : tokens.totalTokens();
        BigDecimal cost = estimateCost(modelId, prompt, completion);
        AiUsageStat stat = new AiUsageStat();
        // 走平台统一主键生成器（容忍时钟回拨），不用 IdWorker 的独立单例，避免旁路绕过加固
        stat.setId(IdGeneratorUtil.nextLongId());
        stat.setStatDate(LocalDate.now());
        stat.setAgentId(agentId);
        stat.setModelId(modelId);
        stat.setUserId(userId);
        stat.setPromptTokens(prompt);
        stat.setCompletionTokens(completion);
        stat.setTotalTokens(total);
        stat.setRequestCount(requestCount);
        stat.setToolCallCount(toolCallCount);
        stat.setCost(cost);
        try {
            // 两方言的累加 upsert 语法不同（PostgreSQL on conflict / MySQL on duplicate key），按数据源选择
            if (isMysqlDialect()) {
                usageStatMapper.upsertMysql(stat);
            } else {
                usageStatMapper.upsert(stat);
            }
        } catch (Exception e) {
            // 统计失败不影响对话主流程
            log.error("用量统计写入失败：userId={}, agentId={}, modelId={}", userId, agentId, modelId, e);
            return;
        }
        incrementCounters(userId, total, requestCount);
    }

    /**
     * 探测数据源是否为 MySQL（延迟一次、结果缓存）。
     *
     * <p>用量统计的累加 upsert 是**双方言**实现：PostgreSQL 用 {@code on conflict … excluded.}，
     * MySQL 8.0 用 {@code on duplicate key update … values(…)}。判定方式与技能仓库装配
     * （{@code AgentScopeConfig#aiSkillRepository}）一致：读数据源的数据库产品名。
     * 探测失败时按 PostgreSQL 处理——写入异常仍由 {@link #accumulate} 吞掉，不影响对话主流程。</p>
     *
     * @return 数据源为 MySQL 时返回 true
     */
    private boolean isMysqlDialect() {
        Boolean cached = mysqlDialect;
        if (cached != null) {
            return cached;
        }
        boolean resolved = false;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection == null ? null : connection.getMetaData();
            String product = metaData == null ? null : metaData.getDatabaseProductName();
            resolved = product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
        } catch (Exception e) {
            log.warn("探测数据库产品名失败，用量统计按 PostgreSQL 方言处理：{}", e.getMessage());
        }
        mysqlDialect = resolved;
        return resolved;
    }

    @Override
    public PageResult<AiUsageStatVo> queryPage(AiUsageStatBo bo, PageQuery pageQuery) {
        Page<AiUsageStat> page = usageStatMapper.selectPage(pageQuery.build(), buildQueryWrapper(bo));
        return PageResult.build(toVos(page.getRecords()), page.getTotal());
    }

    @Override
    public AiUsageOverviewVo overview(AiUsageStatBo bo) {
        List<AiUsageStat> list = usageStatMapper.selectList(buildQueryWrapper(bo));
        AiUsageOverviewVo overview = new AiUsageOverviewVo();
        AiUsageSummaryVo total = new AiUsageSummaryVo();
        total.setDimension("total");
        total.setLabel("合计");
        Map<String, AiUsageSummaryVo> byDate = new LinkedHashMap<>();
        Map<String, AiUsageSummaryVo> byAgent = new LinkedHashMap<>();
        Map<String, AiUsageSummaryVo> byModel = new LinkedHashMap<>();
        Map<Long, String> agentNames = loadAgentNames(list);
        Map<Long, String> modelNames = loadModelNames(list);
        for (AiUsageStat row : list) {
            total.accumulate(row.getPromptTokens(), row.getCompletionTokens(), row.getRequestCount(),
                row.getToolCallCount(), row.getCost());
            String dateKey = row.getStatDate() == null ? "-" : row.getStatDate().toString();
            merge(byDate, dateKey, dateKey, row);
            String agentKey = String.valueOf(row.getAgentId());
            merge(byAgent, agentKey, agentNames.getOrDefault(row.getAgentId(), "已删除智能体"), row);
            String modelKey = String.valueOf(row.getModelId());
            merge(byModel, modelKey, modelNames.getOrDefault(row.getModelId(), "未指定模型"), row);
        }
        overview.setTotal(total);
        overview.setByDate(new ArrayList<>(byDate.values()));
        overview.setByAgent(new ArrayList<>(byAgent.values()));
        overview.setByModel(new ArrayList<>(byModel.values()));
        return overview;
    }

    @Override
    public List<AiUsageStatExportVo> buildExportData(AiUsageStatBo bo) {
        List<AiUsageStatVo> rows = toVos(usageStatMapper.selectList(buildQueryWrapper(bo)));
        List<AiUsageStatExportVo> result = new ArrayList<>(rows.size());
        for (AiUsageStatVo row : rows) {
            AiUsageStatExportVo vo = new AiUsageStatExportVo();
            vo.setStatDate(row.getStatDate() == null ? "" : row.getStatDate().toString());
            vo.setAgentName(row.getAgentName());
            vo.setModelName(row.getModelName());
            vo.setUserName(row.getUserName());
            vo.setPromptTokens(row.getPromptTokens());
            vo.setCompletionTokens(row.getCompletionTokens());
            vo.setTotalTokens(row.getTotalTokens());
            vo.setRequestCount(row.getRequestCount());
            vo.setToolCallCount(row.getToolCallCount());
            vo.setCost(row.getCost());
            result.add(vo);
        }
        return result;
    }

    @Override
    public long todayTokens(Long userId) {
        LocalDate today = LocalDate.now();
        long cached = readCounter(dayTokensKey(userId, today));
        if (cached > 0) {
            return cached;
        }
        return sumTokens(userId, today, today);
    }

    @Override
    public long todayRequests(Long userId) {
        LocalDate today = LocalDate.now();
        String key = KEY_DAY_REQUESTS + userId + ":" + today.format(DAY_FORMAT);
        long cached = readCounter(key);
        if (cached > 0) {
            return cached;
        }
        LambdaQueryWrapper<AiUsageStat> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiUsageStat::getUserId, userId);
        lqw.eq(AiUsageStat::getStatDate, today);
        long sum = 0L;
        for (AiUsageStat row : usageStatMapper.selectList(lqw)) {
            sum += row.getRequestCount() == null ? 0L : row.getRequestCount();
        }
        return sum;
    }

    @Override
    public long monthTokens(Long userId) {
        LocalDate today = LocalDate.now();
        long cached = readCounter(monthTokensKey(userId, today));
        if (cached > 0) {
            return cached;
        }
        return sumTokens(userId, today.withDayOfMonth(1), today);
    }

    /**
     * 汇总某用户在日期区间内的总 Token（数据库口径，作为 Redis 计数缺失时的回退）。
     *
     * @param userId 用户主键
     * @param begin  起始日期（含）
     * @param end    结束日期（含）
     * @return 总 Token
     */
    private long sumTokens(Long userId, LocalDate begin, LocalDate end) {
        LambdaQueryWrapper<AiUsageStat> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiUsageStat::getUserId, userId);
        lqw.ge(AiUsageStat::getStatDate, begin);
        lqw.le(AiUsageStat::getStatDate, end);
        long sum = 0L;
        for (AiUsageStat row : usageStatMapper.selectList(lqw)) {
            sum += row.getTotalTokens() == null ? 0L : row.getTotalTokens();
        }
        return sum;
    }

    /**
     * 估算本轮费用（元）。
     *
     * @param modelId    模型主键
     * @param prompt     输入 Token
     * @param completion 输出 Token
     * @return 费用（单价未配置时返回 0）
     */
    private BigDecimal estimateCost(Long modelId, long prompt, long completion) {
        AiModelConfig model = modelId == null ? null : modelConfigMapper.selectById(modelId);
        if (model == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal input = model.getInputPrice() == null ? BigDecimal.ZERO : model.getInputPrice();
        BigDecimal output = model.getOutputPrice() == null ? BigDecimal.ZERO : model.getOutputPrice();
        return BigDecimal.valueOf(prompt).divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP).multiply(input)
            .add(BigDecimal.valueOf(completion).divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP).multiply(output))
            .setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 累加 Redis 计数（当日 Token / 当日请求 / 当月 Token）。
     *
     * @param userId       用户主键
     * @param tokens       本次总 Token
     * @param requestCount 本次请求数
     */
    private void incrementCounters(Long userId, long tokens, int requestCount) {
        LocalDate today = LocalDate.now();
        String dayTokens = dayTokensKey(userId, today);
        String dayRequests = KEY_DAY_REQUESTS + userId + ":" + today.format(DAY_FORMAT);
        String monthTokens = monthTokensKey(userId, today);
        if (tokens > 0) {
            redissonClient.getAtomicLong(dayTokens).addAndGet(tokens);
            redissonClient.getAtomicLong(monthTokens).addAndGet(tokens);
            RedisUtils.expire(dayTokens, COUNTER_TTL);
            RedisUtils.expire(monthTokens, MONTH_COUNTER_TTL);
        }
        if (requestCount > 0) {
            redissonClient.getAtomicLong(dayRequests).addAndGet(requestCount);
            RedisUtils.expire(dayRequests, COUNTER_TTL);
        }
    }

    /**
     * 读取 Redis 计数。
     *
     * @param key 计数键
     * @return 计数值；键不存在时返回 0
     */
    private long readCounter(String key) {
        if (!RedisUtils.hasKey(key)) {
            return 0L;
        }
        return redissonClient.getAtomicLong(key).get();
    }

    /**
     * 当日 Token 计数键。
     *
     * @param userId 用户主键
     * @param date   日期
     * @return Redis 键
     */
    private String dayTokensKey(Long userId, LocalDate date) {
        return KEY_DAY_TOKENS + userId + ":" + date.format(DAY_FORMAT);
    }

    /**
     * 当月 Token 计数键。
     *
     * @param userId 用户主键
     * @param date   日期
     * @return Redis 键
     */
    private String monthTokensKey(Long userId, LocalDate date) {
        return KEY_MONTH_TOKENS + userId + ":" + date.format(MONTH_FORMAT);
    }

    /**
     * 合并一行分组汇总。
     *
     * @param target 分组容器
     * @param key    分组键
     * @param label  分组显示名
     * @param row    用量行
     */
    private void merge(Map<String, AiUsageSummaryVo> target, String key, String label, AiUsageStat row) {
        AiUsageSummaryVo summary = target.computeIfAbsent(key, ignored -> {
            AiUsageSummaryVo vo = new AiUsageSummaryVo();
            vo.setDimension(key);
            vo.setLabel(label);
            return vo;
        });
        summary.accumulate(row.getPromptTokens(), row.getCompletionTokens(), row.getRequestCount(),
            row.getToolCallCount(), row.getCost());
    }

    /**
     * 构建用量查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiUsageStat> buildQueryWrapper(AiUsageStatBo bo) {
        LambdaQueryWrapper<AiUsageStat> lqw = Wrappers.lambdaQuery();
        lqw.ge(bo.getBeginDate() != null, AiUsageStat::getStatDate, bo.getBeginDate());
        lqw.le(bo.getEndDate() != null, AiUsageStat::getStatDate, bo.getEndDate());
        lqw.eq(bo.getAgentId() != null, AiUsageStat::getAgentId, bo.getAgentId());
        lqw.eq(bo.getModelId() != null, AiUsageStat::getModelId, bo.getModelId());
        lqw.eq(bo.getUserId() != null, AiUsageStat::getUserId, bo.getUserId());
        lqw.orderByDesc(AiUsageStat::getStatDate);
        lqw.orderByDesc(AiUsageStat::getId);
        return lqw;
    }

    /**
     * 批量转换并补齐智能体 / 模型 / 用户名称与单价配置标记。
     *
     * @param list 用量实体
     * @return 视图对象
     */
    private List<AiUsageStatVo> toVos(List<AiUsageStat> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new HashSet<>();
        list.forEach(row -> {
            if (row.getUserId() != null) {
                userIds.add(row.getUserId());
            }
        });
        Map<Long, String> agentNames = loadAgentNames(list);
        Map<Long, ModelMeta> modelMeta = loadModelMeta(list);
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        List<AiUsageStatVo> rows = new ArrayList<>(list.size());
        for (AiUsageStat entity : list) {
            AiUsageStatVo vo = new AiUsageStatVo();
            BeanUtils.copyProperties(entity, vo);
            vo.setAgentName(agentNames.get(entity.getAgentId()));
            vo.setUserName(userNames.get(entity.getUserId()));
            ModelMeta meta = modelMeta.get(entity.getModelId());
            vo.setModelName(meta == null ? null : meta.name());
            vo.setPriceConfigured(meta != null && meta.priced());
            rows.add(vo);
        }
        return rows;
    }

    /**
     * 批量查询智能体名称。
     *
     * @param list 用量实体
     * @return 智能体主键 → 名称
     */
    private Map<Long, String> loadAgentNames(List<AiUsageStat> list) {
        Set<Long> agentIds = new HashSet<>();
        list.forEach(row -> {
            if (row.getAgentId() != null) {
                agentIds.add(row.getAgentId());
            }
        });
        Map<Long, String> names = new HashMap<>();
        if (!agentIds.isEmpty()) {
            for (AiAgent agent : agentService.queryEntities(agentIds)) {
                names.put(agent.getId(), agent.getAgentName());
            }
        }
        return names;
    }

    /**
     * 批量查询模型名称（供汇总分组展示）。
     *
     * @param list 用量实体
     * @return 模型主键 → 配置名称
     */
    private Map<Long, String> loadModelNames(List<AiUsageStat> list) {
        Map<Long, String> names = new HashMap<>();
        loadModelMeta(list).forEach((modelId, meta) -> names.put(modelId, meta.name()));
        return names;
    }

    /**
     * 批量查询模型元数据（配置名称 + 是否已配置单价）。
     *
     * @param list 用量实体
     * @return 模型主键 → 元数据
     */
    private Map<Long, ModelMeta> loadModelMeta(List<AiUsageStat> list) {
        Set<Long> modelIds = new HashSet<>();
        list.forEach(row -> {
            if (row.getModelId() != null) {
                modelIds.add(row.getModelId());
            }
        });
        Map<Long, ModelMeta> meta = new HashMap<>();
        if (modelIds.isEmpty()) {
            return meta;
        }
        for (AiModelConfig model : modelConfigMapper.selectByIds(modelIds)) {
            boolean priced = model.getInputPrice() != null || model.getOutputPrice() != null;
            meta.put(model.getId(), new ModelMeta(model.getConfigName(), priced));
        }
        return meta;
    }

    /**
     * 模型元数据。
     *
     * @param name   配置名称
     * @param priced 是否已配置单价
     */
    private record ModelMeta(String name, boolean priced) {
    }

}