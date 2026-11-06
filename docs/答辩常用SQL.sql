-- =============================================================
-- 广告物料订单系统 · 答辩常用 SQL
-- 用法：Navicat 连接 localhost:3306 → 打开 ad_order_system → 新建查询 → 复制一条运行
-- 说明：全部是 SELECT 只读查询，不会修改任何数据
-- 演示订单：901（二店开业整套物料）  演示任务：1（开业主海报）
-- 换订单时把 901 改成目标订单 id；换任务时把 1 改成目标任务 id
-- =============================================================


-- 1. 订单 901 的状态与任务统计（409 演示：未确认应为 2）
USE ad_order_system;
SELECT o.id,
       o.order_no,
       o.title,
       o.status,
       o.deadline,
       (SELECT COUNT(*) FROM material_task t WHERE t.order_id = o.id) AS total_tasks,
       (SELECT COUNT(*) FROM material_task t WHERE t.order_id = o.id AND t.status = 'CONFIRMED') AS confirmed_tasks,
       (SELECT COUNT(*) FROM material_task t WHERE t.order_id = o.id AND t.status <> 'CONFIRMED') AS unconfirmed_tasks
FROM material_order o
WHERE o.id = 901;


-- 2. 订单 901 的任务明细（409 演示：2 行 PENDING，未分派）
USE ad_order_system;
SELECT t.id,
       t.name,
       t.status,
       t.assignee_id,
       u.username,
       u.real_name,
       t.deadline
FROM material_task t
LEFT JOIN sys_user u ON u.id = t.assignee_id
WHERE t.order_id = 901
ORDER BY t.id;


-- 3. 订单 901 的操作留痕（409 失败后：不应出现 ORDER_COMPLETED）
USE ad_order_system;
SELECT id,
       action,
       action_text,
       detail,
       operator_name,
       operator_role,
       created_at
FROM operation_log
WHERE order_id = 901
ORDER BY id DESC;


-- 4. 409 的硬证据：交付成功留痕的条数（失败后应为 0）
USE ad_order_system;
SELECT COUNT(*) AS completed_count
FROM operation_log
WHERE order_id = 901 AND action = 'ORDER_COMPLETED';


-- 5. 任务 1 的归属（403 证据：assignee_id = 3，即 designer1）
USE ad_order_system;
SELECT t.id,
       t.name,
       t.status,
       t.assignee_id,
       u.username,
       u.real_name
FROM material_task t
LEFT JOIN sys_user u ON u.id = t.assignee_id
WHERE t.id = 1;


-- 6. 账号 id 对照（designer1 = 3，designer2 = 4）
USE ad_order_system;
SELECT id, username, real_name, role, status
FROM sys_user
WHERE id IN (3, 4)
ORDER BY id;


-- 7. 可选：按订单号查订单 id（忘记 901 时用）
USE ad_order_system;
SELECT id, order_no, title, status
FROM material_order
WHERE order_no = 'AO202610280091';


-- 8. 可选：所有进行中订单及未确认数（临时找演示订单时用）
USE ad_order_system;
SELECT o.id,
       o.order_no,
       o.title,
       o.status,
       (SELECT COUNT(*) FROM material_task t
        WHERE t.order_id = o.id AND t.status <> 'CONFIRMED') AS unconfirmed_tasks
FROM material_order o
WHERE o.deleted = 0 AND o.status = 'IN_PROGRESS'
ORDER BY o.id;
