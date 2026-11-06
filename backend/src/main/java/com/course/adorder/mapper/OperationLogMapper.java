package com.course.adorder.mapper;

import com.course.adorder.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OperationLogMapper {

    int insert(OperationLog log);

    /** 某订单的全部操作历史（含订单级与任务级），按时间升序 */
    List<OperationLog> selectByOrderId(@Param("orderId") Long orderId);
}
