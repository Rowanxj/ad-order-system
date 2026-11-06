package com.course.adorder.common;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;

/**
 * 全局异常处理：任何异常都以统一响应结构返回，避免堆栈直接暴露给前端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常（参数错误、状态冲突、无权限等） */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    /** 参数校验失败，取第一个字段错误提示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? "参数校验失败" : fe.getDefaultMessage();
        return Result.error(BusinessException.BAD_REQUEST, msg);
    }

    /** 上传文件超过大小限制 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadSize(MaxUploadSizeExceededException e) {
        return Result.error(BusinessException.BAD_REQUEST, "文件大小超过 20MB 限制");
    }

    /** 请求体不是合法 JSON / 类型对不上：这是客户端问题，不该报 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return Result.error(BusinessException.BAD_REQUEST, "请求体格式不正确，请检查字段类型");
    }

    /** 路径/查询参数类型错误，例如 /orders/abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.error(BusinessException.BAD_REQUEST, "参数格式不正确：" + e.getName());
    }

    /** 数据库约束未通过（超长、唯一冲突等）：记日志后转成可读的 400，避免把 SQL 细节抛给前端 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<Void> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("数据约束未通过", e);
        return Result.error(BusinessException.BAD_REQUEST, "提交的数据不符合约束，请检查长度或取值");
    }

    /** 静态资源/接口路径不存在 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        return Result.error(BusinessException.NOT_FOUND, "请求的资源不存在");
    }

    /** 数据库连不上：给一句能照着修的提示，不要把 JDBC/SQL 细节抛给前端 */
    @ExceptionHandler(MyBatisSystemException.class)
    public Result<Void> handleMyBatisSystem(MyBatisSystemException e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        if (root instanceof SQLException) {
            log.error("数据库连接/访问失败，请检查 MySQL 服务与 DB_PASSWORD", e);
            return Result.error(500,
                    "数据库连接失败：请确认 MySQL 已启动，并检查 .env 中的 DB_USERNAME / DB_PASSWORD");
        }
        log.error("数据访问失败", e);
        return Result.error(500, "数据访问失败，请稍后重试");
    }

    /** 兜底异常 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("服务器内部错误", e);
        return Result.error(500, "服务器内部错误，请稍后重试");
    }
}
