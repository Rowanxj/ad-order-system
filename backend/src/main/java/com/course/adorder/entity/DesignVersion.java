package com.course.adorder.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 设计版本（只追加，不覆盖） */
@Data
public class DesignVersion {

    private Long id;
    private Long taskId;
    private Integer versionNo;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String note;
    private Long uploadedBy;
    private LocalDateTime uploadedAt;
}
