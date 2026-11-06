# 系统设计说明（索引）

> **规格以 `docs-framework/` 为唯一来源。** 本文只保留该目录里没有的两块内容——「六态反馈设计」与「过程记录」——其余一律给指针，避免两套设计说明各自漂移。

## 1. 规格去哪看

| 想知道什么 | 看哪里 |
|---|---|
| 业务背景、角色边界、页面元素 | [`docs-framework/01_PRD.md`](../docs-framework/01_PRD.md) |
| 技术栈、分层、鉴权链路、设计决策 | [`docs-framework/02_Architecture.md`](../docs-framework/02_Architecture.md) |
| 7 张表、索引、密码格式、已知风险 | [`docs-framework/03_Database.md`](../docs-framework/03_Database.md) |
| 31 个接口的请求/响应/失败示例 | [`docs-framework/04_API.md`](../docs-framework/04_API.md) |
| 每个业务场景怎么走 | [`docs-framework/flow/`](../docs-framework/flow/)（全局 + 4 篇场景） |
| 状态机/权限/留痕/验收的机器契约 | [`docs-framework/contracts/`](../docs-framework/contracts/) |
| 验收点 → 实现位置 → 用例 | [`docs-framework/acceptance-matrix.md`](../docs-framework/acceptance-matrix.md) |
| 测试证据 | [`test-record.md`](test-record.md) |

数据表与状态机的图、字段、迁移条件都在上面几份文档里，本文不重复。

## 2. 六态反馈设计（作业底线）

每个页面必须显式处理「加载 / 空数据 / 成功 / 失败 / 冲突 / 无权限」六种状态。当前实现：

| 页面 | 加载 | 空数据 | 成功 | 失败 | 冲突（409） | 无权限（403）/ 未登录（401） |
|---|---|---|---|---|---|---|
| `/login` 登录 | 按钮 `:loading` | — | 跳转工作台 | `ElMessage.error`（账号或密码错误） | — | — |
| `/dashboard` 工作台 | 页级 `v-loading` | `el-empty` | 指标与待办刷新 | 拦截器统一 `ElMessage` | — | 401 跳登录 |
| `/exceptions` 异常管理 | 页级 `v-loading` | `el-empty` | 异常处理成功后刷新 | `ElMessage.error` | — | 菜单不渲染（SALES/MANAGER） |
| `/orders` 订单列表 | 表格 `v-loading` | `el-empty` | 列表刷新 | 拦截器统一 `ElMessage` | 同上，并刷新 | 设计人员只见相关订单；401 清登录态跳登录 |
| `/orders/new` 新建订单 | 提交按钮 `:loading="saving"` | —（表单页） | 跳订单详情 | 前端 `ElMessage.warning` 定位字段；后端 400 显示 message | — | 菜单不渲染（SALES 专属） |
| `/orders/:id` 订单详情 | 页级 `v-loading` | 任务表、操作历史各自 `el-empty` | 状态标签与时间线更新 | `ElMessage.error` | 提示当前状态原因并刷新 | 403 提示且不渲染数据 |
| `/tasks/:id` 任务详情 | 页级 `v-loading` | 版本时间线、意见列表各自 `el-empty` | 版本/意见追加 | `ElMessage.error` | 同上 | 403 提示（设计人员访问他人任务） |
| `/tasks` 任务工作台 | 表格 `v-loading` | `el-empty` | 列表刷新、分派成功提示 | `ElMessage.error` | — | 菜单不渲染（MANAGER 专属）；后端允许 SALES/MANAGER，设计人员 403 |
| `/my-tasks` 我的任务 | 页级 `v-loading` | `el-empty` | 卡片刷新 | `ElMessage.error` | — | 401 跳登录 |
| `/customers` 客户管理 | 表格 `v-loading` | `el-empty` | 列表刷新 | `ElMessage.error` | — | 菜单不渲染（SALES/MANAGER） |

统一机制：

- **错误提示**：`frontend/src/api/request.js` 的响应拦截器解包 `{code,message}`，`code !== 0` 时统一 `ElMessage.error(message)`，页面不再各自处理；
- **401**：拦截器清 `localStorage` 的 token 与用户信息并 `router.push('/login')`；
- **禁止** `alert()`——门禁 G7 会扫。

## 3. 过程记录

### 3.1 初版（课程阶段）

由 AI 辅助生成骨架与实现，人工完成：业务规则核对（状态机矩阵）、接口联调、越权场景验证、以及 2 处缺陷修复（`RequestDtos` 缺失 import；上传文件存储路径拼接错误）。

### 3.2 2026-11-04 契约驱动优化轮

| 类别 | 内容 |
|---|---|
| 文档 | 补齐 `01_PRD` / `02_Architecture` / `03_Database` / `04_API` + 4 篇场景 flow + 验收矩阵 |
| 契约 | 采纳 4 份机器契约（状态机 / 权限 / 留痕 / 验收），由脚本对账代码 |
| 缺陷修复 | 上传文件 IO 移出事务并补偿删除；订单号与版本号并发重试 + 唯一索引；密码改 PBKDF2 且旧哈希登录时透明升级；配置改环境变量；Mapper 去 `SELECT *` |
| 测试 | 原 `docs/api-test.ps1` 的 31 条硬编码断言已由 `docs-framework/contracts/acceptance.json` 承接（脚本删除），用例改由契约驱动，新增场景无需改脚本 |

详细证据见 [`test-record.md`](test-record.md) §五。
