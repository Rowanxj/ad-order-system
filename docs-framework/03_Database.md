# 数据库设计

## 1. 设计约定

- 主键 `id BIGINT AUTO_INCREMENT`；
- 时间 `DATETIME`，统一 `DEFAULT CURRENT_TIMESTAMP`，更新列用 `ON UPDATE`；
- 状态类 `VARCHAR(20)` 存枚举字符串（与 `state-machine.json` 完全一致）；
- 逻辑删除 `deleted TINYINT`（仅 `customer`、`material_order` 使用）；
- 引擎 `InnoDB`，字符集 `utf8mb4`；
- **可执行 DDL 的唯一来源是 `sql/schema.sql`**，本文是它的说明与索引理由，两边不一致以 schema.sql 为准并立即修本文。

## 2. ER 图

```mermaid
erDiagram
| sys_user | --o{ material_order : "created_by" |
| sys_user | --o{ material_task : "assignee_id" |
| customer | --o{ material_order : "customer_id" |
| material_order | --o{ material_task : "order_id" |
| material_task | --o{ design_version : "task_id" |
| material_task | --o{ revision_feedback : "task_id" |
| material_order | --o{ operation_log : "order_id" |
```

## 3. 表结构

### 3.1 `sys_user` 系统用户

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | AI | 主键，演示数据固定 1–4 |
| username | VARCHAR(50) | 否 | 登录名，唯一 |
| password | VARCHAR(128) | 否 | 密码摘要，格式见 §5 |
| real_name | VARCHAR(50) | 否 | 姓名（留痕时冗余写入日志） |
| role | VARCHAR(20) | 否 | `SALES` / `MANAGER` / `DESIGNER` |
| phone | VARCHAR(20) | 是 | 联系电话 |
| status | TINYINT | 否 | 1 | 1 启用 / 0 停用 / 2 待启用（自助注册后需负责人启用）；非启用状态不可登录、不可被分派 |
| created_at | DATETIME | 否 | now |

### 3.2 `customer` 客户

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | AI |
| name | VARCHAR(100) | 否 | 客户名称（公司/店铺） |
| contact_person | VARCHAR(50) | 否 | 联系人 |
| contact_phone | VARCHAR(20) | 是 |
| industry | VARCHAR(50) | 是 | 所属行业 |
| remark | VARCHAR(255) | 是 |
| created_by | BIGINT | 否 | 登记人（客户经理） |
| created_at | DATETIME | 否 | now |
| deleted | TINYINT | 否 | 0 | 逻辑删除 |

### 3.3 `material_order` 物料订单

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | AI |
| order_no | VARCHAR(32) | 否 | `AO+yyyyMMdd+4位序号`，**唯一** |
| customer_id | BIGINT | 否 |
| title | VARCHAR(100) | 否 |
| description | VARCHAR(500) | 是 | 整体需求说明 |
| deadline | DATE | 是 | 整体交付截止日期 |
| status | VARCHAR(20) | 否 | PENDING_CONFIRM | 见状态机契约 |
| created_by | BIGINT | 否 | 登记人 |
| created_at / updated_at | DATETIME | 否 | now |
| deleted | TINYINT | 否 | 0 |

### 3.4 `material_task` 物料任务

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | AI |
| order_id | BIGINT | 否 | 所属订单 |
| material_type | VARCHAR(20) | 否 | `POSTER`/`STAND`/`MENU`/`BROCHURE`/`ONLINE` |
| name | VARCHAR(100) | 否 | 物料名称 |
| spec | VARCHAR(100) | 是 | 尺寸/规格 |
| quantity | INT | 否 | 1 | 数量 |
| usage_desc | VARCHAR(255) | 是 | 用途说明 |
| reference_note | VARCHAR(500) | 是 | 参考说明/客户原话 |
| deadline | DATE | 是 | 该物料截止日期 |
| status | VARCHAR(20) | 否 | PENDING | 见状态机契约 |
| assignee_id | BIGINT | 是 | 设计制作人员；未分派为空 |
| assigned_at | DATETIME | 是 | 分派时间 |
| created_at / updated_at | DATETIME | 否 | now |

### 3.5 `design_version` 设计版本

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | AI |
| task_id | BIGINT | 否 |
| version_no | INT | 否 | 1,2,3… **与 task_id 组合唯一** |
| file_name | VARCHAR(200) | 否 | 原始文件名（下载时回显） |
| file_path | VARCHAR(255) | 否 | 相对路径 `yyyyMM/{uuid}.ext` |
| file_size | BIGINT | 否 | 0 | 字节 |
| note | VARCHAR(255) | 是 | 提交说明 |
| uploaded_by | BIGINT | 否 |
| uploaded_at | DATETIME | 否 | now |

### 3.6 `revision_feedback` 客户修改意见

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | AI |
| task_id | BIGINT | 否 |
| round_no | INT | 否 | 第几轮意见，递增 |
| content | VARCHAR(500) | 否 | 客户原话 |
| recorded_by | BIGINT | 否 | 记录人（客户经理） |
| recorded_at | DATETIME | 否 | now |

### 3.7 `operation_log` 操作历史

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | AI |
| biz_type | VARCHAR(10) | 否 | `ORDER` / `TASK` |
| biz_id | BIGINT | 否 | 业务对象 id（新增任务时记订单 id） |
| order_id | BIGINT | 否 | 冗余，便于按订单一次查出整条时间线 |
| action | VARCHAR(40) | 否 | 动作码，取值见 `contracts/audit-actions.json` |
| action_text | VARCHAR(100) | 否 | 中文短句 |
| detail | VARCHAR(500) | 是 | 变更说明；改值类必须写「原值 → 新值」 |
| operator_id / operator_name / operator_role | BIGINT / VARCHAR(50) / VARCHAR(20) | 否 | **冗余存储**：留痕要能回答"当时是谁、以什么身份" |
| created_at | DATETIME | 否 | now |

## 4. 索引与容量

| 表 | 索引 | 类型 | 用途 |
| --- | --- | --- | --- |
| sys_user | uk_username(username) | 唯一 | 登录 |
| customer | idx_name(name) | 普通 | 关键词搜索 |
| material_order | uk_order_no(order_no) | 唯一 | 取号防重（并发兜底） |
| material_order | idx_customer / idx_status | 普通 | 列表筛选 |
| material_task | idx_order / idx_assignee / idx_status | 普通 | 详情、我的任务、未确认统计 |
| design_version | uk_task_version(task_id, version_no) | 唯一 | 版本号并发兜底 |
| design_version | idx_task(task_id) | 普通 | 版本时间线 |
| revision_feedback | uk_task_round(task_id, round_no) | 唯一 | 轮次并发兜底 |
| revision_feedback | idx_task(task_id) | 普通 | 意见列表 |
| operation_log | idx_order(order_id) / idx_biz(biz_type,biz_id) | 普通 | 订单时间线、任务时间线 |

量级预估：订单 ≤ 1e4、任务 ≤ 1e5、日志 ≤ 1e6，上述索引足够；日志表增长后可加按月归档。

## 5. 密码摘要格式

`PasswordUtil` 支持两种格式，登录时自动识别：

| 格式 | 示例 | 来源 |
| --- | --- | --- |
| PBKDF2（当前写入格式） | `pbkdf2$120000$<saltBase64>$<hashBase64>` | 新密码、重置密码 |
| 兼容旧格式 | 64 位十六进制 SHA-256(salt + 明文) | 历史演示数据 |

升级策略：旧格式用户**登录成功后自动**改写为新格式（`AuthService.login` → `UserMapper.updatePassword`），升级失败只记日志、不影响本次登录。因此无需清库或重置密码，`sql/data.sql` 中的历史哈希会在首次登录时自然淘汰。

## 6. 已知风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 上传文件与本机绑定 | 多实例/迁移后文件不可用 | 生产改对象存储 |
| 日志表持续增长 | 查询变慢 | 目前量级小；后续按 `created_at` 归档 |
| 分页 `LIMIT` 深翻页 | 页码很大时变慢 | 当前数据量小；必要时改游标分页（`id < lastId`） |
