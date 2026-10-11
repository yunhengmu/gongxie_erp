package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiHealthProbeBo;
import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.dromara.ai.domain.vo.AiHealthReportVo;
import org.dromara.ai.service.IAiHealthCheckService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台健康自检 Controller（REQ-AI-35）
 *
 * <p>把「开箱第一公里」与日常运维自查收敛为一屏：密钥是否配齐、向量维度是否一致、Milvus / MCP
 * 是否可用、时区是否错位、出厂密钥是否未收口等。<b>页面只读，不提供一键修复。</b></p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/health")
public class AiHealthController extends BaseController {

    private final IAiHealthCheckService healthCheckService;

    /**
     * 平台健康自检（只读，不发起任何外网调用）
     */
    @SaCheckPermission("ai:health:query")
    @GetMapping("/check")
    public R<AiHealthReportVo> check() {
        return R.ok(healthCheckService.check());
    }

    /**
     * 对单个体检项做真实连接探测（模型连通性 / Milvus / 外部 MCP 连接）
     */
    @SaCheckPermission("ai:health:probe")
    @Log(title = "平台健康自检探测", businessType = BusinessType.OTHER)
    @PostMapping("/probe")
    public R<AiHealthCheckVo> probe(@Validated @RequestBody AiHealthProbeBo bo) {
        return R.ok(healthCheckService.probe(bo));
    }

}
