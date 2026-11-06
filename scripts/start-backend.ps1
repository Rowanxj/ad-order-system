# 启动后端（带文件日志）。不改 jar、不改配置，全部用启动参数覆盖。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\start-backend.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\start-backend.ps1 -Port 18080 -LogLevel debug
#
# 为什么这么做：
#   - 日志落到 logs/ 而不是只在控制台：出问题能回查，控制台一关就没了
#   - 日志文件自动轮转：单文件 20MB，最多留 7 天，避免撑满磁盘
#   - 敏感信息（密码 / JWT）本来就不打印，这里再加一道：日志级别默认 info

param(
    [int]$Port = 8080,
    [string]$LogLevel = 'info',
    [string]$UploadDir = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'backend\target\ad-order-backend-1.0.0.jar'
$logDir = Join-Path $root 'logs'

if (-not (Test-Path $jar)) {
    Write-Host "找不到 jar：$jar" -ForegroundColor Red
    Write-Host "先在 backend 目录执行：mvn -s ..\maven-settings.xml package -DskipTests"
    exit 1
}
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

$args = @(
    '-jar', $jar,
    "--server.port=$Port",
    "--logging.file.name=$logDir\app.log",
    "--logging.level.root=warn",
    "--logging.level.com.course.adorder=$LogLevel",
    '--logging.logback.rollingpolicy.max-file-size=20MB',
    '--logging.logback.rollingpolicy.max-history=7'
)
if ($UploadDir) { $args += "--app.upload-dir=$UploadDir" }

Write-Host "启动后端：端口 $Port，日志 $logDir\app.log，业务日志级别 $LogLevel" -ForegroundColor Cyan
& java @args
