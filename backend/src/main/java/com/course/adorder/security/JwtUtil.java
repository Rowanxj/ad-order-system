package com.course.adorder.security;

import com.course.adorder.common.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析（HMAC-SHA256）。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${app.jwt-secret}") String secret,
                   @Value("${app.jwt-expire-hours}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 3600_000L;
    }

    public String generate(LoginUser user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("realName", user.getRealName())
                .claim("role", user.getRole())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并校验 token，失败抛出 401 业务异常 */
    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return new LoginUser(
                    claims.get("uid", Long.class),
                    claims.getSubject(),
                    claims.get("realName", String.class),
                    claims.get("role", String.class));
        } catch (Exception e) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "登录已过期，请重新登录");
        }
    }
}
