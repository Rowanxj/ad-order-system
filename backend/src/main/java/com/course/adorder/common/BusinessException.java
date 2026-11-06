package com.course.adorder.common;

import lombok.Getter;

/**
 * 业务异常：携带 HTTP 状态语义的错误码，由全局异常处理器统一转换为统一响应。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 400 参数错误 */
    public static final int BAD_REQUEST = 400;
    /** 401 未登录 / 登录过期 */
    public static final int UNAUTHORIZED = 401;
    /** 403 无权限 */
    public static final int FORBIDDEN = 403;
    /** 404 资源不存在 */
    public static final int NOT_FOUND = 404;
    /** 409 业务状态冲突 */
    public static final int CONFLICT = 409;

    private final int code;

    public BusinessException(String message) {
        this(BAD_REQUEST, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
