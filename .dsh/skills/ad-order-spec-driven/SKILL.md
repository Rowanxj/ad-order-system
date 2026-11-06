---
name: ad-order-spec-driven
description: "广告物料订单系统（ad-order-system）的契约驱动交付流程：把「状态机 / 权限矩阵 / 留痕动作」写成 docs-framework/contracts 下的 JSON 契约，用 check-contract.ps1 对账代码，用 check-api.ps1 跑验收，六阶段（0 定位 → 1 领域锁定 → 2 契约成型 → 3 骨架红灯 → 4 逐场景点亮 → 5 对账交付）推进。"
whenToUse: "在 ad-order-system（或同构的 Spring Boot + Vue 后台系统）里新增功能、改字段、加状态、修越权/状态机缺陷，需要课程下一阶段交付或答辩，需要先写文档再写代码，或需要一份能自动体检的工程契约体系时使用。"
---

# 广告物料订单系统 · 契约驱动交付

这套系统的难点从来不是增删改查，而是三件事：**状态不能乱转、权限不能漏、变更必须留痕**。文档写给人看，契约写给机器验。所以本流程把「状态机 / 权限 / 留痕动作」抽成 JSON 契约，交给脚本在每次交付前对账。

一句话记住：**契约不落 JSON，门禁就永远是自我感觉良好。**

## 什么时候用 / 不用

用：

- 给 `ad-order-system` 加功能、加状态、加字段、加接口；
- 课程下一阶段要交付、要演示、要答辩；
- 修「状态跳转报错」「越权能操作别人任务」「改完没留痕」这类缺陷；
- 接手一个同类后台系统（Spring Boot + Vue + MySQL），需要先摸清契约再动手。

不用：纯样式微调、单点文案修改、只跑一次的数据订正。这些直接做，不要套这套流程。

## 与通用 Spec 流程的三处升级

通用的「先写文档再写代码」有三个漏洞：文档勾选靠 AI 自评、状态机只活在 mermaid 图里、权限靠肉眼数注解。本项目针对这三点做了硬处理。

| 通用做法 | 本流程做法 | 换来的能力 |
|---|---|---|
| 阶段门禁是可勾选的 checklist | 门禁是**可执行的脚本**，读契约与代码做 diff、给出退出码 | 不能自欺：改完不跑就是没改完 |
| 状态机画在文档里，代码里散落字符串 | `contracts/state-machine.json` 声明状态与迁移，脚本比对后端字面量与前端状态映射 | 加状态却忘了前端标签 → 立刻报红 |
| 权限靠 `@RequireRole` 目测 | `contracts/permissions.json` ↔ 入口注解 ↔ 服务层归属校验，三方对账 | 越权缺口可被定位到具体接口 |

## 事实层级（谁说了算）

改动必须顺着层级走，**上层没改，下层不许动**。

| 层 | 载体 | 谁能改 | 约束 |
|---|---|---|---|
| L0 领域铁律 | 本 skill 的 `references/domain/ad-order-system.md` | 几乎不改 | 状态机全集、角色职责、留痕底线 |
| L1 机器契约 | `docs-framework/contracts/*.json`、`sql/schema.sql` | 需显式确认 | 唯一可被脚本读取的事实 |
| L2 人读文档 | `docs-framework/01~04*.md`、`docs-framework/flow/*.md` | 随 L1 同步 | 解释 L1，不得引入 L1 之外的概念 |
| L3 代码 | `backend/`、`frontend/` | 每轮一个场景 | 只能引用 L1/L2，禁止发明字段、路径、状态、错误码 |
| L4 证据 | `docs/test-record.md`、`check-api.ps1` 输出 | 每轮追加 | 证明 L3 符合 L1，不是证明 L3 能跑 |

冲突判定：L3 与 L1 不一致时，**先假定 L3 错**；如果确认是 L1 过时，先改 L1，再改 L3，最后补 L4。任何"顺手优化"的字段名都不接受。

## 六阶段与门禁

| 阶段 | 主要产出 | 门禁命令（在项目根执行） |
|---|---|---|
| 0 定位 | 现状盘点表、本轮范围红线 | `check-contract.ps1 -Stage locate` |
| 1 领域锁定 | 状态/角色/留痕三条口径确认 | `check-contract.ps1 -Stage domain` |
| 2 契约成型 | `contracts/*.json` + 五份文档更新 | `check-contract.ps1 -Stage contract -Strict` |
| 3 骨架红灯 | 空实现 + 会失败的测试 | `check-contract.ps1 -Stage skeleton` |
| 4 逐场景点亮 | 一个 flow 一个接口一个页面 | `check-contract.ps1 -Stage logic` |
| 5 对账交付 | 审查报告、验收矩阵、可启动环境 | `check-contract.ps1 -Stage delivery -Strict` + `check-api.ps1` |

阶段 0/1 在文档完备时可以合并，但**顺序不可颠倒**。每轮开头用 `todo_write` 写出本轮阶段计划，只保留一个 `in_progress`。

### 阶段 0 · 定位

先分清是**新建**还是**接管**，两条路径差别很大。

- 新建：从 `references/01-domain-lock.md` 的访谈脚本开始，一次只问 1–2 个问题，先定角色和状态，再谈页面。
- 接管（本仓库现状）：先跑 `check-contract.ps1 -Stage locate`，得到"契约缺失 / 文档缺失 / 禁项"三张清单，再决定补哪一层。**不要**在没有契约的情况下直接改业务代码。

产出：`docs-framework/00_baseline.md`，记录当前表数、接口数、角色数、已知缺口、本轮不做的事。

### 阶段 1 · 领域锁定

讨论页面之前，先锁死三张表：状态全集、角色职责、留痕动作。三者的权威描述在 `references/domain/ad-order-system.md`，本轮若要新增状态或动作，先在那里登记。

门禁要点：

- [ ] 每个状态有中文标签、是否终态、所属实体；
- [ ] 每条迁移有触发角色、触发接口、前置校验、写入的留痕动作；
- [ ] 每个角色能做什么、**不能**做什么，写成人话（"设计人员不能记录客户确认"）。

### 阶段 2 · 契约成型

把领域结论落成机器可读契约，`references/02-contracts.md` 给了四份 JSON 的完整 schema 与本项目的真实样例。同时更新 L2 文档，保证 `01` 的按钮 → `04` 的接口 → `03` 的字段能一条线追下来。

门禁：`-Stage contract -Strict`，要求

- 四份契约齐备且 JSON 可解析；
- `state-machine.json` 的状态全集 ⊇ 代码里出现的状态字面量；
- `permissions.json` 覆盖 `ApiController` 的每一个路由；
- 五份文档无占位符（`TBD`/`待补充`/`...省略`）。

### 阶段 3 · 骨架红灯

只搭壳、不写逻辑：

1. 按契约在 `ApiController` 加路由，Service 方法体 `throw new UnsupportedOperationException("待实现")`；
2. 按 `flow/*.md` 写测试（JUnit 或 `contracts/acceptance.json` 用例），此时必须全红；
3. 前端只做 UI + 空态，不发真实请求。

这是给后续实现划的红线，**红灯不是故障，是保险**。

### 阶段 4 · 逐场景点亮

一轮只点亮一个 flow。每点亮一个，必须同时满足（`references/03-implement.md` 有清单与反例）：

- 状态迁移前先校验当前态，用 `checkTransition` / `equals` 比对，不靠 `if (status != null)`；
- 写状态的 Service 方法必须在同一方法内 `writeLog(...)`，动作码取自 `audit-actions.json`；
- 涉及"本人数据"的接口，入口注解之外还要在服务层做归属校验；
- 前端状态标签来自 `utils/constants.js`，没有标签的状态不许上线。

### 阶段 5 · 对账与交付

- 跑 `check-contract.ps1 -Stage delivery -Strict`，把输出贴进 `docs/test-record.md`；
- 跑 `check-api.ps1`（需要后端与 MySQL 就绪），失败用例逐条定性；
- 反向 Code Review：只列**能落到行号**的问题，禁止"整体不错"；
- 更新 `docs-framework/acceptance-matrix.md`：每个验收点 → 实现位置（文件:行）→ 测试用例 ID。

## 三条铁律

### 铁律一：状态机只有一个入口

任何状态变更必须集中在一处，且必须走过"读现状 → 校验前置态 → 写入目标态 → 留痕"四步。禁止在 Controller 里直接改状态，禁止前端传 `status` 字段。

反例（本项目真实踩过）：

```java
// 错：相信调用方，谁都能把任务改成 CONFIRMED
task.setStatus(req.status());
taskMapper.update(task);
```

```java
// 对：目标态由服务端决定，前置态必须匹配
if (!MaterialTask.PENDING_CONFIRM.equals(task.getStatus())) {
    throw new BusinessException(CONFLICT, "仅“待客户确认”的任务可以确认");
}
taskMapper.updateStatus(taskId, MaterialTask.CONFIRMED);
writeLog("TASK", taskId, task.getOrderId(), "TASK_CONFIRMED", "客户确认", "…", user);
```

### 铁律二：双闸权限

闸一在入口（`@RequireRole` 挡角色），闸二在服务层（挡数据归属）。只有闸一，设计人员能操作别人的任务；只有闸二，列表接口会泄露无关数据。

### 铁律三：留痕是业务的一部分，不是日志

凡是改变"客户可见事实"的写操作（创建、确认、取消、分派、上传、记录意见、改期、交付）都必须写 `operation_log`，且动作码在 `audit-actions.json` 注册。调试日志 `log.debug` 不算留痕。

## 门禁脚本

两个脚本都放在 `<项目根>/.dsh/skills/ad-order-spec-driven/scripts/`，在项目根执行。

```powershell
# 日常：静态对账（不需要数据库）
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1

# 交付/答辩前：严格模式，契约缺失即失败
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1 -Stage delivery -Strict -Markdown

# 接口验收（需要后端 :8080 与 MySQL 就绪）
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-api.ps1
```

退出码：`0` 全绿；`1` 有 FAIL；`2` 环境不满足（例如后端未启动、契约目录缺失且用了 `-Strict`）。

`-Stage` 可选 `locate|domain|contract|skeleton|logic|delivery|all`（默认 `all`）；`-Markdown` 输出可直接贴进测试记录的表格。

脚本能查的和不能查的，一并说清楚，避免误信：

| 能力 | 说明 |
|---|---|
| 能查 | 文档占位符、DDL↔实体↔Mapper 字段错位、接口文档↔Controller↔前端 API 三向不一致、注解权限与矩阵冲突、状态字面量越界、写状态未留痕、禁止项 |
| 不能查 | 事务是否真的生效、并发正确性、SQL 性能、业务语义是否合理、界面是否好看 |

不能查的部分靠阶段 5 的人工审查与 `check-api.ps1` 的运行时断言补。

## 常见反模式

| 反模式 | 症状 | 纠正 |
|---|---|---|
| 先写代码后补文档 | 文档里查不到的字段出现在接口响应里 | 回到阶段 2，先补契约 |
| 状态字符串硬编码 | 后端 `"PENDING_CONFIRM"` 写成 `"PENDING-CONFIRM"`，前端标签空白 | 用实体常量，跑 `-Stage logic` |
| 用注解当权限 | 设计人员仍能操作他人任务 | 服务层补归属校验 |
| 留痕写成条件分支 | 改了截止日期却没记录 | 写操作与留痕同方法、同事务 |
| 为了绿灯改断言 | 测试变绿但业务仍错 | 改契约要显式确认，不许迁就实现 |
| 一次点亮三个场景 | 报错定位不到根因 | 一轮一个 flow |

## 参考索引

- `references/00-bootstrap.md` —— 30 分钟上手：接管现有仓库 / 从零起步两条路径
- `references/01-domain-lock.md` —— 访谈脚本：把口头需求收敛成状态机与权限矩阵
- `references/02-contracts.md` —— 四份契约 JSON 的 schema 与本项目真实样例
- `references/03-implement.md` —— 逐场景点亮：事务、权限、留痕、文件、异常码的落地细则
- `references/04-review-delivery.md` —— 反向审查清单、验收矩阵、答辩话术、部署
- `references/domain/ad-order-system.md` —— 本项目领域手册（7 表 / 2 状态机 / 权限矩阵 / 错误码 / 已知限制）
- `references/templates/constitution.md` —— `AGENTS.md` 模板（本项目定制红线）
- `references/templates/spec-skeleton.md` —— `docs-framework/` 五份文档骨架
- `references/templates/contracts/` —— 四份契约的可复制样例
