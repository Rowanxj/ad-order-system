package com.course.adorder.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 待办提醒专用查询。
 *
 * <p>独立成一个 mapper，而不是往 MaterialOrderMapper / MaterialTaskMapper 里塞：
 * 这些查询只为"首页提醒"服务，口径随时会变（例如以后加"逾期订单"），
 * 混进业务 mapper 会让业务查询和统计查询纠缠。</p>
 */
@Mapper
public interface NotificationMapper {

    /** 某状态的订单数（负责人看"待确认 / 可交付"） */
    int countOrdersByStatus(@Param("status") String status);

    /** 可交付订单：进行中且不存在未确认任务 */
    int countDeliverableOrders();

    /** 某状态的任务数（客户经理看"待客户确认"） */
    int countTasksByStatus(@Param("status") String status);

    /** 待分派任务数：仍为 PENDING 且没有分派人 */
    int countUnassignedTasks();

    /** 我的任务里某状态的数量（设计人员用） */
    int countMyTasksByStatus(@Param("assigneeId") Long assigneeId, @Param("status") String status);

    /** 最近几笔待确认订单（负责人用） */
    List<Map<String, Object>> selectPendingConfirmOrders(@Param("limit") int limit);

    /** 最近几项某状态的任务及其订单（客户经理用） */
    List<Map<String, Object>> selectTasksByStatus(@Param("status") String status, @Param("limit") int limit);

    /** 我的活跃任务（设计人员用：待开始 / 修改中） */
    List<Map<String, Object>> selectMyActiveTasks(@Param("assigneeId") Long assigneeId, @Param("limit") int limit);
}
