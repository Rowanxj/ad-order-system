@echo off
chcp 936 >nul
setlocal enabledelayedexpansion
title 宣传物料订单管理系统 - 一键启动
cls
echo ============================================================
echo   宣传物料订单管理系统  一键启动
echo   前置要求：JDK 17+ / Node.js 18+ / MySQL 8（并已启动）
echo ============================================================
echo.

REM ---------- 1. 环境检查 ----------
where java >nul 2>nul
if errorlevel 1 (
  echo [X] 没有检测到 java。请先安装 JDK 17 或更高版本，并把 bin 目录加入 PATH。
  echo     下载：https://adoptium.net/
  pause & exit /b 1
)
where node >nul 2>nul
if errorlevel 1 (
  echo [X] 没有检测到 node。请先安装 Node.js 18 或更高版本。
  echo     下载：https://nodejs.org/
  pause & exit /b 1
)
echo [OK] java / node 已就绪

REM ---------- 2. 数据库口令 ----------
if "%DB_PASSWORD%"=="" (
  echo.
  echo 请输入本机 MySQL 的 root 密码。
  echo 直接回车则读取项目根目录 .env 里的 DB_PASSWORD。
  set /p DB_PASSWORD=密码:
)
set "DB_ARGS="
if not "%DB_PASSWORD%"=="" set "DB_ARGS=--spring.datasource.password=%DB_PASSWORD%"

REM ---------- 3. 数据库初始化（仅首次） ----------
echo.
echo [1/4] 检查数据库...
mysql -uroot -p%DB_PASSWORD% -N -e "SELECT COUNT(*) FROM ad_order_system.material_order" >nul 2>nul
if errorlevel 1 (
  echo       首次运行：正在建库并导入演示数据...
  mysql -uroot -p%DB_PASSWORD% --default-character-set=utf8mb4 < "%~dp0sql\schema.sql"
  mysql -uroot -p%DB_PASSWORD% --default-character-set=utf8mb4 < "%~dp0sql\data.sql"
  mysql -uroot -p%DB_PASSWORD% --default-character-set=utf8mb4 < "%~dp0sql\demo-more.sql"
  echo       导入完成。
) else (
  echo       数据库已存在，跳过导入（如需重置，见 使用说明.md）。
)

REM ---------- 4. 前端依赖（仅缺 node_modules 时） ----------
if not exist "%~dp0frontend\node_modules" (
  echo [2/4] 首次运行：安装前端依赖（需要联网，约 1-3 分钟）...
  pushd "%~dp0frontend"
  call npm install
  popd
) else (
  echo [2/4] 前端依赖已就绪，跳过安装。
)

REM ---------- 5. 后端 jar（仅缺 target 时构建） ----------
if not exist "%~dp0backend\target\ad-order-backend-1.0.0.jar" (
  where mvn >nul 2>nul
  if errorlevel 1 (
    echo [X] 没有检测到 mvn。请先安装 Maven 3.8+ 并加入 PATH，或手动在 backend 下执行：
    echo     mvn -s ..\maven-settings.xml clean package -DskipTests
    pause & exit /b 1
  )
  echo [3/4] 未找到后端 jar，正在构建（首次需要下载依赖，约 1-3 分钟）...
  pushd "%~dp0backend"
  call mvn -s ..\maven-settings.xml clean package -DskipTests
  if errorlevel 1 (
    echo [X] 后端构建失败，请把上面的错误发给维护者。
    popd
    pause & exit /b 1
  )
  popd
) else (
  echo [3/4] 后端 jar 已就绪，跳过构建。
)

REM ---------- 6. 启动两端 ----------
echo [4/4] 启动后端（新窗口，端口 8080）...
start "ad-order 后端" cmd /k "cd /d %~dp0backend && java -jar target\ad-order-backend-1.0.0.jar %DB_ARGS%"

echo       启动前端（新窗口，端口 5173）...
start "ad-order 前端" cmd /k "cd /d %~dp0frontend && npm run dev"

echo.
echo 等待服务启动（10 秒）...
timeout /t 10 >nul
start http://localhost:5173

echo.
echo ============================================================
echo   启动完成，浏览器应已打开 http://localhost:5173
echo   演示账号（密码均为 123456）：
echo     sales      客户经理
echo     manager    业务负责人
echo     designer1  设计制作人员（李文静）
echo     designer2  设计制作人员（陈涛）
echo.
echo   关闭：直接关掉"ad-order 后端"和"ad-order 前端"两个黑窗口
echo ============================================================
pause
