-- =============================================================
-- 广告制作公司宣传物料订单管理系统 - 初始化数据 / 演示账号
-- 所有演示账号密码均为 123456
--   sales      张欣怡  客户经理   (SALES)
--   manager    王建国  业务负责人 (MANAGER)
--   designer1  李文静  设计制作   (DESIGNER)
--   designer2  陈涛    设计制作   (DESIGNER)
-- 密码摘要 = SHA-256("adorder" + 明文)
-- =============================================================
USE ad_order_system;

-- 1. 演示账号 -------------------------------------------------
INSERT INTO sys_user (id, username, password, real_name, role, phone) VALUES
(1, 'sales',     'a0ab7cf48c8867fed9569149cf4c75860bfabb158516048571f8d42f66ad8e5b', '张欣怡', 'SALES',    '13800000001'),
(2, 'manager',   'a0ab7cf48c8867fed9569149cf4c75860bfabb158516048571f8d42f66ad8e5b', '王建国', 'MANAGER',  '13800000002'),
(3, 'designer1', 'a0ab7cf48c8867fed9569149cf4c75860bfabb158516048571f8d42f66ad8e5b', '李文静', 'DESIGNER', '13800000003'),
(4, 'designer2', 'a0ab7cf48c8867fed9569149cf4c75860bfabb158516048571f8d42f66ad8e5b', '陈涛',   'DESIGNER', '13800000004');

-- 2. 客户 -----------------------------------------------------
INSERT INTO customer (id, name, contact_person, contact_phone, industry, remark, created_by, created_at) VALUES
(1, '鹿屿奶茶（大学城店）', '林晓',  '13911110001', '餐饮',   '新店开业，物料较多，注意开业日期不可延误', 1, '2026-10-20 09:30:00'),
(2, '花间西餐厅',           '赵倩',  '13911110002', '餐饮',   '老客户，三周年店庆',                       1, '2026-10-21 14:00:00'),
(3, '铁馆健身工作室',       '孙鹏',  '13911110003', '健身',   '已完成合作，留作历史数据',                 1, '2026-10-15 10:00:00');

-- 3. 订单 -----------------------------------------------------
-- 订单1：进行中（主演示数据，三种任务状态齐全）
INSERT INTO material_order (id, order_no, customer_id, title, description, deadline, status, created_by, created_at) VALUES
(1, 'AO202610230001', 1, '鹿屿奶茶开业宣传物料', '奶茶店本周六开业，需要海报、展架和公众号配图一组，风格年轻、偏奶茶棕色调。', '2026-10-31', 'IN_PROGRESS', 1, '2026-10-22 10:00:00');
-- 订单2：待确认（演示业务负责人确认范围）
INSERT INTO material_order (id, order_no, customer_id, title, description, deadline, status, created_by, created_at) VALUES
(2, 'AO202610230002', 2, '花间西餐厅周年庆物料', '三周年店庆海报与菜单翻新，具体清单待负责人确认。', '2026-11-10', 'PENDING_CONFIRM', 1, '2026-10-23 09:00:00');
-- 订单3：已交付（历史完整链路数据）
INSERT INTO material_order (id, order_no, customer_id, title, description, deadline, status, created_by, created_at) VALUES
(3, 'AO202610210003', 3, '铁馆健身传单与易拉宝', '秋季招生宣传，传单与易拉宝各一批。', '2026-10-20', 'COMPLETED', 1, '2026-10-15 11:00:00');

-- 4. 物料任务 -------------------------------------------------
-- 订单1 的 3 项任务：修改中 / 设计中 / 待分派
INSERT INTO material_task (id, order_id, material_type, name, spec, quantity, usage_desc, reference_note, deadline, status, assignee_id, assigned_at, created_at) VALUES
(1, 1, 'POSTER', '开业主海报',   '600x900mm',   2, '门店门口与收银台张贴', '客户原话：做得年轻一点，要有新品奶茶大图', '2026-10-26', 'REVISION',        3, '2026-10-22 11:00:00', '2026-10-22 10:00:00'),
(2, 1, 'STAND',  '门店开业展架', '800x1800mm',  1, '开业当天门口迎宾',     '参考去年隔壁奶茶店风格即可',               '2026-10-28', 'DESIGNING',       4, '2026-10-22 11:10:00', '2026-10-22 10:00:00'),
(3, 1, 'ONLINE', '公众号开业配图', '1080x1920px', 3, '公众号推文头图与次图', '与海报同一套色调',                        '2026-10-30', 'PENDING',      NULL, NULL,                  '2026-10-22 10:00:00');
-- 订单2 的 2 项任务：全部待分派
INSERT INTO material_task (id, order_id, material_type, name, spec, quantity, usage_desc, reference_note, deadline, status, assignee_id, assigned_at, created_at) VALUES
(4, 2, 'POSTER', '周年庆主海报', '500x700mm',  1, '店内张贴',       '突出“三年感谢有你”',  '2026-11-05', 'PENDING', NULL, NULL, '2026-10-23 09:00:00'),
(5, 2, 'MENU',   '菜单翻新',     'A3 双面',    2, '堂食桌面与吧台', '新增周年庆套餐栏目',  '2026-11-08', 'PENDING', NULL, NULL, '2026-10-23 09:00:00');
-- 订单3 的任务：已确认（历史）
INSERT INTO material_task (id, order_id, material_type, name, spec, quantity, usage_desc, reference_note, deadline, status, assignee_id, assigned_at, created_at) VALUES
(6, 3, 'BROCHURE', '秋季招生传单', 'A5 三折页', 500, '周边社区派发', '突出团购优惠', '2026-10-18', 'CONFIRMED', 3, '2026-10-15 13:00:00', '2026-10-15 11:00:00');

-- 5. 设计版本（只追加不覆盖） ---------------------------------
INSERT INTO design_version (id, task_id, version_no, file_name, file_path, file_size, note, uploaded_by, uploaded_at) VALUES
(1, 1, 1, '开业主海报-初稿.png', 'demo/poster-v1.png', 1024000, '初稿：亮黄色主视觉', 3, '2026-10-22 15:30:00'),
(2, 6, 1, '招生传单-初稿.pdf',   'demo/brochure-v1.pdf', 2048000, '初稿', 3, '2026-10-16 10:00:00'),
(3, 6, 2, '招生传单-终稿.pdf',   'demo/brochure-v2.pdf', 2100000, '按客户意见调整价格栏后终稿', 3, '2026-10-17 16:00:00');

-- 6. 客户修改意见 ---------------------------------------------
INSERT INTO revision_feedback (id, task_id, round_no, content, recorded_by, recorded_at) VALUES
(1, 1, 1, '客户觉得亮黄色太跳，希望主色调改成奶茶棕；标题“开业大吉”字号加大；去掉右下角价格栏。', 1, '2026-10-23 09:40:00'),
(2, 6, 1, '客户希望价格更醒目，优惠信息放在正面底部。', 1, '2026-10-16 15:00:00');

-- 7. 操作历史（关键动作留痕示例） -----------------------------
INSERT INTO operation_log (biz_type, biz_id, order_id, action, action_text, detail, operator_id, operator_name, operator_role, created_at) VALUES
('ORDER', 1, 1, 'ORDER_CREATED',       '创建订单',          '登记客户“鹿屿奶茶（大学城店）”的开业宣传物料订单，含3项物料任务', 1, '张欣怡', 'SALES',    '2026-10-22 10:00:00'),
('ORDER', 1, 1, 'ORDER_CONFIRMED',     '确认订单',          '负责人确认物料清单与整体截止日期 2026-10-31',                      2, '王建国', 'MANAGER',  '2026-10-22 10:45:00'),
('TASK',  1, 1, 'TASK_ASSIGNED',       '分派任务',          '【开业主海报】分派给 李文静',                                      2, '王建国', 'MANAGER',  '2026-10-22 11:00:00'),
('TASK',  2, 1, 'TASK_ASSIGNED',       '分派任务',          '【门店开业展架】分派给 陈涛',                                      2, '王建国', 'MANAGER',  '2026-10-22 11:10:00'),
('TASK',  1, 1, 'TASK_STARTED',        '开始设计',          '【开业主海报】李文静开始设计',                                     3, '李文静', 'DESIGNER', '2026-10-22 13:00:00'),
('TASK',  1, 1, 'VERSION_UPLOADED',    '上传设计版本',      '【开业主海报】第1版：开业主海报-初稿.png',                         3, '李文静', 'DESIGNER', '2026-10-22 15:30:00'),
('TASK',  1, 1, 'FEEDBACK_RECORDED',   '记录客户修改意见',  '【开业主海报】第1轮：主色改奶茶棕、标题字号加大、去掉价格栏',      1, '张欣怡', 'SALES',    '2026-10-23 09:40:00'),
('ORDER', 2, 2, 'ORDER_CREATED',       '创建订单',          '登记客户“花间西餐厅”的周年庆物料订单，含2项物料任务',              1, '张欣怡', 'SALES',    '2026-10-23 09:00:00'),
('ORDER', 3, 3, 'ORDER_CREATED',       '创建订单',          '登记客户“铁馆健身工作室”的招生宣传订单',                           1, '张欣怡', 'SALES',    '2026-10-15 11:00:00'),
('ORDER', 3, 3, 'ORDER_CONFIRMED',     '确认订单',          '负责人确认物料清单与截止日期',                                     2, '王建国', 'MANAGER',  '2026-10-15 11:30:00'),
('TASK',  6, 3, 'TASK_ASSIGNED',       '分派任务',          '【秋季招生传单】分派给 李文静',                                    2, '王建国', 'MANAGER',  '2026-10-15 13:00:00'),
('TASK',  6, 3, 'VERSION_UPLOADED',    '上传设计版本',      '【秋季招生传单】第1版',                                            3, '李文静', 'DESIGNER', '2026-10-16 10:00:00'),
('TASK',  6, 3, 'FEEDBACK_RECORDED',   '记录客户修改意见',  '【秋季招生传单】第1轮：价格更醒目',                                1, '张欣怡', 'SALES',    '2026-10-16 15:00:00'),
('TASK',  6, 3, 'VERSION_UPLOADED',    '上传设计版本',      '【秋季招生传单】第2版',                                            3, '李文静', 'DESIGNER', '2026-10-17 16:00:00'),
('TASK',  6, 3, 'TASK_CONFIRMED',      '客户确认',          '【秋季招生传单】客户确认第2版，任务完成',                          1, '张欣怡', 'SALES',    '2026-10-18 10:00:00'),
('ORDER', 3, 3, 'ORDER_COMPLETED',     '订单交付',          '订单内全部物料已确认，订单交付完成',                               2, '王建国', 'MANAGER',  '2026-10-20 09:00:00');
