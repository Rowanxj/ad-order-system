# 测试记录

> 最新一轮验收：2026-11-06，66 条 HTTP 用例全绿、80 条单元测试全绿，见 §十一。

## 一、接口级验收（契约驱动，31 项断言）

用例全部写在 `docs-framework/contracts/acceptance.json`，由 `check-api.ps1` 按契约顺序执行（需先导入 sql/ 两个脚本并启动后端）：

```powershell
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-api.ps1
```

最近一次执行结果：**PASS=31 FAIL=0**（2026-11-04，详见 §五.2）

覆盖场景与结论：

| 分类 | 场景 | 结果 |
| --- | --- | --- |
| 正常链路 | 客户经理创建含 2 项物料的订单 → 负责人确认 → 分派 → 设计人员开始/上传版本 → 客户经理记录意见 → 新版本 → 客户确认 → （处理完其余任务后）交付 | 全部通过 |
| 状态机 | 未确认订单不能交付(409)；重复确认(409)；非待确认任务不能确认(409)；已确认任务不能改截止(409)；存在未确认任务时整单不能交付(409，提示剩余数量) | 全部通过 |
| 变更留痕 | 确认后新增物料任务；确认后修改整体/物料截止日期 → 均写入 operation_log（原值→新值） | 全部通过 |
| 权限 | 设计人员确认订单(403)；设计人员执行客户确认(403)；designer2 操作他人任务(403)；designer1 读无关订单/任务(403)；无 token(401) | 全部通过 |
| 数据归属 | 设计人员订单列表/详情仅返回含本人任务的订单 | 全部通过 |
| 文件 | 设计稿上传（multipart）与鉴权下载（带 token，HTTP 200） | 全部通过 |

## 二、浏览器页面验证（Chrome，2026-10-23）

| 页面 | 验证内容 | 结果 |
| --- | --- | --- |
| /login | 表单渲染、演示账号快捷填充、登录跳转 | 通过 |
| /orders | 3 笔演示订单列表、状态标签、物料进度（0/2、0/3、1/1）、筛选区、角色菜单 | 通过 |
| /orders/1 | 订单头（编号/标题/状态）、客户经理操作按钮、需求说明、任务表、操作历史时间线 | 通过 |

任务详情页/工作台/客户管理页使用与已验证页面相同的组件与接口封装，接口行为已由第一部分 31 项断言覆盖。

## 三、开发过程中发现并修复的缺陷

| # | 现象 | 原因 | 修复 |
| --- | --- | --- | --- |
| 1 | 后端编译失败：找不到 RequestDtos | OrderService 缺少 import | 补充 import 后重新构建通过 |
| 2 | 上传文件保存逻辑异常 | TaskService.store() 路径拼接错误 | 改为 uploadDir.resolve(stored) 单一来源拼接 |
| 3 | 测试脚本全部 401 | PowerShell 5.1 向 curl.exe 传参吞掉 JSON 双引号 | 测试脚本统一转义（属测试工具问题，非系统缺陷） |

## 四、回归方法

任何修改后：重跑 `check-contract.ps1`（静态）与 `check-api.ps1`（运行时），再手工在浏览器以 4 个演示账号各登录一次，核对菜单与操作按钮随角色变化。具体命令见 §五.5。

## 五、2026-11-04 契约驱动优化轮

本轮引入项目级 skill `ad-order-spec-driven`，先补文档与契约，再修缺陷，最后用两套门禁验证。

### 5.1 静态契约对账（check-contract.ps1 -Stage delivery -Strict）

| 门禁 | 内容 | 结论 |
| --- | --- | --- |
| G0 | 现状盘点（7 表 / 22 接口 / 7 实体 / 7 页面） | PASS |
| G1 | 文档与契约齐备（01~04 + flow + 4 份 JSON，无占位符） | PASS |
| G2 | DDL ↔ 实体 ↔ Mapper 列一致 | PASS |
| G3 | 接口文档 ↔ Controller ↔ 前端 api 层三方一致 | PASS |
| G4 | 权限矩阵 ↔ 注解 ↔ 服务层归属校验一致 | PASS |
| G5 | 状态机契约 ↔ 后端常量 ↔ 前端状态映射一致 | PASS |
| G6 | 留痕动作码注册齐全、写状态必留痕 | PASS |
| G7 | 禁项扫描（alert/console.log/TODO/SELECT */明文密钥） | PASS |

合计 **FAIL=0 WARN=0**，退出码 0。相比优化前：G1 由 WARN 转 PASS（补齐 5 份文档 + 4 个 flow），G7 由 7 条警告降为 0。

### 5.2 运行时契约验收（check-api.ps1）

在隔离库 `ad_order_check`（由 `sql/schema.sql` + `sql/data.sql` 重建）上跑 31 条真实 HTTP 断言：

**PASS=31 FAIL=0 SKIP=0**，退出码 0。覆盖主链路、9 类状态冲突、5 类越权、未登录、文件上传下载、留痕断言。

### 5.3 本轮修复的缺陷与隐患

| # | 问题 | 位置 | 处理 |
| --- | --- | --- | --- |
| 1 | 上传文件在 `@Transactional` 内做磁盘 IO，事务回滚会留孤儿文件，慢盘还会拉长事务 | `TaskService.uploadVersion` | 落盘移到事务外，事务只包「版本入库 + 状态推进 + 留痕」，失败补偿删除文件 |
| 2 | 订单编号 `COUNT+1` 在并发下重复 | `OrderService.create` | 唯一索引 `uk_order_no` 兜底 + 换号重试 3 次 |
| 3 | 设计版本号 `MAX+1` 在并发下重复 | `TaskService.uploadVersion` | 新增唯一索引 `uk_task_version` + 重试 3 次 |
| 4 | 密码为单轮 SHA-256，易离线爆破 | `PasswordUtil` | 改为 PBKDF2-HMAC-SHA256（12 万次迭代 + 随机盐 + 恒定时间比较），旧哈希登录成功后自动升级 |
| 5 | 数据库口令与 JWT 密钥硬编码 | `application.yml` | 改为 `${DB_PASSWORD:默认}` 等环境变量占位 |
| 6 | Mapper 使用 `SELECT *`，字段变更会静默失配 | 5 个 mapper XML | 全部改为显式列名 |

### 5.4 升级验证

- 登录前库内 `sys_user.password` 为 64 位十六进制（历史 SHA-256）；
- 跑完验收后复查，4 个账号全部变为 `pbkdf2$120000$…`，且旧密码 `123456` 仍可登录 →
  证明「兼容旧格式 + 登录时透明升级」生效，无需清库。

### 5.5 回归方法（更新）

```powershell
# 静态对账（不需要数据库）
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1 -Stage delivery -Strict

# 运行时验收（需要后端与 MySQL，且只对开发库执行——用例会真实建单）
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-api.ps1

# 界面回归（脚本无法判定）：4 个账号各登录一次，核对菜单/按钮/状态标签
```

## 六、2026-11-04 第二轮：正确性与界面

### 6.1 后端正确性

| # | 问题 | 处理 | 证据 |
| --- | --- | --- | --- |
| 1 | 状态迁移是"先查后写"，两个请求同时推进时后写覆盖（丢失更新） | 全部改为**条件更新** `UPDATE ... WHERE id=? AND status=#{from}`，影响行数为 0 → 409 | C07/C16/C22/C26 仍为 409 |
| 2 | 分派只校验了"查到的是 PENDING"，写入时不再校验 | `updateAssignee` 的 SQL 自带 `AND status='PENDING'` | C10–C12 |
| 3 | **订单取消/交付后，其任务仍可开始、上传、确认、改期**（脏数据） | 新增 `requireActiveOrder`，六类任务操作统一前置守卫 | C41、C42 |
| 4 | 订单列表无分页，数据量一大就全表返回 | 新增 `page`/`size`，返回 `{list,total,page,size}`；列表与计数共用 where 片段 | C31–C33 |
| 5 | 修改意见轮次 `MAX+1` 并发重复 | 新增 `uk_task_round` 唯一索引 + 重试 | 索引已建（information_schema 校验 2 列） |
| 6 | DTO 缺 `@Size`/`@Min`，超长文本或数量 0 会穿透到数据库变 500 | 校验规则与 DDL 列长度逐一对齐，物料类型加白名单 `@Pattern` | C34、C35 |
| 7 | 路径参数类型错误（`/orders/abc`）落到兜底异常 → 500 | 新增 400 处理器（类型不匹配 / JSON 不合法 / 数据约束） | C36 |

分页实测（`check-api.ps1` 之外的抽查）：

| 账号 | 请求 | 结果 |
| --- | --- | --- |
| sales | `/orders?page=1&size=2` | total=5，list 长度 2 |
| sales | `/orders?page=2&size=2` | 返回第 2 页，与第 1 页不重叠 |
| designer1 | `/orders?page=1&size=10` | total=3，且只含本人任务的 3 笔订单（计数与列表口径一致） |
| sales | `/orders?page=1&size=999` | 生效 size=100（截断而非报错） |
| sales | `/orders/abc` | code=400，message=「参数格式不正确：id」 |

### 6.2 运行时验收

用例从 31 条扩到 **43 条**（新增分页 3 条、入参校验 3 条、订单结束守卫 6 条）：

**PASS=43 FAIL=0 SKIP=0**，退出码 0。

### 6.3 界面

前端做了视觉与信息层级重构（设计令牌 + 页面结构统一 + 组件抽取 + 分页器）。

**人工回归中发现并修复的两个前端缺陷**（后端一直是对的，验收用例也一直是绿的——说明只有人点才能发现）：

| # | 问题 | 根因 | 处理 |
| --- | --- | --- | --- |
| 1 | 「订单交付」按钮看不到，后端"还有 N 项未确认"的 409 永远触发不了 | 按钮条件写成 `status==='IN_PROGRESS' && tasks.every(CONFIRMED)`，等于前端提前把不该交付的情况藏了 | 只要 `IN_PROGRESS` 就显示按钮；旁边提示"N 项未确认"；点击仍由后端判定并返回 409 |
| 2 | 界面上**没有分派入口**，负责人无法分派 → 设计人员收不到任务 → 整单无法交付 | `api.assignTask` 已定义但没有任何页面调用它（PRD 要求有） | 订单详情任务表操作列新增「分派/改派」（仅负责人、仅"待分派"状态），弹窗选设计人员 |

另外发现「下载设计稿」在生产构建时直接失败：`TaskDetailView` 从 `@/api` 取 `downloadFile`，而 `api/index.js` 没有导出它。已在 `api/index.js` 统一导出。

`OrderDetailView` 已按新规范重做（PageHeader + 卡片 + StatusTag），可作为改造 `TaskDetailView` / `OrderFormView` 的范本。

静态门禁 G1–G7 仍为 **FAIL=0 WARN=0**；生产构建 `✓ built in 4.58s`。

仍需人工确认：4 个账号菜单/按钮随角色变化、分页器切换、筛选后回到第 1 页、窄屏侧边栏折叠、**交付按钮在未确认时点击应弹出未确认数量**。

## 七、2026-11-04 第三轮：账号体系与意见传递

### 7.1 新增能力

| 类别 | 内容 |
| --- | --- |
| 注册 | 登录页自助注册，**只允许注册为设计制作人员**，状态 `2`（待启用），负责人启用后才能登录 |
| 账号管理 | 负责人「账号管理」页：列表、新增（可指定角色）、启用/停用（＝注销）、重置密码 |
| 个人设置 | 顶栏「修改密码」，需验证原密码 |
| 意见传递 | 「我的任务」卡片显示客户意见轮次与最新摘要；记录意见时提供 5 条常用意见模板 |

接口由 22 个增至 **28 个**，`permissions.json` 与 `04_API.md` 同步更新。

### 7.2 手工验证（不进自动化套件：用例会建账号，非幂等）

在隔离库上逐条执行：

| # | 场景 | 结果 |
| --- | --- | --- |
| 1 | 注册 designer3 | code=0，status=2 |
| 2 | 重复注册同名账号 | code=400「该账号已被占用」 |
| 3 | 用待启用账号登录 | code=403「账号待负责人启用」 |
| 4 | 负责人查看账号列表 | code=0，5 条，**密码字段为空** |
| 5 | 负责人启用该账号 | code=0 |
| 6 | 启用后登录 | code=0，role=DESIGNER |
| 7 | 客户经理访问 `/api/users` | code=403「当前角色无权执行此操作」 |
| 8 | 负责人新增 SALES 账号 | code=0，role=SALES |
| 9 | 修改本人密码 | code=0 |
| 10 | 用新密码登录 | code=0 |

### 7.3 回归

- `check-contract.ps1 -Stage delivery -Strict`：**FAIL=0 WARN=0**
- `check-api.ps1`：**PASS=43 FAIL=0**
- 前端：13 个 Vue 文件语法通过，生产构建 `✓ built in 4.07s`

## 八、2026-11-05 第四轮：单元测试补位

此前只有 `check-api.ps1` 的 HTTP 集成验收（43 条），缺少不启动 Spring、不连数据库的毫秒级逻辑测试。本轮补上 JUnit 5 单元测试，作为集成验收的补充而非替代。

### 8.1 用例与结果

```powershell
cd backend
mvn -s ..\maven-settings.xml test
```

**Tests run: 35, Failures: 0, Errors: 0, Skipped: 0**（构建总耗时约 7 秒）

静态门禁复跑：`check-contract.ps1 -Stage delivery -Strict` → **FAIL=0 WARN=0**（2026-11-05）。

| 测试类 | 用例数 | 耗时 | 覆盖范围 |
| --- | --- | --- | --- |
| `PasswordUtilTest` | 4 | 0.35s | PBKDF2 随机盐与正确/错误口令；历史 SHA-256 兼容与 `needsUpgrade` 标记；空口令/空摘要/畸形摘要 |
| `TaskServiceTest` | 31 | 1.16s | 任务状态机的状态前置条件、设计人员归属、订单结束守卫、条件更新冲突、上传事务补偿、版本/意见并发重试、留痕动作、路径穿越 |

### 8.2 `TaskServiceTest` 重点断言

| 规则 | 断言要点 |
| --- | --- |
| 状态机前置条件 | 分派/开始/上传/记录意见/确认/改期分别只接受契约允许的 `from` 状态；越级一律 409 |
| 设计人员归属 | 非本人任务查看/开始返回 403，且不触发任何写操作 |
| 订单结束守卫 | 已交付/已取消订单的任务，分派、开始、上传、记录意见、确认、改期全部 409 |
| 条件更新 | `updateStatus` / `updateAssignee` 影响行数为 0 时按并发冲突返回 409，不写留痕 |
| 上传事务边界 | 版本入库 + 状态推进 + 留痕失败时，补偿删除已落盘文件，临时目录内无孤儿文件 |
| 并发重试 | `uk_task_version` 撞号后换版本号重试；`uk_task_round` 撞轮次后换轮次重试 |
| 留痕 | `TASK_ASSIGNED`、`TASK_STARTED`、`VERSION_UPLOADED`、`FEEDBACK_RECORDED`、`TASK_CONFIRMED`、`TASK_DEADLINE_CHANGED` 动作码与操作人正确 |
| 文件安全 | `resolve("../secret.pdf")` 返回 400，不越出上传根目录 |

### 8.3 边界说明

- 单元测试全部使用 Mockito 模拟 Mapper 与 `OrderService`，事务模板由模拟的 `PlatformTransactionManager` 驱动；**不验证真实 SQL、数据库约束、HTTP 状态码**，这些仍由 `check-api.ps1` 的 43 条用例覆盖。
- 测试不依赖 MySQL 或 `application.yml` 中的数据源，可在无数据库环境下执行，适合提交前快速回归。
- 首次运行若本地仓库缺少 `surefire-junit-platform`，Maven 会从 `maven-settings.xml` 配置的阿里云镜像补下载。

## 九、2026-11-05 第五轮：任务工作台

### 9.1 交付内容

| 层 | 内容 |
| --- | --- |
| 契约 | `GET /api/tasks` 登记进 `permissions.json`（仅 `MANAGER`）与 `04_API.md`，新增 10 条验收用例 C50–C59 |
| 后端 | `MaterialTaskMapper.selectWorkbench/countWorkbench`（列表与计数共用 where）、`TaskService.list`（分页归一化 + 关键词 trim）、`ApiController#listTasks` |
| 筛选 | 状态、分派人、订单、关键词（任务名/订单号/标题/客户名）、`overdue=true`、`unassigned=true` |
| 前端 | `/tasks` 任务工作台页面（负责人菜单），筛选、分页、详情跳转、分派/改派弹窗 |
| 口径 | 逾期 = 任务未确认 + 订单未结束 + 截止日期早于今天；未分派 = `assignee_id IS NULL` |

### 9.2 验证结果

| 项 | 命令 / 环境 | 结果 |
| --- | --- | --- |
| 单元测试 | `mvn -s ..\maven-settings.xml test` | **37/37**（`TaskServiceTest` 33 + `PasswordUtilTest` 4） |
| 静态门禁 | `check-contract.ps1 -Stage delivery -Strict` | **FAIL=0 WARN=0**，退出码 0 |
| 接口验收 | 隔离库 `ad_order_check`，后端端口 8081，`check-api.ps1 -BaseUrl http://localhost:8081/api` | **PASS=60 FAIL=0 SKIP=0**，退出码 0 |
| 前端构建 | `npm run build` | **✓ built in 4.26s**，产物含 `TaskWorkbenchView` chunk |
| 人工页面 | manager 登录 `/tasks`，筛选、分页、分派入口正常 | 通过（用户确认，2026-11-05） |

### 9.3 新增验收用例

> P3 起 C50–C57 的测试夹具名改为 `FILTER_TASK_A/B`，C53 改用演示库里的逾期任务；最新结果见 §十一。

| 用例 | 场景 | 结论 |
| --- | --- | --- |
| C50 | 客户经理创建任务工作台基线订单（2 项任务，含逾期） | PASS |
| C51 | 取出基线订单的两个任务 ID | PASS |
| C52 | 负责人把 `ASSIGNED_TASK` 分派给 designer1 | PASS |
| C53 | 任务工作台按逾期筛选 | PASS |
| C54 | 任务工作台按未分派筛选 | PASS |
| C55 | 任务工作台按订单筛选（该订单 2 项任务） | PASS |
| C56 | 任务工作台按状态与分派人筛选 | PASS |
| C57 | 任务工作台关键词筛选（1 条） | PASS |
| C58 | 任务工作台分页：第 1 页每页 2 条 | PASS |
| C59 | 设计人员不能访问任务工作台(403) | PASS |

### 9.4 边界与待人工确认

- 分页单测覆盖参数归一化与筛选透传；真实 SQL、MySQL 分页与权限拦截由 C53–C59 覆盖。
- `TaskWorkbenchView.vue` 已由用户人工确认可正常打开与操作（2026-11-05）；窄屏与另外 3 个角色的回归仍建议在答辩前补一轮。
- 任务工作台只对负责人开放；客户经理仍从订单详情进入任务，设计人员继续使用 `/my-tasks`。
- 重跑 `check-api.ps1` 前需重建隔离库 `ad_order_check`：C50/C57 依赖固定任务名与数量，在开发库上重复执行会积累同名数据。

## 十、2026-11-05 第六轮：订单与账号单测

### 10.1 结果

```powershell
cd backend
mvn -s ..\maven-settings.xml test
```

**Tests run: 72, Failures: 0, Errors: 0, Skipped: 0**（构建约 7.8 秒）

| 测试类 | 用例数 | 耗时 | 覆盖范围 |
| --- | --- | --- | --- |
| `OrderServiceTest` | 26 | 0.96s | 创建校验、订单号撞号重试、分页参数、详情归属、确认/取消/交付守卫、改期与新增物料留痕、404 |
| `UserServiceTest` | 9 | 0.04s | 账号列表脱敏、用户名查重、创建时 trim/加密/默认启用、自我停用保护、最后负责人保护、重置密码 |
| `TaskServiceTest` | 33 | 0.32s | 任务状态机、归属、订单结束守卫、上传补偿、版本/意见并发重试 |
| `PasswordUtilTest` | 4 | 0.38s | PBKDF2、历史 SHA-256 兼容与升级标记 |

### 10.2 `OrderServiceTest` 重点断言

| 规则 | 断言要点 |
| --- | --- |
| 创建订单 | 空物料 400；物料类型白名单在服务层再校验一次 |
| 订单编号 | `AOyyyyMMdd0001`；撞 `uk_order_no` 后换号重试到 `0002` |
| 分页 | `page=0` 归一到 1，`size=999` 截断到 100，查看者 id/role 透传给 SQL |
| 详情归属 | 设计人员对无关订单 403，且不再查任务列表 |
| 确认/取消/交付 | 前置状态不对 409；条件更新 0 行 409；成功才写留痕 |
| 交付守卫 | 有未确认任务时 409，提示剩余数量 |
| 改期/加料 | 待确认订单只改数据；进行中订单写「原值 → 新值」或 `TASK_ADDED` |
| 留痕兜底 | `bizId` 为空时回退到 `orderId`，操作人姓名/角色写入 |

### 10.3 `UserServiceTest` 重点断言

| 规则 | 断言要点 |
| --- | --- |
| 列表脱敏 | 返回前把 `password` 置空，不把摘要带出服务层 |
| 创建账号 | 用户名 trim 后查重；密码摘要后入库；状态默认 1（启用）；返回前清空密码 |
| 启停保护 | 不能停用自己；目标不存在 404；不能停用唯一启用的负责人 |
| 重置密码 | 目标不存在 404；先摘要再 `updatePassword`，不保存明文 |

### 10.4 边界说明

- 这批测试仍然全部是 Mockito 单元测试，不启动 Spring、不连 MySQL；真实 SQL、HTTP 状态码与数据约束继续由 `check-api.ps1` 的 66 条用例覆盖。
- `UserService` 的账号变更不写 `operation_log`（订单时间线不属于账号），因此测试只验证 Mapper 调用与密码摘要，不验证订单留痕。

## 十一、2026-11-06 第七轮：参考交接版本的安全、工作台与异常管理

### 11.1 从参考项目采纳的内容

| 类别 | 处理 |
| --- | --- |
| 安全收口 | `User.password` 加 `@JsonIgnore`；`/users/designers` 只返回启用设计人员的 `{id,username,realName,status}`；设计稿下载反查 `design_version.file_path`，设计人员只能下载本人任务的文件 |
| 配置 | `application.yml` 支持自动加载项目根目录/`backend` 下的 `.env`，`SERVER_PORT`、`JWT_EXPIRE_HOURS`、`UPLOAD_DIR`、`LOG_LEVEL` 真正生效；数据库连接失败返回可读提示 |
| 业务规则 | 整体/物料截止不能早于今天；物料不能晚于整单；整单改期不能早于最晚物料；确认前改期也写留痕，异常管理据此标记「已延期」 |
| 查询 | 订单列表支持 `deadlineFrom`/`deadlineTo`/`overdue`/`extended`；`/api/tasks` 对 SALES 开放，并支持 `extended`、返回 `orderDeadline` |
| 页面 | 新增 `/dashboard` 工作台与 `/exceptions` 异常管理；任务详情显示 `orderDeadline`，前端日期选择器同步禁用非法日期 |
| 构建 | Vite 按 `vendor-vue` / `vendor-element` / `vendor-http` 分包 |

### 11.2 验证结果

| 项 | 命令 / 环境 | 结果 |
| --- | --- | --- |
| 单元测试 | `mvn -s ..\maven-settings.xml test` | **80/80**（Order 31 + Task 35 + User 10 + Password 4） |
| 静态门禁 | `check-contract.ps1 -Stage delivery -Strict` | **FAIL=0 WARN=0** |
| 接口验收 | 隔离库 `ad_order_check`，8081，`check-api.ps1 -BaseUrl http://localhost:8081/api` | **PASS=66 FAIL=0 SKIP=0** |
| 前端构建 | `npm run build` | **✓ built in 4.05s**，产物含 Dashboard/Exception chunk |

### 11.3 新增/调整的验收用例

| 用例 | 场景 | 结论 |
| --- | --- | --- |
| C60 | 客户经理也能访问任务工作台（角色放宽） | PASS |
| C61 | 订单列表支持只看逾期 | PASS |
| C62 | 订单列表支持只看已延期 | PASS |
| C63 | 整体截止日期早于今天被拒绝(400) | PASS |
| C64 | 物料截止晚于整体截止被拒绝(400) | PASS |
| C65 | 整体截止不能早于最晚物料截止(400) | PASS |

### 11.4 边界与未采纳项

- 参考项目里的「取消单项物料任务」「客户确认版本号 `confirmed_version_no`」涉及新增任务状态/改表，本轮未采纳，避免在答辩前动状态机与 DDL。
- `/dashboard` 与 `/exceptions` 已通过构建和接口准备，但页面视觉仍建议用 manager/sales 各点一遍；`ad_order_check` 重跑前需重建，避免 C50/C57 固定任务名累积。
