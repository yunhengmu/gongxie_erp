# 发版门禁清单

发版前**必须全绿**的五道门禁。命令默认在仓库根（`biz-buddy/`）执行；除另有说明外，要求 **PostgreSQL 栈与 MySQL 8.0 栈都已运行**（`deploy/deploy-pg/` 与 `deploy/deploy-mysql/`，端口互不冲突、可同时起）。

> 为什么是这五道：单测全打桩、不起容器；e2e 只看接口行为——**「框架 × 配置」「只改一边方言」「静默失败」这三类缺陷只能靠真库、真栈、真日志暴露**。

## 1. 集成测试（双栈真库）

```bash
cd RuoYi-Vue-Plus
BIZBUDDY_IT=true ./mvnw -o -pl ruoyi-admin -am test
# Windows PowerShell：$env:BIZBUDDY_IT='true'; .\mvnw.cmd -o -pl ruoyi-admin -am test
```

- **通过标准**：零失败、零错误。`biz-buddy` 与 `ruoyi-admin` 两个模块都要跑（一次覆盖 4 类集成测试：真库方言一致性 / 实体↔schema 对齐 / 技能仓库真库读写 / 上下文装配），坐标默认 `localhost:5432` 与 `localhost:3306`，可用 `BIZBUDDY_IT_PG_URL` / `BIZBUDDY_IT_MYSQL_URL` 覆盖；
- **唯一预期跳过**：`JevClientSmokeTest#shouldParseChoiceAndNoulAnswersFromBailian`（需 `BIZBUDDY_JEV_SMOKE` 才外呼真实决策模型的选做冒烟）。**出现其它跳过一律视同异常**。

## 2. 端到端全量（两栈各 13 层）

```bash
node e2e/run.mjs                                    # PG 栈（后端 8080）
node e2e/run.mjs --base-url http://localhost:8081   # MySQL 栈
```

- **通过标准**：零 FAIL。`L12`（结构化决策闸门）需本地桩，未起桩会 **SKIP**——不算失败，但发版前应起桩跑掉，做到**零 FAIL、零 SKIP**。
- **前端关键路径（浏览器层 `e2e-ui`，Playwright）**：`cd e2e-ui && pnpm install && pnpm run install-browser`（首次），再 `pnpm test`（PG 栈默认 `http://localhost`，MySQL 栈用 `BIZBUDDY_UI_BASE_URL=http://localhost:81 pnpm test`）。栈不可达按 SKIP 处理（不算失败，但发版前应起栈跑掉），**跑完要求零 FAIL**。详见 [../e2e-ui/README.md](../e2e-ui/README.md)。

### L12 的桩（起 / 停）

桩必须跑进 compose 网络（容器解析不到 `host.docker.internal`），且**两栈都要能按容器名解析到它**：

```bash
# 先按本机替换两个占位：<distro>＝`wsl --list --verbose` 里带 * 的发行版名（开发环境有 Ubuntu-22.04 与 Ubuntu-24.04 两种）；
#                          <repo>＝仓库根在 WSL 下的绝对路径（如 /mnt/d/test/mybuddy/biz-buddy）
# 1) 起桩（加入 PG 栈网络）
wsl -d <distro> -- docker run -d --rm --name jev-stub --network bizbuddy_default -p 18080:8080 \
  -v <repo>/e2e/jev:/app bellsoft/liberica-openjdk-rocky:21.0.12-cds java /app/SystemOneStub.java 8080
# 2) 接入 MySQL 栈网络（同一容器可同时属于两个网络）
wsl -d <distro> -- docker network connect bizbuddy-mysql_default jev-stub
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18080/health   # 期望 200

# 3) 补跑 L12（两栈）
node e2e/run.mjs --only L12
node e2e/run.mjs --only L12 --base-url http://localhost:8081

# 4) 收尾（--rm 会自动移除容器）
wsl -d <distro> -- docker rm -f jev-stub
```

> `<distro>` / `<repo>` 是刻意的占位符：开发环境存在 Ubuntu-22.04 与 Ubuntu-24.04 两种，写死会在别的机器上直接撞
> `WSL_E_DISTRO_NOT_FOUND`。`node e2e/run.mjs --only L12` 的 SKIP 提示会**自动探测**这两个值并打印可直接复制的命令
> （见 `e2e/lib/env.mjs` 的 `wslDistro` / `wslRepoPath`），发行版名也可用 `BIZBUDDY_WSL_DISTRO` 覆盖。

> L12 收尾会**自动恢复**原结构化决策配置、删除临时决策模型并清理会话；桩只用于确定性覆盖「网关四条分支 + 关闭档」，不接触任何真实密钥。

## 3. 重建后的冒烟 + nginx 重解析

**每次重建后端容器**（`docker compose up -d --build ruoyi-admin`）都会更换容器 IP，而 nginx 在**启动时**解析并缓存该 IP → **不重启 web 必然 502**（[deploy-pg/README §5 ⑧](../deploy/deploy-pg/README.md)）：

```bash
cd deploy/deploy-pg    && docker compose restart web
cd ../deploy-mysql     && docker compose restart web

# 冒烟必须走 nginx（直连后端 200 不能说明代理链路正常）
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/prod-api/auth/code      # 期望 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:81/prod-api/auth/code   # 期望 200
```

## 4. 日志 ERROR 归零 + 体检页全绿

初始化期**单条 SQL 失败不阻断启动、容器照样 healthy**（故障是静默的），所以必须核对日志：

```bash
docker logs bizbuddy-admin       --tail 500 2>&1 | grep -c ERROR   # 期望 0
docker logs bizbuddy-mysql-admin --tail 500 2>&1 | grep -c ERROR   # 期望 0
```

再登录 →「健康自检」页确认全绿（含 `safety.engine`、模型连通、Milvus / Redis 等分支）。

## 5. 存量库升级须知

建库脚本**只在数据卷为空（首次初始化）时执行**；此后的结构 / 种子变更都需**手工迁移**（两方言都要改）。发版前逐条核对本轮改动涉及的「升级须知」（见各栈 `deploy/*/README.md` 的升级须知小节），把需要的 `ALTER` / 数据订正写进发布说明。

---

## 附：2026-10-15 实测基线（当前通过版本）

| 门禁 | 结果 |
|---|---|
| 集成测试（`BIZBUDDY_IT=true`） | `biz-buddy` **1408 / 0 / 0**（1 例为上述预期跳过）；`ruoyi-admin` **7 / 0 / 0**（含 40.9s 真库装配） |
| e2e 全量 | PG 栈 **13/13 PASS**、MySQL 栈 **13/13 PASS**（均为起桩后的结果，零 FAIL、零 SKIP） |

> 本项目**尚未接入 CI**；上表是「人工按本清单执行」的基线。接入 CI 后，第 1、2 项应直接成为硬件门禁。
