# module-ai 侧多租户鉴权落地：租户隔离是怎么「写死」的

> 回答「我怎么在 module-ai 设置 tenant_id 鉴权分配，不可能让用户看到别的客户数据」。
> 结论先行：**芋道已经内置了整套隔离机制，你不需要写「租户分配」逻辑，只需做三件事 + 补一个缺口。**
> 本文每个说法都对应真实源码（类名、方法、行号）。

---

## 〇、先放对位置：业界怎么看「工具权限 + 租户隔离」

业界（Auth0、agentpatterns.tech、Maxim/Bifrost、smolagents、phidata、Descope、bix-tech、AWS Bedrock）做 Agent 工具权限控制的共识，是一个 **RBAC 多层模型**，「租户隔离」只是其中一层：

| 业界层 | 控制什么 | 芋道真实落点 |
|---|---|---|
| 工具注册时过滤 | agent 只能「看到」授权工具 | `AiChatRoleDO.toolIds` |
| 工具执行时鉴权 | 真调时再验一次权限点 | `SecurityFrameworkService.hasAnyPermissions` |
| 租户隔离（scope） | 只能碰自己租户的数据 | `TenantDatabaseInterceptor` |
| 默认拒绝 | 没授权 = 零工具 | 角色白名单为空 → 无工具 |

**本文档只讲「租户隔离」这一层**（工具过滤 / 执行鉴权见文档 10 步骤⑥、文档 07）。但你要先知道它是这个多层模型里的一环，不是孤立的：

- 「角色 → 工具」管「**能用什么工具**」
- 「租户隔离」管「**工具碰哪个租户的数据**」
- 两者是不同维度，都要有

> ⚠️ **一个重要澄清（本轮核对 `AiChatRoleDO` 源码后修正）**：芋道的「角色」是「**AI 聊天角色**」，不是 RBAC 权限角色。`AiChatRoleDO` 的字段是 `systemMessage`（角色设定/prompt）、`modelId`（模型）、`knowledgeIds`（知识库）、`toolIds`（工具）、`mcpClientNames`（MCP 客户端）——这是一个「AI 助手」的完整配置（类似 ChatGPT 的 GPTs），**不是** admin/operator/customer 那种权限角色。所以芋道「按角色过滤工具」实际是「按 AI 助手角色绑定的工具过滤」（`AiChatMessageServiceImpl.getToolCallbackListByRoleId`），而「用户能不能用某工具」是另一套（`SecurityFrameworkService.hasAnyPermissions` 权限点）。两个「角色」别混。

业界一句精准总结（Descope）：**「Scopes control what the user can do; tenant controls where」**——权限（scope）决定「能做什么」，租户（tenant）决定「在哪个数据空间做」。

**deny by default（默认拒绝）**：业界反复强调。芋道里的体现——AI 聊天角色的 `toolIds` 和 `mcpClientNames` 都为空时，`getToolCallbackListByRoleId` 返回空列表 → 一个工具都没有；租户没匹配就 403。是「默认拒绝、显式授权」，不是「默认放行再拦截」。

---

## 一、先纠正一个概念：「写死」的是规则，不是值

「一定要写死」的正确理解是：**写死「隔离规则」，而不是写死某个 `tenant_id` 值**。

- 写死值 = 只能服务一个客户（错误）
- 写死规则 = 每个客户自动隔离（正确）

芋道把「规则」写死在三层拦截器里，是**框架内置**，你一行不用写：

| 层 | 真实类 | 写死的规则 |
|---|---|---|
| 身份 | `TokenAuthenticationFilter` | token → `LoginUser.tenantId` |
| 越权 | `TenantSecurityWebFilter` | tenantId 与登录用户不一致 → 403 |
| 数据 | `TenantDatabaseInterceptor` | 继承 `TenantBaseDO` 的表自动拼 `WHERE tenant_id` |

---

## 二、三层机制（对应真实源码）

### 第 1 层：身份 —— `TokenAuthenticationFilter`

`yudao-framework/yudao-spring-boot-starter-security/.../filter/TokenAuthenticationFilter.java`

从 `header[login-user]`（网关透传）或 token 解析出 `LoginUser`，关键一行（第 99 行）：

```java
return new LoginUser().setId(accessToken.getUserId())...
        .setTenantId(accessToken.getTenantId())...;   // tenantId 来自 token，不是请求方传的
```

`LoginUser`（第 41 行）有 `tenantId` 字段。它是「这个用户属于哪个租户」的**权威来源**。

### 第 2 层：越权 —— `TenantSecurityWebFilter`

`yudao-framework/yudao-spring-boot-starter-biz-tenant/.../security/TenantSecurityWebFilter.java`

核心逻辑（第 67–84 行）：

```java
Long tenantId = TenantContextHolder.getTenantId();
LoginUser user = SecurityFrameworkUtils.getLoginUser();
if (user != null) {
    if (tenantId == null) {
        tenantId = user.getTenantId();            // 没传 → 用登录用户的租户
        TenantContextHolder.setTenantId(tenantId);
    } else if (!Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId())) {
        // 传了但和登录用户的租户不一致 → 越权，直接 403
        ServletUtils.writeJSON(response, CommonResult.error(...FORBIDDEN..., "您无权访问该租户的数据"));
        return;
    }
}
```

**这就是「不让用户看别的客户数据」的硬闸门**：租户不是请求方随便传的，传了不匹配就 403。

### 第 3 层：数据 —— `TenantDatabaseInterceptor`

`yudao-framework/yudao-spring-boot-starter-biz-tenant/.../db/TenantDatabaseInterceptor.java`

实现 MyBatis-Plus `TenantLineHandler`，第 40–43 行：

```java
public Expression getTenantId() {
    return new LongValue(TenantContextHolder.getRequiredTenantId());  // 从上下文取当前租户
}
```

继承 `TenantBaseDO` 的表（第 74–77 行 `computeIgnoreTable` 判断），查询时**自动拼 `WHERE tenant_id = 当前租户`**。

`TenantBaseDO`（第 19 行）就一个字段：

```java
public abstract class TenantBaseDO extends BaseDO {
    private Long tenantId;   // 多租户编号
}
```

---

## 三、你要写的三件事

### ① 隔离表的 DO 继承 `TenantBaseDO`

```java
public class OrderDO extends TenantBaseDO {  // 自带 tenantId 字段，框架自动隔离
    private Long id;
    private String orderNo;
}
```

继承后，凡是通过 MyBatis-Plus 的 Mapper 查这张表，拦截器自动拼租户条件。**这是「写死」在表结构上的落点。**

### ② 工具方法用 `ToolContext` 拿 tenantId

真实例子：`yudao-module-ai/.../tool/function/DbQueryToolFunction.java`（第 66–133 行）：

```java
@Tool(name = "db_query", description = "只读,自动按当前登录用户租户隔离")
public Response query(..., ToolContext toolContext) {
    // 1. 从工具上下文取 tenantId + loginUser（由 AiUtils.buildCommonToolContext() 注入）
    Long tenantId = (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_TENANT_ID);
    LoginUser loginUser = (LoginUser) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_LOGIN_USER);

    if (tenantId == null || loginUser == null) {
        return Response.error("无登录上下文,拒绝执行");   // 防御：没上下文直接拒
    }

    // 2. 走 MyBatis → 自动隔离；走原生 SQL → 手动拼 WHERE tenant_id = ?
    // 3. 用 TenantUtils.execute(tenantId, ...) 包一层，临时 setTenantId 再执行
}
```

`ToolContext` 里的内容来自 `AiUtils.buildCommonToolContext()`（`AiUtils.java` 第 156–161 行）：

```java
public static Map<String, Object> buildCommonToolContext() {
    Map<String, Object> context = new HashMap<>();
    context.put(TOOL_CONTEXT_LOGIN_USER, SecurityFrameworkUtils.getLoginUser());  // 当前登录用户
    context.put(TOOL_CONTEXT_TENANT_ID, TenantContextHolder.getTenantId());        // 当前租户
    return context;
}
```

### ③ 把 MCP 端点 `permitAll()` 改成 `authenticated()`（当前必须补的缺口）

`yudao-module-ai/.../framework/security/config/SecurityConfiguration.java` 现状（第 30–38 行）：

```java
if (StrUtil.isNotBlank(mcpSseEndpoint)) {
    registry.requestMatchers(mcpSseEndpoint).permitAll();
}
if (StrUtil.isNotBlank(mcpSseMessageEndpoint)) {
    registry.requestMatchers(mcpSseMessageEndpoint).permitAll();
}
if (StrUtil.isNotBlank(mcpStreamableHttpEndpoint)) {
    registry.requestMatchers(mcpStreamableHttpEndpoint).permitAll();   // ← /mcp 匿名可访问
}
```

问题：`permitAll()` 让 MCP 端点**匿名可访问**。匿名请求不走 `TokenAuthenticationFilter`，`TenantContextHolder` 是空的，租户隔离失效；攻击者甚至可能伪造 tenant_id header 越权。

改成：

```java
if (StrUtil.isNotBlank(mcpStreamableHttpEndpoint)) {
    registry.requestMatchers(mcpStreamableHttpEndpoint).authenticated();   // 强制带 token
}
// 同理，sse / sse-message 若还在用，也要改成 authenticated()
```

改完后链路闭环：MCP 请求必须带 token → `TokenAuthenticationFilter` 解析 `LoginUser.tenantId` → `TenantSecurityWebFilter` 校验 → 工具方法拿到正确 tenantId → SQL 自动隔离。

---

## 四、关键分界：MyBatis 自动隔离 vs 原生 SQL 手动隔离

「继承 `TenantBaseDO` 就万事大吉」**只在走 MyBatis-Plus 时成立**。如果工具里用 `JdbcTemplate` 写原生 SQL（像 `DbQueryToolFunction`），拦截器管不到，必须手动拼 `WHERE tenant_id = ?`，或用 `TenantUtils.execute(tenantId, ...)` 包一层。

`TenantUtils.execute()`（`TenantUtils.java` 第 26–38 行）会临时 `setTenantId(tenantId)` + `setIgnore(false)`，执行完恢复：

```java
public static void execute(Long tenantId, Runnable runnable) {
    Long oldTenantId = TenantContextHolder.getTenantId();
    Boolean oldIgnore = TenantContextHolder.isIgnore();
    try {
        TenantContextHolder.setTenantId(tenantId);   // 临时切到指定租户
        TenantContextHolder.setIgnore(false);
        runnable.run();
    } finally {
        TenantContextHolder.setTenantId(oldTenantId);
        TenantContextHolder.setIgnore(oldIgnore);
    }
}
```

---

## 五、完整链路图

```
用户请求（带 token）
   │
   ▼
TokenAuthenticationFilter      token → LoginUser.tenantId（权威来源）
   │
   ▼
TenantSecurityWebFilter        校验 tenantId 匹配，否则 403「您无权访问该租户的数据」
   │
   ▼
AiUtils.buildCommonToolContext()   把 LOGIN_USER + TENANT_ID 放进工具上下文
   │
   ▼
@Tool 工具方法                    ToolContext 注入 → 取 tenantId
   │
   ├─ 走 MyBatis(Mapper)  → TenantDatabaseInterceptor 自动拼 WHERE tenant_id
   └─ 走 JdbcTemplate(原生SQL) → 手动拼 tenant_id，或 TenantUtils.execute 包一层
```

---

## 六、一句话总结

你不需要「设置 tenant_id 鉴权分配」——芋道已经写死了一套规则：**token → `LoginUser.tenantId` → 自动拼 SQL**。你要做的只有：

1. 隔离表的 DO 继承 `TenantBaseDO`；
2. 工具方法用 `ToolContext` 拿 tenantId（原生 SQL 手动拼 / `TenantUtils.execute`）；
3. 把 MCP 端点 `permitAll()` 改成 `authenticated()`。

做完这三件事，任何用户都只能看到自己租户的数据，越权访问会被 `TenantSecurityWebFilter` 挡在 403。

**放进业界框架看**：本文档补的是 RBAC 多层模型里的「租户隔离」层（tenant controls where）；配套的「工具过滤」（谁能用什么工具）见文档 10 步骤⑥，「执行时鉴权」（权限点校验）见文档 07。三层合起来才是业界说的完整 Agent 权限控制——**默认拒绝、注册时过滤、执行时鉴权、数据按租户隔离**。
