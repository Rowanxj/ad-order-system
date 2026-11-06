package com.course.adorder.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/** 系统用户 */
@Data
public class User {

    private Long id;
    private String username;
    /** 密码摘要永远不随 JSON 返回；序列化层再兜一层 */
    @JsonIgnore
    private String password;
    private String realName;
    /** SALES / MANAGER / DESIGNER */
    private String role;
    private String phone;
    private Integer status;
    private LocalDateTime createdAt;
}
