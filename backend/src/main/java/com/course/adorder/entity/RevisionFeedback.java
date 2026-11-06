package com.course.adorder.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 客户修改意见（客户经理记录） */
@Data
public class RevisionFeedback {

    private Long id;
    private Long taskId;
    private Integer roundNo;
    private String content;
    private Long recordedBy;
    private LocalDateTime recordedAt;
}
