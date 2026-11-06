# 订单库备份：mysqldump 到 backups/，文件名带时间戳
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1 -Db ad_order_check
#
# 约定：
#   - 备份放 backups/（已 gitignore），命名 app-<库名>-<yyyyMMdd-HHmmss>.sql
#   - 只保留最近 20 份，避免越攒越多
#   - 口令优先取环境变量 DB_PASSWORD，其次用脚本参数

param(
    [string]$Db = 'ad_order_system',
    [string]$User = 'root',
    [string]$Password = $env:DB_PASSWORD,
    [int]$Keep = 20
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backupDir = Join-Path $root 'backups'
if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Path $backupDir | Out-Null }

if (-not $Password) {
    Write-Host "没有拿到数据库口令：请用 -Password 传入，或先设置环境变量 DB_PASSWORD。" -ForegroundColor Red
    exit 1
}
$env:MYSQL_PWD = $Password

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$file = Join-Path $backupDir ("app-$Db-$stamp.sql")

Write-Host "备份 $Db -> $file"
$uArg = "-u" + $User
& mysqldump $uArg --single-transaction --routines --triggers --default-character-set=utf8mb4 $Db > $file

if ($LASTEXITCODE -ne 0) {
    Write-Host "备份失败（mysqldump 返回 $LASTEXITCODE）" -ForegroundColor Red
    Remove-Item $file -Force -ErrorAction SilentlyContinue
    exit 1
}

$size = [Math]::Round((Get-Item $file).Length / 1KB, 1)
Write-Host "完成：$size KB" -ForegroundColor Green

# 只保留最近 N 份
$old = Get-ChildItem $backupDir -Filter "app-$Db-*.sql" | Sort-Object LastWriteTime -Descending | Select-Object -Skip $Keep
if ($old) {
    $old | Remove-Item -Force
    Write-Host ("清理旧备份 " + $old.Count + " 份")
}
