# Git 日常更新指南（本项目专用）

仓库：<https://github.com/flystreamqi/ad-order-system>

已经配置好的东西（一次性，不用再弄）：

- 远端 `origin` 指向 `https://github.com/flystreamqi/ad-order-system.git`
- 默认分支 `main`，已与 `origin/main` 建立跟踪
- `.gitignore` 已排除 `node_modules/`、`target/`、`.env`、`logs/`、`uploads/`
- `.gitattributes` 保证 `.bat/.ps1` 克隆下来仍是 CRLF

---

## 一、以后每次更新，就这四步

在项目根目录 `D:\Programming_learning\Program1` 打开 PowerShell：

```powershell
git status                 # 1. 看这次改了哪些文件（红色=未暂存，绿色=已暂存）
git add -A                 # 2. 把所有改动放进"待提交"区
git commit -m "改了什么的说明"   # 3. 存成一个版本（本地）
git push                   # 4. 推到 GitHub
```

四步里只有 `git commit` 需要写说明，其余照抄即可。

### 提交说明怎么写

一句话说清"改了什么"，不要写 "update"、"修改" 这种看不出内容的。

```
feat: 订单列表增加逾期筛选
fix: 修复任务分派后状态没刷新的问题
docs: 补充数据库设计说明
style: 调整登录页样式
chore: 升级前端依赖
```

前缀不是强制的，但养成习惯后，以后回看历史一眼就知道改了什么。

---

## 二、常用的查看命令

```powershell
git status           # 当前有没有未提交的改动
git log --oneline    # 提交历史（一行一个）
git diff             # 还没 add 的具体改动内容
git diff --cached    # 已经 add、还没 commit 的内容
```

---

## 三、改错了怎么办

| 情况 | 命令 | 说明 |
|---|---|---|
| 文件改坏了，还没 add | `git restore 文件名` | 恢复到上次提交的样子 |
| 已经 add 了，想撤出暂存区 | `git restore --staged 文件名` | 改动还在，只是不准备提交 |
| commit 了但还没 push | `git reset --soft HEAD~1` | 撤销最近一次提交，改动保留 |
| 已经 push 了，想撤回 | `git revert 提交号` | 生成一个"反向提交"，历史保留，安全 |
| 想看某个版本的文件 | `git show 提交号:文件路径` | 不改工作区，只看 |

> 已经 push 的内容尽量用 `revert`，不要用 `reset --hard` 强推，容易把历史搞乱。

---

## 四、换电脑 / 别人拿到代码

```powershell
git clone https://github.com/flystreamqi/ad-order-system.git
```

克隆下来是源码，**不含** `node_modules` / `target` / `.env`，需要按 `使用说明.md` 自己装依赖、配数据库口令。

---

## 五、本项目的三个坑

1. **别把大文件目录加进来**
   `backend/target/`、`frontend/node_modules/` 已经在 `.gitignore` 里，不要用 `git add -f` 强行加，否则仓库会撑到几百 MB。

2. **`.env` 里有你的数据库口令**
   它被 `.gitignore` 排除，不会上传。也别把 `.env` 改名成别的名字提交。

3. **`启动.bat` 必须保持 GBK + CRLF 换行**
   用 VS Code 打开它会显示乱码（这是正常的，文件本身是 GBK）。如果非要改，改完必须另存为 **GBK 编码**，否则双击又会报一堆 `'xxx' 不是内部或外部命令`。
   最稳妥：要改启动脚本，直接说一声，用脚本重写，别手改。

---

## 六、网络问题

本机 git 已配置走本地代理：

```powershell
git config --global http.https://github.com.proxy http://127.0.0.1:12450
```

如果哪天 `git push` 报 `Connection was reset` / `Failed to connect`：

1. 先确认代理软件开着（就是监听 `127.0.0.1:12450` 的那个）；
2. 代理端口变了的话，重新设置：
   ```powershell
   git config --global http.https://github.com.proxy http://127.0.0.1:新端口
   ```
3. 想取消代理：
   ```powershell
   git config --global --unset http.https://github.com.proxy
   ```

---

## 七、一张图记住

```
改代码 → git status 看 → git add -A → git commit -m "说明" → git push
```

本地提交（commit）可以有很多次，push 一次会把之前所有未推送的提交一起送上去。
