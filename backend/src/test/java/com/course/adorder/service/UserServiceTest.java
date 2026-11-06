package com.course.adorder.service;

import com.course.adorder.common.BusinessException;
import com.course.adorder.dto.RequestDtos;
import com.course.adorder.entity.User;
import com.course.adorder.mapper.UserMapper;
import com.course.adorder.security.LoginUser;
import com.course.adorder.security.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号管理单元测试：密码脱敏、用户名查重、启停保护与重置密码。
 *
 * <p>账号变更不写 operation_log（订单时间线不属于账号），所以这里只验证
 * Mapper 调用与密码摘要，不验证留痕。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Long MANAGER_ID = 2L;

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordUtil passwordUtil;

    private UserService userService;
    private LoginUser manager;

    @BeforeEach
    void setUp() {
        manager = new LoginUser(MANAGER_ID, "manager", "负责人老王", "MANAGER");
        userService = new UserService(userMapper, passwordUtil);
    }

    @Test
    @DisplayName("账号列表：密码摘要不出服务层")
    void listClearsPasswords() {
        User designer = user(3L, "designer1", "设计师小李", "DESIGNER", 1);
        User sales = user(4L, "sales", "客户经理小张", "SALES", 1);
        when(userMapper.selectAll()).thenReturn(List.of(designer, sales));

        List<User> users = userService.list();

        assertSame(designer, users.get(0));
        assertNull(designer.getPassword());
        assertNull(sales.getPassword());
    }

    @Test
    @DisplayName("分派下拉：只返回启用设计人员，且不携带密码字段")
    void designersOnlyReturnsEnabledSafeFields() {
        User enabled = user(3L, "designer1", "李文静", "DESIGNER", 1);
        User disabled = user(4L, "designer2", "陈涛", "DESIGNER", 0);
        when(userMapper.selectByRole("DESIGNER")).thenReturn(List.of(enabled, disabled));

        List<java.util.Map<String, Object>> designers = userService.designers();

        assertEquals(1, designers.size());
        assertEquals(3L, designers.get(0).get("id"));
        assertEquals("designer1", designers.get(0).get("username"));
        assertEquals("李文静", designers.get(0).get("realName"));
        assertEquals(1, designers.get(0).get("status"));
        assertNull(designers.get(0).get("password"));
    }

    @Test
    @DisplayName("创建账号：用户名重复直接拒绝")
    void createRejectsDuplicateUsername() {
        when(userMapper.selectByUsername("manager")).thenReturn(
                user(1L, "manager", "老王", "MANAGER", 1));

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> userService.create(req("manager", "重复账号", "DESIGNER")));
        verify(passwordUtil, never()).encode(anyString());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("创建账号：trim、加密、状态默认启用，返回前清空密码")
    void createTrimsEncodesAndClearsPassword() {
        when(userMapper.selectByUsername("newuser")).thenReturn(null);
        when(passwordUtil.encode("123456")).thenReturn("HASH");
        AtomicReference<String> storedPassword = new AtomicReference<>();
        doAnswer(inv -> {
            User user = inv.getArgument(0);
            storedPassword.set(user.getPassword());
            user.setId(9L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        User created = userService.create(req("  newuser  ", "  新人  ", "DESIGNER"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("newuser", saved.getUsername());
        assertEquals("新人", saved.getRealName());
        assertEquals("DESIGNER", saved.getRole());
        assertEquals(AuthService.STATUS_ENABLED, saved.getStatus());
        assertEquals("HASH", storedPassword.get());
        assertEquals(9L, created.getId());
        assertNull(created.getPassword());
    }

    @Test
    @DisplayName("启停账号：不能改自己的状态")
    void changeStatusRejectsSelf() {
        assertBusiness(BusinessException.BAD_REQUEST,
                () -> userService.changeStatus(manager, MANAGER_ID, AuthService.STATUS_DISABLED));
        verify(userMapper, never()).selectById(anyLong());
        verify(userMapper, never()).changeStatus(anyLong(), any());
    }

    @Test
    @DisplayName("启停账号：目标不存在返回 404")
    void changeStatusRejectsMissingUser() {
        when(userMapper.selectById(9L)).thenReturn(null);

        assertBusiness(BusinessException.NOT_FOUND,
                () -> userService.changeStatus(manager, 9L, AuthService.STATUS_DISABLED));
        verify(userMapper, never()).changeStatus(anyLong(), any());
    }

    @Test
    @DisplayName("启停账号：不能停用唯一启用的负责人")
    void changeStatusRejectsDisablingLastManager() {
        User target = user(1L, "manager", "老王", "MANAGER", AuthService.STATUS_ENABLED);
        when(userMapper.selectById(1L)).thenReturn(target);
        when(userMapper.selectByRole("MANAGER")).thenReturn(List.of(target));

        BusinessException ex = assertBusiness(BusinessException.BAD_REQUEST,
                () -> userService.changeStatus(manager, 1L, AuthService.STATUS_DISABLED));
        assertEquals("至少要保留一个启用的业务负责人", ex.getMessage());
        verify(userMapper, never()).changeStatus(anyLong(), any());
    }

    @Test
    @DisplayName("启停账号：停用设计师正常落库")
    void changeStatusDisablesDesigner() {
        User target = user(3L, "designer1", "设计师小李", "DESIGNER", AuthService.STATUS_ENABLED);
        when(userMapper.selectById(3L)).thenReturn(target);

        userService.changeStatus(manager, 3L, AuthService.STATUS_DISABLED);

        verify(userMapper).changeStatus(3L, AuthService.STATUS_DISABLED);
    }

    @Test
    @DisplayName("重置密码：目标不存在返回 404")
    void resetPasswordRejectsMissingUser() {
        when(userMapper.selectById(9L)).thenReturn(null);

        assertBusiness(BusinessException.NOT_FOUND,
                () -> userService.resetPassword(manager, 9L, "newpass"));
        verify(userMapper, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("重置密码：摘要后更新，不保存明文")
    void resetPasswordEncodesBeforeUpdate() {
        when(userMapper.selectById(3L)).thenReturn(
                user(3L, "designer1", "设计师小李", "DESIGNER", AuthService.STATUS_ENABLED));
        when(passwordUtil.encode("newpass")).thenReturn("HASH2");

        userService.resetPassword(manager, 3L, "newpass");

        verify(passwordUtil).encode("newpass");
        verify(userMapper).updatePassword(3L, "HASH2");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private RequestDtos.UserCreateRequest req(String username, String realName, String role) {
        return new RequestDtos.UserCreateRequest(username, realName, role, "123456", "13800000000");
    }

    private User user(Long id, String username, String realName, String role, int status) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRealName(realName);
        user.setRole(role);
        user.setStatus(status);
        user.setPassword("stored-hash");
        return user;
    }

    private BusinessException assertBusiness(int expectedCode, Executable action) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(expectedCode, ex.getCode(), ex.getMessage());
        return ex;
    }
}
