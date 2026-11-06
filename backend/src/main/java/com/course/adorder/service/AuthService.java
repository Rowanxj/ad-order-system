package com.course.adorder.service;

import com.course.adorder.common.BusinessException;
import com.course.adorder.entity.User;
import com.course.adorder.mapper.UserMapper;
import com.course.adorder.security.JwtUtil;
import com.course.adorder.security.LoginUser;
import com.course.adorder.security.PasswordUtil;
import com.course.adorder.dto.RequestDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 登录认证、自助注册、修改本人密码 */
@Slf4j
@Service
public class AuthService {

    /** 账号状态：1 启用，0 停用，2 待启用（自助注册后需负责人启用） */
    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_PENDING = 2;

    private final UserMapper userMapper;
    private final PasswordUtil passwordUtil;
    private final JwtUtil jwtUtil;

    public AuthService(UserMapper userMapper, PasswordUtil passwordUtil, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordUtil = passwordUtil;
        this.jwtUtil = jwtUtil;
    }

    /** 角色中文名，用于"身份不匹配"这类提示 */
    private static final Map<String, String> ROLE_LABEL = Map.of(
            "SALES", "客户经理",
            "MANAGER", "业务负责人",
            "DESIGNER", "设计制作人员");

    public Map<String, Object> login(String username, String rawPassword, String roleHint) {
        User user = userMapper.selectByUsername(username);
        if (user == null || !passwordUtil.matches(rawPassword, user.getPassword())) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "账号或密码错误");
        }

        Integer status = user.getStatus();
        if (status == null || status != STATUS_ENABLED) {
            if (status != null && status == STATUS_PENDING) {
                throw new BusinessException(BusinessException.FORBIDDEN, "账号待负责人启用，请联系业务负责人");
            }
            throw new BusinessException(BusinessException.FORBIDDEN, "账号已停用，请联系负责人");
        }

        // 登录页先选身份、再输账号：两者必须一致。
        // 否则会出现"以为自己在做客户经理的操作，其实用的是设计人员账号"。
        if (roleHint != null && !roleHint.isEmpty() && !roleHint.equals(user.getRole())) {
            String actual = ROLE_LABEL.getOrDefault(user.getRole(), user.getRole());
            String chosen = ROLE_LABEL.getOrDefault(roleHint, roleHint);
            throw new BusinessException(BusinessException.FORBIDDEN,
                    "该账号是「" + actual + "」，与所选身份「" + chosen + "」不一致，请切换身份后重试");
        }

        // 历史格式（单轮 SHA-256）登录成功后透明升级为 PBKDF2；
        // 升级失败只记日志，不能让用户因此登不进来。
        if (passwordUtil.needsUpgrade(user.getPassword())) {
            try {
                userMapper.updatePassword(user.getId(), passwordUtil.encode(rawPassword));
                log.info("密码摘要已升级为 PBKDF2，userId={}", user.getId());
            }
            catch (RuntimeException e) {
                log.warn("密码摘要升级失败，本次登录不受影响，userId={}", user.getId(), e);
            }
        }

        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getRealName(), user.getRole());
        String token = jwtUtil.generate(loginUser);

        Map<String, Object> userVo = new LinkedHashMap<>();
        userVo.put("id", user.getId());
        userVo.put("username", user.getUsername());
        userVo.put("realName", user.getRealName());
        userVo.put("role", user.getRole());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("user", userVo);
        return data;
    }

    /**
     * 自助注册：只能注册为设计制作人员，且注册后状态为「待启用」。
     * 不允许自助注册成客户经理或业务负责人——角色是权限，不能自己给自己发。
     */
    public Map<String, Object> register(RequestDtos.RegisterRequest req) {
        String username = req.username().trim();
        if (userMapper.selectByUsername(username) != null) {
            throw new BusinessException(BusinessException.BAD_REQUEST, "该账号已被占用，请换一个");
        }

        User user = new User();
        user.setUsername(username);
        user.setRealName(req.realName().trim());
        user.setPassword(passwordUtil.encode(req.password()));
        user.setRole("DESIGNER");
        user.setPhone(req.phone());
        user.setStatus(STATUS_PENDING);
        userMapper.insert(user);

        log.info("自助注册待启用：username={}, id={}", username, user.getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", username);
        data.put("status", STATUS_PENDING);
        data.put("message", "注册成功，等待业务负责人启用后即可登录");
        return data;
    }

    /** 修改本人密码：必须验证原密码 */
    public void changePassword(LoginUser user, String oldPassword, String newPassword) {
        User current = userMapper.selectById(user.getId());
        if (current == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "账号不存在");
        }
        if (!passwordUtil.matches(oldPassword, current.getPassword())) {
            throw new BusinessException(BusinessException.BAD_REQUEST, "原密码不正确");
        }
        if (oldPassword.equals(newPassword)) {
            throw new BusinessException(BusinessException.BAD_REQUEST, "新密码不能与原密码相同");
        }
        userMapper.updatePassword(user.getId(), passwordUtil.encode(newPassword));
        log.info("用户修改密码：userId={}", user.getId());
    }
}
