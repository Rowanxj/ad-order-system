package com.course.adorder.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 密码摘要工具。
 *
 * <p>现行格式（写库）：{@code pbkdf2$<iterations>$<saltBase64>$<hashBase64>}
 * —— PBKDF2-HMAC-SHA256，随机盐，12 万次迭代。相比单轮 SHA-256，把离线爆破成本抬高数个量级，
 * 且不引入任何第三方依赖。</p>
 *
 * <p>兼容格式（历史数据，只读）：64 位十六进制 {@code SHA-256(固定盐 + 明文)}。
 * 早期演示数据使用该格式，登录时仍可校验；登录成功后会透明升级为现行格式，
 * 因此无需清库重造数据。</p>
 */
@Component
public class PasswordUtil {

    private static final String PBKDF2_PREFIX = "pbkdf2";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    @Value("${app.password-salt}")
    private String salt;

    /** 生成新密码摘要（PBKDF2 格式，每次随机盐） */
    public String encode(String rawPassword) {
        byte[] saltBytes = new byte[SALT_BYTES];
        random.nextBytes(saltBytes);
        byte[] hash = pbkdf2(rawPassword, saltBytes, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PBKDF2_PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(saltBytes) + "$" + b64.encodeToString(hash);
    }

    /** 校验密码：自动识别现行格式与历史格式 */
    public boolean matches(String rawPassword, String storedDigest) {
        if (rawPassword == null || storedDigest == null || storedDigest.isEmpty()) {
            return false;
        }
        if (storedDigest.startsWith(PBKDF2_PREFIX + "$")) {
            String[] parts = storedDigest.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] saltBytes = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                byte[] actual = pbkdf2(rawPassword, saltBytes, iterations);
                // 恒定时间比较，避免按字节比较泄露信息
                return MessageDigest.isEqual(expected, actual);
            }
            catch (IllegalArgumentException e) {
                return false;
            }
        }
        // 历史格式：SHA-256(固定盐 + 明文) 的十六进制
        return MessageDigest.isEqual(
                sha256Hex(salt + rawPassword).getBytes(StandardCharsets.UTF_8),
                storedDigest.getBytes(StandardCharsets.UTF_8));
    }

    /** 历史格式需要升级为现行格式（登录成功后调用） */
    public boolean needsUpgrade(String storedDigest) {
        return storedDigest != null && !storedDigest.startsWith(PBKDF2_PREFIX + "$");
    }

    private static byte[] pbkdf2(String rawPassword, byte[] saltBytes, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), saltBytes, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        }
        catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 算法不可用", e);
        }
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
