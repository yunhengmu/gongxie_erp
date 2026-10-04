package org.dromara.ai.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.linpeilie.Converter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.utils.SpringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;

/**
 * AI 模块单元测试的公共支撑：静态依赖初始化。
 *
 * <p>纯单元测试不起 Spring 容器，但以下两类静态能力会在类初始化时要求容器 / MyBatis 上下文，
 * 否则首次触达即抛异常：</p>
 * <ul>
 *   <li>{@code MapstructUtils} / {@code JsonUtils}：类初始化时从 Spring 取 {@code Converter} / {@code JsonMapper} Bean；</li>
 *   <li>{@code LambdaQueryWrapper}：需要目标实体的 MyBatis-Plus {@code TableInfo}（lambda 缓存），
 *       否则抛 "can not find lambda cache for this entity"。</li>
 * </ul>
 *
 * <p>用法：在测试类 {@code @BeforeAll} 中调用一次 {@link #initialize()}（幂等）。</p>
 */
public final class AiTestSupport {

    /**
     * 需要注册 TableInfo 的 AI 实体（org.dromara.ai.domain 下的表实体）。
     */
    private static final String[] ENTITY_NAMES = {
        "AiAgent", "AiAgentKb", "AiAgentSkill", "AiAgentSubagent", "AiAgentTask", "AiAgentTaskRun",
        "AiAgentTool", "AiAgentToolTrust", "AiChannel", "AiChannelIdentity", "AiChannelThread",
        "AiChunk", "AiDocument", "AiEntryAgent", "AiExpertAgent", "AiKnowledgeBase", "AiMcpClient",
        "AiMessage", "AiModelConfig", "AiPermissionDecisionLog", "AiRouteExpertCall", "AiRouteRun",
        "AiScenarioPack", "AiScenarioPackMcpTool", "AiScenarioPackSkill", "AiScenarioPackTool",
        "AiSession", "AiSessionResource", "AiSkill", "AiSkillResource", "AiSubagent", "AiTool",
        "AiToolCallLog", "AiToolTag", "AiToolTagRel", "AiTrace", "AiUsageStat"
    };

    private static final GenericApplicationContext CONTEXT = createContext();

    static {
        registerTableInfo();
    }

    private AiTestSupport() {
    }

    /**
     * 触发最小容器与 TableInfo 初始化（幂等）。
     */
    public static void initialize() {
        CONTEXT.isActive();
    }

    /**
     * 为全部 AI 实体注册 MyBatis-Plus TableInfo（幂等）。
     */
    public static void registerTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("org.dromara.ai.test");
        for (String name : ENTITY_NAMES) {
            try {
                TableInfoHelper.initTableInfo(assistant, Class.forName("org.dromara.ai.domain." + name));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("AI 实体类不存在：" + name, e);
            }
        }
    }

    /**
     * 按属性名浅拷贝构造目标对象，模拟 Mapstruct Plus 的转换语义。
     *
     * @param source     源对象
     * @param targetType 目标类型
     * @param <T>        目标泛型
     * @return 目标实例
     */
    public static <T> T copy(Object source, Class<T> targetType) {
        try {
            T target = targetType.getDeclaredConstructor().newInstance();
            BeanUtils.copyProperties(source, target);
            return target;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造目标对象：" + targetType, e);
        }
    }

    private static GenericApplicationContext createContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("converter", mock(Converter.class));
        context.getBeanFactory().registerSingleton("jsonMapper", JsonMapper.builder().build());
        // RedisUtils 的静态字段在类初始化时即从容器取 RedissonClient，缺它会导致 RedisUtils 无法初始化、
        // 进而 mockStatic(RedisUtils.class) 抛错并污染 Mockito 的 matcher 栈（后续用例报 InvalidUseOfMatchers）
        context.getBeanFactory().registerSingleton("redissonClient", mock(RedissonClient.class));
        context.refresh();
        new SpringUtils().setApplicationContext(context);
        return context;
    }

}