package com.course.adorder.service;

import com.course.adorder.common.BusinessException;
import com.course.adorder.dto.RequestDtos;
import com.course.adorder.entity.Customer;
import com.course.adorder.entity.MaterialOrder;
import com.course.adorder.entity.MaterialTask;
import com.course.adorder.entity.OperationLog;
import com.course.adorder.mapper.MaterialOrderMapper;
import com.course.adorder.mapper.MaterialTaskMapper;
import com.course.adorder.mapper.OperationLogMapper;
import com.course.adorder.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单服务单元测试：状态机、订单号并发重试、分页参数与留痕。
 *
 * <p>不启动 Spring、不连数据库；Mapper 与 CustomerService 全部 mock。
 * 与 {@code check-api.ps1} 的分工：这里锁住分支与参数，真实 SQL/HTTP 由验收契约覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long ORDER_ID = 10L;
    private static final Long CUSTOMER_ID = 1L;
    private static final Long SALES_ID = 2L;
    private static final Long MANAGER_ID = 3L;
    private static final Long DESIGNER_ID = 7L;

    @Mock
    private MaterialOrderMapper orderMapper;
    @Mock
    private MaterialTaskMapper taskMapper;
    @Mock
    private OperationLogMapper logMapper;
    @Mock
    private CustomerService customerService;

    private OrderService orderService;
    private LoginUser sales;
    private LoginUser manager;
    private LoginUser designer;

    @BeforeEach
    void setUp() {
        sales = new LoginUser(SALES_ID, "sales", "客户经理小张", "SALES");
        manager = new LoginUser(MANAGER_ID, "manager", "负责人老王", "MANAGER");
        designer = new LoginUser(DESIGNER_ID, "designer", "设计师小李", "DESIGNER");
        orderService = new OrderService(orderMapper, taskMapper, logMapper, customerService);
    }

    // ------------------------------------------------------------------
    // 创建订单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建订单：没有物料任务直接拒绝")
    void createRejectsEmptyTasks() {
        when(customerService.requireExists(CUSTOMER_ID)).thenReturn(customer());

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> orderService.create(sales, orderReq(List.of())));
        verify(orderMapper, never()).insert(any(MaterialOrder.class));
    }

    @Test
    @DisplayName("创建订单：服务层再校验物料类型白名单")
    void createRejectsInvalidMaterialType() {
        when(customerService.requireExists(CUSTOMER_ID)).thenReturn(customer());
        when(orderMapper.countByOrderNoPrefix(anyString())).thenReturn(0);
        when(orderMapper.insert(any(MaterialOrder.class))).thenAnswer(inv -> {
            ((MaterialOrder) inv.getArgument(0)).setId(99L);
            return 1;
        });

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> orderService.create(sales, orderReq(List.of(
                        taskReq("BILLBOARD", "户外大牌", 1, LocalDate.of(2026, 12, 20))))));
        verify(taskMapper, never()).insert(any(MaterialTask.class));
    }

    @Test
    @DisplayName("创建订单：订单号、物料与创建留痕一起落库")
    void createPersistsOrderTasksAndLog() {
        when(customerService.requireExists(CUSTOMER_ID)).thenReturn(customer());
        when(orderMapper.countByOrderNoPrefix(anyString())).thenReturn(0);
        when(orderMapper.insert(any(MaterialOrder.class))).thenAnswer(inv -> {
            ((MaterialOrder) inv.getArgument(0)).setId(99L);
            return 1;
        });
        when(taskMapper.insert(any(MaterialTask.class))).thenReturn(1);

        Map<String, Object> result = orderService.create(sales, orderReq(List.of(
                taskReq("POSTER", "主海报", 2, LocalDate.of(2026, 12, 20)),
                taskReq("STAND", "门口展架", 1, LocalDate.of(2026, 12, 25)))));

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertEquals(99L, result.get("id"));
        assertEquals("AO" + today + "0001", result.get("orderNo"));

        ArgumentCaptor<MaterialOrder> orderCaptor = ArgumentCaptor.forClass(MaterialOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        MaterialOrder savedOrder = orderCaptor.getValue();
        assertEquals(MaterialOrder.PENDING_CONFIRM, savedOrder.getStatus());
        assertEquals(CUSTOMER_ID, savedOrder.getCustomerId());
        assertEquals(SALES_ID, savedOrder.getCreatedBy());
        assertEquals("秋季新品推广物料", savedOrder.getTitle());

        ArgumentCaptor<MaterialTask> taskCaptor = ArgumentCaptor.forClass(MaterialTask.class);
        verify(taskMapper, times(2)).insert(taskCaptor.capture());
        assertEquals(List.of("主海报", "门口展架"),
                taskCaptor.getAllValues().stream().map(MaterialTask::getName).toList());
        assertTrue(taskCaptor.getAllValues().stream()
                .allMatch(t -> MaterialTask.PENDING.equals(t.getStatus())));

        OperationLog log = captureLog();
        assertEquals("ORDER", log.getBizType());
        assertEquals(99L, log.getBizId());
        assertEquals(99L, log.getOrderId());
        assertEquals("ORDER_CREATED", log.getAction());
        assertEquals(SALES_ID, log.getOperatorId());
        assertTrue(log.getDetail().contains("2 项物料任务"));
        assertEquals(sales.getRealName(), log.getOperatorName());
        assertEquals(sales.getRole(), log.getOperatorRole());
    }

    @Test
    @DisplayName("创建订单：订单号撞唯一索引后换号重试")
    void createRetriesWhenOrderNoConflicts() {
        when(customerService.requireExists(CUSTOMER_ID)).thenReturn(customer());
        when(orderMapper.countByOrderNoPrefix(anyString())).thenReturn(0);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(inv -> {
            MaterialOrder order = inv.getArgument(0);
            if (attempts.getAndIncrement() == 0) {
                throw new DuplicateKeyException("uk_order_no");
            }
            order.setId(99L);
            return 1;
        }).when(orderMapper).insert(any(MaterialOrder.class));
        when(taskMapper.insert(any(MaterialTask.class))).thenReturn(1);

        Map<String, Object> result = orderService.create(sales, orderReq(List.of(
                taskReq("POSTER", "主海报", 1, LocalDate.of(2026, 12, 20)))));

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertEquals("AO" + today + "0002", result.get("orderNo"));
        verify(orderMapper, times(2)).insert(any(MaterialOrder.class));
        assertEquals("ORDER_CREATED", captureLog().getAction());
    }

    @Test
    @DisplayName("创建订单：整体截止日期不能早于今天")
    void createRejectsPastDeadline() {
        when(customerService.requireExists(CUSTOMER_ID)).thenReturn(customer());
        RequestDtos.OrderCreateRequest req = new RequestDtos.OrderCreateRequest(
                CUSTOMER_ID, "日期校验", null, LocalDate.now().minusDays(1),
                List.of(taskReq("POSTER", "海报", 1, LocalDate.now().minusDays(1))));

        assertBusiness(BusinessException.BAD_REQUEST, () -> orderService.create(sales, req));
        verify(orderMapper, never()).insert(any(MaterialOrder.class));
    }

    @Test
    @DisplayName("创建订单：物料截止不能晚于整体截止")
    void createRejectsTaskDeadlineAfterOrderDeadline() {
        when(customerService.requireExists(CUSTOMER_ID)).thenReturn(customer());
        RequestDtos.OrderCreateRequest req = new RequestDtos.OrderCreateRequest(
                CUSTOMER_ID, "日期校验", null, LocalDate.of(2026, 12, 30),
                List.of(taskReq("POSTER", "海报", 1, LocalDate.of(2027, 1, 15))));

        assertBusiness(BusinessException.BAD_REQUEST, () -> orderService.create(sales, req));
        verify(orderMapper, never()).insert(any(MaterialOrder.class));
    }

    @Test
    @DisplayName("新增物料：物料截止不能晚于整体截止")
    void addTaskRejectsDeadlineAfterOrderDeadline() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> orderService.addTask(sales, ORDER_ID,
                        taskReq("POSTER", "加急海报", 1, LocalDate.of(2027, 1, 15))));
        verify(taskMapper, never()).insert(any(MaterialTask.class));
    }

    @Test
    @DisplayName("改整单截止：不能早于最晚物料截止")
    void deadlineRejectsBeforeLatestTaskDeadline() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(taskMapper.selectMaxDeadlineByOrderId(ORDER_ID)).thenReturn(LocalDate.of(2026, 12, 25));

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> orderService.updateDeadline(sales, ORDER_ID, LocalDate.of(2026, 12, 21)));
        verify(orderMapper, never()).updateDeadline(anyLong(), any());
        verify(logMapper, never()).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("订单列表：截止区间/逾期/已延期筛选透传")
    void listPassesDateAndOverdueFilters() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        List<Map<String, Object>> rows = List.<Map<String, Object>>of(Map.of("id", ORDER_ID));
        when(orderMapper.selectList("IN_PROGRESS", CUSTOMER_ID, "海报", DESIGNER_ID,
                from, to, true, true, SALES_ID, "SALES", 0, 10)).thenReturn(rows);
        when(orderMapper.countList("IN_PROGRESS", CUSTOMER_ID, "海报", DESIGNER_ID,
                from, to, true, true, SALES_ID, "SALES")).thenReturn(1);

        Map<String, Object> result = orderService.list(sales, "IN_PROGRESS", CUSTOMER_ID,
                "海报", DESIGNER_ID, from, to, true, true, 1, 10);

        assertSame(rows, result.get("list"));
        verify(orderMapper).selectList("IN_PROGRESS", CUSTOMER_ID, "海报", DESIGNER_ID,
                from, to, true, true, SALES_ID, "SALES", 0, 10);
        verify(orderMapper).countList("IN_PROGRESS", CUSTOMER_ID, "海报", DESIGNER_ID,
                from, to, true, true, SALES_ID, "SALES");
    }

    // ------------------------------------------------------------------
    // 列表与详情
    // ------------------------------------------------------------------

    @Test
    @DisplayName("订单列表：分页归一化并把查看者数据范围传给 Mapper")
    void listNormalizesPaginationAndPassesViewer() {
        List<Map<String, Object>> rows = List.<Map<String, Object>>of(Map.of("id", ORDER_ID));
        when(orderMapper.selectList("PENDING_CONFIRM", CUSTOMER_ID, "海报", DESIGNER_ID,
                null, null, null, null, SALES_ID, "SALES", 0, 100)).thenReturn(rows);
        when(orderMapper.countList("PENDING_CONFIRM", CUSTOMER_ID, "海报", DESIGNER_ID,
                null, null, null, null, SALES_ID, "SALES")).thenReturn(1);

        Map<String, Object> result = orderService.list(sales, "PENDING_CONFIRM", CUSTOMER_ID,
                "海报", DESIGNER_ID, null, null, null, null, 0, 999);

        assertSame(rows, result.get("list"));
        assertEquals(1, result.get("total"));
        assertEquals(1, result.get("page"));
        assertEquals(100, result.get("size"));
        verify(orderMapper).selectList("PENDING_CONFIRM", CUSTOMER_ID, "海报", DESIGNER_ID,
                null, null, null, null, SALES_ID, "SALES", 0, 100);
        verify(orderMapper).countList("PENDING_CONFIRM", CUSTOMER_ID, "海报", DESIGNER_ID,
                null, null, null, null, SALES_ID, "SALES");
    }

    @Test
    @DisplayName("订单详情：设计人员不能读与自己无关的订单")
    void detailRejectsUnrelatedDesigner() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(taskMapper.countByOrderAndAssignee(ORDER_ID, DESIGNER_ID)).thenReturn(0);

        assertBusiness(BusinessException.FORBIDDEN, () -> orderService.detail(designer, ORDER_ID));
        verify(taskMapper, never()).selectByOrderId(anyLong());
    }

    @Test
    @DisplayName("订单详情：返回客户、任务与操作历史")
    void detailReturnsCustomerTasksAndLogs() {
        Customer customer = customer();
        List<Map<String, Object>> tasks = List.<Map<String, Object>>of(Map.of("id", 1L));
        List<OperationLog> logs = List.of(new OperationLog());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(customerService.requireExists(CUSTOMER_ID)).thenReturn(customer);
        when(taskMapper.selectByOrderId(ORDER_ID)).thenReturn(tasks);
        when(logMapper.selectByOrderId(ORDER_ID)).thenReturn(logs);

        Map<String, Object> result = orderService.detail(sales, ORDER_ID);

        assertSame(customer, result.get("customer"));
        assertSame(tasks, result.get("tasks"));
        assertSame(logs, result.get("logs"));
        assertEquals("AO202611050010", result.get("orderNo"));
    }

    // ------------------------------------------------------------------
    // 确认 / 取消 / 交付
    // ------------------------------------------------------------------

    @Test
    @DisplayName("确认订单：非待确认状态返回 409")
    void confirmRejectsWrongState() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));

        assertBusiness(BusinessException.CONFLICT, () -> orderService.confirm(manager, ORDER_ID));
        verify(orderMapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("确认订单：没有物料任务不能确认")
    void confirmRejectsEmptyOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));
        when(taskMapper.selectByOrderId(ORDER_ID)).thenReturn(List.of());

        assertBusiness(BusinessException.BAD_REQUEST, () -> orderService.confirm(manager, ORDER_ID));
        verify(orderMapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("确认订单：条件更新 0 行返回 409")
    void confirmReturnsConflictWhenStatusChangedConcurrently() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));
        when(taskMapper.selectByOrderId(ORDER_ID)).thenReturn(taskRows());
        when(orderMapper.updateStatus(ORDER_ID, MaterialOrder.PENDING_CONFIRM, MaterialOrder.IN_PROGRESS))
                .thenReturn(0);

        assertBusiness(BusinessException.CONFLICT, () -> orderService.confirm(manager, ORDER_ID));
        verify(logMapper, never()).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("确认订单成功：条件更新并留痕")
    void confirmWritesLog() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));
        when(taskMapper.selectByOrderId(ORDER_ID)).thenReturn(taskRows());
        when(orderMapper.updateStatus(ORDER_ID, MaterialOrder.PENDING_CONFIRM, MaterialOrder.IN_PROGRESS))
                .thenReturn(1);

        orderService.confirm(manager, ORDER_ID);

        OperationLog log = captureLog();
        assertEquals("ORDER_CONFIRMED", log.getAction());
        assertEquals("ORDER", log.getBizType());
        assertEquals(ORDER_ID, log.getBizId());
        assertTrue(log.getDetail().contains("2026-12-30"));
        assertEquals(manager.getRealName(), log.getOperatorName());
    }

    @Test
    @DisplayName("取消订单：非待确认状态返回 409")
    void cancelRejectsWrongState() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.COMPLETED));

        assertBusiness(BusinessException.CONFLICT, () -> orderService.cancel(manager, ORDER_ID));
        verify(orderMapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("取消订单成功：条件更新并留痕")
    void cancelWritesLog() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));
        when(orderMapper.updateStatus(ORDER_ID, MaterialOrder.PENDING_CONFIRM, MaterialOrder.CANCELLED))
                .thenReturn(1);

        orderService.cancel(sales, ORDER_ID);

        assertEquals("ORDER_CANCELLED", captureLog().getAction());
    }

    @Test
    @DisplayName("交付订单：非进行中状态返回 409")
    void completeRejectsWrongState() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));

        assertBusiness(BusinessException.CONFLICT, () -> orderService.complete(manager, ORDER_ID));
        verify(taskMapper, never()).countUnconfirmedByOrderId(anyLong());
    }

    @Test
    @DisplayName("交付订单：还有未确认任务时拒绝，并提示数量")
    void completeRejectsUnconfirmedTasks() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(taskMapper.countUnconfirmedByOrderId(ORDER_ID)).thenReturn(2);

        BusinessException ex = assertBusiness(BusinessException.CONFLICT,
                () -> orderService.complete(manager, ORDER_ID));
        assertTrue(ex.getMessage().contains("2"));
        verify(orderMapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("交付订单：条件更新 0 行返回 409")
    void completeReturnsConflictWhenStatusChangedConcurrently() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(taskMapper.countUnconfirmedByOrderId(ORDER_ID)).thenReturn(0);
        when(orderMapper.updateStatus(ORDER_ID, MaterialOrder.IN_PROGRESS, MaterialOrder.COMPLETED))
                .thenReturn(0);

        assertBusiness(BusinessException.CONFLICT, () -> orderService.complete(manager, ORDER_ID));
        verify(logMapper, never()).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("交付订单成功：全部确认后写交付留痕")
    void completeWritesLog() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(taskMapper.countUnconfirmedByOrderId(ORDER_ID)).thenReturn(0);
        when(orderMapper.updateStatus(ORDER_ID, MaterialOrder.IN_PROGRESS, MaterialOrder.COMPLETED))
                .thenReturn(1);

        orderService.complete(manager, ORDER_ID);

        assertEquals("ORDER_COMPLETED", captureLog().getAction());
    }

    // ------------------------------------------------------------------
    // 改期与新增物料
    // ------------------------------------------------------------------

    @Test
    @DisplayName("改整单截止日期：订单已结束返回 409")
    void deadlineRejectsEndedOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.CANCELLED));

        assertBusiness(BusinessException.CONFLICT,
                () -> orderService.updateDeadline(sales, ORDER_ID, LocalDate.of(2027, 1, 15)));
        verify(orderMapper, never()).updateDeadline(anyLong(), any());
    }

    @Test
    @DisplayName("改整单截止日期：确认前调整也写留痕，供异常管理标记已延期")
    void deadlineBeforeOrderConfirmedWritesChangeLog() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));
        LocalDate newDate = LocalDate.of(2027, 1, 15);

        orderService.updateDeadline(sales, ORDER_ID, newDate);

        verify(orderMapper).updateDeadline(ORDER_ID, newDate);
        OperationLog log = captureLog();
        assertEquals("ORDER_DEADLINE_CHANGED", log.getAction());
        assertTrue(log.getDetail().contains("确认前调整"));
    }

    @Test
    @DisplayName("改整单截止日期：进行中订单写原值 → 新值")
    void deadlineInProgressWritesChangeLog() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        LocalDate newDate = LocalDate.of(2027, 1, 15);

        orderService.updateDeadline(sales, ORDER_ID, newDate);

        OperationLog log = captureLog();
        assertEquals("ORDER_DEADLINE_CHANGED", log.getAction());
        assertTrue(log.getDetail().contains("2026-12-30"));
        assertTrue(log.getDetail().contains("2027-01-15"));
    }

    @Test
    @DisplayName("新增物料：订单已结束返回 409")
    void addTaskRejectsEndedOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.COMPLETED));

        assertBusiness(BusinessException.CONFLICT,
                () -> orderService.addTask(sales, ORDER_ID, taskReq("POSTER", "加急海报", 1, null)));
        verify(taskMapper, never()).insert(any(MaterialTask.class));
    }

    @Test
    @DisplayName("新增物料：待确认订单只落任务，不写变更记录")
    void addTaskBeforeOrderConfirmedDoesNotLog() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));

        orderService.addTask(sales, ORDER_ID, taskReq("POSTER", "加急海报", 3, LocalDate.of(2026, 12, 25)));

        ArgumentCaptor<MaterialTask> captor = ArgumentCaptor.forClass(MaterialTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals("加急海报", captor.getValue().getName());
        assertEquals(3, captor.getValue().getQuantity());
        assertEquals(MaterialTask.PENDING, captor.getValue().getStatus());
        verify(logMapper, never()).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("新增物料：进行中订单写 TASK_ADDED 变更记录")
    void addTaskInProgressWritesChangeLog() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));

        orderService.addTask(sales, ORDER_ID, taskReq("POSTER", "加急海报", 3, LocalDate.of(2026, 12, 25)));

        OperationLog log = captureLog();
        assertEquals("TASK", log.getBizType());
        assertEquals(ORDER_ID, log.getBizId());
        assertEquals("TASK_ADDED", log.getAction());
        assertTrue(log.getDetail().contains("加急海报"));
        assertTrue(log.getDetail().contains("数量 3"));
    }

    @Test
    @DisplayName("新增物料：服务层拒绝非法物料类型")
    void addTaskRejectsInvalidType() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> orderService.addTask(sales, ORDER_ID, taskReq("BILLBOARD", "户外大牌", 1, null)));
        verify(taskMapper, never()).insert(any(MaterialTask.class));
    }

    // ------------------------------------------------------------------
    // 公共方法
    // ------------------------------------------------------------------

    @Test
    @DisplayName("订单不存在：返回 404")
    void requireExistsReturns404() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        assertBusiness(BusinessException.NOT_FOUND, () -> orderService.requireExists(ORDER_ID));
    }

    @Test
    @DisplayName("写留痕：bizId 为空时回退到 orderId，操作人信息完整")
    void writeLogFallsBackToOrderId() {
        orderService.writeLog("ORDER", null, ORDER_ID, "ORDER_CREATED", "创建订单", "详情", sales);

        OperationLog log = captureLog();
        assertEquals("ORDER", log.getBizType());
        assertEquals(ORDER_ID, log.getBizId());
        assertEquals(ORDER_ID, log.getOrderId());
        assertEquals("ORDER_CREATED", log.getAction());
        assertEquals("创建订单", log.getActionText());
        assertEquals("详情", log.getDetail());
        assertEquals(SALES_ID, log.getOperatorId());
        assertEquals(sales.getRealName(), log.getOperatorName());
        assertEquals("SALES", log.getOperatorRole());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private MaterialOrder order(String status) {
        MaterialOrder order = new MaterialOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("AO202611050010");
        order.setCustomerId(CUSTOMER_ID);
        order.setTitle("秋季新品推广物料");
        order.setDeadline(LocalDate.of(2026, 12, 30));
        order.setStatus(status);
        order.setCreatedBy(SALES_ID);
        return order;
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.setId(CUSTOMER_ID);
        customer.setName("示例客户");
        return customer;
    }

    private List<Map<String, Object>> taskRows() {
        return List.<Map<String, Object>>of(Map.of("id", 1L));
    }

    private RequestDtos.OrderCreateRequest orderReq(List<RequestDtos.TaskCreateRequest> tasks) {
        return new RequestDtos.OrderCreateRequest(CUSTOMER_ID, "秋季新品推广物料",
                "整体风格参考去年", LocalDate.of(2026, 12, 30), tasks);
    }

    private RequestDtos.TaskCreateRequest taskReq(String type, String name, int quantity, LocalDate deadline) {
        return new RequestDtos.TaskCreateRequest(type, name, "600x900mm", quantity,
                "门店张贴", "要暖色", deadline);
    }

    private OperationLog captureLog() {
        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(logMapper).insert(captor.capture());
        return captor.getValue();
    }

    private BusinessException assertBusiness(int expectedCode, Executable action) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(expectedCode, ex.getCode(), ex.getMessage());
        return ex;
    }
}
