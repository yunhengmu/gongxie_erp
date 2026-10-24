package org.dromara.ai.domain.dto;

import org.dromara.ai.domain.AiScenarioPackMcpTool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 会话级资源集（REQ-AI-32）：装配层消费的**已展开**资源视图。
 *
 * <p>由 {@code AiSessionResourceService} 从 {@code ai_session_resource} 现读并展开场景包后产出，
 * 供 {@code AgentRegistry} 计算「合并决策 / 可见工具 / 技能 / MCP 工具 / 装配缓存键与指纹」。</p>
 *
 * <ul>
 *   <li>{@code signature}：资源集**身份签名**（排序后的 {@code type:key} 列表 → SHA-256 前 8 字节）。
     *       作为装配缓存键与指纹的组成部分，故改选择后下一轮即重新装配生效；同一组合共享实例。</li>
     *   <li>{@code sessionId}：会话主键（无人值守 / 单包兼容路径为空）。装配层据此保证「同一会话切换资源组合时
     *       不复用旧实例」——框架 {@code AgentState} 的 CAS 读取在实例与 Redis 版本错位时会失败（REQ-AI-32 实测）。</li>
 *   <li>{@code packSignature}：包内容指纹片段（包本体 + 三类绑定），包内容变更即触发重建。</li>
 *   <li>{@code packXxx} / {@code directXxx}：分别来自「场景包展开」与「直接选中（会话级）」，
 *       合并时**直接选中优先**（越显式越优先）。</li>
 * </ul>
 *
 * @author BizBuddy
 */
public record AiSessionResourceSet(String signature,
                                   String packSignature,
                                   Map<String, String> packToolDecisions,
                                   List<Long> packSkillIds,
                                   List<AiScenarioPackMcpTool> packMcpTools,
                                   Map<String, String> directToolDecisions,
                                   List<Long> directSkillIds,
                                   List<AiScenarioPackMcpTool> directMcpTools,
                                   Long sessionId) {

    /**
     * 空资源集（未选任何资源）：签名取空列表签名，指纹片段为空串。
     *
     * @return 空资源集
     */
    public static AiSessionResourceSet empty() {
        return new AiSessionResourceSet(signature(List.of()), "", Map.of(), List.of(), List.of(),
            Map.of(), List.of(), List.of(), null);
    }

    /**
     * 合并业务工具决策：包内决策 → 直接选中（后者覆盖前者）。
     *
     * @return 工具编码 → 决策
     */
    public Map<String, String> mergedToolDecisions() {
        Map<String, String> merged = new LinkedHashMap<>(packToolDecisions);
        merged.putAll(directToolDecisions);
        return merged;
    }

    /**
     * 合并技能主键：包内技能 ∪ 直接选中技能（去重保序）。
     *
     * @return 技能主键集合
     */
    public List<Long> mergedSkillIds() {
        Set<Long> merged = new LinkedHashSet<>(packSkillIds);
        merged.addAll(directSkillIds);
        return new ArrayList<>(merged);
    }

    /**
     * 合并 MCP 工具选择：包内选择 → 直接选中（同「连接 + 工具名」以后者为准）。
     *
     * @return MCP 工具选择列表
     */
    public List<AiScenarioPackMcpTool> mergedMcpSelections() {
        List<AiScenarioPackMcpTool> merged = new ArrayList<>(packMcpTools);
        merged.addAll(directMcpTools);
        return merged;
    }

    /**
     * 计算资源集身份签名：排序后的 {@code type:key} 列表 → SHA-256 前 8 字节（十六进制小写）。
     *
     * @param canonicalKeys 规范键列表（形如 {@code tool:create_notice}）
     * @return 16 位十六进制签名
     */
    public static String signature(List<String> canonicalKeys) {
        List<String> sorted = new ArrayList<>(canonicalKeys);
        sorted.sort(Comparator.naturalOrder());
        return sha256Hex(String.join("|", sorted)).substring(0, 16);
    }

    /**
     * 计算 SHA-256 十六进制串。
     *
     * @param text 原文
     * @return 十六进制小写串
     */
    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

}