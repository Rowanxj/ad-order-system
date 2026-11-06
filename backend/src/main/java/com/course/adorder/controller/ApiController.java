package com.course.adorder.controller;

import com.course.adorder.common.Result;
import com.course.adorder.dto.RequestDtos;
import com.course.adorder.entity.Customer;
import com.course.adorder.entity.User;
import com.course.adorder.security.LoginUser;
import com.course.adorder.security.RequireRole;
import com.course.adorder.security.UserContext;
import com.course.adorder.service.AuthService;
import com.course.adorder.service.CustomerService;
import com.course.adorder.service.NotificationService;
import com.course.adorder.service.OrderService;
import com.course.adorder.service.TaskService;
import com.course.adorder.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * 全部业务接口（统一 /api 前缀）。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final AuthService authService;
    private final CustomerService customerService;
    private final OrderService orderService;
    private final TaskService taskService;
    private final UserService userService;
    private final NotificationService notificationService;

    public ApiController(AuthService authService, CustomerService customerService,
                         OrderService orderService, TaskService taskService,
                         UserService userService, NotificationService notificationService) {
        this.authService = authService;
        this.customerService = customerService;
        this.orderService = orderService;
        this.taskService = taskService;
        this.userService = userService;
        this.notificationService = notificationService;
    }

    // ==================== 认证 ====================

    /** 登录（拦截器放行，无需 token）。role 为可选的“以什么身份登录”提示，会参与校验 */
    @PostMapping("/auth/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        String role = body.getOrDefault("role", "").trim();
        if (username.isEmpty() || password.isEmpty()) {
            return Result.error(400, "请输入账号和密码");
        }
        return Result.ok(authService.login(username, password, role));
    }

    /** 当前登录用户 */
    @GetMapping("/auth/me")
    public Result<LoginUser> me() {
        return Result.ok(UserContext.get());
    }

    // ==================== 客户 ====================

    @GetMapping("/customers")
    @RequireRole({"SALES", "MANAGER"})
    public Result<List<Customer>> customers(@RequestParam(required = false) String keyword) {
        return Result.ok(customerService.list(keyword));
    }

    @PostMapping("/customers")
    @RequireRole({"SALES"})
    public Result<Customer> createCustomer(@Valid @RequestBody RequestDtos.CustomerCreateRequest req) {
        return Result.ok(customerService.create(UserContext.get(), req));
    }

    // ==================== 订单 ====================

    /** 订单列表（分页；设计人员只看含本人任务的订单） */
    /** 订单列表（分页；设计人员只看含本人任务的订单；支持截止区间与只看逾期） */
    @GetMapping("/orders")
    public Result<Map<String, Object>> orders(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) Long customerId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Long designerId,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deadlineFrom,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deadlineTo,
                                              @RequestParam(required = false) Boolean overdue,
                                              @RequestParam(required = false) Boolean extended,
                                              @RequestParam(defaultValue = "1") Integer page,
                                              @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(orderService.list(UserContext.get(), status, customerId, keyword, designerId,
                deadlineFrom, deadlineTo, overdue, extended, page, size));
    }

    @PostMapping("/orders")
    @RequireRole({"SALES"})
    public Result<Map<String, Object>> createOrder(@Valid @RequestBody RequestDtos.OrderCreateRequest req) {
        return Result.ok(orderService.create(UserContext.get(), req));
    }

    @GetMapping("/orders/{id}")
    public Result<Map<String, Object>> orderDetail(@PathVariable Long id) {
        return Result.ok(orderService.detail(UserContext.get(), id));
    }

    /** 确认订单（业务负责人） */
    @PutMapping("/orders/{id}/confirm")
    @RequireRole({"MANAGER"})
    public Result<Void> confirmOrder(@PathVariable Long id) {
        orderService.confirm(UserContext.get(), id);
        return Result.ok();
    }

    /** 取消订单（客户经理/负责人，仅待确认） */
    @PutMapping("/orders/{id}/cancel")
    @RequireRole({"SALES", "MANAGER"})
    public Result<Void> cancelOrder(@PathVariable Long id) {
        orderService.cancel(UserContext.get(), id);
        return Result.ok();
    }

    /** 订单交付（业务负责人，全部任务确认后） */
    @PutMapping("/orders/{id}/complete")
    @RequireRole({"MANAGER"})
    public Result<Void> completeOrder(@PathVariable Long id) {
        orderService.complete(UserContext.get(), id);
        return Result.ok();
    }

    /** 修改整体截止日期（客户经理/负责人） */
    @PutMapping("/orders/{id}/deadline")
    @RequireRole({"SALES", "MANAGER"})
    public Result<Void> updateOrderDeadline(@PathVariable Long id,
                                            @Valid @RequestBody RequestDtos.DeadlineRequest req) {
        orderService.updateDeadline(UserContext.get(), id, req.deadline());
        return Result.ok();
    }

    /** 新增物料任务（客户经理；确认后的订单会留变更记录） */
    @PostMapping("/orders/{id}/tasks")
    @RequireRole({"SALES"})
    public Result<Void> addTask(@PathVariable Long id,
                                @Valid @RequestBody RequestDtos.TaskCreateRequest req) {
        orderService.addTask(UserContext.get(), id, req);
        return Result.ok();
    }

    // ==================== 任务 ====================

    /** 任务工作台（客户经理/负责人）：全量任务筛选 + 分页 */
    @GetMapping("/tasks")
    @RequireRole({"SALES", "MANAGER"})
    public Result<Map<String, Object>> listTasks(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Long assigneeId,
                                                 @RequestParam(required = false) Long orderId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Boolean overdue,
                                                 @RequestParam(required = false) Boolean unassigned,
                                                 @RequestParam(required = false) Boolean extended,
                                                 @RequestParam(defaultValue = "1") Integer page,
                                                 @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(taskService.list(status, assigneeId, orderId, keyword,
                overdue, unassigned, extended, page, size));
    }

    /** 我的设计任务（设计人员工作台） */
    @GetMapping("/tasks/my")
    public Result<List<Map<String, Object>>> myTasks(@RequestParam(required = false) String status) {
        return Result.ok(taskService.myTasks(UserContext.get(), status));
    }

    @GetMapping("/tasks/{id}")
    public Result<Map<String, Object>> taskDetail(@PathVariable Long id) {
        return Result.ok(taskService.detail(UserContext.get(), id));
    }

    /** 分派任务（业务负责人） */
    @PutMapping("/tasks/{id}/assign")
    @RequireRole({"MANAGER"})
    public Result<Void> assignTask(@PathVariable Long id, @Valid @RequestBody RequestDtos.AssignRequest req) {
        taskService.assign(UserContext.get(), id, req.assigneeId());
        return Result.ok();
    }

    /** 开始设计（设计人员本人） */
    @PutMapping("/tasks/{id}/start")
    public Result<Void> startTask(@PathVariable Long id) {
        taskService.start(UserContext.get(), id);
        return Result.ok();
    }

    /** 上传设计版本（设计人员本人） */
    @PostMapping("/tasks/{id}/versions")
    public Result<Map<String, Object>> uploadVersion(@PathVariable Long id,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam(value = "note", required = false) String note) {
        return Result.ok(taskService.uploadVersion(UserContext.get(), id, file, note));
    }

    /** 记录客户修改意见（客户经理） */
    @PostMapping("/tasks/{id}/feedbacks")
    @RequireRole({"SALES"})
    public Result<Void> recordFeedback(@PathVariable Long id,
                                       @Valid @RequestBody RequestDtos.FeedbackRequest req) {
        taskService.recordFeedback(UserContext.get(), id, req.content());
        return Result.ok();
    }

    /** 客户确认（客户经理） */
    @PutMapping("/tasks/{id}/confirm")
    @RequireRole({"SALES"})
    public Result<Void> confirmTask(@PathVariable Long id) {
        taskService.confirmTask(UserContext.get(), id);
        return Result.ok();
    }

    /** 修改物料截止日期（客户经理/负责人） */
    @PutMapping("/tasks/{id}/deadline")
    @RequireRole({"SALES", "MANAGER"})
    public Result<Void> updateTaskDeadline(@PathVariable Long id,
                                           @Valid @RequestBody RequestDtos.DeadlineRequest req) {
        taskService.updateDeadline(UserContext.get(), id, req.deadline());
        return Result.ok();
    }

    // ==================== 用户/文件 ====================

    /** 设计人员下拉（分派用；只含启用人员，且不含任何账号敏感字段） */
    @GetMapping("/users/designers")
    @RequireRole({"MANAGER"})
    public Result<List<Map<String, Object>>> designers() {
        return Result.ok(userService.designers());
    }

    // ==================== 注册与账号管理 ====================

    /** 自助注册（公开）：只能注册为设计制作人员，注册后待负责人启用 */
    @PostMapping("/auth/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody RequestDtos.RegisterRequest req) {
        return Result.ok(authService.register(req));
    }

    /** 修改本人密码 */
    @PutMapping("/auth/password")
    public Result<Void> changeMyPassword(@Valid @RequestBody RequestDtos.PasswordChangeRequest req) {
        authService.changePassword(UserContext.get(), req.oldPassword(), req.newPassword());
        return Result.ok();
    }

    /** 账号列表（业务负责人） */
    @GetMapping("/users")
    @RequireRole({"MANAGER"})
    public Result<List<User>> users() {
        return Result.ok(userService.list());
    }

    /** 新增账号（业务负责人，可指定角色） */
    @PostMapping("/users")
    @RequireRole({"MANAGER"})
    public Result<User> createUser(@Valid @RequestBody RequestDtos.UserCreateRequest req) {
        return Result.ok(userService.create(req));
    }

    /** 启用 / 停用账号（业务负责人） */
    @PutMapping("/users/{id}/status")
    @RequireRole({"MANAGER"})
    public Result<Void> changeUserStatus(@PathVariable Long id,
                                         @Valid @RequestBody RequestDtos.UserStatusRequest req) {
        userService.changeStatus(UserContext.get(), id, req.status());
        return Result.ok();
    }

    /** 重置他人密码（业务负责人） */
    @PutMapping("/users/{id}/password")
    @RequireRole({"MANAGER"})
    public Result<Void> resetUserPassword(@PathVariable Long id,
                                          @Valid @RequestBody RequestDtos.PasswordResetRequest req) {
        userService.resetPassword(UserContext.get(), id, req.password());
        return Result.ok();
    }

    // ==================== 待办提醒 ====================

    /** 当前用户的待办汇总与最近消息（任何登录用户；内容按角色不同） */
    @GetMapping("/notifications/summary")
    public Result<Map<String, Object>> notificationSummary() {
        return Result.ok(notificationService.summary(UserContext.get()));
    }

    /** 订单列表顶部的大盘统计（卡在哪个环节的单子最多） */
    @GetMapping("/orders/stats")
    public Result<Map<String, Object>> orderStats() {
        return Result.ok(notificationService.stats(UserContext.get()));
    }

    /** 下载/预览设计稿（需登录；路径来自数据库，防目录穿越） */
    @GetMapping("/files/**")
    public ResponseEntity<FileSystemResource> file(HttpServletRequest request) {
        // 提取 /api/files/ 之后的相对路径
        String uri = request.getRequestURI();
        String relative = uri.substring(uri.indexOf("/api/files/") + "/api/files/".length());
        java.nio.file.Path path = taskService.resolve(UserContext.get(), relative);
        String downloadName = path.getFileName().toString();
        String encoded = URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }
}
