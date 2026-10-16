import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 决策模型桩服务：为 e2e L12 提供确定性的四类判定响应。
 *
 * <p>零依赖 JDK 单文件（只用 {@code com.sun.net.httpserver} + {@code java.util.regex}），
 * 模拟百炼决策模型（TypeSafe System One 协议，{@code decision-model-preview}）。判定行为由请求
 * {@code state} 里的 <b>ASCII 标记</b>决定（不用中文标记，避免多层编码问题）：</p>
 * <ul>
 *   <li>{@code [STUB:HIGH]}：选中候选里第一个非 {@code self}/{@code other} 的键，置信度 0.9；</li>
 *   <li>{@code [STUB:LOW]} ：同上选项，置信度 0.3（低于任何合理阈值，用于验证低置信回落）；</li>
 *   <li>{@code [STUB:OUT]} ：返回闭集外的 {@code choice=ghost_expert}，置信度 0.95（用于验证越界回落）；</li>
 *   <li>{@code [STUB:ERR]} ：HTTP 500，体 {@code {"error":{"message":"stub failure"}}}（用于验证判定异常回落）；</li>
 *   <li>其它       ：{@code choice=self}，置信度 0.9。</li>
 * </ul>
 *
 * <p><b>响应概率和精确为 1.00</b>：{@code probabilities} 只包含请求 {@code criteria} 里真实存在的键，
 * 选中项取其 {@code confidence}、其余键平分余量（末位取余量以免二进制尾差），既与真实提供方形态一致
 * （实测 {@code choice} 恒为分布最大项），又让「分桶基础阈值 / margin 二次过滤」两条分支可被覆盖。
 * {@code criteria} 的键以极简正则 +
 * 字符串扫描解析（先定位 {@code "criteria"} 后配对的 {@code {...}} 子串，再取其中的键）；
 * 解析失败安全回退为 {@code self}。</p>
 *
 * <p>启动方式（在 WSL 内跑进 compose 网络；容器解析不到 {@code host.docker.internal}，桩必须与服务同网）：</p>
 * <pre>
 *   wsl -d &lt;distro&gt; -- docker run -d --rm --name jev-stub --network bizbuddy_default -p 18080:8080 \
 *     -v &lt;repo&gt;/e2e/jev:/app bellsoft/liberica-openjdk-rocky:21.0.12-cds java /app/SystemOneStub.java 8080
 * </pre>
 * <p>{@code <distro>}／{@code <repo>} 按本机替换（开发环境有 Ubuntu-22.04 与 Ubuntu-24.04 两种，
 * 不写死）；{@code node e2e/run.mjs --only L12} 的 SKIP 提示会自动探测并打印实际命令。</p>
 * <p>端口从 {@code args[0]} 取（默认 8080）；启动后打印 {@code stub listening on <port>}。</p>
 */
public class SystemOneStub {

    /** 高置信标记 */
    private static final String MARKER_HIGH = "[STUB:HIGH]";
    /** 低置信标记 */
    private static final String MARKER_LOW = "[STUB:LOW]";
    /** 越界标记 */
    private static final String MARKER_OUT = "[STUB:OUT]";
    /** 判定异常标记 */
    private static final String MARKER_ERR = "[STUB:ERR]";

    /** 闭集固定项：入口智能体自答 */
    private static final String CHOICE_SELF = "self";
    /** 闭集固定项：无法判断归属 */
    private static final String CHOICE_OTHER = "other";
    /** 闭集外的伪造选项（验证越界回落） */
    private static final String CHOICE_OUT_OF_SET = "ghost_expert";

    /** criteria 对象内「键」的正则：形如 {@code "key":}，值落在引号内，不会误匹配 */
    private static final Pattern CRITERIA_KEY = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:");

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0].trim()) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", SystemOneStub::handleHealth);
        server.createContext("/compatible-mode/v1/systemone", SystemOneStub::handleSystemOne);
        server.createContext("/v1/systemone", SystemOneStub::handleSystemOne);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("stub listening on " + port);
    }

    /** 健康检查：供 L12 前置从 Windows 侧经已发布端口探活。 */
    private static void handleHealth(HttpExchange exchange) {
        respond(exchange, 200, "{\"ok\":true}");
    }

    /** System One 判定入口：按 state 里的标记返回四类响应。 */
    private static void handleSystemOne(HttpExchange exchange) {
        String body = readBody(exchange);
        // 异常标记优先：直接返回 500，模拟判定服务不可用
        if (body.contains(MARKER_ERR)) {
            System.out.println("[stub] systemone 判定请求：marker=ERR，返回 HTTP 500");
            respond(exchange, 500, "{\"error\":{\"message\":\"stub failure\"}}");
            return;
        }
        List<String> keys = parseCriteriaKeys(body);
        String choice;
        double confidence;
        String marker;
        if (body.contains(MARKER_OUT)) {
            marker = "OUT";
            choice = CHOICE_OUT_OF_SET;
            confidence = 0.95;
        } else if (body.contains(MARKER_LOW)) {
            marker = "LOW";
            choice = firstExpertKey(keys);
            confidence = 0.3;
        } else if (body.contains(MARKER_HIGH)) {
            marker = "HIGH";
            choice = firstExpertKey(keys);
            confidence = 0.9;
        } else {
            marker = "DEFAULT";
            choice = CHOICE_SELF;
            confidence = 0.9;
        }
        System.out.println("[stub] systemone 判定请求：marker=" + marker
            + "，criteria键=" + keys + "，choice=" + choice + "，confidence=" + confidence);
        respond(exchange, 200, buildResponse(keys, choice, confidence));
    }

    /** 取 criteria 里第一个既非 self 也非 other 的键；无则回退 self。 */
    private static String firstExpertKey(List<String> keys) {
        for (String key : keys) {
            if (!CHOICE_SELF.equals(key) && !CHOICE_OTHER.equals(key)) {
                return key;
            }
        }
        return CHOICE_SELF;
    }

    /**
     * 从请求体解析 {@code questions.<id>.criteria} 对象的键（保持出现顺序）。
     *
     * <p>步骤：定位 {@code "criteria"} → 找其后第一个 {@code &#123;} → 按花括号配对（跳过字符串字面量，
     * 避免值里含花括号/冒号干扰）得到 criteria 子串 → 正则取出键。</p>
     *
     * @param body 请求体
     * @return 键列表；解析失败返回空列表
     */
    private static List<String> parseCriteriaKeys(String body) {
        int anchor = body.indexOf("\"criteria\"");
        if (anchor < 0) {
            return new ArrayList<>();
        }
        int start = body.indexOf('{', anchor);
        if (start < 0) {
            return new ArrayList<>();
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int end = -1;
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) {
            return new ArrayList<>();
        }
        String criteria = body.substring(start, end + 1);
        List<String> keys = new ArrayList<>();
        Matcher matcher = CRITERIA_KEY.matcher(criteria);
        while (matcher.find()) {
            keys.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return keys;
    }

    /**
     * 组装 System One 响应：概率分布只含 criteria 真实键，**选中项取 {@code confidence}、其余平分余量**
     * （和精确为 1.00，不触发应用侧宽松校验的告警）。
     *
     * <p><b>为什么不再把选中项写成 1.0</b>：应用侧的闸门判定要求 {@code top1}
     * （概率分布的最大值）达分桶基础阈值，且 {@code top1 / top2 ≥ marginRatio}。若选中项恒为 1.0、
     * 其余恒为 0.0，则 {@code top1} 恒为 1.0 且 margin 恒被放行——「低置信」与「margin 拒绝」两条
     * 分支将永远无法被覆盖。真实提供方（百炼）返回的是完整分布（实测 {@code choice} 恒为分布的最大项、
     * {@code confidence ≈ top1}），此处对齐该形态。</p>
     *
     * @param keys       闭集键（解析失败为空）
     * @param choice     选中的选项（越界时不在 keys 中，质量让给 self / 首个键）
     * @param confidence 置信度（即选中项的概率）
     * @return 响应 JSON
     */
    private static String buildResponse(List<String> keys, String choice, double confidence) {
        LinkedHashMap<String, Double> probabilities = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            // 解析失败的安全回退：只给闭集固定项 self 挂 1.0（不引入任何未知键）
            probabilities.put(CHOICE_SELF, 1.0);
        } else {
            // 选中项在闭集内即以 confidence 作为其概率；越界（OUT）或解析异常则把质量让给 self（无 self 时让给首个键）
            String massKey = keys.contains(choice) ? choice
                : (keys.contains(CHOICE_SELF) ? CHOICE_SELF : keys.get(0));
            int others = keys.size() - 1;
            double share = others <= 0 ? 0D : round4((1D - confidence) / others);
            double assigned = 0D;
            int index = 0;
            for (String key : keys) {
                if (key.equals(massKey)) {
                    probabilities.put(key, confidence);
                    continue;
                }
                index++;
                // 最后一个非选中键取「余量」而非等分值，保证概率和精确为 1.00
                double value = index == others ? round4(1D - confidence - assigned) : share;
                assigned += value;
                probabilities.put(key, Math.max(0D, value));
            }
        }
        StringBuilder probabilitiesJson = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            if (!first) {
                probabilitiesJson.append(',');
            }
            first = false;
            probabilitiesJson.append('"').append(escape(entry.getKey())).append("\":")
                .append(String.valueOf(entry.getValue()));
        }
        probabilitiesJson.append('}');
        return "{\"model\":\"decision-model-preview\",\"request_id\":\"stub\",\"latency_ms\":5,"
            + "\"answers\":{\"expert\":{\"type\":\"choice\",\"choice\":\"" + escape(choice) + "\","
            + "\"confidence\":" + confidence + ",\"probabilities\":" + probabilitiesJson + "}},"
            + "\"usage\":{\"input_tokens\":42}}";
    }

    /** 四舍五入到 4 位小数（与提供方两位小数量级一致，且避免二进制尾差触发校验告警）。 */
    private static double round4(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    /** 读取请求体（UTF-8）。 */
    private static String readBody(HttpExchange exchange) {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 发送 JSON 响应并关闭交换。 */
    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (Exception e) {
            System.out.println("[stub] 响应写出失败：" + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    /** JSON 字符串最小转义（键与选项均为简单标识，仅兜底引号/反斜杠）。 */
    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}