/*
 * 提醒接口已合并到 ApiController（GET /api/notifications/summary）。
 *
 * 原因：项目的契约门禁（check-contract.ps1 的 G3）以 ApiController 为唯一的路由来源
 * 做「文档 ↔ 代码 ↔ 前端 api 层」三方对账。拆成独立 controller 会让该接口脱离门禁视野，
 * 文档登记了却查不到实现。
 *
 * 结论：新增接口一律加在 ApiController；确实需要拆分时，必须同步升级 G3 支持多 controller
 * 扫描，否则接口会变成"门禁看不见的暗接口"。
 */
final class NotificationControllerNote {
    private NotificationControllerNote() {
    }
}
