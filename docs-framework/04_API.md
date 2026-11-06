# API 契约

## 1. 全局约定

- 前缀 `/api`，全部返回 JSON；
- 鉴权：`Authorization: Bearer <token>`，登录与自助注册除外；
- **HTTP 状态码恒为 200**，业务结果看 `body.code`；
- 成功：`{"code":0,"message":"success","data":...}`
- 失败：`{"code":<业务码>,"message":"可直接给客户看的中文"}`，`data` 为 `null`；
- 时间格式：日期 `yyyy-MM-dd`，日期时间 `yyyy-MM-ddTHH:mm:ss`（前端统一格式化）；
- 文件上传：`multipart/form-data`，上限 20MB。

### 错误码

| code | 含义 | 触发示例 |
|---|---|---|
| 0 | 成功 | — |
| 400 | 参数/业务规则不满足 | 缺字段、订单无物料、文件类型不支持、账号已被占用 |
| 401 | 未登录或凭证失效 | 无 token、token 过期 |
| 403 | 无权限 | 角色不符、非本人任务、访问无关订单、账号未启用 |
| 404 | 资源不存在 | 订单/任务/文件不存在 |
| 409 | 状态冲突 | 重复确认、跳步、未全部确认就交付、已结束订单再改 |
| 500 | 服务内部错误 | 未预期异常（兜底，不暴露堆栈） |

错误码只在本文定义一次，代码中引用 `BusinessException` 常量，不得自造新码。

### 账号状态

`sys_user.status`：`1` 启用 / `0` 停用 / `2` 待启用。
非 `1` 一律不能登录：`2` 返回「账号待负责人启用」，`0` 返回「账号已停用」。

## 2. 接口总览

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | /api/auth/login | 公开 | 登录，返回 JWT 与用户信息 |
| POST | /api/auth/register | 公开 | 自助注册（只能注册为设计制作人员，待负责人启用） |
| GET | /api/auth/me | 登录用户 | 当前登录用户 |
| PUT | /api/auth/password | 登录用户 | 修改本人密码 |
| GET | /api/customers | 客户经理/负责人 | 客户列表（关键词） |
| POST | /api/customers | 客户经理 | 新增客户 |
| GET | /api/orders | 登录用户 | 订单列表（分页；支持截止区间/只看逾期/已延期；设计人员数据受限） |
| POST | /api/orders | 客户经理 | 新建订单（含物料明细） |
| GET | /api/orders/stats | 登录用户 | 订单大盘统计（待确认 / 待设计 / 设计中 / 待客户确认 / 可交付） |
| GET | /api/orders/{id} | 登录用户 | 订单详情（任务 + 操作历史） |
| PUT | /api/orders/{id}/confirm | 负责人 | 确认订单 |
| PUT | /api/orders/{id}/cancel | 客户经理/负责人 | 取消订单（仅待确认） |
| PUT | /api/orders/{id}/complete | 负责人 | 订单交付（全部任务已确认） |
| PUT | /api/orders/{id}/deadline | 客户经理/负责人 | 修改整体截止日期 |
| POST | /api/orders/{id}/tasks | 客户经理 | 新增物料任务（确认后留变更记录） |
| GET | /api/tasks | 客户经理/负责人 | 任务工作台（分页；状态/分派人/订单/关键词/逾期/未分派/已延期筛选） |
| GET | /api/tasks/my | 登录用户 | 我的设计任务工作台（含客户意见条数与最新内容） |
| GET | /api/tasks/{id} | 登录用户 | 任务详情（版本 + 意见） |
| PUT | /api/tasks/{id}/assign | 负责人 | 分派/改派 |
| PUT | /api/tasks/{id}/start | 设计人员本人 | 开始设计 |
| POST | /api/tasks/{id}/versions | 设计人员本人 | 上传设计版本（multipart） |
| POST | /api/tasks/{id}/feedbacks | 客户经理 | 记录客户修改意见 |
| PUT | /api/tasks/{id}/confirm | 客户经理 | 记录客户确认 |
| PUT | /api/tasks/{id}/deadline | 客户经理/负责人 | 修改物料截止日期 |
| GET | /api/users | 负责人 | 账号列表 |
| POST | /api/users | 负责人 | 新增账号（可指定角色） |
| PUT | /api/users/{id}/status | 负责人 | 启用 / 停用 / 待启用 |
| PUT | /api/users/{id}/password | 负责人 | 重置他人密码 |
| GET | /api/users/designers | 负责人 | 设计人员下拉 |
| GET | /api/notifications/summary | 登录用户 | 待办汇总与最近消息（按角色） |
| GET | /api/files/** | 登录用户 | 设计稿下载（防目录穿越） |

## 3. 接口详情

### 3.1 登录 / 注册 / 改密码

**登录** `POST /api/auth/login`（公开）

```json
请求：{ "username": "sales", "password": "123456", "role": "SALES" }   // role 可选：登录人所选身份
成功：{ "code": 0, "data": { "token": "eyJ...", "user": { "id": 1, "username": "sales", "realName": "张欣怡", "role": "SALES" } } }
失败：{ "code": 400, "message": "账号或密码错误" }
失败：{ "code": 403, "message": "账号待负责人启用，请联系业务负责人" }
失败：{ "code": 403, "message": "该账号是「设计制作人员」，与所选身份「客户经理」不一致，请切换身份后重试" }
```

**自助注册** `POST /api/auth/register`（公开）

```json
请求：{ "username": "designer3", "realName": "王小明", "password": "123456", "phone": "13800000000" }
成功：{ "code": 0, "data": { "username": "designer3", "status": 2, "message": "注册成功，等待业务负责人启用后即可登录" } }
失败：{ "code": 400, "message": "该账号已被占用，请换一个" }
```

- 账号格式：字母开头，3–20 位字母/数字/下划线；
- **角色固定为 DESIGNER，状态固定为 2（待启用）**——不允许自助注册成客户经理或负责人；
- 负责人启用后才能登录。

**修改本人密码** `PUT /api/auth/password`

```json
请求：{ "oldPassword": "123456", "newPassword": "abc123456" }
成功：{ "code": 0 }
失败：{ "code": 400, "message": "原密码不正确" }
```

### 3.2 账号管理（仅负责人）

| 接口 | 请求 | 说明 |
|---|---|---|
| `GET /api/users` | — | 返回全部账号；**密码字段不下发**（服务层置空） |
| `POST /api/users` | `{username, realName, role, password, phone}` | 可指定 SALES / MANAGER / DESIGNER，建好即为启用 |
| `PUT /api/users/{id}/status` | `{status: 0/1/2}` | 停用即"注销"；**不能停用自己**；**至少要保留一个启用的负责人** |
| `PUT /api/users/{id}/password` | `{password}` | 负责人重置，用于用户忘记密码 |

返回示例（列表元素）：

```json
{ "id": 3, "username": "designer1", "realName": "李文静", "role": "DESIGNER", "phone": "138...", "status": 1, "createdAt": "2026-10-23T18:16:43" }
```

> 账号操作**不写 `operation_log`**：那张表是订单维度的时间线（`order_id` 非空），
> 账号变更走应用日志审计，避免污染订单历史。

### 3.3 新建订单

`POST /api/orders`，`@RequireRole({"SALES"})`

```json
请求：
{
  "customerId": 1,
  "title": "秋季新品宣传物料",
  "description": "整体风格参考去年",
  "deadline": "2026-12-30",
  "tasks": [
    { "materialType": "POSTER", "name": "主海报", "spec": "600x900mm", "quantity": 2,
      "usageDesc": "门店张贴", "referenceNote": "要暖色", "deadline": "2026-12-20" }
  ]
}
成功：{ "code": 0, "data": { "id": 12, "orderNo": "AO202611040012" } }
失败：{ "code": 400, "message": "订单至少包含一项物料任务" }
```

- 物料类型白名单：POSTER / STAND / MENU / BROCHURE / ONLINE；
- 数量 1–9999；标题 ≤100 字；单笔最多 50 项物料；
- 副作用：写 `operation_log`，动作 `ORDER_CREATED`。

### 3.4 确认 / 取消 / 交付

| 接口 | 角色 | 前置 | 失败示例 | 留痕 |
|---|---|---|---|---|
| `PUT /api/orders/{id}/confirm` | MANAGER | 状态 `PENDING_CONFIRM`，且至少 1 项物料 | `{"code":409,"message":"订单当前状态不允许执行该操作（当前状态：IN_PROGRESS）"}` | `ORDER_CONFIRMED` |
| `PUT /api/orders/{id}/cancel` | SALES/MANAGER | 状态 `PENDING_CONFIRM` | 同上 | `ORDER_CANCELLED` |
| `PUT /api/orders/{id}/complete` | MANAGER | 状态 `IN_PROGRESS`，未确认任务数为 0 | `{"code":409,"message":"还有 2 项物料任务未确认，整笔订单不能交付"}` | `ORDER_COMPLETED` |

状态迁移全部采用**条件更新**：`UPDATE ... WHERE id=? AND status=#{from}`，
影响行数为 0 时返回 409「状态已被其他人改变，请刷新后重试」。

### 3.5 改期与新增物料

| 接口 | 角色 | 前置 | 留痕 |
|---|---|---|---|
| `PUT /api/orders/{id}/deadline` | SALES | 订单未结束 | 仅 `IN_PROGRESS` 时写 `ORDER_DEADLINE_CHANGED` |
| `PUT /api/tasks/{id}/deadline` | SALES | 任务未确认、订单未结束 | 仅 `IN_PROGRESS` 时写 `TASK_DEADLINE_CHANGED` |
| `POST /api/orders/{id}/tasks` | SALES | 订单为 `PENDING_CONFIRM`/`IN_PROGRESS` | 进行中时写 `TASK_ADDED` |

改值类留痕的 `detail` 必须是「原值 → 新值」，例如：

```
原截止日期 2026-12-20 → 新截止日期 2026-12-28（客户同意后变更）
```

### 3.6 任务：分派 / 开始 / 上传

| 接口 | 角色 | 前置 | 失败示例 |
|---|---|---|---|
| `PUT /api/tasks/{id}/assign` | MANAGER | 任务 `PENDING`，订单未结束；被分派人须为启用状态的设计人员 | `{"code":400,"message":"所选人员不是启用状态的设计制作人员"}` |
| `PUT /api/tasks/{id}/start` | DESIGNER 本人 | `assignee_id = 当前用户`；状态 `PENDING`；订单未结束 | `{"code":403,"message":"设计人员只能处理分派给自己的任务"}` |
| `POST /api/tasks/{id}/versions` | DESIGNER 本人 | 状态 `DESIGNING`/`REVISION`；扩展名白名单 | `{"code":400,"message":"不支持的文件类型，请上传图片、PDF 或设计源文件"}` |

上传成功返回：

```json
{ "code": 0, "data": { "versionNo": 2, "taskId": 5, "status": "PENDING_CONFIRM" } }
```

**上传 ≠ 客户确认**：上传后只进入「待客户确认」。

### 3.7 任务：意见与客户确认

| 接口 | 角色 | 前置 | 留痕 |
|---|---|---|---|
| `POST /api/tasks/{id}/feedbacks` | SALES | 状态 `PENDING_CONFIRM` | `FEEDBACK_RECORDED`，任务转 `REVISION` |
| `PUT /api/tasks/{id}/confirm` | SALES | 状态 `PENDING_CONFIRM` | `TASK_CONFIRMED`，任务转 `CONFIRMED` |

```json
请求：{ "content": "颜色改成奶茶棕，logo 再放大一点" }
失败：{ "code": 400, "message": "修改意见不能超过 500 字" }
```

### 3.8 查询类接口

| 接口 | 响应 `data` 结构要点 |
|---|---|
| `GET /api/customers?keyword=` | `[{id,name,contactPerson,contactPhone,industry,remark,createdBy}]`（不分页） |
| `GET /api/orders?status=&customerId=&keyword=&designerId=&deadlineFrom=&deadlineTo=&overdue=&extended=&page=1&size=10` | `{list:[...], total, page, size}`；DESIGNER 只返回含本人任务的订单；`overdue=true` 只看未结束且已过截止的订单，`extended=true` 只看有过改期留痕的订单 |
| `GET /api/orders/{id}` | `{...订单字段, customer:{...}, tasks:[...], logs:[...]}` |
| `GET /api/tasks?status=&assigneeId=&orderId=&keyword=&overdue=&unassigned=&extended=&page=1&size=10` | `{list:[{id,orderId,orderNo,orderTitle,orderStatus,orderDeadline,customerName,materialType,name,spec,quantity,deadline,status,assigneeId,assigneeName,versionCount,feedbackCount,createdAt}], total, page, size}`；客户经理/负责人 |
| `GET /api/tasks/my?status=` | `[{id,orderNo,orderTitle,customerName,materialType,name,spec,quantity,deadline,status,feedbackCount,latestFeedback}]`（仅本人任务，不分页） |
| `GET /api/tasks/{id}` | `{...任务字段, orderNo, orderTitle, orderStatus, orderDeadline, versions:[...], feedbacks:[...]}` |
| `GET /api/users` | `[{id,username,realName,role,phone,status,createdAt}]` |
| `GET /api/users/designers` | `[{id,realName,username,status}]` |
| `GET /api/files/{path}` | 二进制流，`Content-Disposition: attachment`；路径来自数据库；设计人员只能下载本人任务的设计稿 |

#### 任务工作台筛选（`GET /api/tasks`，客户经理/负责人）

| 参数 | 类型 | 默认 | 规则 |
|---|---|---|---|
| `status` | string | 全部 | 任务状态：`PENDING` / `DESIGNING` / `PENDING_CONFIRM` / `REVISION` / `CONFIRMED` |
| `assigneeId` | long | 全部 | 只看分派给某位设计人员的任务 |
| `orderId` | long | 全部 | 只看某笔订单下的任务 |
| `keyword` | string | 全部 | 模糊匹配任务名、订单号、订单标题、客户名 |
| `overdue` | boolean | 不筛 | `true` 时只看「任务未确认、订单未结束、截止日期早于今天」的任务 |
| `unassigned` | boolean | 不筛 | `true` 时只看 `assignee_id IS NULL` 的任务 |
| `extended` | boolean | 不筛 | `true` 时只看有过 `TASK_DEADLINE_CHANGED` 留痕的任务 |
| `page` / `size` | int | 1 / 10 | 与订单列表同一套分页规则，`size` 上限 100 |

- 响应固定 `{list,total,page,size}`；
- 排序：先按状态 `REVISION → PENDING_CONFIRM → DESIGNING → PENDING → CONFIRMED`，同状态按截止日期升序、`id` 兜底，保证翻页稳定；
- 只有 `SALES` / `MANAGER` 可访问，其他角色 403；设计人员的个人任务仍走 `GET /api/tasks/my`。

#### 订单列表筛选（`GET /api/orders`）

| 参数 | 类型 | 默认 | 规则 |
|---|---|---|---|
| `deadlineFrom` / `deadlineTo` | date | 不筛 | 整体截止日期区间，格式 `yyyy-MM-dd` |
| `overdue` | boolean | 不筛 | `true` 时只看未结束（待确认/进行中）且整体截止早于今天的订单 |
| `extended` | boolean | 不筛 | `true` 时只看有过 `ORDER_DEADLINE_CHANGED` 留痕的订单 |

#### 日期边界（创建/改期/新增物料）

- 整体截止、物料截止都不能早于今天；
- 物料截止不能晚于订单整体截止；
- 整体截止不能早于该订单最晚的物料截止；
- 后端 Service 是最终判定方；前端日期选择器只是把非法日期变灰并提前提示。

#### 分页约定（`GET /api/orders` 与 `GET /api/tasks`）

| 参数 | 类型 | 默认 | 规则 |
|---|---|---|---|
| `page` | int | 1 | 小于 1 按 1 处理 |
| `size` | int | 10 | 小于 1 按 10；**上限 100**，超过按 100 |

- 响应固定 `{list,total,page,size}`，即使只有一页也是该结构；
- `total` 是应用同样筛选条件与数据归属后的总数；
- 排序固定 `created_at DESC, id DESC`，保证分页稳定。

### 3.9 数据归属规则

| 接口 | DESIGNER 可见范围 | 越界结果 |
|---|---|---|
| `GET /api/orders` | 仅含本人任务的订单（SQL 过滤） | 不出现，非报错 |
| `GET /api/orders/{id}` | 仅含本人任务的订单 | 403 |
| `GET /api/tasks` | 不适用（仅客户经理/负责人） | 403 |
| `GET /api/tasks/my` | 本人任务 | 不出现 |
| `GET /api/tasks/{id}` | 本人任务 | 403 |

## 4. 契约与实现的一致性

- 本文"接口总览"表被 `check-contract.ps1` 的 G3 与 `ApiController.java` 双向核对：文档登记了代码没有 → FAIL；代码有文档没登记 → FAIL；
- 前端 `src/api/index.js` 的每个请求路径同样被核对；
- 角色列被 G4 与 `@RequireRole` 及 `permissions.json` 核对；
- 因此**加接口必须同时改四处**：Controller、本文、`permissions.json`、前端 api 层。
