package com.course.adorder.security;

import com.course.adorder.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 登录认证 + 角色权限拦截器：
 * 1. 校验 Authorization: Bearer <token>，解析出登录用户写入 UserContext；
 * 2. 若 Handler 方法标注了 @RequireRole，校验当前角色是否在允许范围内；
 * 3. 请求结束后清理 ThreadLocal。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 放行跨域预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "未登录或缺少凭证");
        }
        LoginUser user = jwtUtil.parse(auth.substring(7));
        UserContext.set(user);

        // 方法级角色校验
        if (handler instanceof HandlerMethod hm) {
            RequireRole anno = hm.getMethodAnnotation(RequireRole.class);
            if (anno != null && Arrays.stream(anno.value()).noneMatch(r -> r.equals(user.getRole()))) {
                throw new BusinessException(BusinessException.FORBIDDEN, "当前角色无权执行此操作");
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
