package com.course.adorder.mapper;

import com.course.adorder.entity.MaterialOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface MaterialOrderMapper {

    int insert(MaterialOrder order);

    MaterialOrder selectById(@Param("id") Long id);

    /** 统计某天前缀的订单数，用于生成订单编号 */
    int countByOrderNoPrefix(@Param("prefix") String prefix);

    /** 订单列表（分页）：按角色数据范围与筛选条件查询 */
    List<Map<String, Object>> selectList(@Param("status") String status,
                                         @Param("customerId") Long customerId,
                                         @Param("keyword") String keyword,
                                         @Param("designerId") Long designerId,
                                         @Param("deadlineFrom") java.time.LocalDate deadlineFrom,
                                         @Param("deadlineTo") java.time.LocalDate deadlineTo,
                                         @Param("overdue") Boolean overdue,
                                         @Param("extended") Boolean extended,
                                         @Param("viewerId") Long viewerId,
                                         @Param("viewerRole") String viewerRole,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    /** 订单列表总数（与 selectList 使用同一套 where 条件） */
    int countList(@Param("status") String status,
                  @Param("customerId") Long customerId,
                  @Param("keyword") String keyword,
                  @Param("designerId") Long designerId,
                  @Param("deadlineFrom") java.time.LocalDate deadlineFrom,
                  @Param("deadlineTo") java.time.LocalDate deadlineTo,
                  @Param("overdue") Boolean overdue,
                  @Param("extended") Boolean extended,
                  @Param("viewerId") Long viewerId,
                  @Param("viewerRole") String viewerRole);

    /**
     * 条件更新订单状态：只有当前状态等于 from 才会写入。
     * 返回 0 表示状态已被别人改过，调用方据此返回 409，避免"先查后写"的丢失更新。
     */
    int updateStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    /** 更新整体截止日期 */
    int updateDeadline(@Param("id") Long id, @Param("deadline") java.time.LocalDate deadline);
}
