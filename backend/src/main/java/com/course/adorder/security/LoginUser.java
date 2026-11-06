package com.course.adorder.security;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前登录用户信息（由 JWT 解析而来，存放在 ThreadLocal 中）。
 */
@Data
@AllArgsConstructor
public class LoginUser {

    private Long id;
    private String username;
    private String realName;
    /** SALES 客户经理 / MANAGER 业务负责人 / DESIGNER 设计制作人员 */
    private String role;
}
