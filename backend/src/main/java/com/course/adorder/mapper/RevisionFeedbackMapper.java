package com.course.adorder.mapper;

import com.course.adorder.entity.RevisionFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface RevisionFeedbackMapper {

    int insert(RevisionFeedback feedback);

    /** 任务的修改意见列表（含记录人姓名），按轮次升序 */
    List<Map<String, Object>> selectByTaskId(@Param("taskId") Long taskId);

    Integer selectMaxRoundNo(@Param("taskId") Long taskId);
}
