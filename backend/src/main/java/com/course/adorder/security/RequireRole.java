package com.course.adorder.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口角色限制：标注在 Controller 方法上，允许的任一角色即可访问。
 * 未标注的接口只要求登录，不限制角色。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色数组，任一匹配即可 */
    String[] value();
}
