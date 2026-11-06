package com.course.adorder.service;

import com.course.adorder.common.BusinessException;
import com.course.adorder.dto.RequestDtos;
import com.course.adorder.entity.MaterialOrder;
import com.course.adorder.entity.MaterialTask;
import com.course.adorder.entity.OperationLog;
import com.course.adorder.mapper.MaterialOrderMapper;
import com.course.adorder.mapper.MaterialTaskMapper;
import com.course.adorder.mapper.OperationLogMapper;
import com.course.adorder.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单服务：状态机与核心业务规则的落点。
 *
 * 订单状态机：PENDING_CONFIRM → IN_PROGRESS → COMPLETED；PENDING_CONFIRM → CANCELLED
 * 关键规则：
 *  1) 只有业务负责人可确认订单（确认物料清单与截止时间）；
 *  2) 确认后不可直接覆盖原要求——整体截止日期变更、新增物料任务必须写入变更记录；
 *  3) 订单内全部物料任务确认后，整笔订单才能交付完成。
 */
@Slf4j
@Service
public class OrderService {

    private final MaterialOrderMapper orderMapper;
    private final MaterialTaskMapper taskMapper;
    private final OperationLogMapper logMapper;
    private final CustomerService customerService;

    public OrderService(MaterialOrderMapper orderMapper, MaterialTaskMapper taskMapper,
                        OperationLogMapper logMapper, CustomerService customerService) {
        this.orderMapper = orderMapper;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.customerService = customerService;
    }

    /** 创建订单（客户经理） */
    @Transactional
    public Map<String, Object> create(LoginUser user, RequestDtos.OrderCreateRequest req) {
        customerService.requireExists(req.customerId());
        if (req.tasks() == null || req.tasks().isEmpty()) {
            throw new BusinessException("订单至少包含一项物料任务");
        }
        // 截止日期边界：整体不能早于今天，物料不能早于今天、也不能晚于整体
        requireNotPast(req.deadline(), "整体截止日期");
        for (RequestDtos.TaskCreateRequest t : req.tasks()) {
            requireValidTaskDeadline(t.deadline(), req.deadline());
        }

        MaterialOrder order = new MaterialOrder();
        order.setCustomerId(req.customerId());
        order.setTitle(req.title());
        order.setDescription(req.description());
        order.setDeadline(req.deadline());
        order.setStatus(MaterialOrder.PENDING_CONFIRM);
        order.setCreatedBy(user.getId());

        // 订单编号 = AO + 日期 + 4 位序号；并发下可能撞号，
        // 由唯一索引 uk_order_no 兜底，换号重试（每次序号再往后挪一位）。
        for (int attempt = 1; ; attempt++) {
            order.setOrderNo(nextOrderNo(attempt));
            try {
                orderMapper.insert(order);
                break;
            }
            catch (DuplicateKeyException e) {
                if (attempt >= 3) {
                    throw new BusinessException(500, "订单编号生成冲突，请重试");
                }
                log.warn("订单编号冲突，第 {} 次重试，title={}", attempt, req.title());
            }
        }

        for (RequestDtos.TaskCreateRequest t : req.tasks()) {
            insertTask(order.getId(), t);
        }
        writeLog("ORDER", order.getId(), order.getId(), "ORDER_CREATED", "创建订单",
                "登记订单，含 " + req.tasks().size() + " 项物料任务，等待负责人确认", user);
        return Map.of("id", order.getId(), "orderNo", order.getOrderNo());
    }

    /**
     * 订单列表（分页 + 角色数据归属）。
     * 设计人员只看到含本人任务的订单，过滤在 SQL 层完成，保证 total 与分页一致。
     */
    public Map<String, Object> list(LoginUser viewer, String status, Long customerId,
                                    String keyword, Long designerId,
                                    LocalDate deadlineFrom, LocalDate deadlineTo,
                                    Boolean overdue, Boolean extended, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 100);

        List<Map<String, Object>> rows = orderMapper.selectList(status, customerId, keyword, designerId,
                deadlineFrom, deadlineTo, overdue, extended,
                viewer.getId(), viewer.getRole(), (p - 1) * s, s);
        int total = orderMapper.countList(status, customerId, keyword, designerId,
                deadlineFrom, deadlineTo, overdue, extended, viewer.getId(), viewer.getRole());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        result.put("page", p);
        result.put("size", s);
        return result;
    }

    /** 订单详情：基本信息 + 任务列表 + 操作历史 */
    public Map<String, Object> detail(LoginUser viewer, Long id) {
        MaterialOrder order = requireExists(id);
        // 设计人员数据归属校验：只能查看包含自己任务的订单
        if ("DESIGNER".equals(viewer.getRole())
                && taskMapper.countByOrderAndAssignee(id, viewer.getId()) == 0) {
            throw new BusinessException(BusinessException.FORBIDDEN, "设计人员只能查看与自己相关的订单");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", order.getId());
        result.put("orderNo", order.getOrderNo());
        result.put("title", order.getTitle());
        result.put("description", order.getDescription());
        result.put("deadline", order.getDeadline());
        result.put("status", order.getStatus());
        result.put("createdAt", order.getCreatedAt());
        result.put("customer", customerService.requireExists(order.getCustomerId()));
        result.put("tasks", taskMapper.selectByOrderId(id));
        result.put("logs", logMapper.selectByOrderId(id));
        return result;
    }

    /** 确认订单（业务负责人）：待确认 → 进行中 */
    @Transactional
    public void confirm(LoginUser user, Long id) {
        MaterialOrder order = requireExists(id);
        checkTransition(order.getStatus(), MaterialOrder.PENDING_CONFIRM, "订单");
        if (taskMapper.selectByOrderId(id).isEmpty()) {
            throw new BusinessException("订单还没有物料任务，无法确认");
        }
        // 条件更新：读到与写入之间若被人抢先改了状态，影响行数为 0，按冲突处理
        if (orderMapper.updateStatus(id, MaterialOrder.PENDING_CONFIRM, MaterialOrder.IN_PROGRESS) == 0) {
            throw new BusinessException(BusinessException.CONFLICT, "订单状态已被其他人改变，请刷新后重试");
        }
        writeLog("ORDER", id, id, "ORDER_CONFIRMED", "确认订单",
                "确认物料清单与整体截止日期 " + (order.getDeadline() == null ? "-" : order.getDeadline()), user);
    }

    /** 取消订单：仅待确认状态可取消 */
    @Transactional
    public void cancel(LoginUser user, Long id) {
        MaterialOrder order = requireExists(id);
        checkTransition(order.getStatus(), MaterialOrder.PENDING_CONFIRM, "订单");
        if (orderMapper.updateStatus(id, MaterialOrder.PENDING_CONFIRM, MaterialOrder.CANCELLED) == 0) {
            throw new BusinessException(BusinessException.CONFLICT, "订单状态已被其他人改变，请刷新后重试");
        }
        writeLog("ORDER", id, id, "ORDER_CANCELLED", "取消订单", "订单在待确认阶段被取消", user);
    }

    /** 订单交付：进行中且全部物料任务已确认 */
    @Transactional
    public void complete(LoginUser user, Long id) {
        MaterialOrder order = requireExists(id);
        checkTransition(order.getStatus(), MaterialOrder.IN_PROGRESS, "订单");
        int unconfirmed = taskMapper.countUnconfirmedByOrderId(id);
        if (unconfirmed > 0) {
            throw new BusinessException(BusinessException.CONFLICT,
                    "还有 " + unconfirmed + " 项物料任务未确认，整笔订单不能交付");
        }
        if (orderMapper.updateStatus(id, MaterialOrder.IN_PROGRESS, MaterialOrder.COMPLETED) == 0) {
            throw new BusinessException(BusinessException.CONFLICT, "订单状态已被其他人改变，请刷新后重试");
        }
        writeLog("ORDER", id, id, "ORDER_COMPLETED", "订单交付",
                "订单内全部物料已确认，订单交付完成", user);
    }

    /** 修改整体截止日期（客户经理/负责人）：任何未结束订单的变更都留痕 */
    @Transactional
    public void updateDeadline(LoginUser user, Long id, LocalDate deadline) {
        MaterialOrder order = requireExists(id);
        if (MaterialOrder.COMPLETED.equals(order.getStatus()) || MaterialOrder.CANCELLED.equals(order.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT, "订单已结束，不能修改截止日期");
        }
        requireNotPast(deadline, "整体截止日期");
        // 整体截止不能早于任何未取消物料的截止，否则等于把物料排到交付之后
        LocalDate maxTaskDeadline = taskMapper.selectMaxDeadlineByOrderId(id);
        if (maxTaskDeadline != null && deadline.isBefore(maxTaskDeadline)) {
            throw new BusinessException("整体截止日期不能早于物料的最晚截止日期（" + maxTaskDeadline + "）");
        }
        orderMapper.updateDeadline(id, deadline);
        // 任何未结束订单的截止日期变化都留痕；异常管理据此标记「已延期」
        writeLog("ORDER", id, id, "ORDER_DEADLINE_CHANGED", "变更整体截止日期",
                "原截止日期 " + order.getDeadline() + " → 新截止日期 " + deadline
                        + (MaterialOrder.IN_PROGRESS.equals(order.getStatus())
                        ? "（客户同意后变更）" : "（确认前调整）"),
                user);
    }

    /** 确认后新增物料任务（客户经理）：进行中订单的新增物料必须留变更记录 */
    @Transactional
    public void addTask(LoginUser user, Long orderId, RequestDtos.TaskCreateRequest t) {
        MaterialOrder order = requireExists(orderId);
        if (!MaterialOrder.PENDING_CONFIRM.equals(order.getStatus())
                && !MaterialOrder.IN_PROGRESS.equals(order.getStatus())) {
            throw new BusinessException(BusinessException.CONFLICT, "订单已结束，不能新增物料任务");
        }
        requireValidTaskDeadline(t.deadline(), order.getDeadline());
        insertTask(orderId, t);
        if (MaterialOrder.IN_PROGRESS.equals(order.getStatus())) {
            writeLog("TASK", null, orderId, "TASK_ADDED", "新增物料任务",
                    "订单确认后新增物料【" + t.name() + "】，数量 " + t.quantity()
                            + "，截止日期 " + (t.deadline() == null ? "-" : t.deadline()) + "（变更记录）", user);
        }
    }

    private void insertTask(Long orderId, RequestDtos.TaskCreateRequest t) {
        // 服务层再校验一次类型白名单：DTO 注解会被绕过（例如内部调用），这里不能省
        if (!TaskService.MATERIAL_TYPES.contains(t.materialType())) {
            throw new BusinessException("物料类型不合法：" + t.materialType());
        }
        MaterialTask task = new MaterialTask();
        task.setOrderId(orderId);
        task.setMaterialType(t.materialType());
        task.setName(t.name());
        task.setSpec(t.spec());
        task.setQuantity(t.quantity() == null ? 1 : t.quantity());
        task.setUsageDesc(t.usageDesc());
        task.setReferenceNote(t.referenceNote());
        task.setDeadline(t.deadline());
        task.setStatus(MaterialTask.PENDING);
        taskMapper.insert(task);
    }

    public MaterialOrder requireExists(Long id) {
        MaterialOrder o = orderMapper.selectById(id);
        if (o == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        return o;
    }

    private void checkTransition(String current, String expected, String name) {
        if (!expected.equals(current)) {
            throw new BusinessException(BusinessException.CONFLICT,
                    name + "当前状态不允许执行该操作（当前状态：" + current + "）");
        }
    }

    /** 截止日期不能早于今天；null 表示未设置，允许 */
    private void requireNotPast(LocalDate deadline, String label) {
        if (deadline != null && deadline.isBefore(LocalDate.now())) {
            throw new BusinessException(label + "不能早于今天");
        }
    }

    /** 物料截止日期：不能早于今天，也不能晚于订单整体截止日期 */
    private void requireValidTaskDeadline(LocalDate taskDeadline, LocalDate orderDeadline) {
        requireNotPast(taskDeadline, "物料截止日期");
        if (taskDeadline != null && orderDeadline != null && taskDeadline.isAfter(orderDeadline)) {
            throw new BusinessException("物料截止日期不能晚于订单整体截止日期（" + orderDeadline + "）");
        }
    }

    /**
     * 订单编号：AO + 日期 + 当日序号。
     * offset 用于撞号后重试时顺延，正常路径为 1。
     */
    private String nextOrderNo(int offset) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "AO" + date;
        int seq = orderMapper.countByOrderNoPrefix(prefix) + offset;
        return prefix + String.format("%04d", seq);
    }

    /** 写操作历史 */
    public void writeLog(String bizType, Long bizId, Long orderId, String action,
                         String actionText, String detail, LoginUser operator) {
        OperationLog log = new OperationLog();
        log.setBizType(bizType);
        log.setBizId(bizId == null ? orderId : bizId);
        log.setOrderId(orderId);
        log.setAction(action);
        log.setActionText(actionText);
        log.setDetail(detail);
        log.setOperatorId(operator.getId());
        log.setOperatorName(operator.getRealName());
        log.setOperatorRole(operator.getRole());
        logMapper.insert(log);
    }
}
