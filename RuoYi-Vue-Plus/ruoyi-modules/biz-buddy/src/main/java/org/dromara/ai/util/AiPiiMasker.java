package org.dromara.ai.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自由文本 PII 脱敏工具（手机号 / 身份证 / 邮箱 / 银行卡 / IPv4）。
 *
 * <p>与框架 {@code @Sensitive} 的分工：字段级注解只能处理「整串即敏感值」的结构化字段；
 * 模型回答与工具结果是**自由文本**，PII 夹杂在句中，故用本工具按正则就地掩码。</p>
 *
 * @author BizBuddy
 */
public final class AiPiiMasker {

    /**
     * 邮箱
     */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 身份证（18 位，含校验位 X）
     */
    private static final Pattern ID_CARD =
        Pattern.compile("(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)");

    /**
     * 银行卡（62 / 4 / 51-55 开头，16~19 位）
     */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(?:62|4\\d|5[1-5])\\d{12,17}(?!\\d)");

    /**
     * 手机号（中国大陆）
     */
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /**
     * IPv4
     */
    private static final Pattern IPV4 = Pattern.compile("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)");

    /**
     * PII 类型：邮箱
     */
    public static final String TYPE_EMAIL = "EMAIL";

    /**
     * PII 类型：身份证
     */
    public static final String TYPE_ID_CARD = "ID_CARD";

    /**
     * PII 类型：银行卡
     */
    public static final String TYPE_BANK_CARD = "BANK_CARD";

    /**
     * PII 类型：手机号
     */
    public static final String TYPE_MOBILE = "MOBILE";

    /**
     * PII 类型：IP
     */
    public static final String TYPE_IP = "IP";

    private AiPiiMasker() {
    }

    /**
     * 脱敏结果。
     *
     * @param text  脱敏后的文本
     * @param count 命中次数
     * @param types 命中的 PII 类型（去重，按检测顺序）
     */
    public record Outcome(String text, int count, List<String> types) {
    }

    /**
     * 对自由文本做 PII 掩码。
     *
     * @param text 原文（可空）
     * @return 脱敏结果；入参为空时原样返回、计数为 0
     */
    public static Outcome mask(String text) {
        if (text == null || text.isEmpty()) {
            return new Outcome(text, 0, List.of());
        }
        int[] count = {0};
        List<String> types = new ArrayList<>();
        String working = text;
        working = apply(working, EMAIL, AiPiiMasker::maskEmail, TYPE_EMAIL, types, count);
        working = apply(working, ID_CARD, AiPiiMasker::maskIdCard, TYPE_ID_CARD, types, count);
        working = apply(working, BANK_CARD, AiPiiMasker::maskBankCard, TYPE_BANK_CARD, types, count);
        working = apply(working, MOBILE, AiPiiMasker::maskMobile, TYPE_MOBILE, types, count);
        working = apply(working, IPV4, AiPiiMasker::maskIp, TYPE_IP, types, count);
        return new Outcome(working, count[0], types);
    }

    /**
     * 对自由文本做 PII 掩码（只要文本）。
     *
     * @param text 原文（可空）
     * @return 脱敏后的文本
     */
    public static String maskText(String text) {
        return mask(text).text();
    }

    /**
     * 单模式就地替换并计数。
     */
    private static String apply(String text, Pattern pattern, Function<String, String> replacer,
                                String type, List<String> types, int[] count) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        matcher.reset();
        String replaced = matcher.replaceAll(match -> {
            count[0]++;
            return replacer.apply(match.group());
        });
        if (!types.contains(type)) {
            types.add(type);
        }
        return replaced;
    }

    private static String maskEmail(String value) {
        int at = value.indexOf('@');
        String local = value.substring(0, at);
        return (local.length() <= 1 ? "*" : local.charAt(0) + "***") + value.substring(at);
    }

    private static String maskIdCard(String value) {
        return value.substring(0, 6) + "********" + value.substring(value.length() - 4);
    }

    private static String maskBankCard(String value) {
        return value.substring(0, 4) + " **** **** " + value.substring(value.length() - 4);
    }

    private static String maskMobile(String value) {
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private static String maskIp(String value) {
        int lastDot = value.lastIndexOf('.');
        return value.substring(0, lastDot + 1) + "x";
    }

}