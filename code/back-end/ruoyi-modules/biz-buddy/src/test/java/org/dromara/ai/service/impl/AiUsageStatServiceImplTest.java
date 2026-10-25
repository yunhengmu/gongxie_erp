package org.dromara.ai.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import io.github.linpeilie.Converter;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.context.support.StaticApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link AiUsageStatServiceImpl} 单元测试：覆盖累加写入、费用估算、Redis 计数回退与汇总口径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 用量统计 Service 单元测试")
class AiUsageStatServiceImplTest {

    @Mock
    private AiUsageStatMapper usageStatMapper;

    @Mock
    private IAiAgentService agentService;

    @Mock
    private AiModelConfigMapper modelConfigMapper;

    @Mock
    private UserService userService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private DataSource dataSource;

    @InjectMocks
    private AiUsageStatServiceImpl usageStatService;

    /**
     * 注入最小内存 Spring 容器，供 RedisUtils / IdGeneratorUtil 等工具类静态初始化使用，不连接真实外部依赖。
     */
    @BeforeAll
    static void initStaticContext() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("converter", mock(Converter.class));
        context.getBeanFactory().registerSingleton("redissonClient", mock(RedissonClient.class));
        context.getBeanFactory().registerSingleton("identifierGenerator", mock(IdentifierGenerator.class));
        context.getBeanFactory().registerSingleton("jsonMapper", JsonMapper.builder().build());
        context.refresh();
        new SpringUtil().setApplicationContext(context);
    }

    /**
     * 验证正常累加：写入统计实体、估算费用并累加 Redis 计数。
     */
    @Test
    @DisplayName("累加用量并写入统计与计数")
    void shouldAccumulateUsageAndCounters() {
        AiModelConfig model = model(9L, "gpt");
        model.setInputPrice(new BigDecimal("2"));
        model.setOutputPrice(new BigDecimal("3"));
        when(modelConfigMapper.selectById(9L)).thenReturn(model);
        when(usageStatMapper.upsert(any(AiUsageStat.class))).thenReturn(1);
        RAtomicLong atomicLong = mock(RAtomicLong.class);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        try (MockedStatic<IdGeneratorUtil> id = mockStatic(IdGeneratorUtil.class);
             MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            id.when(IdGeneratorUtil::nextLongId).thenReturn(123L);

            usageStatService.accumulate(7L, 5L, 9L, new ChatTokenUsage(100, 200, null), 2, 1);

            ArgumentCaptor<AiUsageStat> captor = ArgumentCaptor.forClass(AiUsageStat.class);
            verify(usageStatMapper).upsert(captor.capture());
            AiUsageStat stat = captor.getValue();
            assertEquals(123L, stat.getId());
            assertEquals(7L, stat.getUserId());
            assertEquals(5L, stat.getAgentId());
            assertEquals(9L, stat.getModelId());
            assertEquals(300L, stat.getTotalTokens());
            assertEquals(2, stat.getToolCallCount());
            assertEquals(1, stat.getRequestCount());
            // 对话用量与知识库入库用量共用一张表，类型必须区分
            assertEquals(AiUsageStat.USAGE_TYPE_CHAT, stat.getUsageType());
            // (100/1000)*2 + (200/1000)*3 = 0.2 + 0.6 = 0.8
            assertEquals(0, new BigDecimal("0.800000").compareTo(stat.getCost()));
            // 当日 + 当月 Token 各累加 300；当日请求累加 1
            verify(atomicLong, times(2)).addAndGet(300L);
            verify(atomicLong).addAndGet(1L);
        }
    }

    /**
     * 验证 MySQL 数据源改用 {@code on duplicate key update} 版 upsert。
     *
     * <p>PostgreSQL 版 SQL 含 {@code on conflict … excluded.}，在 MySQL 8.0 上是**语法错误**，
     * 曾导致 MySQL 栈用量一直不落库。</p>
     */
    @Test
    @DisplayName("MySQL 数据源走 MySQL 版 upsert")
    void shouldUseMysqlUpsertOnMysqlDataSource() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(usageStatMapper.upsertMysql(any(AiUsageStat.class))).thenReturn(1);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(mock(RAtomicLong.class));

        try (MockedStatic<IdGeneratorUtil> id = mockStatic(IdGeneratorUtil.class);
             MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            id.when(IdGeneratorUtil::nextLongId).thenReturn(9L);

            usageStatService.accumulate(1L, 2L, 3L, new ChatTokenUsage(10, 20, null), 0, 1);

            verify(usageStatMapper).upsertMysql(any(AiUsageStat.class));
            verify(usageStatMapper, never()).upsert(any(AiUsageStat.class));
        }
    }

    /**
     * 验证知识库入库用量：记入哨兵智能体、类型为入库，只累计 Token 与费用，不占用对话配额。
     */
    @Test
    @DisplayName("入库用量只记成本不占对话配额")
    void shouldAccumulateEmbeddingWithoutQuota() {
        AiModelConfig model = model(9L, "embed");
        model.setInputPrice(new BigDecimal("2"));
        when(modelConfigMapper.selectById(9L)).thenReturn(model);
        when(usageStatMapper.upsert(any(AiUsageStat.class))).thenReturn(1);

        try (MockedStatic<IdGeneratorUtil> id = mockStatic(IdGeneratorUtil.class)) {
            id.when(IdGeneratorUtil::nextLongId).thenReturn(321L);

            usageStatService.accumulateEmbedding(7L, 9L, 1500L);

            ArgumentCaptor<AiUsageStat> captor = ArgumentCaptor.forClass(AiUsageStat.class);
            verify(usageStatMapper).upsert(captor.capture());
            AiUsageStat stat = captor.getValue();
            assertEquals(321L, stat.getId());
            assertEquals(7L, stat.getUserId());
            // 入库无智能体归属，固定记在哨兵 0 名下
            assertEquals(AiUsageStat.NO_AGENT_ID, stat.getAgentId());
            assertEquals(AiUsageStat.USAGE_TYPE_EMBEDDING, stat.getUsageType());
            // 向量化只有输入侧消耗
            assertEquals(1500L, stat.getPromptTokens());
            assertEquals(0L, stat.getCompletionTokens());
            assertEquals(1500L, stat.getTotalTokens());
            assertEquals(0, stat.getRequestCount());
            assertEquals(0, stat.getToolCallCount());
            // (1500/1000)*2 = 3
            assertEquals(0, new BigDecimal("3.000000").compareTo(stat.getCost()));
            // 入库不累加 Redis 日 / 月配额计数
            verifyNoInteractions(redissonClient);
        }
    }

    /**
     * 验证缺少用户 / 智能体 / Token 时直接跳过，不产生任何写入。
     */
    @Test
    @DisplayName("关键入参缺失时跳过累加")
    void shouldSkipAccumulateWhenKeyArgsMissing() {
        usageStatService.accumulate(null, 5L, 9L, new ChatTokenUsage(1, 2, 3), 0, 1);
        usageStatService.accumulate(7L, null, 9L, new ChatTokenUsage(1, 2, 3), 0, 1);
        usageStatService.accumulate(7L, 5L, 9L, null, 0, 1);
        // 入库用量：无归属用户或 Token 非正数同样跳过
        usageStatService.accumulateEmbedding(null, 9L, 10L);
        usageStatService.accumulateEmbedding(7L, 9L, 0L);

        verifyNoInteractions(usageStatMapper, redissonClient);
    }

    /**
     * 验证写入统计失败时不抛出，且不累加 Redis 计数，避免影响对话主流程。
     */
    @Test
    @DisplayName("统计写入失败时静默处理")
    void shouldSwallowUpsertFailure() {
        when(usageStatMapper.upsert(any(AiUsageStat.class))).thenThrow(new RuntimeException("db down"));

        try (MockedStatic<IdGeneratorUtil> id = mockStatic(IdGeneratorUtil.class)) {
            id.when(IdGeneratorUtil::nextLongId).thenReturn(1L);

            assertDoesNotThrow(() -> usageStatService.accumulate(1L, 2L, 3L,
                new ChatTokenUsage(1, 2, 3), 0, 1));
            verifyNoInteractions(redissonClient);
        }
    }

    /**
     * 验证未指定模型时费用按 0 计，且 total 缺省时按 prompt + completion 计算。
     */
    @Test
    @DisplayName("未指定模型时费用为零")
    void shouldUseZeroCostWhenModelMissing() {
        when(usageStatMapper.upsert(any(AiUsageStat.class))).thenReturn(1);
        RAtomicLong atomicLong = mock(RAtomicLong.class);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        try (MockedStatic<IdGeneratorUtil> id = mockStatic(IdGeneratorUtil.class);
             MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            id.when(IdGeneratorUtil::nextLongId).thenReturn(1L);

            usageStatService.accumulate(1L, 2L, null, new ChatTokenUsage(10, 20, 40), 0, 0);

            ArgumentCaptor<AiUsageStat> captor = ArgumentCaptor.forClass(AiUsageStat.class);
            verify(usageStatMapper).upsert(captor.capture());
            assertEquals(40L, captor.getValue().getTotalTokens());
            assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().getCost()));
        }
    }

    /**
     * 验证当日 Token 命中 Redis 计数时直接返回缓存值。
     */
    @Test
    @DisplayName("当日 Token 命中缓存")
    void shouldReadCachedTodayTokens() {
        RAtomicLong atomicLong = mock(RAtomicLong.class);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
        when(atomicLong.get()).thenReturn(500L);

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.hasKey(anyString())).thenReturn(true);
            assertEquals(500L, usageStatService.todayTokens(7L));
        }
        verify(usageStatMapper, never()).selectList(any());
    }

    /**
     * 验证当日 Token 未命中缓存时回退数据库汇总，并兼容 null。
     */
    @Test
    @DisplayName("当日 Token 回退数据库汇总")
    void shouldFallbackTodayTokensToDatabase() {
        AiUsageStat row1 = stat(7L, 100L, null);
        AiUsageStat row2 = stat(7L, 50L, 3);
        when(usageStatMapper.selectList(any())).thenReturn(List.of(row1, row2));

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.hasKey(anyString())).thenReturn(false);
            assertEquals(150L, usageStatService.todayTokens(7L));
        }
    }

    /**
     * 验证当日请求数未命中缓存时回退数据库求和，并兼容 null。
     */
    @Test
    @DisplayName("当日请求数回退数据库求和")
    void shouldFallbackTodayRequestsToDatabase() {
        AiUsageStat row1 = stat(7L, 0L, 2);
        AiUsageStat row2 = stat(7L, 0L, null);
        when(usageStatMapper.selectList(any())).thenReturn(List.of(row1, row2));

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.hasKey(anyString())).thenReturn(false);
            assertEquals(2L, usageStatService.todayRequests(7L));
        }
    }

    /**
     * 验证当月 Token 未命中缓存时回退数据库汇总。
     */
    @Test
    @DisplayName("当月 Token 回退数据库汇总")
    void shouldFallbackMonthTokensToDatabase() {
        when(usageStatMapper.selectList(any())).thenReturn(List.of(stat(7L, 80L, 1)));

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.hasKey(anyString())).thenReturn(false);
            assertEquals(80L, usageStatService.monthTokens(7L));
        }
    }

    /**
     * 验证分页查询补齐智能体 / 模型 / 用户名称与单价标记。
     */
    @Test
    @DisplayName("分页查询补齐展示字段")
    void shouldFillDisplayFieldsOnPage() {
        AiUsageStat row = stat(7L, 100L, 1);
        row.setAgentId(5L);
        row.setModelId(9L);
        row.setStatDate(LocalDate.of(2026, 1, 2));
        Page<AiUsageStat> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(row));
        when(usageStatMapper.selectPage(any(), any())).thenReturn(page);
        AiAgent agent = new AiAgent() {
        };
        agent.setId(5L);
        agent.setAgentName("小Z");
        when(agentService.queryEntities(any())).thenReturn(List.of(agent));
        AiModelConfig model = model(9L, "gpt");
        model.setConfigName("GPT");
        model.setInputPrice(BigDecimal.ONE);
        when(modelConfigMapper.selectByIds(any())).thenReturn(List.of(model));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(7L, "张三"));

        PageResult<AiUsageStatVo> result = usageStatService.queryPage(new AiUsageStatBo(), new PageQuery());

        assertEquals(1, result.getRows().size());
        AiUsageStatVo vo = (AiUsageStatVo) result.getRows().iterator().next();
        assertEquals("小Z", vo.getAgentName());
        assertEquals("GPT", vo.getModelName());
        assertEquals("张三", vo.getUserName());
        assertTrue(vo.getPriceConfigured());
        assertEquals(1L, result.getTotal());
    }

    /**
     * 验证空列表分页返回空行。
     */
    @Test
    @DisplayName("空分页返回空行")
    void shouldReturnEmptyRowsForEmptyPage() {
        Page<AiUsageStat> page = new Page<>(1, 10, 0);
        page.setRecords(List.of());
        when(usageStatMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<AiUsageStatVo> result = usageStatService.queryPage(new AiUsageStatBo(), new PageQuery());
        assertTrue(result.getRows().isEmpty());
    }

    /**
     * 验证总览按合计 / 日期 / 智能体 / 模型四个维度聚合，并处理缺失的智能体与模型。
     */
    @Test
    @DisplayName("总览按维度聚合")
    void shouldBuildOverviewByDimensions() {
        AiUsageStat row1 = stat(7L, 100L, 1);
        row1.setAgentId(5L);
        row1.setModelId(9L);
        row1.setStatDate(LocalDate.of(2026, 1, 2));
        row1.setPromptTokens(60L);
        row1.setCompletionTokens(40L);
        AiUsageStat row2 = stat(8L, 20L, 1);
        row2.setAgentId(6L);
        row2.setModelId(null);
        row2.setStatDate(null);
        row2.setPromptTokens(10L);
        row2.setCompletionTokens(10L);
        when(usageStatMapper.selectList(any())).thenReturn(List.of(row1, row2));
        AiAgent agent = new AiAgent() {
        };
        agent.setId(5L);
        agent.setAgentName("小Z");
        when(agentService.queryEntities(any())).thenReturn(List.of(agent));
        AiModelConfig model = model(9L, "gpt");
        model.setConfigName("GPT");
        when(modelConfigMapper.selectByIds(any())).thenReturn(List.of(model));

        AiUsageOverviewVo overview = usageStatService.overview(new AiUsageStatBo());

        assertEquals(120L, overview.getTotal().getTotalTokens());
        assertEquals("合计", overview.getTotal().getLabel());
        // 日期：2026-01-02 与 "-" 各一组
        assertEquals(2, overview.getByDate().size());
        // 智能体：小Z 与「已删除智能体」各一组
        assertEquals(2, overview.getByAgent().size());
        AiUsageSummaryVo deleted = overview.getByAgent().stream()
            .filter(s -> "已删除智能体".equals(s.getLabel())).findFirst().orElseThrow();
        assertEquals(20L, deleted.getTotalTokens());
        // 模型：GPT 与「未指定模型」各一组
        assertEquals(2, overview.getByModel().size());
    }

    /**
     * 验证导出数据按行字段映射。
     */
    @Test
    @DisplayName("构建导出数据")
    void shouldBuildExportData() {
        AiUsageStat row = stat(7L, 100L, 1);
        row.setAgentId(5L);
        row.setModelId(9L);
        row.setStatDate(LocalDate.of(2026, 1, 2));
        when(usageStatMapper.selectList(any())).thenReturn(List.of(row));
        AiModelConfig model = model(9L, "gpt");
        model.setConfigName("GPT");
        when(modelConfigMapper.selectByIds(any())).thenReturn(List.of(model));
        when(agentService.queryEntities(any())).thenReturn(List.of());
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of());

        List<AiUsageStatExportVo> export = usageStatService.buildExportData(new AiUsageStatBo());

        assertEquals(1, export.size());
        assertEquals("2026-01-02", export.get(0).getStatDate());
        assertEquals("GPT", export.get(0).getModelName());
        assertEquals(100L, export.get(0).getTotalTokens());
    }

    private AiModelConfig model(Long id, String code) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setModelCode(code);
        return model;
    }

    private AiUsageStat stat(Long userId, Long totalTokens, Integer requestCount) {
        AiUsageStat stat = new AiUsageStat();
        stat.setUserId(userId);
        stat.setTotalTokens(totalTokens);
        stat.setRequestCount(requestCount);
        stat.setToolCallCount(0);
        stat.setCost(BigDecimal.ZERO);
        return stat;
    }

}