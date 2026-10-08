# 二次开发：新增一个业务工具

「给智能体加一个能调用的业务工具」是本项目最常见的扩展。本文以**只读工具**为主线讲全流程，末尾说明**写入类工具**的额外要求。

## 0. 心智模型

工具是**两段式**的，缺一不可：

```
ai_tool 表（元数据：编码/描述/参数/实现标识/启停）
        ＋
代码侧实现（AgentTool 实例，由 AiToolFactory 按 impl_name 装配）
```

**注册页不产生能力**：只在页面上填一行元数据，而不在代码里登记实现，装配时会被静默跳过（`AiToolFactory#create` 返回空）。这是刻意的安全设计，避免出现「裸 SQL / 裸 HTTP」式万能工具。

## 1. 实现工具类

在 `RuoYi-Vue-Plus/ruoyi-modules/biz-buddy/src/main/java/org/dromara/ai/core/agent/tools/` 下新增类，继承 `io.agentscope.core.tool.ToolBase`：

```java
package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询某个业务对象的工具（只读、无副作用）。
 */
public class GetSomethingTool extends ToolBase {

    /** 工具编码，须与 ai_tool.tool_code 的种子行一致 */
    public static final String TOOL_NAME = "get_something";

    /** 工具描述：写清「什么时候该调」，模型据此判断 */
    private static final String DESCRIPTION =
        "查询某个业务对象的详情。当用户询问该对象的名称/状态/负责人等具体信息时调用；不要凭猜测回答。";

    public GetSomethingTool(/* 注入所需 Service */) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)          // 只读工具：无副作用
            .concurrencySafe(true)); // 可并发调用
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        String id = use == null ? null : use.getId();
        String name = use == null ? TOOL_NAME : use.getName();

        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        String keyword = String.valueOf(input.getOrDefault("keyword", ""));
        if (keyword.isBlank()) {
            return Mono.just(ToolResultBlock.error("keyword 不能为空").withIdAndName(id, name));
        }
        try {
            String result = "（这里调用你的 Service 并把结果渲染成文本）";
            return Mono.just(ToolResultBlock.text(result).withIdAndName(id, name));
        } catch (Exception e) {
            // 业务异常不要打断整轮对话，交回模型解释
            return Mono.just(ToolResultBlock.error(e.getMessage()).withIdAndName(id, name));
        }
    }

    /** 参数 JSON Schema：description 会进模型视野，写清楚每个字段的含义与格式 */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", Map.of("type", "string", "description", "查询关键词"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("keyword"));
        return schema;
    }
}
```

要点：

- `name` 必须与 `ai_tool.tool_code` / `impl_name` 的约定一致；
- `description` 决定**模型何时调用**，比参数说明更重要，写"什么时候该调"；
- 工具不抛异常打断链路——业务失败用 `ToolResultBlock.error(...)` 回给模型；
- 参考实现：`GetCurrentTimeTool`（最简）、`SearchNoticeTool` / `CreateNoticeTool`（带权限与写入）。

## 2. 在 `AiToolFactory` 登记

文件：`core/agent/AiToolFactory.java`，三处改动：

```java
// ① 新增实现标识常量
public static final String IMPL_GET_SOMETHING = "get_something";

// ② isSupported 里加一个分支
|| IMPL_GET_SOMETHING.equals(implName)

// ③ create 里加一个分支，返回实例
if (IMPL_GET_SOMETHING.equals(implName)) {
    return Optional.of(new GetSomethingTool(/* 注入 */));
}
```

> `impl_name` 与 `tool_code` 可以不同，但**建议同名**，减少心智负担。`impl_name` 是代码侧装配用的 key；`tool_code` 是审计与权限规则用的标识。

## 3. 登记元数据

两种方式，任选：

**(a) 页面登记（开发调试推荐）** —— 登录后进入「业务工具注册」页，新增一行：

| 字段 | 值 | 说明 |
|---|---|---|
| 工具编码 `tool_code` | `get_something` | 唯一，作为 AgentTool 的 name |
| 工具显示名 `tool_name` | `查询业务对象` | 界面展示 |
| 用途说明 `description` | 同工具描述 | 同时作为模型可见的 description |
| 工具类型 `tool_type` | `0` | `0` 只读 / `1` 写入 |
| 参数定义 `params_schema` | JSON Schema | 与代码里 `buildSchema()` 一致 |
| 实现标识 `impl_name` | `get_something` | 必须与代码登记一致 |
| 状态 `status` | `0` | `0` 启用 / `1` 停用 |
| 对外暴露 `mcp_exposed` | `0` | `1` 才会出现在自建 `/mcp` 出口（**仅只读工具可勾选**） |

**(b) 写入种子脚本（交付推荐）** —— 在 `RuoYi-Vue-Plus/script/sql/postgres/postgres_ry_ai.sql` 的 `ai_tool` 种子段追加一行 `insert`，保证新环境一键建库即带此工具。

## 4. 让智能体用上它

工具的**可见面**由装配期决定，两条路径：

- **绑定到智能体**：智能体定义页绑定该工具并设置决策（`0` 允许 / `1` 需确认 / `2` 禁止）；
- **会话内选中**：对话底栏 `+` →「工具」勾选（下一轮生效）。

> 入口智能体「小Z」不绑业务工具；想让小Z 自答时就具备该能力，走会话资源集。

## 5. 写入类工具的额外要求

`tool_type = '1'` 的写工具，除上面步骤外还要注意：

1. **人工确认（HITL）**：由决策档位驱动，写工具**默认「需确认」**——命中即由框架暂停并弹卡片，无需在工具里自己写确认逻辑；
2. **工具描述要写明确认由平台处理**：否则模型可能只输出「请确认后我将执行」而**不调用工具**（写工具描述里建议明确「调用时平台会自动弹出人工确认卡，直接调用即可」）；
3. **执行期复核业务权限**：

```java
Long operatorId = /* 从 RuntimeContext 取，不要用 LoginHelper */;
Set<String> perms = permissionService.getMenuPermission(operatorId);
if (!perms.contains("*:*:*") && !perms.contains("system:xxx:add")) {
    return Mono.just(ToolResultBlock.error("当前用户无操作权限").withIdAndName(id, name));
}
```

4. **操作人取自 `RuntimeContext`**：工具跑在 Reactor 线程，Sa-Token 的线程上下文不可用；
5. **数据权限**：涉及部门数据隔离时，用不挂 `@DataPermission` 的 `*ByAi` 服务方法并显式做归属校验（注解式数据权限在 Reactor 线程取不到登录态）。

## 6. 测试

在 `biz-buddy/src/test/java/org/dromara/ai/core/agent/tools/` 下补单测（参考同级 `*ToolTest`），至少覆盖：

- 正常参数 → 返回成功文本；
- 缺参 / 非法参数 → 返回 error 且含可读原因；
- 无权限用户 → 拒绝；
- 业务异常 → 返回 error 而非抛出。

```bash
cd RuoYi-Vue-Plus
./mvnw -o -pl ruoyi-modules/biz-buddy test
```

**单测之外，建议再跑一次端到端回归**（尤其改的是写工具 / 工具描述 / 装配与权限时）——单测走 Mockito 打桩、不起 Spring 容器，验证不了「模型是否真的会调这个工具」：

```bash
cd ..                       # 仓库根（biz-buddy/）
node e2e/run.mjs --only L4,L5 --verbose   # L4 对话与专家编排；L5 写工具 HITL 闭环
```

`e2e/` 自带用例，**一般不需要为单个工具写新用例**；它会真实建会话、发 SSE、触发工具调用并核对编排轨迹。用法与前置见仓库根 [README](../README.md#端到端测试e2e) 或 [docs/README.md](README.md)。

## 7. 常见坑

| 现象 | 原因 |
|---|---|
| 工具注册了但模型看不到 | 未在 `AiToolFactory` 登记实现（`create` 返回空即被跳过） |
| 改完代码没生效 | 装配实例按指纹缓存，改实现/决策/提示词会触发重建；若改的是**工具 Schema**，需重启或等待重建 |
| 写工具不被调用、只文字回复 | 工具描述/提示词让模型以为"要先问用户"；参见第 5 节第 2 点 |
| 入口智能体用不上该工具 | 小Z 不绑业务工具，需在会话资源集里选中 |
| 报 `Invalid 'tools[0].function.name'` | 工具名不合 LLM 规范（MCP 工具会被自动归一化，自建工具请用具名规范字符） |
| 聊天里上传的图片 / 文档在页面上「加载失败」 | `sys_oss_config.domain_url` 不是**浏览器可达**地址（服务端读写走 `endpoint`，故解析与模型识图仍正常）；见 [configuration.md](configuration.md) §3 与部署文档排障 |
| 知识库文档显示「已解析」但点「向量化」被拒 | 该文档**没有切片**（例如只写了正文的种子数据）；先点「重新解析」生成切片再向量化 |