package com.course.adorder.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 物料订单 */
@Data
public class MaterialOrder {

    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    private Long id;
    private String orderNo;
    private Long customerId;
    private String title;
    private String description;
    private LocalDate deadline;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
