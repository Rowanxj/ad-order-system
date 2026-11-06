# `AGENTS.md` 模板（项目宪法）

> 复制到项目根目录，改掉方括号里的内容。这份文件是**给 AI 读的约束**，不是给人看的介绍——所以每条都要可判定（能验证有没有遵守）。

```markdown
# 项目宪法 · 广告物料订单系统

本项目按「契约驱动交付」推进：`docs-framework/` 与 `docs-framework/contracts/` 是唯一事实来源。
任何编码动作之前，先读 `docs-framework/` 与 `.dsh/skills/ad-order-spec-driven/references/domain/ad-order-system.md`。

## 1. 技术栈锁定

| 层 | 选型 | 版本 | 不许引入 |
|---|---|---|---|
| 后端 | Spring Boot + MyBatis | 3.3.x / 3.0.x | JPA、Hibernate、MyBatis-Plus（本仓库不用） |
| 语言 | Java | 17+ | Kotlin、Groovy |
| 数据库 | MySQL | 8.0 | 其他数据库方言 |
| 前端 | Vue 3 + Vite + Element Plus + Pinia | — | React、Ant Design、UI 框架替换 |
| 认证 | JWT（jjwt）+ 拦截器 + `@RequireRole` | — | Spring Security（当前未引入） |

未在本表内的依赖，引入前必须说明理由并得到确认。

## 2. 分层与目录

```
backend/src/main/java/com/course/adorder/
├── common/      统一响应 Result、BusinessException、全局异常处理
├── config/      拦截器注册、跨域
├── security/    JWT、登录上下文、角色注解
├── controller/  REST 入口，只做参数接收与响应包装
├── service/     业务规则与状态机（唯一允许改状态的地方）
├── mapper/      MyBatis 接口
├── entity/      数据实体 + 状态常量
└── dto/         请求 DTO（record）
frontend/src/{api,router,stores,layout,utils,views}
```

红线：

- Controller 里**禁止**写业务判断（除参数非空校验）；
- Service 里**禁止**直接拼 HTML/视图相关逻辑；
- 前端组件**禁止**直接 `import axios`，必须走 `src/api/`。

## 3. 状态与常量

1. 状态值**只能**来自实体常量（`MaterialOrder.PENDING_CONFIRM`），禁止裸写字符串；
2. 新增状态必须同时改四处：实体常量、`docs-framework/contracts/state-machine.json`、`frontend/src/utils/constants.js`、DDL 注释；
3. 状态迁移前必须校验**当前态**，格式固定：
   `if (!Entity.TARGET.equals(entity.getStatus())) throw new BusinessException(CONFLICT, "...（当前：" + x + "）");`
4. 禁止接受前端传入的状态字段做赋值。

## 4. 权限

1. 入口按 `permissions.json` 决定是否加 `@RequireRole`；
2. 任何入参含 id 的写接口，服务层必须比对数据归属，失败抛 403；
3. 列表接口的数据范围在 **SQL 层**过滤，不许查到内存再过滤；
4. 新增路由必须同时登记 `permissions.json`，否则门禁 G4 会 FAIL。

## 5. 留痕

1. 凡是让"客户看到的事实"发生变化的写操作，必须写 `operation_log`；
2. 留痕调用 `orderService.writeLog(...)`，必须与业务写入**同方法同事务**；
3. 动作码必须登记在 `audit-actions.json`，格式 `大写_蛇形`；
4. 改值类动作的 `detail` 必须写成「原值 → 新值」；
5. 操作人信息从 `UserContext` 取，禁止由前端传入。

## 6. 事务与异常

1. 写方法加 `@Transactional`，跨表写入必须同事务；
2. 不使用 `try/catch` 吞异常；业务错误抛 `BusinessException(code, 中文消息)`；
3. 错误消息必须**能直接给客户看**：带当前状态、剩余数量等上下文，不带类名与英文枚举；
4. 错误码只用 400/401/403/404/409/500，不自造。

## 7. 前端

1. 状态标签统一从 `utils/constants.js` 取，页面不写中文映射；
2. 所有请求经 `src/api/` 封装，组件不拼 URL；
3. 禁止 `alert()`、`console.log`、`debugger`；
4. 提交类按钮必须有 `loading` 且防重复；
5. 401 由拦截器统一处理跳转，页面不各自处理。

## 8. 输出与协作格式

- 一次只实现**一个 flow**，一轮一提交；
- 输出完整文件，禁止 `// ...省略`、`// 其余不变`；
- 改动前先说明：改哪个契约、改哪个文件、为什么；
- 结论必须可验证：说"改好了"要附 `check-contract.ps1` / `check-api.ps1` 的结果。

## 9. 收工前必须执行

```powershell
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1
```

FAIL 不为 0 时，不许声称任务完成。若确认为脚本误报，先修脚本假设，再改代码。
```

## 使用建议

- 把这份文件放项目根 `AGENTS.md`；DSH 每轮会读它，等于每次对话都重新上了一遍规矩。
- 与其他工具协作时，同一内容可复制为 `.rules` / `.cursorrules`。
- **别写空话**：一句"遵循最佳实践"不会改变任何行为；一句"新增状态必须同时改四处"能。
- 每次因为 AI 犯错而新增一条规则时，同时想清楚"怎么检查它有没有遵守"——检查不了的规则，最后都会被忽略。
