package com.course.adorder.service;

import com.course.adorder.common.BusinessException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务状态机与权限/留痕的单元测试。
 *
 * <p>这组测试不启动 Spring、不连数据库：Mapper 与 OrderService 全部 mock，
 * 事务模板用模拟的 PlatformTransactionManager 驱动。目的是用毫秒级反馈锁住
 * 最容易出错的几条规则：状态前置条件、设计人员归属、订单结束后不可推进、
 * 上传后的状态推进与失败补偿、版本/意见并发重试。</p>
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final Long TASK_ID = 1L;
    private static final Long ORDER_ID = 10L;
    private static final Long DESIGNER_ID = 7L;
    private static final Long OTHER_DESIGNER_ID = 8L;
    private static final Long MANAGER_ID = 2L;

    @Mock
    private MaterialTaskMapper taskMapper;
    @Mock
    private MaterialOrderMapper orderMapper;
    @Mock
    private DesignVersionMapper versionMapper;
    @Mock
    private RevisionFeedbackMapper feedbackMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private OrderService orderService;
    @Mock
    private PlatformTransactionManager txManager;

    @TempDir
    Path uploadDir;

    private TaskService taskService;
    private LoginUser designer;
    private LoginUser manager;

    @BeforeEach
    void setUp() {
        designer = new LoginUser(DESIGNER_ID, "designer", "设计师小周", "DESIGNER");
        manager = new LoginUser(MANAGER_ID, "manager", "负责人老李", "MANAGER");
        taskService = new TaskService(taskMapper, orderMapper, versionMapper, feedbackMapper,
                userMapper, orderService, txManager, uploadDir.toString());
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("我的任务：按当前用户与状态查询")
    void myTasksDelegatesToMapper() {
        List<Map<String, Object>> rows = List.of(Map.of("id", TASK_ID));
        when(taskMapper.selectMyTasks(DESIGNER_ID, MaterialTask.DESIGNING)).thenReturn(rows);

        assertSame(rows, taskService.myTasks(designer, MaterialTask.DESIGNING));
    }

    @Test
    @DisplayName("任务工作台：分页归一化并把六类筛选原样传给 Mapper")
    void workbenchNormalizesPaginationAndPassesFilters() {
        List<Map<String, Object>> rows = List.<Map<String, Object>>of(Map.of("id", TASK_ID));
        when(taskMapper.selectWorkbench("PENDING", 3L, ORDER_ID, "海报", true, true, null, 0, 100))
                .thenReturn(rows);
        when(taskMapper.countWorkbench("PENDING", 3L, ORDER_ID, "海报", true, true, null)).thenReturn(1);

        Map<String, Object> result = taskService.list("PENDING", 3L, ORDER_ID, "  海报  ",
                true, true, null, 0, 999);

        assertSame(rows, result.get("list"));
        assertEquals(1, result.get("total"));
        assertEquals(1, result.get("page"));
        assertEquals(100, result.get("size"));
        verify(taskMapper).selectWorkbench("PENDING", 3L, ORDER_ID, "海报", true, true, null, 0, 100);
        verify(taskMapper).countWorkbench("PENDING", 3L, ORDER_ID, "海报", true, true, null);
    }

    @Test
    @DisplayName("任务工作台：page/size 为空时回退到 1/10")
    void workbenchUsesDefaultPagination() {
        when(taskMapper.selectWorkbench(null, null, null, null, null, null, null, 0, 10))
                .thenReturn(List.of());
        when(taskMapper.countWorkbench(null, null, null, null, null, null, null)).thenReturn(0);

        Map<String, Object> result = taskService.list(null, null, null, "   ", null, null, null, null, null);

        assertEquals(1, result.get("page"));
        assertEquals(10, result.get("size"));
        assertEquals(0, result.get("total"));
        verify(taskMapper).selectWorkbench(null, null, null, null, null, null, null, 0, 10);
    }

    @Test
    @DisplayName("任务详情：设计人员不能查看分派给别人的任务")
    void detailRejectsOtherDesignersTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING_CONFIRM, OTHER_DESIGNER_ID));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));

        assertBusiness(BusinessException.FORBIDDEN, () -> taskService.detail(designer, TASK_ID));
        verify(versionMapper, never()).selectByTaskId(anyLong());
    }

    @Test
    @DisplayName("任务详情：包含订单上下文、版本与意见列表")
    void detailReturnsOrderContext() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(versionMapper.selectByTaskId(TASK_ID)).thenReturn(List.of());
        when(feedbackMapper.selectByTaskId(TASK_ID)).thenReturn(List.of());

        Map<String, Object> detail = taskService.detail(designer, TASK_ID);

        assertEquals("AO202611050001", detail.get("orderNo"));
        assertEquals("主视觉海报", detail.get("name"));
        assertEquals(MaterialTask.DESIGNING, detail.get("status"));
        assertTrue(((List<?>) detail.get("versions")).isEmpty());
        assertTrue(((List<?>) detail.get("feedbacks")).isEmpty());
    }

    // ------------------------------------------------------------------
    // 分派
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分派：非待分派状态不可分派")
    void assignRejectsNonPendingTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));

        assertBusiness(BusinessException.CONFLICT,
                () -> taskService.assign(manager, TASK_ID, DESIGNER_ID));
        verify(taskMapper, never()).updateAssignee(anyLong(), anyLong());
        verify(orderService, never()).writeLog(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("分派：订单已结束后任务不可再推进")
    void assignRejectsEndedOrder() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.COMPLETED));

        assertBusiness(BusinessException.CONFLICT,
                () -> taskService.assign(manager, TASK_ID, DESIGNER_ID));
        verify(userMapper, never()).selectById(anyLong());
        verify(taskMapper, never()).updateAssignee(anyLong(), anyLong());
    }

    @Test
    @DisplayName("分派：只能分派给启用状态的设计制作人员")
    void assignRejectsNonDesignerAssignee() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(userMapper.selectById(9L)).thenReturn(user(9L, "SALES", 1));

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> taskService.assign(manager, TASK_ID, 9L));
        verify(taskMapper, never()).updateAssignee(anyLong(), anyLong());
    }

    @Test
    @DisplayName("分派：并发下条件更新失败返回 409")
    void assignReturnsConflictWhenStatusChangedConcurrently() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(userMapper.selectById(DESIGNER_ID)).thenReturn(user(DESIGNER_ID, "DESIGNER", 1));
        when(taskMapper.updateAssignee(TASK_ID, DESIGNER_ID)).thenReturn(0);

        assertBusiness(BusinessException.CONFLICT,
                () -> taskService.assign(manager, TASK_ID, DESIGNER_ID));
        verify(orderService, never()).writeLog(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("分派成功：写入条件更新并留痕")
    void assignWritesLogOnSuccess() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(userMapper.selectById(DESIGNER_ID)).thenReturn(user(DESIGNER_ID, "DESIGNER", 1));
        when(taskMapper.updateAssignee(TASK_ID, DESIGNER_ID)).thenReturn(1);

        taskService.assign(manager, TASK_ID, DESIGNER_ID);

        verify(taskMapper).updateAssignee(TASK_ID, DESIGNER_ID);
        verify(orderService).writeLog(eq("TASK"), eq(TASK_ID), eq(ORDER_ID),
                eq("TASK_ASSIGNED"), eq("分派任务"),
                argThat(detail -> detail.contains("主视觉海报") && detail.contains("设计师小周")),
                eq(manager));
    }

    // ------------------------------------------------------------------
    // 开始设计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("开始设计：不能操作分派给别人的任务")
    void startRejectsOtherDesignersTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING, OTHER_DESIGNER_ID));

        assertBusiness(BusinessException.FORBIDDEN, () -> taskService.start(designer, TASK_ID));
        verify(taskMapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("开始设计：订单已结束后不可推进")
    void startRejectsEndedOrder() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.CANCELLED));

        assertBusiness(BusinessException.CONFLICT, () -> taskService.start(designer, TASK_ID));
        verify(taskMapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("开始设计：仅待分派状态可开始")
    void startRejectsNonPendingTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));

        assertBusiness(BusinessException.CONFLICT, () -> taskService.start(designer, TASK_ID));
        verify(taskMapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("开始设计：并发下条件更新失败返回 409")
    void startReturnsConflictWhenStatusChangedConcurrently() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(taskMapper.updateStatus(TASK_ID, MaterialTask.PENDING, MaterialTask.DESIGNING)).thenReturn(0);

        assertBusiness(BusinessException.CONFLICT, () -> taskService.start(designer, TASK_ID));
        verify(orderService, never()).writeLog(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("开始设计成功：条件更新并留痕")
    void startWritesLogOnSuccess() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(taskMapper.updateStatus(TASK_ID, MaterialTask.PENDING, MaterialTask.DESIGNING)).thenReturn(1);

        taskService.start(designer, TASK_ID);

        verify(orderService).writeLog(eq("TASK"), eq(TASK_ID), eq(ORDER_ID),
                eq("TASK_STARTED"), eq("开始设计"), contains("主视觉海报"), eq(designer));
    }

    // ------------------------------------------------------------------
    // 上传设计版本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("上传版本：只有设计中/修改中可上传")
    void uploadRejectsNonUploadableStatus() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING_CONFIRM));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));

        assertBusiness(BusinessException.CONFLICT,
                () -> taskService.uploadVersion(designer, TASK_ID, null, "备注"));
        verify(versionMapper, never()).insert(any(DesignVersion.class));
    }

    @Test
    @DisplayName("上传版本：空文件直接拒绝")
    void uploadRejectsEmptyFile() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        MockMultipartFile empty = new MockMultipartFile("file", "poster.pdf",
                "application/pdf", new byte[0]);

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> taskService.uploadVersion(designer, TASK_ID, empty, "空文件"));
        verify(versionMapper, never()).insert(any(DesignVersion.class));
    }

    @Test
    @DisplayName("上传版本：拒绝不在白名单内的扩展名")
    void uploadRejectsUnsupportedExtension() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        MockMultipartFile exe = new MockMultipartFile("file", "virus.exe",
                "application/octet-stream", "x".getBytes());

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> taskService.uploadVersion(designer, TASK_ID, exe, "坏文件"));
        verify(versionMapper, never()).insert(any(DesignVersion.class));
    }

    @Test
    @DisplayName("上传成功：版本追加、状态推进到待客户确认并留痕")
    void uploadInsertsVersionAndAdvancesStatus() throws IOException {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(versionMapper.selectMaxVersionNo(TASK_ID)).thenReturn(2);
        when(taskMapper.updateStatus(TASK_ID, MaterialTask.DESIGNING, MaterialTask.PENDING_CONFIRM)).thenReturn(1);
        MockMultipartFile file = new MockMultipartFile("file", "poster.pdf",
                "application/pdf", "pdf".getBytes());

        Map<String, Object> result = taskService.uploadVersion(designer, TASK_ID, file, "初稿");

        assertEquals(3, result.get("versionNo"));
        assertEquals(MaterialTask.PENDING_CONFIRM, result.get("status"));

        ArgumentCaptor<DesignVersion> captor = ArgumentCaptor.forClass(DesignVersion.class);
        verify(versionMapper).insert(captor.capture());
        DesignVersion saved = captor.getValue();
        assertEquals(TASK_ID, saved.getTaskId());
        assertEquals(3, saved.getVersionNo());
        assertEquals("poster.pdf", saved.getFileName());
        assertEquals(3L, saved.getFileSize().longValue());
        assertEquals("初稿", saved.getNote());
        assertEquals(DESIGNER_ID, saved.getUploadedBy());
        assertTrue(saved.getFilePath().matches("\\d{6}/[0-9a-f-]+\\.pdf"), saved.getFilePath());
        assertTrue(Files.exists(uploadDir.resolve(saved.getFilePath())), "事务成功后文件应保留");

        verify(orderService).writeLog(eq("TASK"), eq(TASK_ID), eq(ORDER_ID),
                eq("VERSION_UPLOADED"), eq("上传设计版本"),
                argThat(detail -> detail.contains("第 3 版") && detail.contains("poster.pdf")),
                eq(designer));
    }

    @Test
    @DisplayName("上传失败：事务回滚后补偿删除已落盘文件")
    void uploadDeletesStoredFileWhenTransactionFails() throws IOException {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(versionMapper.selectMaxVersionNo(TASK_ID)).thenReturn(null);
        when(taskMapper.updateStatus(TASK_ID, MaterialTask.DESIGNING, MaterialTask.PENDING_CONFIRM)).thenReturn(0);
        MockMultipartFile file = new MockMultipartFile("file", "poster.pdf",
                "application/pdf", "pdf".getBytes());

        assertBusiness(BusinessException.CONFLICT,
                () -> taskService.uploadVersion(designer, TASK_ID, file, "会失败"));

        try (Stream<Path> files = Files.walk(uploadDir)) {
            assertEquals(0, files.filter(Files::isRegularFile).count(), "事务失败后不应留下孤儿文件");
        }
        verify(orderService, never()).writeLog(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("上传版本：版本号撞唯一索引时换号重试")
    void uploadRetriesWhenVersionNoConflicts() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(versionMapper.selectMaxVersionNo(TASK_ID)).thenReturn(1, 2);
        when(versionMapper.insert(any(DesignVersion.class)))
                .thenThrow(new DuplicateKeyException("uk_task_version"))
                .thenReturn(1);
        when(taskMapper.updateStatus(TASK_ID, MaterialTask.DESIGNING, MaterialTask.PENDING_CONFIRM)).thenReturn(1);
        MockMultipartFile file = new MockMultipartFile("file", "poster.pdf",
                "application/pdf", "pdf".getBytes());

        Map<String, Object> result = taskService.uploadVersion(designer, TASK_ID, file, "重试");

        assertEquals(3, result.get("versionNo"));
        ArgumentCaptor<DesignVersion> captor = ArgumentCaptor.forClass(DesignVersion.class);
        verify(versionMapper, times(2)).insert(captor.capture());
        assertEquals(2, captor.getAllValues().get(0).getVersionNo());
        assertEquals(3, captor.getAllValues().get(1).getVersionNo());
    }

    // ------------------------------------------------------------------
    // 记录客户意见
    // ------------------------------------------------------------------

    @Test
    @DisplayName("记录意见：仅待客户确认状态可记录")
    void feedbackRejectsNonPendingConfirmTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));

        assertBusiness(BusinessException.CONFLICT,
                () -> taskService.recordFeedback(manager, TASK_ID, "改一下"));
        verify(feedbackMapper, never()).insert(any(RevisionFeedback.class));
    }

    @Test
    @DisplayName("记录意见：订单已结束后不可再记录")
    void feedbackRejectsEndedOrder() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING_CONFIRM));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.COMPLETED));

        assertBusiness(BusinessException.CONFLICT,
                () -> taskService.recordFeedback(manager, TASK_ID, "改一下"));
        verify(feedbackMapper, never()).insert(any(RevisionFeedback.class));
    }

    @Test
    @DisplayName("记录意见成功：轮次递增、任务退回修改中并留痕")
    void feedbackInsertsRoundAndMovesToRevision() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING_CONFIRM));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(feedbackMapper.selectMaxRoundNo(TASK_ID)).thenReturn(1);
        when(taskMapper.updateStatus(TASK_ID, MaterialTask.PENDING_CONFIRM, MaterialTask.REVISION)).thenReturn(1);
        String content = "客户希望把主标题放大，并把底色改成品牌蓝，另外加一行活动时间与门店地址，整体风格再简洁一些，谢谢，辛苦了";

        taskService.recordFeedback(manager, TASK_ID, content);

        ArgumentCaptor<RevisionFeedback> captor = ArgumentCaptor.forClass(RevisionFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        RevisionFeedback saved = captor.getValue();
        assertEquals(2, saved.getRoundNo());
        assertEquals(content, saved.getContent());
        assertEquals(MANAGER_ID, saved.getRecordedBy());

        verify(orderService).writeLog(eq("TASK"), eq(TASK_ID), eq(ORDER_ID),
                eq("FEEDBACK_RECORDED"), eq("记录客户修改意见"),
                argThat(detail -> detail.contains("第 2 轮") && detail.endsWith("…")),
                eq(manager));
    }

    @Test
    @DisplayName("记录意见：轮次撞唯一索引时换轮重试")
    void feedbackRetriesWhenRoundConflicts() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING_CONFIRM));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(feedbackMapper.selectMaxRoundNo(TASK_ID)).thenReturn(1, 2);
        when(feedbackMapper.insert(any(RevisionFeedback.class)))
                .thenThrow(new DuplicateKeyException("uk_task_round"))
                .thenReturn(1);
        when(taskMapper.updateStatus(TASK_ID, MaterialTask.PENDING_CONFIRM, MaterialTask.REVISION)).thenReturn(1);

        taskService.recordFeedback(manager, TASK_ID, "第一轮意见");

        ArgumentCaptor<RevisionFeedback> captor = ArgumentCaptor.forClass(RevisionFeedback.class);
        verify(feedbackMapper, times(2)).insert(captor.capture());
        assertEquals(3, captor.getAllValues().get(1).getRoundNo());
    }

    // ------------------------------------------------------------------
    // 客户确认
    // ------------------------------------------------------------------

    @Test
    @DisplayName("客户确认：仅待客户确认状态可确认")
    void confirmRejectsNonPendingConfirmTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));

        assertBusiness(BusinessException.CONFLICT, () -> taskService.confirmTask(manager, TASK_ID));
        verify(taskMapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("客户确认：订单已结束后不可确认")
    void confirmRejectsEndedOrder() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING_CONFIRM));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.COMPLETED));

        assertBusiness(BusinessException.CONFLICT, () -> taskService.confirmTask(manager, TASK_ID));
        verify(taskMapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("客户确认成功：条件更新并留痕")
    void confirmWritesLogOnSuccess() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING_CONFIRM));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        when(taskMapper.updateStatus(TASK_ID, MaterialTask.PENDING_CONFIRM, MaterialTask.CONFIRMED)).thenReturn(1);

        taskService.confirmTask(manager, TASK_ID);

        verify(orderService).writeLog(eq("TASK"), eq(TASK_ID), eq(ORDER_ID),
                eq("TASK_CONFIRMED"), eq("客户确认"), contains("客户确认当前版本"), eq(manager));
    }

    // ------------------------------------------------------------------
    // 截止日期变更
    // ------------------------------------------------------------------

    @Test
    @DisplayName("改截止日期：已确认完成的任务不可修改")
    void deadlineRejectsConfirmedTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.CONFIRMED));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));

        assertBusiness(BusinessException.CONFLICT,
                () -> taskService.updateDeadline(manager, TASK_ID, LocalDate.of(2026, 11, 20)));
        verify(taskMapper, never()).updateDeadline(anyLong(), any());
    }

    @Test
    @DisplayName("改截止日期：订单已结束后不可修改")
    void deadlineRejectsEndedOrder() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.CANCELLED));

        assertBusiness(BusinessException.CONFLICT,
                () -> taskService.updateDeadline(manager, TASK_ID, LocalDate.of(2026, 11, 20)));
        verify(taskMapper, never()).updateDeadline(anyLong(), any());
    }

    @Test
    @DisplayName("改截止日期：进行中订单的变更必须留痕")
    void deadlineWritesChangeLogForInProgressOrder() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));
        LocalDate newDeadline = LocalDate.of(2026, 11, 20);

        taskService.updateDeadline(manager, TASK_ID, newDeadline);

        verify(taskMapper).updateDeadline(TASK_ID, newDeadline);
        verify(orderService).writeLog(eq("TASK"), eq(TASK_ID), eq(ORDER_ID),
                eq("TASK_DEADLINE_CHANGED"), eq("变更物料截止日期"),
                argThat(detail -> detail.contains("2026-11-10") && detail.contains("2026-11-20")),
                eq(manager));
    }

    @Test
    @DisplayName("改截止日期：待确认订单的确认前调整也写留痕")
    void deadlineBeforeOrderConfirmedWritesChangeLog() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.PENDING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.PENDING_CONFIRM));
        LocalDate newDeadline = LocalDate.of(2026, 11, 20);

        taskService.updateDeadline(manager, TASK_ID, newDeadline);

        verify(taskMapper).updateDeadline(TASK_ID, newDeadline);
        verify(orderService).writeLog(eq("TASK"), eq(TASK_ID), eq(ORDER_ID),
                eq("TASK_DEADLINE_CHANGED"), eq("变更物料截止日期"),
                argThat(detail -> detail.contains("确认前调整")),
                eq(manager));
    }

    @Test
    @DisplayName("改截止日期：不能早于今天，也不能晚于整单截止")
    void deadlineRejectsInvalidBoundaries() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING));
        when(orderService.requireExists(ORDER_ID)).thenReturn(order(MaterialOrder.IN_PROGRESS));

        assertBusiness(BusinessException.BAD_REQUEST,
                () -> taskService.updateDeadline(manager, TASK_ID, LocalDate.now().minusDays(1)));
        assertBusiness(BusinessException.BAD_REQUEST,
                () -> taskService.updateDeadline(manager, TASK_ID, LocalDate.of(2027, 1, 15)));
        verify(taskMapper, never()).updateDeadline(anyLong(), any());
    }

    // ------------------------------------------------------------------
    // 文件读取安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("文件读取：拒绝路径穿越")
    void resolveRejectsPathTraversal() {
        assertBusiness(BusinessException.BAD_REQUEST, () -> taskService.resolve(designer, "../secret.pdf"));
    }

    @Test
    @DisplayName("文件读取：设计人员不能下载别人任务的设计稿")
    void resolveRejectsOtherDesignersFile() throws IOException {
        Path file = uploadDir.resolve("poster.pdf");
        Files.writeString(file, "pdf");
        DesignVersion version = new DesignVersion();
        version.setTaskId(TASK_ID);
        version.setFilePath("poster.pdf");
        when(versionMapper.selectByFilePath("poster.pdf")).thenReturn(version);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(MaterialTask.DESIGNING, OTHER_DESIGNER_ID));

        assertBusiness(BusinessException.FORBIDDEN, () -> taskService.resolve(designer, "poster.pdf"));
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private MaterialTask task(String status) {
        return task(status, DESIGNER_ID);
    }

    private MaterialTask task(String status, Long assigneeId) {
        MaterialTask task = new MaterialTask();
        task.setId(TASK_ID);
        task.setOrderId(ORDER_ID);
        task.setName("主视觉海报");
        task.setStatus(status);
        task.setAssigneeId(assigneeId);
        task.setDeadline(LocalDate.of(2026, 11, 10));
        return task;
    }

    private MaterialOrder order(String status) {
        MaterialOrder order = new MaterialOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("AO202611050001");
        order.setTitle("秋季新品推广物料");
        order.setDeadline(LocalDate.of(2026, 12, 30));
        order.setStatus(status);
        return order;
    }

    private User user(Long id, String role, int status) {
        User user = new User();
        user.setId(id);
        user.setUsername(role.toLowerCase() + id);
        user.setRealName("设计师小周");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private BusinessException assertBusiness(int expectedCode, Executable action) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(expectedCode, ex.getCode(), ex.getMessage());
        return ex;
    }
}
