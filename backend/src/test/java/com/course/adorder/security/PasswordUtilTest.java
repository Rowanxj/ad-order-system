package com.course.adorder.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码摘要规则：新口令用 PBKDF2（随机盐），旧数据用固定盐 SHA-256，登录后透明升级。
 *
 * <p>这是项目里的第一个单元测试。它不启动 Spring 容器，直接 new 出被测对象，
 * 所以跑得很快（毫秒级），可以放进每次提交前的习惯里。</p>
 */
class PasswordUtilTest {

    private PasswordUtil passwordUtil;

    @BeforeEach
    void setUp() {
        passwordUtil = new PasswordUtil();
        // 常量盐值在真实环境由 application.yml 注入，这里直接反射塞进去
        ReflectionTestUtils.setField(passwordUtil, "salt", "adorder");
    }

    @Test
    @DisplayName("新密码使用 PBKDF2 摘要，可校验正确/错误口令")
    void encodeAndMatch() {
        String digest = passwordUtil.encode("123456");

        assertTrue(digest.startsWith("pbkdf2$120000$"), "新摘要必须带 PBKDF2 前缀与迭代次数");
        assertTrue(passwordUtil.matches("123456", digest), "正确口令应通过");
        assertFalse(passwordUtil.matches("654321", digest), "错误口令必须拒绝");
        assertFalse(passwordUtil.needsUpgrade(digest), "PBKDF2 摘要不需要再升级");
    }

    @Test
    @DisplayName("同一口令两次摘要的盐不同（随机盐）")
    void encodeUsesRandomSalt() {
        String first = passwordUtil.encode("123456");
        String second = passwordUtil.encode("123456");

        assertFalse(first.equals(second), "随机盐意味着同一口令两次摘要不应相同");
        assertTrue(passwordUtil.matches("123456", first));
        assertTrue(passwordUtil.matches("123456", second));
    }

    @Test
    @DisplayName("历史 SHA-256 格式仍可登录，并会被标记为待升级")
    void legacySha256StillWorks() {
        String legacy = PasswordUtil.sha256Hex("adorder" + "123456");

        assertTrue(passwordUtil.matches("123456", legacy), "旧数据必须还能登录，否则演示库全废");
        assertFalse(passwordUtil.matches("000000", legacy));
        assertTrue(passwordUtil.needsUpgrade(legacy), "旧格式应被标记为待升级");
    }

    @Test
    @DisplayName("空口令/空摘要/畸形摘要一律不通过")
    void nullAndEmptyNeverMatch() {
        assertFalse(passwordUtil.matches(null, "pbkdf2$120000$x$y"));
        assertFalse(passwordUtil.matches("123456", null));
        assertFalse(passwordUtil.matches("123456", ""));
        assertFalse(passwordUtil.matches("123456", "not-a-valid-digest"));
    }
}
