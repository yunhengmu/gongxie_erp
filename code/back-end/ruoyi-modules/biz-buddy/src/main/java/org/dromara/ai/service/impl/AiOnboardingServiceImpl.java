package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiDocument;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.dromara.ai.domain.vo.AiHealthReportVo;
import org.dromara.ai.domain.vo.AiOnboardingItemVo;
import org.dromara.ai.domain.vo.AiOnboardingReportVo;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.service.IAiHealthCheckService;
import org.dromara.ai.service.IAiOnboardingService;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 首登检查清单 Service 实现。
 *
 * <p>完成判据全部来自现有数据：模型密钥复用健康自检的两项结论；文档项看是否存在
 * 「已解析 + 已向量化」的文档；对话项看当前用户是否已有正常助手消息。全部只读。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiOnboardingServiceImpl implements IAiOnboardingService {

    /**
     * 健康自检项：对话模型密钥
     */
    private static final String HEALTH_CHAT_KEY = "model.chat.key";

    /**
     * 健康自检项：向量模型密钥与维度
     */
    private static final String HEALTH_EMBEDDING = "model.embedding";

    /**
     * 文档解析完成
     */
    private static final String PARSE_DONE = "2";

    /**
     * 文档向量化完成
     */
    private static final String VECTOR_DONE = "2";

    private final IAiHealthCheckService healthCheckService;

    private final AiDocumentMapper documentMapper;

    private final AiMessageMapper messageMapper;

    @Override
    public AiOnboardingReportVo checklist() {
        Map<String, String> health = healthStatus();
        List<AiOnboardingItemVo> items = new ArrayList<>();
        items.add(item("chat-model", "配置对话模型密钥",
            "到「模型配置」为对话模型补全 API Key", usable(health.get(HEALTH_CHAT_KEY))));
        items.add(item("embedding-model", "配置向量模型密钥",
            "到「模型配置」为向量模型补全 API Key 并核对维度", usable(health.get(HEALTH_EMBEDDING))));
        items.add(item("document", "完成文档解析与向量化",
            "到「文档与切片」对一篇文档执行解析 + 向量化", documentReady()));
        items.add(item("first-chat", "发送第一条消息",
            "在下方输入框向小Z提一个问题", firstChatDone()));

        int done = (int) items.stream().filter(AiOnboardingItemVo::isDone).count();
        AiOnboardingReportVo report = new AiOnboardingReportVo();
        report.setItems(items);
        report.setRequiredCount(items.size());
        report.setDoneCount(done);
        report.setAllDone(done == items.size());
        return report;
    }

    /**
     * 健康自检项状态：key → status；失败时按「未就绪」处理，不影响清单返回。
     */
    private Map<String, String> healthStatus() {
        Map<String, String> result = new HashMap<>();
        try {
            AiHealthReportVo report = healthCheckService.check();
            if (report == null || report.getItems() == null) {
                return result;
            }
            for (AiHealthCheckVo item : report.getItems()) {
                if (item != null && item.getKey() != null) {
                    result.put(item.getKey(), item.getStatus());
                }
            }
        } catch (Exception e) {
            log.warn("首登清单读取健康自检失败，按未就绪处理：{}", e.getMessage());
        }
        return result;
    }

    /**
     * 健康自检结论是否可用（PASS / WARN 视为就绪；FAIL / UNKNOWN 视为未就绪）。
     */
    private static boolean usable(String status) {
        return AiHealthCheckVo.STATUS_PASS.equals(status) || AiHealthCheckVo.STATUS_WARN.equals(status);
    }

    /**
     * 是否存在已完成解析且完成向量化的文档。
     */
    private boolean documentReady() {
        try {
            Long count = documentMapper.selectCount(Wrappers.lambdaQuery(AiDocument.class)
                .eq(AiDocument::getParseStatus, PARSE_DONE)
                .eq(AiDocument::getVectorStatus, VECTOR_DONE));
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("首登清单查询文档状态失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 当前用户是否已有正常助手消息（说明第一轮对话已完成）。
     */
    private boolean firstChatDone() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return false;
        }
        try {
            Long count = messageMapper.selectCount(Wrappers.lambdaQuery(AiMessage.class)
                .eq(AiMessage::getRole, AiMessage.ROLE_ASSISTANT)
                .eq(AiMessage::getStatus, AiMessage.STATUS_NORMAL)
                .inSql(AiMessage::getSessionId,
                    "select id from ai_session where del_flag = '0' and user_id = " + userId));
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("首登清单查询对话状态失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 构造清单项。
     */
    private static AiOnboardingItemVo item(String key, String title, String description, boolean done) {
        AiOnboardingItemVo vo = new AiOnboardingItemVo();
        vo.setKey(key);
        vo.setTitle(title);
        vo.setDescription(description);
        vo.setDone(done);
        return vo;
    }

}
