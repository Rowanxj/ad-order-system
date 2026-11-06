package com.course.adorder.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 请求 DTO 集中定义（Java record，配合 jakarta validation 注解）。
 *
 * <p>校验规则与 {@code sql/schema.sql} 的列长度严格对齐：
 * 少一个约束，超长输入就会穿透到数据库层，变成 500 而不是可读的 400。</p>
 */
public class RequestDtos {

    /** 物料类型白名单，与 material_task.material_type 注释一致 */
    public static final String MATERIAL_TYPE_REGEX = "POSTER|STAND|MENU|BROCHURE|ONLINE";

    /** 角色白名单 */
    public static final String ROLE_REGEX = "SALES|MANAGER|DESIGNER";

    /** 账号格式：字母开头，3–20 位字母/数字/下划线 */
    public static final String USERNAME_REGEX = "^[A-Za-z][A-Za-z0-9_]{2,19}$";

    /** 创建客户 */
    public record CustomerCreateRequest(
            @NotBlank(message = "客户名称不能为空")
            @Size(max = 100, message = "客户名称不能超过 100 字") String name,

            @NotBlank(message = "联系人不能为空")
            @Size(max = 50, message = "联系人不能超过 50 字") String contactPerson,

            @Size(max = 20, message = "联系电话不能超过 20 字") String contactPhone,

            @Size(max = 50, message = "所属行业不能超过 50 字") String industry,

            @Size(max = 255, message = "备注不能超过 255 字") String remark) {
    }

    /** 单项物料任务 */
    public record TaskCreateRequest(
            @NotBlank(message = "物料类型不能为空")
            @Pattern(regexp = MATERIAL_TYPE_REGEX, message = "物料类型只能是海报/展架/菜单/宣传册/线上配图")
            String materialType,

            @NotBlank(message = "物料名称不能为空")
            @Size(max = 100, message = "物料名称不能超过 100 字") String name,

            @Size(max = 100, message = "规格不能超过 100 字") String spec,

            @NotNull(message = "数量不能为空")
            @Min(value = 1, message = "数量必须大于 0")
            @Max(value = 9999, message = "数量不能超过 9999") Integer quantity,

            @Size(max = 255, message = "用途说明不能超过 255 字") String usageDesc,

            @Size(max = 500, message = "参考说明不能超过 500 字") String referenceNote,

            java.time.LocalDate deadline) {
    }

    /** 创建订单（含若干物料任务） */
    public record OrderCreateRequest(
            @NotNull(message = "客户不能为空") Long customerId,

            @NotBlank(message = "订单标题不能为空")
            @Size(max = 100, message = "订单标题不能超过 100 字") String title,

            @Size(max = 500, message = "需求说明不能超过 500 字") String description,

            java.time.LocalDate deadline,

            @NotEmpty(message = "订单至少包含一项物料任务")
            @Size(max = 50, message = "单笔订单最多 50 项物料")
            java.util.List<@Valid TaskCreateRequest> tasks) {
    }

    /** 分派任务 */
    public record AssignRequest(@NotNull(message = "请选择设计制作人员") Long assigneeId) {
    }

    /** 修改截止日期 */
    public record DeadlineRequest(@NotNull(message = "截止日期不能为空") java.time.LocalDate deadline) {
    }

    /** 记录客户修改意见 */
    public record FeedbackRequest(
            @NotBlank(message = "修改意见内容不能为空")
            @Size(max = 500, message = "修改意见不能超过 500 字") String content) {
    }

    /** 自助注册：只能注册成设计制作人员，且注册后待负责人启用 */
    public record RegisterRequest(
            @NotBlank(message = "账号不能为空")
            @Pattern(regexp = USERNAME_REGEX, message = "账号需以字母开头，3–20 位字母、数字或下划线")
            String username,

            @NotBlank(message = "姓名不能为空")
            @Size(max = 50, message = "姓名不能超过 50 字") String realName,

            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 32, message = "密码长度需在 6–32 位之间") String password,

            @Size(max = 20, message = "手机号不能超过 20 字") String phone) {
    }

    /** 负责人新增账号：可指定角色，建好即为启用状态 */
    public record UserCreateRequest(
            @NotBlank(message = "账号不能为空")
            @Pattern(regexp = USERNAME_REGEX, message = "账号需以字母开头，3–20 位字母、数字或下划线")
            String username,

            @NotBlank(message = "姓名不能为空")
            @Size(max = 50, message = "姓名不能超过 50 字") String realName,

            @NotBlank(message = "角色不能为空")
            @Pattern(regexp = ROLE_REGEX, message = "角色只能是 SALES / MANAGER / DESIGNER")
            String role,

            @NotBlank(message = "初始密码不能为空")
            @Size(min = 6, max = 32, message = "密码长度需在 6–32 位之间") String password,

            @Size(max = 20, message = "手机号不能超过 20 字") String phone) {
    }

    /** 启用 / 停用账号：1 启用，0 停用，2 待启用 */
    public record UserStatusRequest(
            @NotNull(message = "状态不能为空")
            @Min(value = 0, message = "状态取值不合法")
            @Max(value = 2, message = "状态取值不合法") Integer status) {
    }

    /** 修改自己的密码 */
    public record PasswordChangeRequest(
            @NotBlank(message = "原密码不能为空") String oldPassword,

            @NotBlank(message = "新密码不能为空")
            @Size(min = 6, max = 32, message = "新密码长度需在 6–32 位之间") String newPassword) {
    }

    /** 负责人重置他人密码 */
    public record PasswordResetRequest(
            @NotBlank(message = "新密码不能为空")
            @Size(min = 6, max = 32, message = "密码长度需在 6–32 位之间") String password) {
    }
}
