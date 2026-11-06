-- =============================================================
-- 广告制作公司宣传物料订单管理系统 - 数据库建表脚本
-- 数据库：ad_order_system (MySQL 8.0, utf8mb4)
-- 7 张表：sys_user / customer / material_order / material_task
--         design_version / revision_feedback / operation_log
-- =============================================================

CREATE DATABASE IF NOT EXISTS ad_order_system
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ad_order_system;

-- -------------------------------------------------------------
-- 1. 系统用户表（3 类角色：SALES 客户经理 / MANAGER 业务负责人 / DESIGNER 设计制作人员）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username    VARCHAR(50)  NOT NULL                COMMENT '登录账号',
  password    VARCHAR(128) NOT NULL                COMMENT '密码摘要 PBKDF2(随机盐) 或历史 SHA-256(固定盐+明文)',
  real_name   VARCHAR(50)  NOT NULL                COMMENT '姓名',
  role        VARCHAR(20)  NOT NULL                COMMENT '角色 SALES/MANAGER/DESIGNER',
  phone       VARCHAR(20)  NULL                    COMMENT '联系电话',
  status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态 1启用 0停用 2待启用（自助注册后需负责人启用）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB COMMENT='系统用户表';

-- -------------------------------------------------------------
-- 2. 客户表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS customer;
CREATE TABLE customer (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '客户ID',
  name          VARCHAR(100) NOT NULL                COMMENT '客户名称(公司/店铺)',
  contact_person VARCHAR(50) NOT NULL                COMMENT '联系人',
  contact_phone VARCHAR(20)  NULL                    COMMENT '联系电话',
  industry      VARCHAR(50)  NULL                    COMMENT '所属行业',
  remark        VARCHAR(255) NULL                    COMMENT '备注',
  created_by    BIGINT       NOT NULL                COMMENT '登记人用户ID',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (id),
  KEY idx_name (name)
) ENGINE=InnoDB COMMENT='客户表';

-- -------------------------------------------------------------
-- 3. 物料订单表
--    状态: PENDING_CONFIRM 待确认 / IN_PROGRESS 进行中
--          COMPLETED 已交付 / CANCELLED 已取消
-- -------------------------------------------------------------
DROP TABLE IF EXISTS material_order;
CREATE TABLE material_order (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  order_no    VARCHAR(32)  NOT NULL                COMMENT '订单编号 AO+日期+序号',
  customer_id BIGINT       NOT NULL                COMMENT '客户ID',
  title       VARCHAR(100) NOT NULL                COMMENT '订单标题',
  description VARCHAR(500) NULL                    COMMENT '整体需求说明',
  deadline    DATE         NULL                    COMMENT '整体交付截止日期',
  status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING_CONFIRM' COMMENT '订单状态',
  created_by  BIGINT       NOT NULL                COMMENT '创建人(客户经理)ID',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_customer (customer_id),
  KEY idx_status (status)
) ENGINE=InnoDB COMMENT='物料订单表';

-- -------------------------------------------------------------
-- 4. 物料任务表（一笔订单下的海报/展架/菜单/宣传册/线上配图等任务）
--    状态: PENDING 待分派 / DESIGNING 设计中 / PENDING_CONFIRM 待客户确认
--          REVISION 修改中 / CONFIRMED 已确认
-- -------------------------------------------------------------
DROP TABLE IF EXISTS material_task;
CREATE TABLE material_task (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  order_id      BIGINT       NOT NULL                COMMENT '所属订单ID',
  material_type VARCHAR(20)  NOT NULL                COMMENT '物料类型 POSTER/STAND/MENU/BROCHURE/ONLINE',
  name          VARCHAR(100) NOT NULL                COMMENT '物料名称',
  spec          VARCHAR(100) NULL                    COMMENT '尺寸/规格',
  quantity      INT          NOT NULL DEFAULT 1      COMMENT '数量',
  usage_desc    VARCHAR(255) NULL                    COMMENT '用途说明',
  reference_note VARCHAR(500) NULL                   COMMENT '参考说明/客户原话',
  deadline      DATE         NULL                    COMMENT '该物料截止日期',
  status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
  assignee_id   BIGINT       NULL                    COMMENT '设计制作人员ID',
  assigned_at   DATETIME     NULL                    COMMENT '分派时间',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_order (order_id),
  KEY idx_assignee (assignee_id),
  KEY idx_status (status)
) ENGINE=InnoDB COMMENT='物料任务表';

-- -------------------------------------------------------------
-- 5. 设计版本表（只追加不覆盖：新版本追加记录，旧版本保留可查）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS design_version;
CREATE TABLE design_version (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '版本ID',
  task_id     BIGINT       NOT NULL                COMMENT '所属任务ID',
  version_no  INT          NOT NULL                COMMENT '版本号 1,2,3... 递增',
  file_name   VARCHAR(200) NOT NULL                COMMENT '原始文件名',
  file_path   VARCHAR(255) NOT NULL                COMMENT '存储相对路径',
  file_size   BIGINT       NOT NULL DEFAULT 0      COMMENT '文件大小(字节)',
  note        VARCHAR(255) NULL                    COMMENT '提交说明',
  uploaded_by BIGINT       NOT NULL                COMMENT '上传人ID',
  uploaded_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_version (task_id, version_no),
  KEY idx_task (task_id)
) ENGINE=InnoDB COMMENT='设计版本表';

-- -------------------------------------------------------------
-- 6. 客户修改意见表（客户经理记录，记录后任务进入"修改中"）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS revision_feedback;
CREATE TABLE revision_feedback (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '意见ID',
  task_id     BIGINT       NOT NULL                COMMENT '所属任务ID',
  round_no    INT          NOT NULL                COMMENT '修改轮次 1,2,3...',
  content     VARCHAR(500) NOT NULL                COMMENT '客户修改意见内容',
  recorded_by BIGINT       NOT NULL                COMMENT '记录人(客户经理)ID',
  recorded_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_round (task_id, round_no),
  KEY idx_task (task_id)
) ENGINE=InnoDB COMMENT='客户修改意见表';

-- -------------------------------------------------------------
-- 7. 操作历史表（订单/任务的关键动作全部留痕，含确认后的变更记录）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS operation_log;
CREATE TABLE operation_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  biz_type      VARCHAR(10)  NOT NULL                COMMENT '业务类型 ORDER/TASK',
  biz_id        BIGINT       NOT NULL                COMMENT '业务ID',
  order_id      BIGINT       NOT NULL                COMMENT '冗余订单ID便于查询',
  action        VARCHAR(40)  NOT NULL                COMMENT '动作编码',
  action_text   VARCHAR(100) NOT NULL                COMMENT '动作中文描述',
  detail        VARCHAR(500) NULL                    COMMENT '变更说明(如 原值→新值)',
  operator_id   BIGINT       NOT NULL                COMMENT '操作人ID',
  operator_name VARCHAR(50)  NOT NULL                COMMENT '操作人姓名(冗余)',
  operator_role VARCHAR(20)  NOT NULL                COMMENT '操作人角色',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (id),
  KEY idx_order (order_id),
  KEY idx_biz (biz_type, biz_id)
) ENGINE=InnoDB COMMENT='操作历史表';
