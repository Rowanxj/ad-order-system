package com.course.adorder.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 操作历史（订单/任务关键动作留痕，含确认后的变更记录） */
@Data
public class OperationLog {

    public static final String TYPE_ORDER = "ORDER";
    public static final String TYPE_TASK = "TASK";

    private Long id;
    private String bizType;
    private Long bizId;
    private Long orderId;
    private String action;
    private String actionText;
    private String detail;
    private Long operatorId;
    private String operatorName;
    private String operatorRole;
    private LocalDateTime createdAt;
}
