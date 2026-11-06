package com.course.adorder.service;

import com.course.adorder.common.BusinessException;
import com.course.adorder.dto.RequestDtos;
import com.course.adorder.entity.User;
import com.course.adorder.mapper.UserMapper;
import com.course.adorder.security.LoginUser;
import com.course.adorder.security.PasswordUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号管理（仅业务负责人可用）。
 *
 * <p>说明：这一层刻意<b>不写 operation_log</b>。operation_log 是订单维度的时间线
 * （order_id 非空），把账号操作塞进去会污染订单历史；账号变更走应用日志审计。</p>
 */
@Slf4j
@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordUtil passwordUtil;

    public UserService(UserMapper userMapper, PasswordUtil passwordUtil) {
        this.userMapper = userMapper;
        this.passwordUtil = passwordUtil;
    }

    /** 全部账号；密码摘要不出服务层 */
    public List<User> list() {
        List<User> users = userMapper.selectAll();
        users.forEach(u -> u.setPassword(null));
        return users;
    }

    /**
     * 分派下拉：只返回启用状态的设计制作人员，且只输出前端需要的字段。
     *
     * <p>不直接返回 {@link User} 实体：即使实体上已用 {@code @JsonIgnore} 隐藏密码，
     * 接口层也不应把账号实体原样吐给前端，避免以后加字段时再泄露一次。</p>
     */
    public List<Map<String, Object>> designers() {
        return userMapper.selectByRole("DESIGNER").stream()
                .filter(u -> u.getStatus() != null && u.getStatus() == AuthService.STATUS_ENABLED)
                .map(u -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", u.getId());
                    item.put("username", u.getUsername());
                    item.put("realName", u.getRealName());
                    item.put("status", u.getStatus());
                    return item;
                })
                .toList();
    }

    /** 负责人创建账号：可指定任意角色，建好即可用 */
    public User create(RequestDtos.UserCreateRequest req) {
        String username = req.username().trim();
        if (userMapper.selectByUsername(username) != null) {
            throw new BusinessException(BusinessException.BAD_REQUEST, "该账号已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setRealName(req.realName().trim());
        user.setRole(req.role());
        user.setPhone(req.phone());
        user.setPassword(passwordUtil.encode(req.password()));
        user.setStatus(AuthService.STATUS_ENABLED);
        userMapper.insert(user);

        log.info("负责人创建账号：username={}, role={}, operator={}", username, req.role(), user.getId());
        user.setPassword(null);
        return user;
    }

    /**
     * 启用 / 停用 / 待启用。
     * 停用即“注销”：账号不再可登录，但历史留痕里的姓名与角色保留，便于追责。
     */
    public void changeStatus(LoginUser operator, Long id, Integer status) {
        if (operator.getId().equals(id)) {
            throw new BusinessException(BusinessException.BAD_REQUEST, "不能修改自己的启用状态");
        }
        User target = userMapper.selectById(id);
        if (target == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "账号不存在");
        }

        boolean disabling = status == AuthService.STATUS_DISABLED || status == AuthService.STATUS_PENDING;
        if (disabling && "MANAGER".equals(target.getRole())) {
            long activeManagers = userMapper.selectByRole("MANAGER").size();
            if (activeManagers <= 1 && target.getStatus() == AuthService.STATUS_ENABLED) {
                throw new BusinessException(BusinessException.BAD_REQUEST, "至少要保留一个启用的业务负责人");
            }
        }

        userMapper.changeStatus(id, status);
        log.info("账号状态变更：userId={}, status={}, operator={}", id, status, operator.getUsername());
    }

    /** 负责人重置密码（用户忘记密码时使用） */
    public void resetPassword(LoginUser operator, Long id, String newPassword) {
        User target = userMapper.selectById(id);
        if (target == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "账号不存在");
        }
        userMapper.updatePassword(id, passwordUtil.encode(newPassword));
        log.info("负责人重置密码：userId={}, operator={}", id, operator.getUsername());
    }
}
