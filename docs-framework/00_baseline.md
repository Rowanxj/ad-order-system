# 本轮基线

> 由 `.dsh/skills/ad-order-spec-driven` 的体检生成，记录"现在在哪、这轮做什么"。
> 每次进入新一轮开发前更新，不要让它变成历史文件。

## 1. 现状盘点

| 项 | 数量 | 来源 |
|---|---|---|
| 数据表 | 7 | `sql/schema.sql` |
| 后端实体 | 7 | `backend/src/main/java/com/course/adorder/entity/` |
| REST 接口 | 31 | `ApiController.java` |
| 前端页面 | 12 | `frontend/src/views/` |
| 唯一索引 | 4 | `uk_username`、`uk_order_no`、`uk_task_version`、`uk_task_round` |
| 留痕动作码 | 12 | `contracts/audit-actions.json` |
| 验收用例 | 66 | `contracts/acceptance.json` |
| JUnit 单元测试 | 80 | `backend/src/test/`（`PasswordUtilTest` 4 + `TaskServiceTest` 35 + `OrderServiceTest` 31 + `UserServiceTest` 10） |

## 2. 契约采纳情况

| 契约 | 状态 | 说明 |
|---|---|---|
| `contracts/state-machine.json` | 已采纳 | 2 个实体 / 9 个状态 / 8 条迁移 / 7 条禁止迁移 |
| `contracts/permissions.json` | 已采纳 | 31 条路由，区分 annotation / service / login / public 四种校验层 |
| `contracts/audit-actions.json` | 已采纳 | 12 个动作码，与代码中 `writeLog` 调用一致 |
| `contracts/acceptance.json` | 已采纳 | 66 条用例，隔离库上 66/66 通过（2026-11-06） |

## 3. 体检结论

`check-contract.ps1 -Stage delivery -Strict`：**FAIL=0 WARN=0**。

| 门禁 | 结论 |
|---|---|
| G0 现状盘点 | PASS |
| G1 文档与契约齐备 | PASS（01~04 + flow(4) + contracts(4)，无占位符） |
| G2 数据层对账 | PASS（DDL ↔ 实体 ↔ Mapper，Mapper 均为显式列名） |
| G3 接口三方对账 | PASS（31 条接口文档 ↔ Controller ↔ 前端 api 层） |
| G4 权限对账 | PASS |
| G5 状态机对账 | PASS |
| G6 留痕对账 | PASS |
| G7 禁项扫描 | PASS |

`check-api.ps1`：**PASS=66 FAIL=0**（隔离库 `ad_order_check`）。

## 4. 已关闭的缺口

### 第一轮（文档与契约）

| # | 原缺口 | 处理 |
|---|---|---|
| 1–5 | `01_PRD` / `02_Architecture` / `03_Database` / `04_API` / `flow` 缺失 | 全部补齐，页面元素到接口到字段可追溯 |
| 6 | 上传文件 IO 在事务内 | 落盘移出事务 + 失败补偿删除 |
| 7 | 取号/版本号并发风险 | 唯一索引 + 重试 |
| 8 | 密码单轮 SHA-256 | PBKDF2 + 登录透明升级 |
| 9 | 配置硬编码 | 环境变量占位 |
| 10 | `SELECT *` ×5 | 显式列名 |

### 第二轮（正确性与界面）

| # | 原缺口 | 处理 |
|---|---|---|
| 11 | 状态迁移"先查后写"会丢失更新 | 全部改条件更新，影响行数 0 → 409 |
| 12 | 分派写入时不校验状态 | SQL 内 `AND status='PENDING'` |
| 13 | 订单列表无分页 | `page`/`size` + `{list,total,page,size}`，列表与计数共用 where |
| 14 | 意见轮次并发重复 | `uk_task_round` + 重试 |
| 15 | DTO 缺长度/范围校验，超长输入变 500 | 校验与 DDL 逐列对齐，物料类型白名单 |
| 16 | 类型错误/坏 JSON 落 500 | 统一 400 处理器 |
| 17 | 界面朴素、无设计体系 | 设计令牌 + 统一页面结构 + 组件抽取 + 分页器 |
| 18 | 订单取消/交付后其任务仍可推进 | 六类任务操作统一加 `requireActiveOrder` 守卫 |

### 第三轮（账号与意见传递）

| # | 需求 | 处理 |
|---|---|---|
| 19 | 自助注册、区分角色 | 登录页注册（只允许 DESIGNER，注册后 status=2 待启用）；负责人「账号管理」可建任意角色 |
| 20 | 账户设置 / 注销 | 停用即注销（保留留痕）；负责人重置密码；个人可改自己密码 |
| 21 | 客户意见看不到 | 我的任务卡片直接显示「客户意见 N 轮 + 摘要」；记录意见提供 5 条常用模板 |

### 第四轮（单元测试）

| # | 原缺口 | 处理 |
|---|---|---|
| 22 | 只有 HTTP 集成验收，逻辑分支反馈慢 | 新增 `PasswordUtilTest`（4 条）与 `TaskServiceTest`（31 条），Mockito 隔离数据库，覆盖状态机、设计人员归属、订单结束守卫、条件更新冲突、上传事务补偿与版本/意见并发重试 |

### 第五轮（任务工作台）

| # | 原缺口 | 处理 |
|---|---|---|
| 23 | 负责人缺少任务总览，只能一单单点进去看 | 新增 `GET /api/tasks`（仅负责人）与 `/tasks` 页面：状态/分派人/订单/关键词/逾期/未分派筛选 + 分页，并支持从工作台直接分派/改派；验收 C50–C59 |

### 第六轮（订单与账号单测）

| # | 原缺口 | 处理 |
|---|---|---|
| 24 | 订单状态机与账号规则只有 HTTP 验收，分支问题定位慢 | 新增 `OrderServiceTest`（26 条）与 `UserServiceTest`（9 条），覆盖创建/订单号重试/分页/详情归属/确认与交付守卫/改期与加料留痕，以及账号脱敏/查重/最后负责人保护/重置密码；全套单测 72 条 |

### 第七轮（安全、配置、工作台与异常管理）

| # | 来源 | 处理 |
|---|---|---|
| 25 | 参考交接版本 `项目文件.zip` | 采纳安全收口（密码不序列化、分派下拉最小字段、设计稿下载归属校验）、`.env` 配置加载、截止日期边界、订单逾期/改期筛选，以及工作台 `/dashboard` 和异常管理 `/exceptions` 两个页面；单测扩到 80，验收扩到 66 |

## 5. 仍未做（明确排除）

| 项 | 原因 | 触发条件 |
|---|---|---|
| 客户列表与"我的任务"分页 | 数据量小，全量返回更简单 | 单客户量级上千 |
| 上传/取号/轮次的并发压测 | 需要独立压测环境 | 交付前要求性能证据 |
| 对象存储 | 无外网环境 | 多实例部署 |
| 深翻页优化（游标分页） | 当前数据量小 | 页码上千 |
| 日志归档 | 量级未到 | 日志表过百万 |

## 6. 下一轮的完成定义

- `check-contract.ps1 -Stage delivery -Strict` 保持 FAIL=0；
- `check-api.ps1` 保持 66/66；新增功能先加用例再写实现；
- `acceptance-matrix.md` 新增验收点都有「实现位置 + 用例 id」；
- `mvn -s ..\maven-settings.xml test` 保持 80 条全绿，新增状态/权限规则先补单元测试；
- 界面回归（4 账号菜单/按钮、分页器、筛选回第 1 页、窄屏折叠）人工过一遍。
