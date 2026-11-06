package com.course.adorder.service;

import com.course.adorder.common.BusinessException;
import com.course.adorder.dto.RequestDtos;
import com.course.adorder.entity.DesignVersion;
import com.course.adorder.entity.MaterialOrder;
import com.course.adorder.entity.MaterialTask;
import com.course.adorder.entity.RevisionFeedback;
import com.course.adorder.entity.User;
import com.course.adorder.mapper.DesignVersionMapper;
import com.course.adorder.mapper.MaterialOrderMapper;
import com.course.adorder.mapper.MaterialTaskMapper;
import com.course.adorder.mapper.RevisionFeedbackMapper;
import com.course.adorder.mapper.UserMapper;
import com.course.adorder.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 物料任务服务：任务状态机与设计版本/修改意见规则。
 *
 * 任务状态机：
 *   PENDING（待分派） → DESIGNING（设计中） → PENDING_CONFIRM（待客户确认）
 *   PENDING_CONFIRM → REVISION（修改中） → PENDING_CONFIRM → CONFIRMED（已确认）
 * 关键规则：
 *  1) 设计人员只能处理分派给自己的任务；
 *  2) 上传设计稿只是提交，不代表客户确认；只有客户经理能记录客户确认结果；
 *  3) 新版本只追加，旧版本不可覆盖；
 *  4) 修改意见记录后任务进入修改中，需再次上传新版本。
 */
@Slf4j
@Service
public class TaskService {

    private final MaterialTaskMapper taskMapper;
    private final MaterialOrderMapper orderMapper;
    private final DesignVersionMapper versionMapper;
    private final RevisionFeedbackMapper feedbackMapper;
    private final UserMapper userMapper;
    private final OrderService orderService;
    private final Path uploadDir;
    private final TransactionTemplate txTemplate;

    public TaskService(MaterialTaskMapper taskMapper, MaterialOrderMapper orderMapper,
                       DesignVersionMapper versionMapper, RevisionFeedbackMapper feedbackMapper,
                       UserMapper userMapper, OrderService orderService,
                       PlatformTransactionManager txManager,
                       @org.springframework.beans.factory.annotation.Value("${app.upload-dir}") String uploadDir) {
        this.taskMapper = taskMapper;
        this.orderMapper = orderMapper;
        this.versionMapper = versionMapper;
        this.feedbackMapper = feedbackMapper;
        this.userMapper = userMapper;
        this.orderService = orderService;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /** 设计人员工作台：我的任务 */
    public List<Map<String, Object>> myTasks(LoginUser user, String status) {
        return taskMapper.selectMyTasks(user.getId(), status);
    }

    /**
     * 任务工作台（负责人/客户经理）：全量任务筛选 + 分页。
     *
     * <p>与 {@link #myTasks} 的区别：这里没有 assignee 数据归属限制，负责人需要看到
     * 所有任务来排产、发现未分派与逾期项。分页参数与订单列表保持同一套规则：
     * page 小于 1 按 1，size 小于 1 按 10，上限 100。</p>
     */
    public Map<String, Object> list(String status, Long assigneeId, Long orderId, String keyword,
                                    Boolean overdue, Boolean unassigned, Boolean extended,
                                    Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 100);
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        List<Map<String, Object>> rows = taskMapper.selectWorkbench(status, assigneeId, orderId, kw,
                overdue, unassigned, extended, (p - 1) * s, s);
        int total = taskMapper.countWorkbench(status, assigneeId, orderId, kw, overdue, unassigned, extended);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        result.put("page", p);
        result.put("size", s);
        return result;
    }

    /** 任务详情：任务 + 订单上下文 + 版本列表 + 意见列表 */
    public Map<String, Object> detail(LoginUser viewer, Long taskId) {
        MaterialTask task = requireExists(taskId);
        MaterialOrder order = orderService.requireExists(task.getOrderId());
        if ("DESIGNER".equals(viewer.getRole()) && !viewer.getId().equals(task.getAssigneeId())) {
            throw new BusinessException(BusinessException.FORBIDDEN, "设计人员只能查看分派给自己的任务");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("orderId", task.getOrderId());
        result.put("orderNo", order.getOrderNo());
        result.put("orderTitle", order.getTitle());
        result.put("orderStatus", order.getStatus());
        result.put("orderDeadline", order.getDeadline());
        result.put("materialType", task.getMaterialType());
        result.put("name", task.getName());
        result.put("spec", task.getSpec());
        result.put("quantity", task.getQuantity());
        result.put("usageDesc", task.getUsageDesc());
        result.put("referenceNote", task.getReferenceNote());
        result.put("deadline", task.getDeadline());
        result.put("status", task.getStatus());
        result.put("assigneeId", task.getAssigneeId());
        result.put("versions", versionMapper.selectByTaskId(taskId));
        result.put("feedbacks", feedbackMapper.selectByTaskId(taskId));
        return result;
    }

    /** 分派任务（业务负责人）：待分派 → 指定人员 */
    @Transactional
    public void assign(LoginUser user, Long taskId, Long assigneeId) {
        MaterialTask task = requireExists(taskId);
        if (!MaterialTask.PENDING.equals(task.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT, "仅“待分派”状态的任务可以分派（当前：" + task.getStatus() + "）");
        }
        requireActiveOrder(task.getOrderId());
        User assignee = userMapper.selectById(assigneeId);
        if (assignee == null || !"DESIGNER".equals(assignee.getRole()) || assignee.getStatus() != 1) {
            throw new BusinessException("所选人员不是启用状态的设计制作人员");
        }
        if (taskMapper.updateAssignee(taskId, assigneeId) == 0) {
            throw new BusinessException(BusinessException.CONFLICT, "任务状态已变化（可能已开始设计），请刷新后重试");
        }
        orderService.writeLog("TASK", taskId, task.getOrderId(), "TASK_ASSIGNED", "分派任务",
                "【" + task.getName() + "】分派给 " + assignee.getRealName(), user);
    }

    /** 开始设计（设计人员本人）：待分派 → 设计中 */
    @Transactional
    public void start(LoginUser user, Long taskId) {
        MaterialTask task = requireExists(taskId);
        requireAssignee(task, user);
        requireActiveOrder(task.getOrderId());
        if (!MaterialTask.PENDING.equals(task.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT, "仅“待分派”状态的任务可以开始设计（当前：" + task.getStatus() + "）");
        }
        if (taskMapper.updateStatus(taskId, MaterialTask.PENDING, MaterialTask.DESIGNING) == 0) {
            throw new BusinessException(BusinessException.CONFLICT, "任务状态已被其他人改变，请刷新后重试");
        }
        orderService.writeLog("TASK", taskId, task.getOrderId(), "TASK_STARTED", "开始设计",
                "【" + task.getName() + "】开始设计", user);
    }

    /**
     * 上传设计版本（设计人员本人）：设计中/修改中 → 待客户确认。
     *
     * 事务边界说明（重要）：
     *  1) 文件落盘是慢 IO，放在事务外，避免长时间占用数据库连接与行锁；
     *  2) 只有「版本入库 + 状态推进 + 写留痕」三步进事务，保证三者要么全成要么全败；
     *  3) 事务失败时补偿删除已落盘文件，避免孤儿文件；
     *  4) 版本号由 MAX+1 计算，并发下可能撞号，靠 uk_task_version 唯一索引兜底并重试。
     */
    public Map<String, Object> uploadVersion(LoginUser user, Long taskId, MultipartFile file, String note) {
        // ① 事务外做快速校验，尽早给出 403/409，避免白落盘
        MaterialTask probe = requireExists(taskId);
        requireAssignee(probe, user);
        requireActiveOrder(probe.getOrderId());
        requireUploadable(probe);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的设计稿文件");
        }

        // ② 事务外落盘
        final String storedPath = store(file);
        final String originalName = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        final long fileSize = file.getSize();

        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    Map<String, Object> result = txTemplate.execute(status -> {
                        // 事务内重读：避免校验与写入之间任务状态被别人改掉
                        MaterialTask task = requireExists(taskId);
                        requireAssignee(task, user);
                        requireUploadable(task);
                        requireActiveOrder(task.getOrderId());

                        Integer maxNo = versionMapper.selectMaxVersionNo(taskId);
                        int versionNo = (maxNo == null ? 0 : maxNo) + 1;

                        DesignVersion version = new DesignVersion();
                        version.setTaskId(taskId);
                        version.setVersionNo(versionNo);
                        version.setFileName(originalName);
                        version.setFilePath(storedPath);
                        version.setFileSize(fileSize);
                        version.setNote(note);
                        version.setUploadedBy(user.getId());
                        versionMapper.insert(version);

                        // 上传设计稿 ≠ 客户确认：条件更新推进到“待客户确认”，
                        // from 用事务内读到的当前状态，防止与"记录意见"等并发操作互相覆盖
                        if (taskMapper.updateStatus(taskId, task.getStatus(), MaterialTask.PENDING_CONFIRM) == 0) {
                            throw new BusinessException(BusinessException.CONFLICT, "任务状态已被其他人改变，请刷新后重试");
                        }
                        orderService.writeLog("TASK", taskId, task.getOrderId(), "VERSION_UPLOADED", "上传设计版本",
                                "【" + task.getName() + "】第 " + versionNo + " 版：" + originalName, user);

                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("versionNo", versionNo);
                        map.put("taskId", taskId);
                        map.put("status", MaterialTask.PENDING_CONFIRM);
                        return map;
                    });
                    return result;
                }
                catch (DuplicateKeyException e) {
                    // 并发上传撞了版本号，事务已回滚，换号重试
                    if (attempt >= 3) {
                        throw new BusinessException(500, "版本号冲突，请重试");
                    }
                    log.warn("版本号冲突，第 {} 次重试，taskId={}", attempt, taskId);
                }
            }
        }
        catch (RuntimeException e) {
            // ③ 事务未成功 → 删除已落盘文件，不留孤儿
            deleteQuietly(storedPath);
            throw e;
        }
    }

    /** 仅“设计中/修改中”可上传，且上传后进入待客户确认 */
    private void requireUploadable(MaterialTask task) {
        if (!MaterialTask.DESIGNING.equals(task.getStatus()) && !MaterialTask.REVISION.equals(task.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT,
                    "当前状态不允许上传设计稿（当前：" + task.getStatus() + "），上传后即进入待客户确认");
        }
    }

    /**
     * 订单已结束（已交付 / 已取消）时，其任务不允许再推进。
     * 少了这一层，取消订单后设计人员仍能继续开始设计、上传、确认，产生"已取消订单还在推进"的脏数据。
     */
    private MaterialOrder requireActiveOrder(Long orderId) {
        MaterialOrder order = orderService.requireExists(orderId);
        if (MaterialOrder.COMPLETED.equals(order.getStatus()) || MaterialOrder.CANCELLED.equals(order.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT, "订单已结束，不能继续处理其物料任务");
        }
        return order;
    }

    /** 删除已落盘文件，失败只记日志（不能因清理失败掩盖原始异常） */
    private void deleteQuietly(String relativePath) {
        try {
            Path p = uploadDir.resolve(relativePath).normalize();
            if (p.startsWith(uploadDir)) {
                Files.deleteIfExists(p);
            }
        }
        catch (IOException e) {
            log.warn("回滚上传文件失败，需人工清理：{}", relativePath, e);
        }
    }

    /** 记录客户修改意见（客户经理）：待客户确认 → 修改中 */
    @Transactional
    public void recordFeedback(LoginUser user, Long taskId, String content) {
        MaterialTask task = requireExists(taskId);
        if (!MaterialTask.PENDING_CONFIRM.equals(task.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT,
                    "仅“待客户确认”状态的任务可以记录修改意见（当前：" + task.getStatus() + "）");
        }
        requireActiveOrder(task.getOrderId());

        // 轮次由 MAX+1 计算，并发下由 uk_task_round 唯一索引兜底并重试
        for (int attempt = 1; ; attempt++) {
            try {
                Integer maxRound = feedbackMapper.selectMaxRoundNo(taskId);
                int round = (maxRound == null ? 0 : maxRound) + 1;

                RevisionFeedback fb = new RevisionFeedback();
                fb.setTaskId(taskId);
                fb.setRoundNo(round);
                fb.setContent(content);
                fb.setRecordedBy(user.getId());
                feedbackMapper.insert(fb);

                if (taskMapper.updateStatus(taskId, MaterialTask.PENDING_CONFIRM, MaterialTask.REVISION) == 0) {
                    throw new BusinessException(BusinessException.CONFLICT, "任务状态已被其他人改变，请刷新后重试");
                }
                orderService.writeLog("TASK", taskId, task.getOrderId(), "FEEDBACK_RECORDED", "记录客户修改意见",
                        "【" + task.getName() + "】第 " + round + " 轮：" + abbreviate(content), user);
                return;
            }
            catch (DuplicateKeyException e) {
                if (attempt >= 3) {
                    throw new BusinessException(500, "意见轮次冲突，请重试");
                }
                log.warn("意见轮次冲突，第 {} 次重试，taskId={}", attempt, taskId);
            }
        }
    }

    /** 客户确认（客户经理）：待客户确认 → 已确认。上传文件不等于确认，只有客户经理能执行。 */
    @Transactional
    public void confirmTask(LoginUser user, Long taskId) {
        MaterialTask task = requireExists(taskId);
        if (!MaterialTask.PENDING_CONFIRM.equals(task.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT,
                    "仅“待客户确认”状态的任务可以确认（当前：" + task.getStatus() + "）");
        }
        requireActiveOrder(task.getOrderId());
        if (taskMapper.updateStatus(taskId, MaterialTask.PENDING_CONFIRM, MaterialTask.CONFIRMED) == 0) {
            throw new BusinessException(BusinessException.CONFLICT, "任务状态已被其他人改变，请刷新后重试");
        }
        orderService.writeLog("TASK", taskId, task.getOrderId(), "TASK_CONFIRMED", "客户确认",
                "【" + task.getName() + "】客户确认当前版本，任务完成", user);
    }

    /** 修改物料截止日期（客户经理/负责人）：任何未结束订单的变更都留痕 */
    @Transactional
    public void updateDeadline(LoginUser user, Long taskId, LocalDate deadline) {
        MaterialTask task = requireExists(taskId);
        MaterialOrder order = orderService.requireExists(task.getOrderId());
        if (MaterialTask.CONFIRMED.equals(task.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT, "任务已确认完成，不能修改截止日期");
        }
        if (MaterialOrder.COMPLETED.equals(order.getStatus()) || MaterialOrder.CANCELLED.equals(order.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT, "订单已结束，不能修改任务截止日期");
        }
        if (deadline == null) {
            throw new BusinessException("截止日期不能为空");
        }
        if (deadline.isBefore(LocalDate.now())) {
            throw new BusinessException("物料截止日期不能早于今天");
        }
        if (order.getDeadline() != null && deadline.isAfter(order.getDeadline())) {
            throw new BusinessException("物料截止日期不能晚于订单整体截止日期（" + order.getDeadline() + "）");
        }
        taskMapper.updateDeadline(taskId, deadline);
        orderService.writeLog("TASK", taskId, task.getOrderId(), "TASK_DEADLINE_CHANGED", "变更物料截止日期",
                "【" + task.getName() + "】原截止日期 " + task.getDeadline() + " → 新截止日期 " + deadline
                        + (MaterialOrder.IN_PROGRESS.equals(order.getStatus())
                        ? "（客户同意后变更）" : "（确认前调整）"),
                user);
    }

    private MaterialTask requireExists(Long taskId) {
        MaterialTask t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "物料任务不存在");
        }
        return t;
    }

    private void requireAssignee(MaterialTask task, LoginUser user) {
        if (!user.getId().equals(task.getAssigneeId())) {
            throw new BusinessException(BusinessException.FORBIDDEN, "设计人员只能处理分派给自己的任务");
        }
    }

    private String abbreviate(String s) {
        return s.length() <= 50 ? s : s.substring(0, 50) + "…";
    }

    /** 保存上传文件到 uploads/yyyyMM/uuid.ext，返回相对路径 */
    private String store(MultipartFile file) {
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.lastIndexOf('.') >= 0) {
            ext = original.substring(original.lastIndexOf('.'));
            if (!ext.matches("\\.(png|jpg|jpeg|gif|webp|pdf|psd|ai|cdr|zip|rar)")) {
                throw new BusinessException("不支持的文件类型，请上传图片、PDF 或设计源文件");
            }
        }
        try {
            String month = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            Path dir = uploadDir.resolve(month);
            Files.createDirectories(dir);
            String stored = month + "/" + UUID.randomUUID() + ext;
            Files.copy(file.getInputStream(), uploadDir.resolve(stored), StandardCopyOption.REPLACE_EXISTING);
            return stored;
        } catch (IOException e) {
            throw new BusinessException(500, "文件保存失败，请稍后重试");
        }
    }

    /** 读取上传文件（用于下载/预览）：路径校验 + 设计人员数据归属校验 */
    public Path resolve(LoginUser viewer, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")) {
            throw new BusinessException(BusinessException.BAD_REQUEST, "非法文件路径");
        }
        String storedPath = relativePath.replace('\\', '/');
        DesignVersion version = versionMapper.selectByFilePath(storedPath);
        if (version == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "文件不存在或已被清理");
        }
        // 设计人员只能下载自己任务的设计稿；客户经理/负责人按业务需要可以下载
        if ("DESIGNER".equals(viewer.getRole())) {
            MaterialTask task = requireExists(version.getTaskId());
            if (!viewer.getId().equals(task.getAssigneeId())) {
                throw new BusinessException(BusinessException.FORBIDDEN,
                        "设计人员只能下载分派给自己的任务的设计稿");
            }
        }
        Path p = uploadDir.resolve(storedPath).normalize();
        if (!p.startsWith(uploadDir) || !Files.exists(p)) {
            throw new BusinessException(BusinessException.NOT_FOUND, "文件不存在或已被清理");
        }
        return p;
    }

    /** 允许的物料类型（校验用） */
    public static final Set<String> MATERIAL_TYPES = Set.of("POSTER", "STAND", "MENU", "BROCHURE", "ONLINE");
}
