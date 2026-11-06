# 验收矩阵

把课程/需求里的每个验收点映射到「实现位置 + 证据用例 + 当前状态」。

- **实现位置**用 `文件:行`，行号对应 2026-11-04 第二轮优化后的代码；
- **证据用例**指向 `docs-framework/contracts/acceptance.json` 的 id，跑 `check-api.ps1` 即可复现；
- **状态**只有三种：`已验证`（当前跑过且绿）、`待验证`（用例存在但未跑）、`人工`（脚本无法判定，需人看）。

## 1. 状态机与业务规则

| 验收点 | 实现位置 | 证据 | 状态 |
|---|---|---|---|
| 只有负责人能确认订单 | `ApiController.java:109` + `AuthInterceptor.java:44` | C05、C06 | 已验证 |
| 重复确认订单返回 409 | `OrderService.java:136`（前置校验）+ `:143`（条件更新） | C07 | 已验证 |
| 未确认订单不能直接交付 | `OrderService.java:163` | C04 | 已验证 |
| 全部任务确认后才能交付 | `OrderService.java:163–171` | C22、C26 | 已验证 |
| 只有待确认订单能取消 | `OrderService.java:152–156` | 人工（无自动化用例） | 人工 |
| 已确认任务不能改期 | `TaskService.java:293` | C21 | 已验证 |
| 任务必须经「待客户确认」才能确认 | `TaskService.java:278` | C16 | 已验证 |
| 上传设计稿 ≠ 客户确认 | `TaskService.java:149`（条件更新到 `PENDING_CONFIRM`） | C15、C20 | 已验证 |
| 记录意见后任务转「修改中」 | `TaskService.java:240–260` | C17 | 已验证 |
| 修改中可再次上传后回到待确认 | `TaskService.java:218`（`requireUploadable`） | C18 | 已验证 |

## 2. 权限与数据归属

| 验收点 | 实现位置 | 证据 | 状态 |
|---|---|---|---|
| 未登录访问返回 401 | `AuthInterceptor.java:36` + `JwtUtil.java:52` | C01 | 已验证 |
| 设计人员不能确认订单 | `ApiController.java:109` | C05 | 已验证 |
| 设计人员不能记录客户确认 | `ApiController.java:196` | C19 | 已验证 |
| 设计人员不能操作他人任务 | `TaskService.java:317`（`requireAssignee`） | C13 | 已验证 |
| 设计人员不能读无关订单 | `OrderService.java:112`（`countByOrderAndAssignee`） | C28 | 已验证 |
| 订单列表对设计人员自动过滤 | `MaterialOrderMapper.xml`（`listFilter` 片段） | C03、C28 间接；列表过滤本身 | 人工 |
| 非设计人员不能分派 | `ApiController.java:164` | 人工 | 人工 |
| 任务工作台仅负责人可访问 | `ApiController.java:162`（`@RequireRole({"MANAGER"})`） | C59 | 已验证 |

## 3. 变更留痕

| 验收点 | 实现位置 | 证据 | 状态 |
|---|---|---|---|
| 创建订单留痕 | `OrderService.java:52` | C02 | 已验证 |
| 确认订单留痕 | `OrderService.java:136` | C06 | 已验证 |
| 交付留痕 | `OrderService.java:163` | 人工（C26 只证明被拦） | 人工 |
| 确认后新增物料留痕 | `OrderService.java:195` | C08、C30 | 已验证 |
| 确认后整单改期留痕（含原值→新值） | `OrderService.java:180` | C09、C30 | 已验证 |
| 分派/改派留痕 | `TaskService.java:109` | C10–C12 | 已验证 |
| 开始设计留痕 | `TaskService.java:127` | C14 | 已验证 |
| 上传版本留痕 | `TaskService.java:149` | C15 | 已验证 |
| 记录意见留痕 | `TaskService.java:240` | C17 | 已验证 |
| 客户确认留痕 | `TaskService.java:278` | C20 | 已验证 |
| 动作码全部在注册表内且都被使用 | `contracts/audit-actions.json` | 门禁 G6 | 已验证 |
| 写状态的方法必然写留痕 | 全 `service/` | 门禁 G6 | 已验证 |

## 4. 正确性与并发（第二轮新增）

| 验收点 | 实现位置 | 证据 | 状态 |
|---|---|---|---|
| 状态迁移原子：条件更新，影响行数为 0 → 409 | `MaterialOrderMapper.xml`、`MaterialTaskMapper.xml` 的 `updateStatus`；`OrderService.java:143`、`TaskService.java:133` | C07、C16、C22、C26 仍为 409 | 已验证 |
| 订单已结束（交付/取消）时其任务不可再推进 | `TaskService.java:233`（`requireActiveOrder`） | C41、C42 | 已验证 |
| 分派只在「待分派」生效（条件写进 SQL） | `MaterialTaskMapper.xml` 的 `updateAssignee`；`TaskService.java:118` | C10–C12 | 已验证 |
| 订单列表分页，返回 `{list,total,page,size}` | `OrderService.java:94`、`ApiController.java:87` | C31、C32 | 已验证 |
| 任务工作台按状态/分派人/订单/关键词/逾期/未分派/已延期筛选 | `TaskService.java:88`、`MaterialTaskMapper.xml:77`（列表与计数共用 where） | C53–C57 | 已验证 |
| 任务工作台分页，`size` 上限 100 | `TaskService.java:90`、`ApiController.java:169` | C58 | 已验证 |
| `/api/tasks` 对客户经理开放，设计人员仍 403 | `ApiController.java:169`（`@RequireRole({"SALES","MANAGER"})`） | C59、C60 | 已验证 |
| 订单列表支持截止区间/只看逾期/已延期 | `MaterialOrderMapper.xml` 的 `listFilter`；`OrderService.list` | C61、C62 | 已验证 |
| 截止日期边界（不能早于今天、物料不能晚于整单、整单不能早于最晚物料） | `OrderService.requireNotPast/requireValidTaskDeadline`、`TaskService.updateDeadline` | C63–C65 | 已验证 |
| `size` 超上限被截断而非报错 | `OrderService.java:98` | C33 | 已验证 |
| 物料类型白名单 | `RequestDtos.java` `@Pattern` + `OrderService.java:210` | C34 | 已验证 |
| 数量必须 ≥ 1 | `RequestDtos.java` `@Min(1)` | C35 | 已验证 |
| 路径参数类型错误返回 400（不是 500） | `GlobalExceptionHandler.java:49` | C36 | 已验证 |
| 请求体 JSON 不合法返回 400 | `GlobalExceptionHandler.java:43` | 人工 | 人工 |
| 数据库约束未通过返回 400 且带日志 | `GlobalExceptionHandler.java:55` | 人工 | 人工 |
| 评论轮次并发由 `uk_task_round` 兜底并重试 | `TaskService.java:241` + `schema.sql` | 人工（需并发） | 人工 |
| 上传文件不占用事务，失败不留孤儿文件 | `TaskService.java:149`、`:226` | C15、C18 仍通过 | 已验证 |
| 设计人员不能下载他人任务的设计稿 | `TaskService.resolve(viewer, path)` + `DesignVersionMapper.selectByFilePath` | 单测 `resolveRejectsOtherDesignersFile` | 已验证（单测） |
| 密码摘要不随账号 JSON 返回，分派下拉只输出安全字段 | `User.password @JsonIgnore`、`UserService.designers` | 单测 `designersOnlyReturnsEnabledSafeFields` | 已验证（单测） |

| 自助注册只允许设计人员，且注册后待启用 | `AuthService.register` + 登录状态校验 | 人工（见 test-record §七.2） | 已验证（手工） |
| 账号管理仅负责人可用 | `ApiController` 上 `@RequireRole({"MANAGER"})` | 人工（§七.2 第 7 项） | 已验证（手工） |
| 停用即注销，历史留痕姓名角色不变 | `UserService.changeStatus` | 人工 | 已验证（手工） |
| 客户意见在设计师工作台可见 | `MaterialTaskMapper.xml` 的 feedbackCount / latestFeedback | 人工 | 已验证（手工） |

## 5. 工程一致性（门禁覆盖）

| 验收点 | 证据 | 状态 |
|---|---|---|
| 接口文档 ↔ Controller ↔ 前端 api 层一致 | 门禁 G3 | 已验证 |
| DDL ↔ 实体 ↔ Mapper 列一致 | 门禁 G2 | 已验证 |
| 权限契约 ↔ 注解 ↔ 服务层校验一致 | 门禁 G4 | 已验证 |
| 后端状态常量 ↔ 前端状态映射一致 | 门禁 G5 | 已验证 |
| 无 `alert`/`console.log`/`TODO`/`SELECT *`/明文密钥 | 门禁 G7 | 已验证 |
| 新密码使用 PBKDF2，旧哈希登录透明升级 | `PasswordUtil.java:51` + `AuthService.java:41` | 已验证 |
| 订单与账号规则有独立单测 | `OrderServiceTest`（31 条）+ `UserServiceTest`（10 条） | 已验证 |

## 6. 界面与体验（脚本无法判定）

| 验收点 | 证据 | 状态 |
|---|---|---|
| 菜单与按钮随角色变化 | 4 个演示账号各登录一次 | 人工 |
| 加载态、空态齐全 | 各业务页面逐个查看 | 人工 |
| 分页器切换、筛选后回到第 1 页 | 订单列表手工点一遍 | 人工 |
| 任务工作台筛选、分页、直接分派/改派入口 | `TaskWorkbenchView.vue` 手工点一遍 | 已验证（人工） |
| 工作台指标、逾期订单与异常管理页 | `DashboardView.vue`、`ExceptionView.vue` 用 manager/sales 各点一遍 | 人工 |
| 错误提示为中文且可读 | 制造一次 409（重复确认）观察 | 人工 |
| 下载设计稿可正常打开 | 任务详情点下载 | 人工 |
| 窄屏（<1100px）侧边栏折叠后仍可导航 | 拖窄浏览器窗口 | 人工 |

> 「人工」项不是缺陷清单，而是**脚本的能力边界**。交付前必须逐项点一遍，不能因为门禁全绿就跳过。

