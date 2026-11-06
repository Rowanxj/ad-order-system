package com.course.adorder.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 客户 */
@Data
public class Customer {

    private Long id;
    private String name;
    private String contactPerson;
    private String contactPhone;
    private String industry;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Integer deleted;
}
