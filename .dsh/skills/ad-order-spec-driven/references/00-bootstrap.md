# 起步：30 分钟内让门禁跑起来

两条路径，先判断自己在哪条上：

| 你的处境 | 走哪条 | 第一阶段产出 |
|---|---|---|
| 仓库里已经有 `backend/` `frontend/` 和能跑的后端（本仓库现状） | 路径 A：接管 | `00_baseline.md` + 采纳契约 |
| 只有选题想法 / 空目录 | 路径 B：从零 | `01_PRD.md` + `state-machine.json` 草稿 |

## 路径 A：接管现有仓库

### A1. 先盘点，不动代码（5 分钟）

```powershell
cd <项目根>
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1 -Stage locate
```

看到「表 N / 接口 N / 实体 N / 页面 N」就说明脚本读懂了你的工程。如果读到 0，先修脚本假设的三件事：`sql/schema.sql` 能建表、Controller 用标准注解、`frontend/src/api/` 有请求封装。

### A2. 全量体检，把"现状欠债"列出来（5 分钟）

```powershell
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1
```

此刻的结果**不代表你做错了**，它代表"项目还没采纳契约"。把 FAIL 抄进 `docs-framework/00_baseline.md`，作为本轮要还的债。

### A3. 采纳契约（10 分钟）

```powershell
New-Item -ItemType Directory -Force docs-framework\contracts
Copy-Item .dsh\skills\ad-order-spec-driven\references\templates\contracts\*.json docs-framework\contracts\
```

然后**逐份核对**，不是照抄：

1. `state-machine.json`：打开 `backend/.../entity/MaterialOrder.java` 与 `MaterialTask.java`，常数名逐一比对；再打开 `frontend/src/utils/constants.js`，确认每个状态都有中文标签。
2. `permissions.json`：打开 `ApiController.java`，逐个路由核对 `@RequireRole`。**特别注意 `guard` 字段**——代码里没有注解但服务层做了归属校验的，写 `"guard": "service"` 并填 `serviceCheck`，不要写成 `annotation`，否则门禁会冤枉它。
3. `audit-actions.json`：搜索全部 `writeLog(`，确认每个动作码都登记、每条登记都被使用。
4. `acceptance.json`：`assigneeId` 这类写死的主键必须和 `sql/data.sql` 的实际自增 id 对齐。

### A4. 复跑，只允许剩"真债"（5 分钟）

```powershell
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1
```

目标：G2/G3/G5/G6 全 PASS。剩下的 FAIL 应该只有两类——**文档还没写**（G1）与**代码确实有缺口**。两者都要进 `00_baseline.md`，不要为了让绿灯而改契约。

### A5. 跑一次真实验收（需要后端 + MySQL）

```powershell
# 1) 建库与造数（会 DROP 重建，确认连的是开发库）
mysql -uroot -p < sql\schema.sql
mysql -uroot -p < sql\data.sql
# 2) 启动后端后
powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-api.ps1
```

全绿说明契约与实现真的一致；任何一条红，都是"你以为实现了，其实没有"。

## 路径 B：从零起步

顺序不能变，每一步都有可检查的产物：

1. **定角色**（1 轮对话）：只问"谁在用这个系统、各自最怕出什么错"。产出角色表。
2. **定状态**（1 轮对话）：只问"这个东西一生要经历哪几种状态、谁能推进它"。产出状态全集与迁移表。
3. **定留痕**（1 轮对话）：只问"哪些动作发生后，客户或其他岗位必须能查到是谁做的"。
4. **写 PRD 与接口契约**：按 `references/01-domain-lock.md` 的模板填，先写页面元素，再写接口，最后反查字段。
5. **落机器契约**：把第 2、3 步的结论填进三份 JSON。
6. **骨架**：Controller + 空 Service + 会失败的用例。
7. **点亮**：一轮一个场景。

不要在这一步之前写任何 Service 业务代码。

## 起步阶段最常踩的五个坑

| 坑 | 现象 | 纠正 |
|---|---|---|
| 把模板契约当圣旨照抄 | 契约里写的和代码不一样，门禁一直红 | 模板只是骨架，字段/角色必须按你的代码改，或按契约改代码 |
| 为了让门禁变绿而改契约 | G4/G5 突然全绿，但业务其实不对 | 改契约要能说出"为什么这次业务规则变了" |
| 直接改 `docs-framework/` 里的旧文档去迁就代码 | 文档变成代码的复印件，失去约束力 | 先判定谁对，再改；改完在文档里记一笔变更原因 |
| 一次点亮三个场景 | 测试红了不知道是哪个功能的问题 | 一轮一个 flow，一轮一次提交 |
| 用 `guard: annotation` 描述服务层校验 | 门禁误报"角色不一致" | 服务层校验用 `guard: service` + `serviceCheck` |

## 完成判据

起步阶段做完的标志不是"代码能跑"，而是这四条同时成立：

- [ ] `check-contract.ps1` 的 FAIL 只剩"文档未写"这类已知项；
- [ ] `check-api.ps1` 至少覆盖一条完整主链路 + 一条越权 + 一条状态冲突；
- [ ] `docs-framework/00_baseline.md` 写清了本轮不做什么；
- [ ] 有一个人（或你自己隔一天）能照文档把系统跑起来。
