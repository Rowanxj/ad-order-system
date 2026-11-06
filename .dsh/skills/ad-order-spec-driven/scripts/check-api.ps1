#requires -Version 5.1
<#
.SYNOPSIS
  广告物料订单系统 · 契约驱动验收。按 acceptance.json 顺序发真实 HTTP 请求并断言业务 code。

.DESCRIPTION
  用例全部写在 docs-framework/contracts/acceptance.json，脚本只负责执行与报告。
  想加场景就改 JSON，不要改脚本；这是「契约即代码」的一半。

  依赖：后端已在运行、MySQL 已导入 sql/schema.sql 与 sql/data.sql、curl.exe 可用。

.PARAMETER Root
  项目根目录，默认当前目录。

.PARAMETER BaseUrl
  覆盖契约里的 baseUrl，例如 http://localhost:18080/api。

.PARAMETER List
  只打印用例清单，不发请求。用于评审契约。

.PARAMETER Markdown
  追加一张可粘贴到 docs/test-record.md 的结果表。

.PARAMETER JsonReport
  写出 JSON 报告。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-api.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-api.ps1 -List
#>
[CmdletBinding()]
param(
    [string]$Root = (Get-Location).Path,
    [string]$BaseUrl = '',
    [string]$Contract = '',
    [switch]$List,
    [switch]$Markdown,
    [string]$JsonReport = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Read-Text([string]$path) {
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

$Root = (Resolve-Path -LiteralPath $Root).Path
if ([string]::IsNullOrWhiteSpace($Contract)) {
    $Contract = Join-Path $Root 'docs-framework/contracts/acceptance.json'
}
if (-not (Test-Path -LiteralPath $Contract)) {
    Write-Output ('[环境] 未找到验收契约：' + $Contract)
    Write-Output '        先按 references/02-contracts.md 采纳契约，或从 references/templates/contracts/ 复制。'
    exit 2
}

try {
    $spec = (Read-Text $Contract) | ConvertFrom-Json
}
catch {
    Write-Output ('[环境] 验收契约 JSON 解析失败：' + $_.Exception.Message)
    exit 2
}

$base = if ($BaseUrl) { $BaseUrl.TrimEnd('/') } else { ([string]$spec.baseUrl).TrimEnd('/') }
$cases = @($spec.cases)

if ($List) {
    Write-Output ''
    Write-Output ('验收契约：' + $Contract)
    Write-Output ('目标地址：' + $base)
    Write-Output ('用例数：' + $cases.Count)
    Write-Output ''
    Write-Output ('{0,-5} {1,-10} {2,-6} {3,-34} {4}' -f 'ID', '账号', '期望', '接口', '名称')
    foreach ($c in $cases) {
        $who = if ($c.as) { $c.as } else { '(匿名)' }
        $req = ([string]$c.method).ToUpper() + ' ' + $c.path
        Write-Output ('{0,-5} {1,-10} {2,-6} {3,-34} {4}' -f $c.id, $who, $c.expect, $req, $c.name)
    }
    Write-Output ''
    exit 0
}

$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue)
if (-not $curl) {
    Write-Output '[环境] 找不到 curl.exe，Windows 10 及以上自带；请确认 PATH。'
    exit 2
}

$tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ('adorder-check-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

$script:vars = @{}
$script:results = New-Object System.Collections.ArrayList

function Invoke-Curl([string[]]$arguments) {
    $raw = & curl.exe @arguments 2>&1
    if ($null -eq $raw) { return '' }
    return ($raw -join "`n")
}

function Get-ByPath($obj, [string]$path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return $obj }
    $cur = $obj
    foreach ($seg in $path.Split('.')) {
        if ($null -eq $cur) { return $null }
        if ($seg -match '^\d+$') {
            $idx = [int]$seg
            if ($cur -is [System.Array]) {
                if ($idx -ge $cur.Length) { return $null }
                $cur = $cur[$idx]
            }
            elseif ($cur -is [System.Collections.IList]) {
                if ($idx -ge $cur.Count) { return $null }
                $cur = $cur[$idx]
            }
            else { return $null }
        }
        else {
            $prop = $cur.PSObject.Properties[$seg]
            if ($null -eq $prop) { return $null }
            $cur = $prop.Value
        }
    }
    return $cur
}

function Expand-Vars([string]$text) {
    if ($null -eq $text) { return $text }
    return [regex]::Replace($text, '\{(\w+)\}', {
            param($m)
            $k = $m.Groups[1].Value
            if ($script:vars.ContainsKey($k)) { return [string]$script:vars[$k] }
            return $m.Value
        })
}

# ---------------------------------------------------------------------------
# 预检：后端是否可达
# ---------------------------------------------------------------------------
$probe = Invoke-Curl @('-s', '--max-time', '8', ($base + '/orders'))
if ([string]::IsNullOrWhiteSpace($probe) -or $probe -match 'curl: \(7\)|Failed to connect|拒绝') {
    Write-Output ('[环境] 后端不可达：' + $base)
    Write-Output '        先启动后端（java -jar backend/target/ad-order-backend-1.0.0.jar），并确认 MySQL 已导入 sql/。'
    Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 2
}

# ---------------------------------------------------------------------------
# 登录
# ---------------------------------------------------------------------------
$tokens = @{}
if ($spec.accounts) {
    foreach ($name in $spec.accounts.PSObject.Properties.Name) {
        $acc = $spec.accounts.$name
        $body = @{ username = $acc.username; password = $acc.password } | ConvertTo-Json -Compress
        $bf = Join-Path $tmpDir ('login-' + $name + '.json')
        [System.IO.File]::WriteAllText($bf, $body, (New-Object System.Text.UTF8Encoding($false)))
        $raw = Invoke-Curl @('-s', '--max-time', '10', '-X', 'POST', ($base + '/auth/login'),
            '-H', 'Content-Type: application/json', '--data-binary', ('@' + $bf))
        try { $resp = $raw | ConvertFrom-Json } catch { $resp = $null }
        if ($resp -and $resp.code -eq 0 -and $resp.data.token) {
            $tokens[$name] = $resp.data.token
        }
        else {
            Write-Output ('[环境] 账号 ' + $name + ' 登录失败：' + $raw)
            Write-Output '        确认 sql/data.sql 已导入，演示账号密码为 123456。'
            Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
            exit 2
        }
    }
}

# ---------------------------------------------------------------------------
# 执行用例
# ---------------------------------------------------------------------------
Write-Output ''
Write-Output ('=== ad-order 契约验收  ' + $base + '  用例 ' + $cases.Count + ' ===')
Write-Output ''

$pass = 0; $fail = 0; $skip = 0

foreach ($c in $cases) {
    $missing = @()
    if ($c.requires) {
        foreach ($r in @($c.requires)) { if (-not $script:vars.ContainsKey($r)) { $missing += $r } }
    }
    if ($missing.Count -gt 0) {
        $skip++
        Write-Output ('[SKIP] ' + $c.id + ' ' + $c.name + '  （缺少变量 ' + ($missing -join ',') + '）')
        [void]$script:results.Add([pscustomobject]@{ id = $c.id; name = $c.name; verdict = 'SKIP'; expect = $c.expect; actual = $null; message = '缺少变量' })
        continue
    }

    $path = Expand-Vars ([string]$c.path)
    $method = ([string]$c.method).ToUpper()
    $args = @('-s', '--max-time', '20', '-X', $method, ($base + $path))

    if ($c.as -and $tokens.ContainsKey([string]$c.as)) {
        $args += @('-H', ('Authorization: Bearer ' + $tokens[[string]$c.as]))
    }

    if ($c.json) {
        $bf = Join-Path $tmpDir ($c.id + '.json')
        [System.IO.File]::WriteAllText($bf, ($c.json | ConvertTo-Json -Depth 8 -Compress), (New-Object System.Text.UTF8Encoding($false)))
        $args += @('-H', 'Content-Type: application/json', '--data-binary', ('@' + $bf))
    }

    if ($c.upload) {
        $uf = Join-Path $tmpDir ([string]$c.upload.fileName)
        [System.IO.File]::WriteAllText($uf, [string]$c.upload.text, (New-Object System.Text.UTF8Encoding($false)))
        $args += @('-F', (([string]$c.upload.field) + '=@' + $uf))
        if ($c.form) {
            foreach ($fn in $c.form.PSObject.Properties.Name) {
                $args += @('-F', ($fn + '=' + [string]$c.form.$fn))
            }
        }
    }

    $raw = Invoke-Curl $args
    try { $resp = $raw | ConvertFrom-Json } catch { $resp = $null }
    $code = if ($resp) { $resp.code } else { $null }
    $msg = if ($resp) { [string]$resp.message } else { $raw }

    $ok = ($null -ne $code -and [int]$code -eq [int]$c.expect)

    if ($ok -and $c.expectFields) {
        foreach ($fp in @($c.expectFields)) {
            if ($null -eq (Get-ByPath $resp $fp)) {
                $ok = $false
                $msg = '缺少期望字段 ' + $fp
                break
            }
        }
    }
    if ($ok -and $c.expectArrayLength) {
        foreach ($prop in $c.expectArrayLength.PSObject.Properties) {
            $arr = Get-ByPath $resp $prop.Name
            $actualLen = if ($null -eq $arr) { -1 } else { @($arr).Count }
            if ($actualLen -ne [int]$prop.Value) {
                $ok = $false
                $msg = '数组长度不符：' + $prop.Name + ' 期望 ' + $prop.Value + ' 实得 ' + $actualLen
                break
            }
        }
    }
    if ($ok -and $c.expectContains) {
        foreach ($needle in @($c.expectContains)) {
            if ($raw -notmatch [regex]::Escape([string]$needle)) {
                $ok = $false
                $msg = '响应中未出现 ' + $needle
                break
            }
        }
    }

    if ($ok -and $c.capture) {
        foreach ($k in $c.capture.PSObject.Properties.Name) {
            $v = Get-ByPath $resp ([string]$c.capture.$k)
            if ($null -ne $v) { $script:vars[$k] = $v }
        }
    }

    if ($ok) {
        $pass++
        Write-Output ('[PASS] ' + $c.id + ' ' + $c.name + '  (code=' + $code + ')')
        [void]$script:results.Add([pscustomobject]@{ id = $c.id; name = $c.name; verdict = 'PASS'; expect = $c.expect; actual = $code; message = $msg })
    }
    else {
        $fail++
        $tail = if ($msg -and $msg.Length -gt 120) { $msg.Substring(0, 120) + '…' } else { $msg }
        Write-Output ('[FAIL] ' + $c.id + ' ' + $c.name)
        Write-Output ('       期望 code=' + $c.expect + '，实得 code=' + $code + '；message=' + $tail)
        [void]$script:results.Add([pscustomobject]@{ id = $c.id; name = $c.name; verdict = 'FAIL'; expect = $c.expect; actual = $code; message = $msg })
    }
}

Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Output ''
Write-Output ('======== 结果: PASS=' + $pass + ' FAIL=' + $fail + ' SKIP=' + $skip + ' ========')

$exitCode = 0
if ($fail -gt 0) { $exitCode = 1 }
Write-Output ('退出码 ' + $exitCode)

if ($Markdown) {
    Write-Output ''
    Write-Output '<!-- 可粘贴到 docs/test-record.md -->'
    Write-Output ''
    Write-Output '| 用例 | 场景 | 结论 | 期望 | 实得 |'
    Write-Output '| --- | --- | --- | --- | --- |'
    foreach ($r in $script:results) {
        Write-Output ('| ' + $r.id + ' | ' + $r.name + ' | ' + $r.verdict + ' | ' + $r.expect + ' | ' + $r.actual + ' |')
    }
    Write-Output ('| 合计 | ' + $base + ' | PASS=' + $pass + ' FAIL=' + $fail + ' SKIP=' + $skip + ' | | |')
}

if ($JsonReport) {
    $report = [pscustomobject]@{
        baseUrl   = $base
        contract  = $Contract
        pass      = $pass
        fail      = $fail
        skip      = $skip
        exitCode  = $exitCode
        results   = $script:results
    }
    $outPath = $JsonReport
    if (-not [System.IO.Path]::IsPathRooted($outPath)) { $outPath = Join-Path $Root $outPath }
    [System.IO.File]::WriteAllText($outPath, ($report | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
    Write-Output ('报告已写入：' + $outPath)
}

exit $exitCode
