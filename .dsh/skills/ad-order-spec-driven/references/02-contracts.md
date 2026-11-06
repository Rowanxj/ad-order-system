# 阶段 2 · 契约成型：四份 JSON 的写法

契约的读者是**脚本**，不是人。所以字段名、取值、层级都必须固定；写得含糊，门禁就只能给出含糊的结论。

四份文件统一放在 `docs-framework/contracts/`：

```
docs-framework/contracts/
├── state-machine.json     # 状态全集与迁移
├── permissions.json       # 路由 × 角色 × 校验层
├── audit-actions.json     # 留痕动作注册表
└── acceptance.json        # 运行时验收用例
```

复制起点：`.dsh/skills/ad-order-spec-driven/references/templates/contracts/`。**复制后必须逐字段核对**，模板描述的是参考实现，不是你的实现。

---

## 一、`state-machine.json`

```jsonc
{
  "version": 1,
  "entities": {
    "ORDER": {                        // 实体键，大写，与业务叫法一致
      "name": "物料订单",
      "table": "material_order",      // 必须存在于 sql/schema.sql
      "javaClass": "MaterialOrder",   // 必须存在于 entity/ 目录
      "service": "OrderService",
      "frontendMap": "ORDER_STATUS",  // 必须存在于 frontend/src/utils/constants.js
      "statusField": "status",
      "initial": "PENDING_CONFIRM",
      "statuses": [                   // 状态全集：多一个少一个都会报红
        { "code": "PENDING_CONFIRM", "label": "待确认", "terminal": false }
      ],
      "transitions": [
        {
          "id": "O1",                 // 短标识，报告里用
          "from": "PENDING_CONFIRM",
          "to": "IN_PROGRESS",
          "trigger": "确认订单",
          "api": "PUT /api/orders/{id}/confirm",   // 必须与 Controller 路由一致
          "roles": ["MANAGER"],
          "guards": ["订单至少含 1 项物料任务"],     // 人可以读，脚本不解析
          "audit": "ORDER_CONFIRMED"                // 必须登记在 audit-actions.json
        }
      ],
      "forbidden": [                  // 「不能做什么」也是契约
        { "from": "COMPLETED", "to": "*", "reason": "已交付订单不可再流转" }
      ]
    }
  }
}
```

脚本会校验的（每条都能定位到文件）：

| 校验点 | 失败信息 |
|---|---|
| `statuses[].code` 与 `javaClass` 里的 `public static final String` 双向一致 | 契约有、常量没有 / 常量有、契约没有 |
| `frontendMap` 指向的映射键集合与 `statuses` 双向一致 | 「状态 X 在前端映射中缺失」 |
| `table` 的 `status VARCHAR(...) DEFAULT 'X'` 是合法状态 | 「默认值不是合法状态」 |
| 每条 `transitions[].api` 在 Controller 中真实存在 | 「迁移声明的接口不存在」 |
| `from` / `to` 在 `statuses` 中已定义 | 「迁移的 from 状态未定义」 |

脚本**不**校验的：`guards` 的文字是否与代码实现一致。这只能靠人。

### 写 `roles` 的注意点

用代码里的角色码（`SALES`/`MANAGER`/`DESIGNER`），不要写中文。中文只出现在 `label`、`trigger`、`guards`。

---

## 二、`permissions.json`

```jsonc
{
  "version": 1,
  "roles": { "SALES": "客户经理：…" },
  "routes": [
    {
      "api": "PUT /api/tasks/{id}/start",
      "roles": ["DESIGNER"],
      "guard": "service",                    // 关键字段，见下表
      "handler": "ApiController#startTask",
      "serviceCheck": "TaskService#requireAssignee",   // guard=service 时必填
      "dataScope": "仅被分派人可操作"
    }
  ]
}
```

`guard` 的四种取值，决定门禁用哪种方式对账：

| guard | 含义 | 门禁期待 | 用错会怎样 |
|---|---|---|---|
| `public` | 不需要登录 | 方法上**不应**有 `@RequireRole` | 写了反而报 WARN |
| `login` | 任何登录用户 | 方法上**不应**有 `@RequireRole`；数据范围靠 `dataScope` 说明 | — |
| `annotation` | 靠入口注解挡角色 | `@RequireRole` 的值必须与 `roles` **集合相等** | 少一个角色 → FAIL |
| `service` | 入口不拦，服务层按数据归属拦 | `serviceCheck` 指向的方法必须存在，且方法体内有归属校验代码 | 指向空方法 → FAIL |

`guard: service` 的判定逻辑：方法体里必须出现 `getAssigneeId()`、`countByOrderAndAssignee(` 或 `FORBIDDEN` 之一。这不是完美的语义分析，但能挡住"声明了校验其实没写"。

**教训**：`/api/tasks/{id}/start` 这类接口没有 `@RequireRole`，很多人在契约里写成 `annotation`，然后被门禁判红。它其实是 `service`——权限由 `requireAssignee` 承担。

脚本还会反向查：Controller 里有、`permissions.json` 没登记的路由，一律 FAIL（防止"偷偷加的接口"）。

---

## 三、`audit-actions.json`

```jsonc
{
  "version": 1,
  "storage": "operation_log",
  "fields": { "detail": "改值类动作必须写成「原值 → 新值」" },
  "actions": [
    {
      "code": "ORDER_DEADLINE_CHANGED",
      "biz": "ORDER",                                  // ORDER | TASK
      "text": "变更整体截止日期",
      "api": "PUT /api/orders/{id}/deadline",
      "when": "确认后改期，必须写原值→新值",
      "detailPattern": "原截止日期.*→"                  // 可选，给人工审查用的锚点
    }
  ]
}
```

双向校验，两边都会报：

- 代码里 `writeLog(...)` 的动作码没登记 → **FAIL**（拼错的动作码永远不会有人查到）；
- 登记的动作用代码里从没写过 → **WARN**（死码，或实现漏了）。

另外一条独立规则：任何方法体内出现 `updateStatus(`、`updateAssignee(`，或同时出现 `setStatus(` + `update(`，就必须同方法出现 `writeLog(`，否则 FAIL。这条规则抓的是"改了状态忘了留痕"，项目里最常见的漏留痕方式。

---

## 四、`acceptance.json`

```jsonc
{
  "version": 1,
  "baseUrl": "http://localhost:8080/api",
  "accounts": {
    "sales": { "username": "sales", "password": "123456" }
  },
  "cases": [
    {
      "id": "C06",
      "name": "负责人确认订单",
      "as": "sales",                                  // 账号键；null 表示不带 token
      "method": "PUT",
      "path": "/orders/{oid}/confirm",                // {变量} 来自前面 case 的 capture
      "json": { "deadline": "2026-12-31" },           // 可选 JSON 体
      "upload": { "field": "file", "fileName": "v1.png", "text": "fixture" },  // 可选 multipart
      "form": { "note": "说明" },                      // 可选，与 upload 搭配
      "expect": 0,                                    // 业务 code，不是 HTTP 状态
      "expectFields": ["data.tasks"],                 // 可选，校验字段存在
      "expectContains": ["ORDER_DEADLINE_CHANGED"],   // 可选，校验原始响应里出现某串
      "capture": { "oid": "data.id" },                // 可选，点号路径，支持数组下标
      "requires": ["oid"]                             // 可选，缺变量则 SKIP 而不是 FAIL
    }
  ]
}
```

要点：

- 用例**按顺序执行**，后面的用例可以用前面的 `capture` 结果。所以"创建 → 分派 → 上传 → 确认"必须写成纵向链，不能打乱。
- `expect` 是 `{code}` 里的业务码。本项目的错误一律 HTTP 200 + 业务码，所以不能断言 HTTP 状态码。
- 用例之间有副作用（会真的建单）。**只对开发库跑**，不要对着演示库或线上库跑。
- `capture` 的路径用点号：`data.id`、`data.tasks.0.id`。取不到时不报错，后续 `{变量}` 会保持原样，若配置了 `requires` 则整条 SKIP。

### 用例覆盖的最低要求

| 类别 | 条数下限 | 说明 |
|---|---|---|
| 主链路 | 1 条完整走下去 | 从建单到交付 |
| 状态冲突 | ≥ 3 | 重复确认、跳步、终态再操作 |
| 越权（角色） | ≥ 3 | 每个"不能做"的角色都试一次 |
| 越权（数据归属） | ≥ 2 | 设计人员读/写他人任务 |
| 未登录 | ≥ 1 | 401 |
| 留痕 | ≥ 1 | 用 `expectContains` 断言操作历史里出现动作码 |

本项目当前 31 条，覆盖上表全部类别。

---

## 五、人读文档（L2）

机器契约之外仍需要 `01_PRD.md` / `02_Architecture.md` / `03_Database.md` / `04_API.md` / `flow/*.md`，因为课程评审和答辩看的是这些。骨架见 `references/templates/spec-skeleton.md`。

两者关系：**JSON 是摘要，Markdown 是正文**。改业务规则时，先改 JSON（机器马上能验证），再同步 Markdown（人能看到原因）。不要只改一边。

## 收尾自检

- [ ] 四份 JSON 都能被 PowerShell `ConvertFrom-Json` 解析（有中文也要能解析，保存为 UTF-8 无 BOM）；
- [ ] `check-contract.ps1 -Strict` 的 G4/G5/G6 无 FAIL；
- [ ] `check-api.ps1 -List` 的用例覆盖上表六类；
- [ ] 每份契约顶部的 `note` 写清了"这份文件回答什么问题"。
