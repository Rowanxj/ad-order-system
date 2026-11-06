package com.course.adorder.service;

import com.course.adorder.mapper.NotificationMapper;
import com.course.adorder.security.LoginUser;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 顶部提醒：按角色算出"有多少事在等我、分别是哪些"。
 *
 * <p>职责边界：只做汇总与跳转信息，不改变任何业务状态，也不写 operation_log。
 * 计数口径就是各角色"能操作、且还没做完"的对象，和权限矩阵一一对应：
 * MANAGE 看订单层面，SALES 看待客户确认的物料，DESIGNER 只看自己的任务。</p>
 */
@Service
public class NotificationService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int RECENT_LIMIT = 5;

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    public Map<String, Object> summary(LoginUser user) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> recent = new ArrayList<>();

        switch (user.getRole()) {
            case "MANAGER" -> buildManager(items, recent);
            case "SALES" -> buildSales(items, recent);
            default -> buildDesigner(user, items, recent);
        }

        int total = 0;
        for (Map<String, Object> item : items) {
            total += (Integer) item.get("count");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverTime", LocalDateTime.now().format(TIME_FMT));
        data.put("serverTimeIso", LocalDateTime.now().toString());
        data.put("role", user.getRole());
        data.put("total", total);
        data.put("items", items);
        data.put("recent", recent);
        return data;
    }

    private void buildManager(List<Map<String, Object>> items, List<Map<String, Object>> recent) {
        addItem(items, "待确认订单", notificationMapper.countOrdersByStatus("PENDING_CONFIRM"),
                "/orders?status=PENDING_CONFIRM", "订单已登记，等你确认物料清单与截止时间");
        addItem(items, "待分派物料", notificationMapper.countUnassignedTasks(),
                "/orders", "物料还没指定设计人员");
        addItem(items, "可交付订单", notificationMapper.countDeliverableOrders(),
                "/orders?status=IN_PROGRESS", "全部物料已确认，等你交付");

        for (Map<String, Object> row : notificationMapper.selectPendingConfirmOrders(RECENT_LIMIT)) {
            addRecent(recent, text(row.get("orderNo"), row.get("title")),
                    "/orders/" + row.get("id"), row.get("createdAt"), "待确认");
        }
    }

    private void buildSales(List<Map<String, Object>> items, List<Map<String, Object>> recent) {
        addItem(items, "待客户确认物料", notificationMapper.countTasksByStatus("PENDING_CONFIRM"),
                "/orders", "设计师已交稿，等你记录客户确认或修改意见");

        for (Map<String, Object> row : notificationMapper.selectTasksByStatus("PENDING_CONFIRM", RECENT_LIMIT)) {
            addRecent(recent, text(row.get("name"), row.get("orderNo")),
                    "/tasks/" + row.get("id"), row.get("updatedAt"), "待客户确认");
        }
    }

    private void buildDesigner(LoginUser user, List<Map<String, Object>> items, List<Map<String, Object>> recent) {
        addItem(items, "待开始任务", notificationMapper.countMyTasksByStatus(user.getId(), "PENDING"),
                "/my-tasks", "已分派给你，等你点「开始设计」");
        addItem(items, "修改中（有新意见）", notificationMapper.countMyTasksByStatus(user.getId(), "REVISION"),
                "/my-tasks", "客户提了修改意见，等你出新版本");

        for (Map<String, Object> row : notificationMapper.selectMyActiveTasks(user.getId(), RECENT_LIMIT)) {
            String status = String.valueOf(row.get("status"));
            String label = switch (status) {
                case "REVISION" -> "修改中";
                case "PENDING" -> "待开始";
                case "DESIGNING" -> "设计中";
                default -> "待客户确认";
            };
            addRecent(recent, text(row.get("name"), row.get("orderNo")),
                    "/tasks/" + row.get("id"), row.get("updatedAt"), label);
        }
    }

    private void addItem(List<Map<String, Object>> items, String label, int count, String path, String hint) {
        if (count <= 0) {
            return;   // 为 0 的不显示，避免"有 3 条提醒但都是 0"
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("count", count);
        item.put("path", path);
        item.put("hint", hint);
        items.add(item);
    }

    private void addRecent(List<Map<String, Object>> recent, String text, String path, Object at, String label) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("text", text);
        row.put("path", path);
        row.put("at", at);
        row.put("label", label);
        recent.add(row);
    }

    private String text(Object main, Object sub) {
        String m = main == null ? "未命名" : String.valueOf(main);
        String s = sub == null ? "" : String.valueOf(sub);
        return s.isEmpty() ? m : m + " · " + s;
    }

    /**
     * 订单列表顶部的大盘统计：一眼看到"卡在哪个环节的单子最多"。
     *
     * <p>与 {@link #summary} 的区别：summary 只给"与我有关且需要我处理的"（铃铛用），
     * stats 给的是全局分布（列表页用），设计人员看到的仍是与本人相关的任务数，
     * 避免他看到一个自己无权处理的大盘。</p>
     */
    public Map<String, Object> stats(LoginUser user) {
        boolean designer = "DESIGNER".equals(user.getRole());

        int pendingTasks = designer
                ? notificationMapper.countMyTasksByStatus(user.getId(), "PENDING")
                : notificationMapper.countTasksByStatus("PENDING");
        int designingTasks = designer
                ? notificationMapper.countMyTasksByStatus(user.getId(), "DESIGNING")
                : notificationMapper.countTasksByStatus("DESIGNING");
        int pendingConfirmTasks = designer
                ? notificationMapper.countMyTasksByStatus(user.getId(), "PENDING_CONFIRM")
                : notificationMapper.countTasksByStatus("PENDING_CONFIRM");

        int pendingConfirmOrders = designer ? 0 : notificationMapper.countOrdersByStatus("PENDING_CONFIRM");
        int deliverableOrders = designer ? 0 : notificationMapper.countDeliverableOrders();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", designer ? "MINE" : "ALL");
        data.put("orderPendingConfirm", pendingConfirmOrders);
        data.put("orderDeliverable", deliverableOrders);
        data.put("taskPending", pendingTasks);
        data.put("taskDesigning", designingTasks);
        data.put("taskPendingConfirm", pendingConfirmTasks);
        return data;
    }
}
