# 港口靠泊协同服务（Berth Coordination）

船代提交船次（船长/吃水/货类/ETA 区间/作业时长/岸桥数），码头调度枚举并比较候选靠泊计划，
对 **泊位 + 引航员 + 拖轮 + 岸桥整组资源** 做暂留或原子确认；确认后的计划才下发引航、拖轮、岸桥班组。
系统在**整个靠泊操纵与装卸窗口**校验吃水下净空（UKC ≥ 1m）、货类限制、人员值班/交接、
设备工作区间/转场/周转，并提供潮汐修订、岸桥故障、改靠、ETA 迟到的影响分析与版本化重排；
已完成的引航/装卸记录在应用层与数据库触发器双层保证不可覆盖。

**滞期争议（本迭代新增）**：把各方回执（准备就绪、天气停工/复工、作业中断/恢复、修复回执、完工）
与合同规则（免计时长、分钟费率、例外条款）组合成**可质证的可计时区段与金额**；支持边收事件边累计的
暂计账单与完工定稿两种口径、任意时刻回放（只看该时刻前送达的回执）、迟到回执触发的区段更正留痕、
双方证据展示；计划停工预告不得替代实际停工凭证，回执入账后不可篡改（DB 触发器）。

- 后端：Java（`--release 21`，已在预装 **JDK 25.0.1** 上构建运行）、Spring Boot 3.5.7、Spring JDBC、PostgreSQL
- 前端：`src/main/resources/static/` 原生 HTML/CSS/JS 单页（无需 Node 构建）
- 接口文档：springdoc-openapi，`/swagger-ui.html`
- 一键启动：`Dockerfile` + `docker-compose.yml`（PostgreSQL 18）

---

## 1. 题目场景与内置样例

固定样例时间为 **2026-11-26 晚**，重置接口/回放会反复恢复该数据：

- **B1 深水泊位**：可容船长 320m、图示水深 9.5m；深水船吃水 11.5m、UKC 要求 1m
  ⇒ 需要潮位 `h ≥ 11.5 + 1.0 − 9.5 = 3.0m`。
- 30 分钟一档的模拟潮汐中，**只有 S=20:00 这一个 15 分钟网格起点**能通过全窗口 UKC 校验
  （19:30 档潮位 2.8m 不够；21:30 档回落到 2.75m，20:15/20:30 起点也失败）。
- 该时段唯一有 DEEP 资质且值班的引航员 **PILOT-1 张远航**（值班 17:00–次日 02:00，交接 30m），
  唯一适配 300m/11.5m 船舶的拖轮 **TUG-1 港拖16**（周转 30m）；岸桥 QC-1~QC-4。
- **VOY-A 远集 A**：ETA `19:30–20:30`，作业 60m，需 2 台岸桥。
- **VOY-B 远集 B**：ETA `19:00–20:00`，已提交 20:00 靠泊，与 A 竞争同一组合。
- 另有 VOY-C/D/E 覆盖集装箱、滚装与“ETA 18–19 改 19–20”的开场故事。
- 次日 08:00 前后还有早潮窗口与 PILOT-3/TUG-3 早班，用于“等潮/等资源”的备选与重排。

---

## 2. 核心设计决定：何时占用整组靠泊资源

两种策略都已实现，并可用接口参数或回放对照：

| 策略 | 行为 | 优点 | 代价 |
| --- | --- | --- | --- |
| **`TENTATIVE_HOLD`（默认）** | 调度“暂存”候选时，即在同一事务写入泊位/引航/拖轮/岸桥的 `TENTATIVE` 占用，TTL 默认 120s；到期由 500ms 扫描器自动释放 | 确认后资源不会被抢 | ETA 尚宽时资源可能空等 → 用短 TTL + 一键取消控制 |
| **`ATOMIC_CONFIRM`** | 暂存只保存方案、**不占资源**；确认时在一个事务内重校全部约束并插入 `FIRM` 占用 | 资源周转率高 | 已讨论方案可能在最后一步被别人锁走，确认必须原子重校+锁定 |

暂存接口可用 `"holdResources": false/true` 临时选择策略；`app.planning.hold-on-stage` 改全局默认。

**唯一性保证（不依赖应用锁）**：所有占用统一落在 `resource_occupation` 表。
PostgreSQL 触发器 `trg_occupation_overlap` 在写入前对 `resource_lock` 中该资源的锁父行 `SELECT … FOR UPDATE`，
再检查半开区间重叠；并发确认同一组合时恰好一个事务成功，另一个收到 `23P01/RESOURCE_OVERLAP` → HTTP 409。
另有部分唯一索引保证同一船次至多一个 `CONFIRMED` 方案。

---

## 3. 约束与时间轴模型

设 `S = berth_from`（靠泊操纵开始）：

```
引航段    [S-45, S+15]            占用区间前后各加 handover_min 交接缓冲
拖轮靠泊  [S-15, S+30]            占用尾部加 turnaround_min 周转
靠泊操纵  [S, S+30]               泊位占用开始
装卸作业  [S+30, S+30+duration]   每台岸桥占用至 opsEnd + transfer_min 转场
拖轮离泊  [opsEnd, opsEnd+30]     同样加周转缓冲
离泊段    [opsEnd, opsEnd+30]     泊位占用结束 = opsEnd+30
```

- **UKC**：对 `[S, opsEnd]` 与每个重叠潮汐档校验 `chart_depth + tide_height − draft ≥ ukc_min(=1.0)`，并检查潮汐覆盖完整性。
- 泊位：船长 ≤ `max_length_m`，货类必须在 `berth_cargo_limit` 名单内。
- 引航员：船长/吃水/资质（深水大船需 `DEEP`）+ 占用区间（含交接）完整落在某个 `work_window`。
- 拖轮：船长/吃水适配 + 靠/离两区间（含周转）均不冲突、在班。
- 岸桥：需要 N 台不同岸桥，每台工作窗覆盖 `[opsStart, opsEnd+transfer]`，且无占用/故障重叠。
- 候选生成：15 分钟网格，从 `etaStart` 枚举到 `etaEnd + 960m`；ETA 窗口内不可行点全部保留并给出
  结构化原因（`UKC_VIOLATION`、`*_HELD_TENTATIVE`、`*_BOOKED_FIRM`、`PILOT_OFF_DUTY`、
  `INSUFFICIENT_CRANES` 等，含占用方船次/方案/暂留到期时间），可行点按时间先后取前 6。

---

## 4. 滞期争议：回执 + 合同规则 → 可质证区段与金额

固定样例船次 **VOY-L（远衡 L 轮）**，争议焦点从“停了多久”转为“谁造成停工”：

| 时间 | 事件 | 回执（凭证号 · 方） | 合同处理 |
| --- | --- | --- | --- |
| 07:30 | QC-2 计划保养预告 10:30–11:30 | PLAN-QC2-MAINT · 港方 | **预告不作实际停工凭证** |
| 08:00 | 准备就绪（NOR） | NOR-2026-088 · 船方 | 合同前 2 小时免计 → **10:00 起计** |
| 10:00–11:00 | 天气停工（阵风 9 级） | PORT-WX-1001/1002 · 港方确认 | **暂停计时**（例外条款） |
| 11:00–12:30 | 岸桥 QC-2 故障停工 | SHIP-LOG-110/111 · 船方；PORT-QC-REP-207 · 港方 | **仍计时**，归因港方设备 |
| 12:30 | 修复完成，**回执 13:00 才送达（迟到 30′）** | PORT-QC-REP-207 | 迟到回执**更正 11:00–12:30 区段** |
| 14:00 | 完工 | PORT-CPL-300 · 港方 | 计时截止 → **定稿** |

**定稿结果：可计时区间 11:00–14:00（180 分钟）× 每分钟 1000/60 元 = 应收 3000 元。**

关键机制：

- **账单是纯函数** `f(截至 asOf 已送达回执, 合同, asOf)`：回放 12:00 时 12:31/13:00/14:00 送达的
  回执不可见，未闭合停工延伸到 12:00 即止 —— **尚未发生的时长不计入**，12:00 只能显示
  暂计 60 分钟 / 1000 元。
- **两种结算口径**（`GET .../laytime?mode=`）：
  `STREAMING` 边收事件边累计（暂计额随回执增长：0→66.67→1516.67→2000→3000，船代可随时查看，
  代价是迟到回执会更正区段）；`AT_COMPLETION` 完工收齐再结算（完工回执送达前不出具金额，
  数字稳定，代价是争议期间没有最终数）。
- **迟到回执更正留痕**：每条回执入账即重算一版账单（`laytime_statement`），相邻版本间分类变化的
  区段写入 `laytime_correction` —— 11:00–12:30 由「停工待证」更正为「岸桥故障·港方设备」。
- **双方证据**：每个区段附定责回执（船方停工报告 + 港方修复回执）；船方“天气延续”主张因无港方
  确认且被修复回执否定，标记 `REFUTED`；合同只认港方确认凭证。
- **计划停工不得替代实际停工凭证**：计划保养预告只作展示，不参与区段分类。
- **回执不可篡改**：`laytime_event` 由触发器 `trg_laytime_event_immutable` 禁止 UPDATE/DELETE；
  定稿（`POST .../laytime/finalize`）后拒绝新回执（409）。

---

## 5. 目录结构

```
pom.xml / Dockerfile / docker-compose.yml
scripts/replay.sh                 # 命令行一键验证（登录 + /api/replays/full + 断言）
src/main/resources/
  application.yml                 # DB 环境变量、TTL、候选网格配置
  schema.sql                      # 表/索引（幂等）；触发器由 Java 下发
  static/                         # 单页前端 index.html / app.js / styles.css
src/main/java/com/port/berth/
  PortBerthApplication.java
  config/                         # 鉴权拦截器、CORS、OpenAPI、PG 触发器初始化
  domain/                         # 枚举、Catalog 记录、Eval、ConstraintMath、Laytime（滞期模型）
  repo/                           # CatalogDao / PlanDao / LaytimeDao（JdbcTemplate + 行映射）
  service/                        # PlanningEngine / PlanService / HoldSweeper
                                  # ExecutionService / DisruptionService / ReplanService
                                  # ReplayService / SeedService / AuthService
                                  # LaytimeEngine（滞期纯函数引擎）/ LaytimeService
  web/                            # REST 控制器、全局异常处理（含 LaytimeController）
src/test/java/.../                # 10 个纯单元测试（约束数学 + 样例唯一窗口 + 滞期引擎规格）
```

---

## 6. 一键启动

### 6.1 Docker Compose（推荐，干净环境）

```bash
cd /workspace/92
docker compose up --build
```

- 应用：<http://localhost:8080>（单页操作界面）
- Swagger UI：<http://localhost:8080/swagger-ui.html>，OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- PostgreSQL 18：容器内 `db:5432`，宿主机映射 **5433**（避免与本机 5432 冲突），
  库/用户/密码 `berth/berth/berth123`，数据卷 `pgdata`
- 镜像构建内已配置 `docker/settings-docker.xml` 走阿里云 Maven 源

> 本任务容器内没有安装 Docker CLI/守护进程，因此镜像构建与 `docker compose up` 未在本会话实际执行；
> `docker-compose.yml` 已通过 YAML 解析校验，Dockerfile 为标准多阶段构建。应用本身已用等价方式
> （JDK 25 + 可执行 jar + 真实 PostgreSQL 18）完整验证，见第 8 节。

### 6.2 本地开发（使用任务容器预装运行时）

PostgreSQL 18 已在 `127.0.0.1:5432` 运行，开发库/账号 `ccdev/ccdev`：

```bash
# 连通性检查
/opt/postgresql-18/bin/psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" -c 'select 1'

# 若数据库进程未运行，可尝试：
# /opt/postgresql-18/bin/pg_ctl -D /tmp/cc-services/postgres -l /tmp/pg.log start

cd /workspace/92
export JAVA_HOME=/opt/jdk-25
mvn spring-boot:run
# 或：mvn package && /opt/jdk-25/bin/java -jar target/berth-coordination.jar
```

零额外配置即可启动：默认连接 `jdbc:postgresql://127.0.0.1:5432/ccdev`（`ccdev/ccdev`），
首次启动自动执行 `schema.sql`、创建两个 PL/pgSQL 触发器并播种样例数据（幂等）。
也可用环境变量覆盖：`DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/PORT/HOLD_ON_STAGE/HOLD_TTL_SECONDS`。

---

## 7. 测试账号

| 用户名 | 密码 | 角色 | 能做什么 |
| --- | --- | --- | --- |
| `agent` | `agent123` | AGENT 船代 | 提交船次、补报 ETA、撤回授权 |
| `dispatch` | `dispatch123` | DISPATCHER 调度 | 生成候选、暂存/确认/取消、扰动录入、改靠/重排、回放、重置 |
| `pilot` | `pilot123` | TEAM 引航班组 | 看板、开工/完成/登记作业 |
| `tug` | `tug123` | TEAM 拖轮班组 | 同上 |
| `crane` | `crane123` | TEAM 岸桥班组 | 同上（含部分装卸量登记） |
| `admin` | `admin123` | ADMIN | 全部权限 |

登录：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatch","password":"dispatch123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
```

除 `POST /api/auth/login` 与 `GET /api/meta` 外，`/api/**` 都需要
`Authorization: Bearer <token>`；角色不足返回 403。Swagger 中点 **Authorize** 填 token。

---

## 8. 本题验证方式与实际验证结论

### 8.1 自动化验证（本会话实际执行）

```bash
mvn test
# Tests run: 10, Failures: 0, Errors: 0, Skipped: 0  → BUILD SUCCESS
# （含 LaytimeEngineTest 6 条：定稿 180min/3000 元、12:00 暂计 60min/1000 元、
#   迟到回执更正区段、计划预告不作凭证、船方单方主张不暂停、迟到 30 分钟标记）

mvn package
# 生成 target/berth-coordination.jar（约 28 MB，Spring Boot fat jar）
```

```bash
# 启动 jar 后执行（默认 http://127.0.0.1:8080）
bash scripts/replay.sh
# ==> 断言：66/66 通过；allPassed=True
# ALL_REPLAY_CHECKS_PASSED
```

该脚本真实登录、调用 `POST /api/replays/full`（约 12 秒，含真实 2s TTL 等待、
500ms 扫描释放与双线程并发确认竞速），递归统计全部断言并以退出码反映结果。

此外实际执行并通过的验证：

- **全新空库验证**：另建空数据库、以 `DB_NAME=... PORT=8081 java -jar` 启动，
  建表/触发器/播种一次成功，回放同样全部通过（验证了 Compose 首次启动的初始化路径）。
- **HTTP 权限/业务冒烟 8/8**：匿名 401、公开 meta、船代提交船次、船代暂存 403、
  VOY-E ETA 补报状态变 `ETA_UPDATED`、TEAM 看板 200、错误密码 400、OpenAPI 200。
- **滞期争议 HTTP 冒烟（本迭代实际执行）**：
  `GET .../laytime?asOf=12:00` → 暂计 60 分钟 / 1000.00 元且区段不越过 12:00；
  `asOf=13:00` → 11:00–12:30 已更正为 CRANE_FAULT、暂计 120 分钟 / 2000.00 元；
  最新 → 定稿 180 分钟 / 3000.00 元、可计时区间连续 11:00–14:00；
  `mode=AT_COMPLETION` 在 12:00 返回 `settled=false, amount=null`、完工后 3000.00 元；
  版本链 8 版暂计额 0→66.67→1516.67→2000→3000 逐版累计；
  定稿后新回执 409、船代定稿 403、匿名查询 401；
  船代提交的天气主张自动标记“未确认”且不暂停计时；
  `POST /api/replays/laytime` 独立回放 20/20 通过。
- 前端 `node --check app.js` 通过；所有按钮处理函数均有定义；`/`、`/app.js`、`/styles.css`、
  `/swagger-ui/index.html` 均实际返回 200；OpenAPI JSON 含 7 个滞期端点。
- 回放中包含**数据库层自证不可变**：对 `work_record` 与 `laytime_event` 直接
  `UPDATE/DELETE` 均被触发器拒绝。

### 8.2 界面手工验证路径

1. 打开 <http://localhost:8080>，用 `dispatch/dispatch123` 登录（登录页可点账号芯片填充）。
2. **② 调度工作台** → 选 `VOY-A` → “生成/刷新候选计划”：可见唯一绿色可行卡
   `B1 20:00`，分配 `PILOT-1/TUG-1/QC-1/QC-2`；其余红色卡给出 UKC/值班等原因。
   - 设 TTL=2 秒点“暂存并暂留资源”，卡片变 `STAGED` 并显示到期时间；不确认，数秒后刷新可见 `EXPIRED`。
   - 对 `VOY-B` 生成候选，可见 20:00 卡被 A 的暂留/确认阻挡及结构化等待原因。
   - 可试“仅暂存不占资源”，再让 B 先确认，最后 A “直接原子确认”会收到 409。
3. **③ 班组看板**：只有 `CONFIRMED` 方案出现；可开工、登记部分作业量、完成分段。
4. **④ 模拟与扰动**：修订 21:00 潮位为 `2.20`、插入 QC-2 故障窗口、查看影响项；
   回到调度页对确认方案“扰动后重排”，或用确认卡上的“改靠/改时”。
5. **⑤ 滞期争议**：选 `VOY-L`，顶部为合同条款与金额大数字；点 **12:00 争议中** 回放按钮，
   可见暂计 1000 元、11:00 起黄色“停工待证”区段；点 **13:00 修复回执**，可见该区段被更正为
   橙色“岸桥故障·仍计时”并出现在“被更正区段”列表；点 **14:00 完工** 或“最新”，
   可见定稿 3000 元与连续 11:00–14:00 可计时区间；切换“完工收齐再结算”口径，
   12:00 时无金额。下方为双方回执表（迟到红色标记）、账单版本链（可逐版回看）、
   回执登记表单与“定稿锁定”按钮。
6. **⑥ 一键回放验证**：四个按钮分别运行顺序集/扰动集/滞期争议集/完整集，页面渲染每条断言与完整 JSON。
7. **① 船代申报**：可新建船次、对 VOY-E 把 ETA 18–19 补报为 19–20、撤回授权。

---

## 9. 一键回放到底核对了什么

`POST /api/replays/orderings`、`/api/replays/disruptions`、`/api/replays/laytime`、`/api/replays/full`
（DISPATCHER/ADMIN）每次先重置固定样例，再输出带断言与完整时间线的报告：

| 回放块 | 核对点（全部 PASS） |
| --- | --- |
| **顺序1：A 暂存后未确认** | A 的 B1 20:00 候选可行且选到 P1/T1/QC1/QC2；A `STAGED`；B 同时点不可行且原因含 `*_HELD_TENTATIVE`；B 强确认 409；TTL 到期 A `EXPIRED`、≥5 条占用全 `RELEASED`、扫描延迟 <3s；A 确认数 0；释放后 B 可行并成为唯一 `CONFIRMED` |
| **顺序2：A 暂留期内确认** | A `CONFIRMED` 且唯一；B 被 `*_BOOKED_FIRM` 阻挡；B 暂存/确认均 409；B 得到等待 >120m 的次日备选；B 确认数 0 |
| **顺序3：B 先确认** | B 直接原子确认成功；A 候选显示 FIRM 阻挡；A 暂存、最后一步确认均 409；确认计数 B=1/A=0 |
| **并发竞速** | 两线程同时确认 A/B 同一组合：恰好 1 个成功，库中 A/B 合计仅 1 个 `CONFIRMED`（触发器+资源锁行兜底） |
| **策略对照** | `holdResources=false` 时暂存零占用；B 先确认；A 事后确认 409（“最后一步失效”），并附两种策略权衡说明 |
| **潮汐修订** | 先写 1 条引航完成记录 + 1 条 15 分钟装卸台账；21:00 潮位 3.2→2.2 后分析出指向装卸段的 `UKC_VIOLATION`；重排模式 `RESCHEDULE`、次日 08:00 新方案 `CONFIRMED`、只排剩余 45m；2 条记录保留；DB 阻止 UPDATE/DELETE |
| **岸桥故障** | QC-2 20:45–22:30 故障生成 `CRANE_FAULT`；重排为 `SAME_SLOT_REASSIGN`，新分配含 QC-3 不含 QC-2；引航记录保留 |
| **改靠指令** | 对 VOY-D 下达改靠 B2↔B3：新版本确认且泊位变化；≥4 条 `BERTH_CHANGE` 逐段影响说明 |
| **ETA 迟到** | ETA 最早时间晚于计划靠泊点时生成 ≥4 条 `ETA_LATE`（引航/拖轮/靠泊/装卸），随后重排出 `CONFIRMED v2` |
| **滞期争议** | 合同免计 120′/费率 1000 元每小时；回放 12:00 为暂计 60′/1000.00 元且区段不越过 12:00、11:00 起为待证；回放 13:00 时 11:00–12:30 已更正为 `CRANE_FAULT`、暂计 120′/2000.00 元；定稿 `FINAL` 180′/3000.00 元、可计时区间连续 11:00–14:00；被更正区段留痕（`PENDING_EVIDENCE→CRANE_FAULT`，回执 PORT-QC-REP-207）；船方天气主张 `REFUTED`；被更正区段附船方+港方双方证据；计划保养预告未参与区段分类；完工结算口径 12:00 无金额、完工后同额；修复回执迟到 30′；8 条回执生成 8 版账单；DB 阻止 UPDATE/DELETE 回执 |

---

## 10. 主要 HTTP API

| 方法/路径 | 角色 | 说明 |
| --- | --- | --- |
| `POST /api/auth/login` | 公开 | 登录拿 token |
| `GET /api/meta` | 公开 | 船舶/泊位/潮汐/人员/设备/故障/演示账号 |
| `GET/POST /api/voyages` | 登录 / AGENT+ | 船次列表/提交 |
| `GET /api/voyages/{id}` | 登录 | 船次 + 全部方案详情 + 记录 + 事件 |
| `PATCH /api/voyages/{id}/eta` | AGENT+ | 补报 ETA；若有确认方案同时做 ETA 迟到影响分析 |
| `POST /api/voyages/{id}/withdraw` | AGENT+ | 撤回授权并释放该船全部资源 |
| `POST /api/voyages/{id}/candidates/generate` | DISPATCHER+ | 枚举候选并落库 |
| `GET /api/plans`、`GET /api/plans/{id}` | 登录 | 方案/分段/原因/分配 |
| `POST /api/plans/{id}/stage` | DISPATCHER+ | body：`{"holdResources":true,"holdTtlSeconds":120}` |
| `POST /api/plans/{id}/confirm` | DISPATCHER+ | 事务内重校 + 原子锁定 FIRM |
| `POST /api/plans/{id}/cancel` | DISPATCHER+ | 取消暂存并释放 |
| `POST /api/segments/{id}/start|complete|log` | TEAM/DISPATCHER | 开工/完成/部分作业台账 |
| `GET /api/teams/board` | 登录 | 仅 CONFIRMED 方案的引航/拖轮/岸桥/泊位看板 |
| `PUT /api/simulation/tides/{slotId}` | DISPATCHER+ | 修订潮汐并分析 |
| `POST /api/simulation/crane-faults` | DISPATCHER+ | 插入设备故障窗口并分析 |
| `POST /api/plans/{id}/replan` | DISPATCHER+ | 扰动重排（默认自动确认） |
| `POST /api/plans/{id}/change-berth` | DISPATCHER+ | 改靠/改时指令 |
| `GET /api/disruptions[/{id}]` | 登录 | 扰动与影响项 |
| `GET /api/voyages/{id}/laytime/contract` | 登录 | 滞期合同条款（免计/费率/例外规则） |
| `GET/POST /api/voyages/{id}/laytime/events` | 登录 / 全角色 | 回执列表 / 登记回执（提交方按角色：船代→船方，其余→港方） |
| `GET /api/voyages/{id}/laytime?asOf=&mode=` | 登录 | 任一时刻账单回放；`mode=STREAMING/AT_COMPLETION` |
| `GET /api/voyages/{id}/laytime/statements` | 登录 | 账单版本链（暂计额逐版累计） |
| `GET /api/voyages/{id}/laytime/corrections` | 登录 | 被更正区段留痕 |
| `POST /api/voyages/{id}/laytime/finalize` | DISPATCHER+ | 完工定稿锁定（之后新回执 409） |
| `POST /api/replays/orderings|disruptions|laytime|full` | DISPATCHER+ | 一键回放 |
| `POST /api/admin/reset` | DISPATCHER+ | 清空并恢复固定样例 |

---

## 11. 配置与运维说明

- `app.planning.hold-on-stage`、`app.planning.default-hold-ttl-seconds`、
  `app.planning.candidate-step-minutes`、`app.planning.max-wait-minutes` 见 `application.yml`，
  均可用同名环境变量覆盖（`HOLD_ON_STAGE`、`HOLD_TTL_SECONDS` …）。
- `HoldSweeper` 每 500ms 扫描 `STAGED` 且到期的方案，释放其 `TENTATIVE` 占用并写 `HOLD_EXPIRED` 事件。
- `ccdev/ccdev` 是任务容器的隔离开发账号，**不要直接作为生产配置**；Compose 使用独立的 `berth` 库与密码。
- 服务不依赖 Redis/港口实机/第三方账号；外部潮汐与设备状态全部来自库内模拟表和模拟录入接口。
