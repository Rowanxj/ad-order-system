# 阶段 3–4 · 骨架红灯 → 逐场景点亮

## 阶段 3：只搭壳

目标：让项目**能编译、能启动、测试全红**。此时一个业务方法都不许有真实逻辑。

### 3.1 后端空壳

按契约加路由，Service 方法体统一：

```java
@Service
public class TaskService {
    /** 记录客户确认：待客户确认 → 已确认 */
    @Transactional
    public void confirmTask(LoginUser user, Long taskId) {
        throw new UnsupportedOperationException("待实现");
    }
}
```

同时把**不该有逻辑**的地方也留好位置：`@RequireRole` 按 `permissions.json` 写、`@Transactional` 先加上（哪怕空壳）、DTO 按 `03_Database.md` 的字段写。

### 3.2 测试先红

两条路，都要跑起来：

| 层次 | 载体 | 何时用 |
|---|---|---|
| 契约级 | `docs-framework/contracts/acceptance.json` | 接口已存在但逻辑未实现：现在跑必然红 |
| 单元级 | JUnit + Mockito | 状态守卫这类纯判断逻辑，需要断言"抛了什么异常" |

契约级红灯的写法：先把 `acceptance.json` 写全（这是设计），再跑 `check-api.ps1`，此时应当是"接不上"或大量 FAIL。等你点亮时，它一条条变绿。

### 3.3 前端骨架

- `api/index.js` 先把方法名按 `04_API.md` 写全（可以指向空实现）；
- 页面只做表格、表单、空态、`v-loading`；
- 不接真实数据，也不许留 mock 分支到开发路径。

### 骨架门禁

```powershell
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1 -Stage skeleton
```

此刻的 FAIL 只允许来自"禁项"（比如遗留 `alert()`），`UnsupportedOperationException` 是预期的，脚本会给一条 INFO 统计。

---

## 阶段 4：一次点亮一个场景

一轮只做**一个 flow**，做完就跑门禁、跑验收、更新 `test-record.md`。三个场景一起做，红的时候你分不清是谁的错。

### 4.1 状态变更的标准四步

顺序固定，不许调换：

```java
@Transactional
public void confirmTask(LoginUser user, Long taskId) {
    // ① 读现状
    MaterialTask task = requireExists(taskId);

    // ② 校验前置（状态 + 跨表条件），失败抛 409
    if (!MaterialTask.PENDING_CONFIRM.equals(task.getStatus())) {
        throw new BusinessException(BusinessException.CONFLICT,
                "仅“待客户确认”状态的任务可以确认（当前：" + task.getStatus() + "）");
    }

    // ③ 写入目标态（目标态写死，绝不接受前端传入）
    taskMapper.updateStatus(taskId, MaterialTask.CONFIRMED);

    // ④ 同事务留痕
    orderService.writeLog("TASK", taskId, task.getOrderId(), "TASK_CONFIRMED",
            "客户确认", "【" + task.getName() + "】客户确认当前版本，任务完成", user);
}
```

四个常见错误写法，见一个改一个：

| 错误写法 | 后果 |
|---|---|
| `task.setStatus(req.getStatus())` | 前端可以任意改状态 |
| `if (req.getStatus().equals("CONFIRMED")) ...` | 用请求参数当状态判断依据 |
| 先 `updateStatus` 再校验 | 校验失败但已写入 |
| 校验与写入之间夹着远程调用 | 事务边界被拉长，锁竞争 |

### 4.2 双闸权限

```java
// 闸一：入口按契约注解
@PutMapping("/tasks/{id}/start")
public Result<Void> startTask(...)   // guard=service，所以这里没有 @RequireRole

// 闸二：服务层按数据归属
private void requireAssignee(MaterialTask task, LoginUser user) {
    if (!user.getId().equals(task.getAssigneeId())) {
        throw new BusinessException(BusinessException.FORBIDDEN, "设计人员只能处理分派给自己的任务");
    }
}
```

判定"我要不要加闸二"：**这个接口的入参里，有没有一个 id 指向"别人的数据"？** 有就必须比对归属。

列表类接口的归属过滤放在 SQL 层，不要查出来再在 Java 里 `filter`——那样数据已经泄露到内存，且分页会错：

```xml
<if test="viewerRole == 'DESIGNER'">
    AND EXISTS (SELECT 1 FROM material_task t WHERE t.order_id = o.id AND t.assignee_id = #{viewerId})
</if>
```

### 4.3 跨表前置条件

最容易漏的一类校验：**状态对，但业务不满足**。例如"订单交付"：

```java
checkTransition(order.getStatus(), MaterialOrder.IN_PROGRESS, "订单");
int unconfirmed = taskMapper.countUnconfirmedByOrderId(id);
if (unconfirmed > 0) {
    throw new BusinessException(BusinessException.CONFLICT,
            "还有 " + unconfirmed + " 项物料任务未确认，整笔订单不能交付");
}
```

两个要求：

1. 错误信息要**带数量**——"还有 2 项未确认"比"不能交付"有用一万倍；
2. 统计用 `COUNT` 查询，不要 `selectList` 后在内存里数（数据量一大就慢，且可能被分页截断）。

### 4.4 文件上传

三条底线：

```java
// 白名单，不放行未知扩展名
if (!ext.matches("\\.(png|jpg|jpeg|gif|webp|pdf|psd|ai|cdr|zip|rar)")) { ... }

// 磁盘名不可预测，原名只进数据库
String stored = month + "/" + UUID.randomUUID() + ext;

// 读取时防目录穿越
if (relativePath.contains("..")) { throw new BusinessException(BAD_REQUEST, "非法文件路径"); }
Path p = uploadDir.resolve(relativePath).normalize();
if (!p.startsWith(uploadDir) || !Files.exists(p)) { throw ... }
```

注意 `normalize()` 之后还要再 `startsWith` 判定——只做其中一个都会被绕过。

### 4.5 前端三件事

| 事项 | 正确写法 | 错误写法 |
|---|---|---|
| 错误提示 | Axios 拦截器统一 `ElMessage.error(message)` | 组件里 `alert()` |
| 防重复提交 | 按钮 `:loading="submitting"` + 首行 `if (submitting) return` | 只靠禁用样式 |
| 状态展示 | 统一从 `utils/constants.js` 取 `label` / `type` | 页面里手写 `v-if="status === 'IN_PROGRESS'"` 套中文 |

第三条不是洁癖：状态标签散落在页面里，新增状态时必然漏一处，页面就出现空白标签。门禁 G5 抓的就是这个。

### 4.6 每轮的收尾动作

```powershell
# 1) 静态对账
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1

# 2) 运行时验收（后端在跑）
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-api.ps1 -Markdown

# 3) 把 Markdown 表格贴进 docs/test-record.md，写清本轮点亮了哪个 flow
```

三条同时满足才算点亮：

- [ ] 本场景相关的验收用例全绿，且**没有为了让绿而改 `expect`**；
- [ ] 本场景新增/修改的状态迁移已登记进 `state-machine.json`；
- [ ] 前端没有绕过 `src/api/` 直接发请求，没有 mock 残留。

## 常见回退信号

看到下面任何一条，立刻停下回到上一阶段，不要继续堆代码：

| 信号 | 说明 |
|---|---|
| 验收用例改了 `expect` 才变绿 | 契约被实现绑架了 |
| 代码里出现契约中没有的状态字符串 | 领域模型已经跑偏 |
| 一个方法里出现两次 `updateStatus` | 状态机被拆进了业务分支 |
| 错误码开始出现 4xx 以外的自定义码 | 错误码体系正在失控 |
| 前端开始自己拼 URL | 接口契约正在失效 |
