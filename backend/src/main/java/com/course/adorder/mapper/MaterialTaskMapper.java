package com.course.adorder.mapper;

import com.course.adorder.entity.MaterialTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface MaterialTaskMapper {

    int insert(MaterialTask task);

    MaterialTask selectById(@Param("id") Long id);

    /** 订单详情中的任务列表（含分派人姓名与版本数） */
    List<Map<String, Object>> selectByOrderId(@Param("orderId") Long orderId);

    /** 设计人员工作台：分派给自己的任务 */
    List<Map<String, Object>> selectMyTasks(@Param("assigneeId") Long assigneeId,
                                            @Param("status") String status);

    /** 任务工作台（负责人/客户经理）：全量任务筛选，与 countWorkbench 共用同一套 where */
    List<Map<String, Object>> selectWorkbench(@Param("status") String status,
                                              @Param("assigneeId") Long assigneeId,
                                              @Param("orderId") Long orderId,
                                              @Param("keyword") String keyword,
                                              @Param("overdue") Boolean overdue,
                                              @Param("unassigned") Boolean unassigned,
                                              @Param("extended") Boolean extended,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    /** 任务工作台总数（与 selectWorkbench 使用同一套筛选条件） */
    int countWorkbench(@Param("status") String status,
                       @Param("assigneeId") Long assigneeId,
                       @Param("orderId") Long orderId,
                       @Param("keyword") String keyword,
                       @Param("overdue") Boolean overdue,
                       @Param("unassigned") Boolean unassigned,
                       @Param("extended") Boolean extended);

    /** 订单内未取消物料的最晚截止日期（改整单截止时校验边界） */
    LocalDate selectMaxDeadlineByOrderId(@Param("orderId") Long orderId);

    /** 订单内未确认的任务数（用于"全部确认后才能交付"校验） */
    int countUnconfirmedByOrderId(@Param("orderId") Long orderId);

    /** 订单内分派给某人的任务数（用于设计人员数据归属校验） */
    int countByOrderAndAssignee(@Param("orderId") Long orderId, @Param("assigneeId") Long assigneeId);

    /**
     * 条件更新任务状态：只有当前状态等于 from 才会写入。
     * 返回 0 表示状态已被他人改变，调用方据此返回 409。
     */
    int updateStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    /** 分派/改派：仅"待分派"状态可改派，条件写在 SQL 里保证原子性 */
    int updateAssignee(@Param("id") Long id, @Param("assigneeId") Long assigneeId);

    int updateDeadline(@Param("id") Long id, @Param("deadline") LocalDate deadline);
}
