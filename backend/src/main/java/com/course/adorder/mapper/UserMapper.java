package com.course.adorder.mapper;

import com.course.adorder.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {

    User selectByUsername(@Param("username") String username);

    User selectById(@Param("id") Long id);

    /** 查询某角色的全部启用用户（如设计人员下拉列表） */
    List<User> selectByRole(@Param("role") String role);

    /** 全部用户（用户管理列表，密码字段不外泄由 Service 负责清空） */
    List<User> selectAll();

    /** 新增账号（自助注册 或 负责人创建） */
    int insert(User user);

    /** 更新密码摘要（登录成功后把历史格式透明升级为 PBKDF2） */
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /** 启用 / 停用 / 待启用：1 启用，0 停用，2 待启用 */
    int changeStatus(@Param("id") Long id, @Param("status") Integer status);
}
