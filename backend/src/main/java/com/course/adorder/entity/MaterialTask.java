package com.course.adorder.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 物料任务（订单下的一项物料制作任务） */
@Data
public class MaterialTask {

    public static final String PENDING = "PENDING";
    public static final String DESIGNING = "DESIGNING";
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String REVISION = "REVISION";
    public static final String CONFIRMED = "CONFIRMED";

    private Long id;
    private Long orderId;
    /** POSTER 海报 / STAND 展架 / MENU 菜单 / BROCHURE 宣传册 / ONLINE 线上配图 */
    private String materialType;
    private String name;
    private String spec;
    private Integer quantity;
    private String usageDesc;
    private String referenceNote;
    private LocalDate deadline;
    private String status;
    private Long assigneeId;
    private LocalDateTime assignedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
