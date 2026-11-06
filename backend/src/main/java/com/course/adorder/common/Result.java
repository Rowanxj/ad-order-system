package com.course.adorder.common;

import lombok.Data;

/**
 * 统一响应结构：{ code, message, data }
 * code = 0 表示成功；其余为错误码（401 未登录 / 403 无权限 / 404 不存在 / 409 状态冲突 / 500 服务器错误）
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
