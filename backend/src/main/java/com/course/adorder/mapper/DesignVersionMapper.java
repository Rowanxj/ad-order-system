package com.course.adorder.mapper;

import com.course.adorder.entity.DesignVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface DesignVersionMapper {

    int insert(DesignVersion version);

    /** 任务的设计版本列表（含上传人姓名），按版本号升序 */
    List<Map<String, Object>> selectByTaskId(@Param("taskId") Long taskId);

    Integer selectMaxVersionNo(@Param("taskId") Long taskId);

    /** 按落盘路径反查版本（文件下载归属校验用） */
    DesignVersion selectByFilePath(@Param("filePath") String filePath);
}
