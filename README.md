# 广告制作公司宣传物料订单管理系统

AI 全栈课程贯穿式大作业 · 常规赛道选题一。系统管理"一笔宣传物料订单从登记、确认、分派、设计、修改到客户确认交付"的完整业务过程，重点体现岗位分工、状态流转、变更留痕与权限边界。

## 一、技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3 + Vite + Vue Router + Pinia + Element Plus + Axios |
| 后端 | Spring Boot 3.3 + MyBatis 3.0 + JWT（jjwt）+ Bean Validation |
| 数据库 | MySQL 8.0（7 张表，utf8mb4） |
| 认证 | 登录签发 JWT，拦截器统一校验，`@RequireRole` 方法级角色控制 |

## 二、快速开始（新环境部署）

前置要求：JDK 17+、Maven 3.8+、Node 18+、MySQL 8.0。

```bash
# 1. 建库与初始化数据（含演示账号）
mysql -uroot -p < sql/schema.sql
mysql -uroot -p < sql/data.sql

# 2. 配置数据库口令：复制 .env.example 为 .env，修改 DB_PASSWORD
#    （application.yml 也会自动加载项目根目录或 backend/ 下的 .env）

# 3. 启动后端（8080）
cd backend
mvn -s ../maven-settings.xml clean package -DskipTests
java -jar target/ad-order-backend-1.0.0.jar

# 4. 启动前端（5173，已配置代理到 8080）
cd frontend
npm install
npm run dev
```

浏览器访问 <http://localhost:5173>。

### 演示账号（密码均为 123456）

| 账号 | 姓名 | 角色 | 主要操作 |
| --- | --- | --- | --- |
| `sales` | 张欣怡 | 客户经理 | 登记/取消订单、新增物料、记录客户意见、记录客户确认 |
| `manager` | 王建国 | 业务负责人 | 确认订单、分派/改派任务、订单交付 |
| `designer1` | 李文静 | 设计制作人员 | 开始设计、上传设计稿版本 |
| `designer2` | 陈涛 | 设计制作人员 | 同上（用于演示数据归属） |

## 三、角色与状态机

```
订单：PENDING_CONFIRM(待确认) → IN_PROGRESS(进行中) → COMPLETED(已交付)
      PENDING_CONFIRM → CANCELLED(已取消)

任务：PENDING(待分派) → DESIGNING(设计中) → PENDING_CONFIRM(待客户确认)
      PENDING_CONFIRM → REVISION(修改中) → PENDING_CONFIRM → CONFIRMED(已确认)
```

### 关键业务规则（与验收场景对应）

1. 只有业务负责人能确认订单；确认后物料清单不可直接覆盖，整体截止日期变更、新增物料均写入变更记录（`operation_log`）。
2. 设计制作人员只能查看/处理分派给自己的任务（接口级数据归属校验）。
3. 上传设计稿只是提交（进入"待客户确认"），不代表客户确认；只有客户经理能记录客户确认结果。
4. 设计版本只追加不覆盖，任何历史版本可随时查看与下载。
5. 订单内全部物料任务确认后，整笔订单才能交付。
6. 每一步关键操作（创建/确认/分派/上传/意见/变更/交付）均记录操作人、角色、时间与说明。
7. 整体截止/物料截止不能早于今天；物料截止不能晚于整单截止；改整单截止不能早于最晚物料截止。
8. 工作台 `/dashboard` 汇总待确认、待分派、待确认物料、逾期与进行中；异常管理 `/exceptions` 集中处理逾期、待分派、待客户确认与已延期。

## 四、项目结构

```
ad-order-system/
├── sql/                 # 建表与初始化数据
├── backend/             # Spring Boot 后端
│   └── src/main/java/com/course/adorder/
│       ├── common/      # 统一响应 Result、业务异常、全局异常处理
│       ├── config/      # 拦截器与跨域配置
│       ├── security/    # JWT、登录上下文、角色注解
│       ├── controller/  # REST 接口（/api/**）
│       ├── service/     # 业务逻辑与状态机规则
│       ├── mapper/      # MyBatis 接口
│       └── entity/      # 数据实体
│   └── src/main/resources/mapper/  # MyBatis XML
├── frontend/            # Vue 3 前端
│   └── src/{api,router,stores,layout,views,utils}
├── docs/                # 设计说明、测试记录、API 测试脚本
└── README.md
```

## 五、核心接口清单

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| POST | /api/auth/login | 公开 | 登录，返回 JWT |
| GET | /api/auth/me | 登录用户 | 当前登录用户信息 |
| GET/POST | /api/customers | 客户经理/负责人 | 客户列表/新增 |
| GET/POST | /api/orders | 登录用户 | 订单列表（截止区间/只看逾期/已延期/分页；设计人员自动过滤数据归属）/新建 |
| GET | /api/orders/{id} | 登录用户 | 订单详情（任务+操作历史） |
| PUT | /api/orders/{id}/confirm | 负责人 | 确认订单 |
| PUT | /api/orders/{id}/complete | 负责人 | 订单交付（全部任务已确认） |
| PUT | /api/orders/{id}/cancel | 客户经理/负责人 | 取消订单（仅待确认） |
| PUT | /api/orders/{id}/deadline | 客户经理/负责人 | 修改整体截止日期（确认后留变更记录） |
| POST | /api/orders/{id}/tasks | 客户经理 | 新增物料任务（确认后留变更记录） |
| GET | /api/tasks | 客户经理/负责人 | 任务工作台（状态/分派人/订单/关键词/逾期/未分派/已延期 + 分页） |
| GET | /api/tasks/my | 设计人员 | 我的设计任务工作台 |
| GET | /api/tasks/{id} | 登录用户 | 任务详情（版本+意见） |
| PUT | /api/tasks/{id}/assign | 负责人 | 分派/改派 |
| PUT | /api/tasks/{id}/start | 设计人员本人 | 开始设计 |
| POST | /api/tasks/{id}/versions | 设计人员本人 | 上传设计版本（multipart） |
| POST | /api/tasks/{id}/feedbacks | 客户经理 | 记录客户修改意见 |
| PUT | /api/tasks/{id}/confirm | 客户经理 | 记录客户确认 |
| PUT | /api/tasks/{id}/deadline | 客户经理/负责人 | 修改物料截止日期（留痕） |
| GET | /api/users/designers | 负责人 | 设计人员下拉（仅启用人员，不含密码摘要） |
| GET | /api/files/** | 登录用户 | 设计稿下载（防目录穿越；设计人员仅能下载本人任务） |

统一响应结构：`{ code, message, data }`，`code=0` 成功；401 未登录、403 无权限、404 不存在、409 状态冲突、400 参数错误。

## 六、已知限制

- 密码摘要：新写入为 PBKDF2-HMAC-SHA256（12 万次迭代 + 随机盐 + 恒定时间比较）；`sql/data.sql` 中的历史 SHA-256 哈希在**首次登录成功后自动升级**，无需清库或重置密码。
- 账号接口不输出密码摘要：`User.password` 加了 `@JsonIgnore`，分派下拉也只返回 `{id, username, realName, status}`。
- 设计稿下载做了归属校验：设计人员只能下载分派给自己的任务的设计稿。
- 截止日期边界：整体不能早于今天、不能早于最晚物料截止；物料不能早于今天、不能晚于整单截止；前端选择器同步禁用非法日期，后端仍做最终校验。
- 配置支持项目根目录或 `backend/` 下的 `.env`，并可用 `SERVER_PORT`、`JWT_EXPIRE_HOURS`、`UPLOAD_DIR`、`LOG_LEVEL` 覆盖。
- JWT 密钥与数据库口令支持环境变量覆盖（`JWT_SECRET`、`DB_PASSWORD`、`DB_URL`、`DB_USERNAME`），未设置时回退到本地演示值；生产部署必须显式设置。
- 上传文件存本地磁盘（`backend/uploads/`，已加入 .gitignore），无对象存储；落盘在事务外，写库失败会补偿删除，不留孤儿文件。
- 初始化数据中 design_version 的 demo 文件仅为占位记录，不随库提供真实图片；通过界面新上传的文件可正常预览下载。
- 列表分页：订单列表已分页（默认 10 条/页，`size` 上限 100）；客户与"我的任务"数据量小，仍全量返回。
- 并发一致：状态迁移用**条件更新**（`WHERE status = 前置态`），影响行数为 0 即返回 409，不做"先查后写"；分派同样带 `status='PENDING'` 条件。
- 订单编号、设计版本号、修改意见轮次在并发下可能撞号，靠唯一索引（`uk_order_no`、`uk_task_version`、`uk_task_round`）+ 重试兜底，未做并发压测。

## 七、测试

见 [docs/test-record.md](docs/test-record.md)。单元测试不依赖 MySQL，当前共 80 条：

```powershell
cd backend
mvn -s ..\maven-settings.xml test
```

接口级验收由契约驱动，一键执行：

```powershell
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-api.ps1
```

用例写在 `docs-framework/contracts/acceptance.json`；新增场景只改用例、不改脚本。

## 八、工程质量门禁（契约驱动）

本项目采用契约驱动开发：状态机、权限矩阵、留痕动作以 JSON 契约写在 `docs-framework/contracts/`，由脚本对账代码。流程说明见项目级 skill `.dsh/skills/ad-order-spec-driven/`。

```powershell
# 1) 静态对账：文档↔契约↔DDL↔实体↔Mapper↔Controller↔前端 api 层（不需要数据库）
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1

# 2) 交付/答辩前严格模式（契约缺失即失败）
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1 -Stage delivery -Strict -Markdown

# 3) 运行时验收：66 条真实 HTTP 断言，用例写在 contracts/acceptance.json（需要后端与 MySQL）
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-api.ps1
```

当前状态：静态对账 `FAIL=0 WARN=0`（`-Stage delivery -Strict`）；运行时验收 `PASS=66 FAIL=0`；单元测试 `80/80`。

文档入口：`docs-framework/00_baseline.md`（现状与范围）、`01_PRD.md`、`02_Architecture.md`、`03_Database.md`、`04_API.md`、`flow/`（逐场景）、`acceptance-matrix.md`（验收点 → 实现 → 用例）、`docs/交接优化记录.md`（本轮参考交接版本的采纳与验证）。
