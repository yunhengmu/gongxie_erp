package org.dromara.ai.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.config.AiSecretManager;
import org.dromara.ai.domain.AiKnowledgeBase;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiModelConfigBo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.domain.vo.AiModelTestVo;
import org.dromara.ai.mapper.AiKnowledgeBaseMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 模型配置 Service 业务层处理
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiModelConfigServiceImpl implements IAiModelConfigService {

    /**
     * 模型类型：向量
     */
    private static final String TYPE_EMBEDDING = "embedding";

    /**
     * 副本名称后缀：重名时继续追加（原名copy → 原名copycopy）
     */
    private static final String COPY_SUFFIX = "copy";

    /**
     * 副本名称生成的最大尝试次数（防止病态重名场景下的长查询）
     */
    private static final int MAX_SUFFIX_ATTEMPTS = 50;

    /**
     * 配置名称列宽（{@code varchar(100)}）：候选名超限必须显式报错，不静默截断原名
     */
    private static final int NAME_MAX_LENGTH = 100;

    /**
     * 名称冲突时的最大插入尝试次数（由唯一索引 `uk_ai_model_config_name` 兜住并发）
     */
    private static final int MAX_INSERT_ATTEMPTS = 3;

    /**
     * 密钥已配置时的掩码
     */
    private static final String KEY_MASK = "******";

    private final AiModelConfigMapper baseMapper;

    private final AiKnowledgeBaseMapper knowledgeBaseMapper;

    private final AiSecretManager secretManager;

    private final AiProperties aiProperties;

    @Override
    public AiModelConfigVo queryById(Long id) {
        AiModelConfigVo vo = baseMapper.selectVoById(id);
        if (vo != null) {
            vo.setApiKeyMask(maskOf(vo.getApiKey()));
        }
        return vo;
    }

    @Override
    public PageResult<AiModelConfigVo> queryPageList(AiModelConfigBo bo, PageQuery pageQuery) {
        Page<AiModelConfigVo> result = baseMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        result.getRecords().forEach(vo -> vo.setApiKeyMask(maskOf(vo.getApiKey())));
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public List<AiModelConfigVo> queryOptions(String modelType) {
        LambdaQueryWrapper<AiModelConfig> lqw = Wrappers.lambdaQuery();
        lqw.eq(StringUtils.isNotBlank(modelType), AiModelConfig::getModelType, modelType);
        lqw.eq(AiModelConfig::getStatus, "0");
        lqw.orderByDesc(AiModelConfig::getUpdateTime);
        List<AiModelConfigVo> list = baseMapper.selectVoList(lqw);
        list.forEach(vo -> vo.setApiKeyMask(maskOf(vo.getApiKey())));
        return list;
    }

    @Override
    public Boolean insertByBo(AiModelConfigBo bo) {
        AiModelConfig add = MapstructUtils.convert(bo, AiModelConfig.class);
        validEntityBeforeSave(add);
        add.setApiKey(secretManager.encrypt(bo.getApiKey()));
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    /**
     * 复制一份模型配置：整行复制既有业务列，名称按「原名 + copy」自动唯一化。
     *
     * <p><b>不复用 {@link #insertByBo}</b>：它会对入参密钥再加密一次——传入密文会把密文当明文
     * 加密，副本密钥直接作废。故这里自行保证同口径校验（复用 {@link #validEntityBeforeSave}：
     * 向量模型必须填维度 + 重名拦截）。</p>
     *
     * <p><b>本方法刻意不加 {@code @Transactional}</b>：名称冲突需要「插入失败 → 重新查库 → 再插」
     * 的重试；而 PostgreSQL 在事务内一条语句失败后整个事务进入 abort 态，后续语句会报
     * {@code current transaction is aborted}，重试必然失败。单条 insert 自身原子，无需事务——
     * 请勿为「与其它写方法保持一致」补上 {@code @Transactional}，否则重试机制会静默失效。</p>
     *
     * @param id 源配置主键
     * @return 副本视图对象（含自动生成的名称与新主键）
     */
    @Override
    public AiModelConfigVo copyById(Long id) {
        AiModelConfig source = baseMapper.selectById(id);
        if (source == null) {
            // 已逻辑删除的行同样查不到（@TableLogic 自动附加 del_flag='0'）
            throw new ServiceException("源配置不存在或已被删除");
        }
        AiModelConfig copy = copyOf(source);
        for (int attempt = 1; attempt <= MAX_INSERT_ATTEMPTS; attempt++) {
            copy.setConfigName(nextCopyName(source.getConfigName()));
            // 与新增同口径校验：向量模型必须填维度 + 重名拦截（后者兜住 selectCount 与 insert 之间的竞态）
            validEntityBeforeSave(copy);
            try {
                baseMapper.insert(copy);
                return queryById(copy.getId());
            } catch (DuplicateKeyException e) {
                // 唯一索引 uk_ai_model_config_name 命中：下一轮重新算名，自然跳过刚落库的冲突名
                log.warn("模型配置复制名称冲突，重试第 {} 次：源 id={}", attempt, source.getId());
            }
        }
        throw new ServiceException("副本名称冲突，请稍后重试");
    }

    /**
     * 构造副本实体：只搬业务列，主键 / 名称 / 审计列 / 逻辑删除标志一律留空。
     *
     * <p><b>审计列必须留空</b>：{@code InjectionMetaObjectHandler.insertFill} 在实体自带
     * {@code createTime} 时会沿用旧值，且 {@code createBy} 非空会让整段填充被跳过（{@code updateBy}
     * 落空）。若直接插入源行实体，副本会记到原作者名下、创建时间变成源行的时间——
     * 故这里只做白名单拷贝，审计列交由自动填充（与新增路径逐列同形）。</p>
     *
     * <p>实体新增配置列时需同步本方法。</p>
     *
     * @param source 源配置
     * @return 可直接插入的副本实体
     */
    private AiModelConfig copyOf(AiModelConfig source) {
        AiModelConfig copy = new AiModelConfig();
        copy.setProvider(source.getProvider());
        copy.setModelType(source.getModelType());
        copy.setModelCode(source.getModelCode());
        copy.setBaseUrl(source.getBaseUrl());
        // 密钥密文原样搬运：不解密、不重加密（明文不进内存与日志，副本与源共享同一密钥）
        copy.setApiKey(source.getApiKey());
        copy.setDimension(source.getDimension());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setMaxRetries(source.getMaxRetries());
        copy.setInputPrice(source.getInputPrice());
        copy.setOutputPrice(source.getOutputPrice());
        copy.setStatus(source.getStatus());
        copy.setRemark(source.getRemark());
        return copy;
    }

    /**
     * 生成不重复的副本名称：「原名 + copy」，重名则继续追加 copy（原名copycopy…）。
     *
     * @param sourceName 源配置名称
     * @return 当前有效行中不存在的副本名称
     */
    private String nextCopyName(String sourceName) {
        String candidate = sourceName + COPY_SUFFIX;
        for (int attempts = 0; attempts <= MAX_SUFFIX_ATTEMPTS; attempts++) {
            if (candidate.length() > NAME_MAX_LENGTH) {
                throw new ServiceException("配置名称已达 " + NAME_MAX_LENGTH
                    + " 字符上限，无法追加副本后缀，请先缩短原名称");
            }
            if (!nameExists(candidate)) {
                return candidate;
            }
            candidate = candidate + COPY_SUFFIX;
        }
        throw new ServiceException("副本名称生成失败：连续重名次数过多，请先清理同名配置");
    }

    /**
     * 名称是否已存在（与 {@link #validEntityBeforeSave} 同口径：逻辑删除的行不参与判重）。
     *
     * @param configName 待判定的配置名称
     * @return 是否已存在
     */
    private boolean nameExists(String configName) {
        LambdaQueryWrapper<AiModelConfig> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiModelConfig::getConfigName, configName);
        return baseMapper.selectCount(lqw) > 0;
    }

    @Override
    public Boolean updateByBo(AiModelConfigBo bo) {
        AiModelConfig update = MapstructUtils.convert(bo, AiModelConfig.class);
        // 密钥留空表示不修改：置 null 后由 MyBatis-Plus 忽略该字段
        update.setApiKey(secretManager.encrypt(bo.getApiKey()));
        validEntityBeforeSave(update);
        return baseMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (isValid) {
            List<AiModelConfig> list = baseMapper.selectByIds(ids);
            if (list.size() != ids.size()) {
                throw new ServiceException("您没有删除权限!");
            }
            for (Long id : ids) {
                LambdaQueryWrapper<AiKnowledgeBase> ref = Wrappers.lambdaQuery();
                ref.eq(AiKnowledgeBase::getEmbeddingModelId, id);
                if (knowledgeBaseMapper.selectCount(ref) > 0) {
                    throw new ServiceException("该模型已被知识库引用为向量模型，请先调整知识库配置后再删除");
                }
            }
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public AiModelTestVo testConnection(AiModelConfigBo bo) {
        AiModelTestVo result = new AiModelTestVo();
        long start = System.currentTimeMillis();
        try {
            String apiKey = bo.getApiKey();
            if (StringUtils.isBlank(apiKey) && bo.getId() != null) {
                apiKey = decryptApiKey(bo.getId());
            }
            validateEndpoint(bo.getBaseUrl());
            boolean embedding = isEmbedding(bo.getModelType());
            String url = resolveEndpoint(bo.getBaseUrl(), embedding);
            String body = buildTestBody(bo.getModelCode(), embedding, bo.getDimension(), isDashScopeHost(bo.getBaseUrl()));
            int timeout = bo.getTimeoutSeconds() != null ? bo.getTimeoutSeconds() : aiProperties.getTestTimeoutSeconds();

            HttpRequest request = HttpUtil.createPost(url)
                .header("Content-Type", "application/json")
                .timeout(timeout * 1000)
                .body(body);
            if (StringUtils.isNotBlank(apiKey)) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            int status;
            String responseBody;
            try (HttpResponse response = request.execute()) {
                status = response.getStatus();
                responseBody = response.body();
            }

            if (status < 200 || status >= 300) {
                result.setSuccess(false);
                result.setMessage(buildHttpErrorMessage(status, responseBody));
            } else if (embedding) {
                fillEmbeddingResult(result, bo, responseBody);
            } else {
                fillChatResult(result, responseBody);
            }
        } catch (ServiceException e) {
            result.setSuccess(false);
            result.setMessage(e.getMessage());
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("连通性测试异常：" + e.getMessage());
        }
        result.setElapsedMs(System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public String decryptApiKey(Long id) {
        AiModelConfig config = baseMapper.selectById(id);
        return config == null ? null : secretManager.decrypt(config.getApiKey());
    }

    /**
     * 校验向量模型返回的向量维度。
     *
     * @param result       测试结果
     * @param bo           业务对象
     * @param responseBody 响应体
     */
    private void fillEmbeddingResult(AiModelTestVo result, AiModelConfigBo bo, String responseBody) {
        JsonNode root = JsonUtils.getJsonMapper().readTree(responseBody);
        // 兼容两种返回结构：OpenAI 兼容 data[0].embedding / DashScope 原生 output.embeddings[0].embedding
        JsonNode vector = root.path("data").path(0).path("embedding");
        if (!isVectorNode(vector)) {
            vector = root.path("output").path("embeddings").path(0).path("embedding");
        }
        if (!isVectorNode(vector)) {
            result.setSuccess(false);
            result.setMessage("响应中未找到向量数据：" + abbreviate(responseBody));
            return;
        }
        int actual = vector.size();
        String message = "连通性正常，返回向量维度 " + actual;
        if (bo.getDimension() != null && bo.getDimension() != actual) {
            message += "；与配置的向量维度 " + bo.getDimension() + " 不一致，请修正后再保存";
        }
        result.setSuccess(true);
        result.setMessage(message);
    }

    /**
     * 判断节点是否为非空向量数组。
     *
     * @param node JSON 节点
     * @return 是否为向量数组
     */
    private boolean isVectorNode(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() && node.isArray() && node.size() > 0;
    }

    /**
     * 校验对话模型返回结果。
     *
     * @param result       测试结果
     * @param responseBody 响应体
     */
    private void fillChatResult(AiModelTestVo result, String responseBody) {
        JsonNode choices = JsonUtils.getJsonMapper().readTree(responseBody).path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            result.setSuccess(false);
            result.setMessage("响应中未找到对话结果：" + abbreviate(responseBody));
            return;
        }
        String content = choices.get(0).path("message").path("content").asText("");
        result.setSuccess(true);
        result.setMessage("连通性正常，模型回复：" + abbreviate(content));
    }

    /**
     * 构建连通性测试的请求体。
     *
     * @param modelCode  模型标识
     * @param embedding  是否向量模型
     * @param dimension  配置的向量维度（仅 DashScope 会随请求下发 dimensions，使返回维度可预期）
     * @param dashscope  是否 DashScope 域名
     * @return 请求体 JSON
     */
    private String buildTestBody(String modelCode, boolean embedding, Integer dimension, boolean dashscope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelCode);
        if (embedding) {
            body.put("input", "连通性测试");
            if (dashscope && dimension != null) {
                body.put("dimensions", dimension);
                body.put("encoding_format", "float");
            }
        } else {
            body.put("messages", List.of(Map.of("role", "user", "content", "连通性测试，请只回复 ok")));
            body.put("max_tokens", 16);
        }
        return JsonUtils.toJsonString(body);
    }

    /**
     * 端点合法性预检：把常见的地址写法问题转成可执行的修正建议。
     *
     * @param baseUrl 服务地址
     */
    private void validateEndpoint(String baseUrl) {
        String base = StringUtils.trim(baseUrl);
        if (StringUtils.isBlank(base)) {
            throw new ServiceException("服务地址不能为空");
        }
        if (base.contains("{") || base.contains("}")) {
            throw new ServiceException("服务地址仍包含占位符（如 {WorkspaceId}），请替换为真实业务空间 ID 后再测试");
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new ServiceException("服务地址需以 http:// 或 https:// 开头");
        }
        if (isDashScopeHost(base) && base.contains("/api/v1") && !base.contains("/compatible-mode")) {
            throw new ServiceException("该地址是 DashScope 原生接口，本平台统一使用 OpenAI 兼容协议；"
                + "请改用 https://dashscope.aliyuncs.com/compatible-mode/v1"
                + "，或 https://<业务空间ID>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        }
    }

    /**
     * 是否 DashScope（含新版百炼 maas 域名）地址。
     *
     * @param baseUrl 服务地址
     * @return 是否 DashScope
     */
    private boolean isDashScopeHost(String baseUrl) {
        String base = StringUtils.trim(baseUrl).toLowerCase(Locale.ROOT);
        return base.contains("dashscope.aliyuncs.com") || base.contains(".maas.aliyuncs.com");
    }

    /**
     * 构建非 2xx 响应的可读错误信息。
     *
     * @param status       HTTP 状态码
     * @param responseBody 响应体
     * @return 错误说明
     */
    private String buildHttpErrorMessage(int status, String responseBody) {
        String message = "HTTP " + status + "：" + extractError(responseBody);
        if (status == 404) {
            message += "；请确认服务地址是 OpenAI 兼容**根地址**（如 DashScope 用 .../compatible-mode/v1），无需自己拼 /embeddings 或 /chat/completions";
        } else if (status == 400 && responseBody != null && responseBody.contains("Workspace endpoint is invalid")) {
            message += "；该地址需要真实业务空间 ID，请把 {WorkspaceId} 替换为百炼控制台里的业务空间 ID";
        }
        return message;
    }

    /**
     * 拼接 OpenAI 兼容端点地址。
     *
     * @param baseUrl   服务地址
     * @param embedding 是否向量模型
     * @return 完整端点
     */
    private String resolveEndpoint(String baseUrl, boolean embedding) {
        String base = StringUtils.trim(baseUrl);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = embedding ? "/embeddings" : "/chat/completions";
        return base.endsWith(suffix) ? base : base + suffix;
    }

    /**
     * 从错误响应中提取可读信息。
     *
     * @param responseBody 响应体
     * @return 错误说明
     */
    private String extractError(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return "服务端未返回内容";
        }
        try {
            JsonNode node = JsonUtils.getJsonMapper().readTree(responseBody);
            String message = node.path("error").path("message").asText("");
            if (StringUtils.isBlank(message)) {
                message = node.path("message").asText("");
            }
            if (StringUtils.isNotBlank(message)) {
                return abbreviate(message);
            }
        } catch (Exception ignored) {
            // 非 JSON 响应，直接截断原文
        }
        return abbreviate(responseBody);
    }

    /**
     * 构建模型配置动态查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiModelConfig> buildQueryWrapper(AiModelConfigBo bo) {
        LambdaQueryWrapper<AiModelConfig> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getConfigName()), AiModelConfig::getConfigName, bo.getConfigName());
        lqw.eq(StringUtils.isNotBlank(bo.getProvider()), AiModelConfig::getProvider, bo.getProvider());
        lqw.eq(StringUtils.isNotBlank(bo.getModelType()), AiModelConfig::getModelType, bo.getModelType());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiModelConfig::getStatus, bo.getStatus());
        lqw.orderByDesc(AiModelConfig::getCreateTime);
        return lqw;
    }

    /**
     * 保存前的数据校验。
     *
     * @param entity 实体数据
     */
    private void validEntityBeforeSave(AiModelConfig entity) {
        if (isEmbedding(entity.getModelType()) && entity.getDimension() == null) {
            throw new ServiceException("向量模型必须填写向量维度");
        }
        LambdaQueryWrapper<AiModelConfig> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiModelConfig::getConfigName, entity.getConfigName());
        lqw.ne(entity.getId() != null, AiModelConfig::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("配置名称已存在：" + entity.getConfigName());
        }
    }

    /**
     * 是否向量模型。
     *
     * @param modelType 模型类型
     * @return 是否向量模型
     */
    private boolean isEmbedding(String modelType) {
        return TYPE_EMBEDDING.equalsIgnoreCase(StringUtils.trim(modelType));
    }

    /**
     * 计算密钥掩码，避免密文或明文下发前端。
     *
     * @param cipherText 密钥密文
     * @return 掩码或空串
     */
    private String maskOf(String cipherText) {
        return StringUtils.isNotBlank(cipherText) ? KEY_MASK : "";
    }

    /**
     * 截断过长文本，避免测试结果刷屏。
     *
     * @param text 原文
     * @return 截断后的文本
     */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed;
    }

}