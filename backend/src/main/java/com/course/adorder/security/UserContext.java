package com.course.adorder.security;

/**
 * 基于 ThreadLocal 的当前登录用户上下文。
 * 由 AuthInterceptor 在请求进入时写入，请求结束时清理，避免内存泄漏。
 */
public class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getId();
    }

    public static String getRole() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getRole();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
