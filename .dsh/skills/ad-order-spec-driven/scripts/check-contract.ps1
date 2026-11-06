#requires -Version 5.1
<#
.SYNOPSIS
  广告物料订单系统 · 契约体检。读契约与代码做对账，输出记分卡。

.DESCRIPTION
  只读脚本：不修改任何文件。所有结论都可定位到文件与行号，便于直接修复。

.PARAMETER Stage
  locate   现状盘点（表/接口/页面/文档）
  domain   领域词汇对账（状态字面量、角色）
  contract 契约与文档齐备性
  skeleton 骨架阶段（允许空实现，只查禁项）
  logic    逻辑对账（字段、接口、权限、状态、留痕）
  delivery 交付对账（logic + 文档完备 + 验收材料）
  all      默认，全部执行

.PARAMETER Strict
  契约缺失由 WARN 升级为 FAIL。交付/答辩前必须加。

.PARAMETER Markdown
  额外输出一份可直接贴进 docs/test-record.md 的 Markdown 表格。

.PARAMETER JsonReport
  把完整结果写成 JSON（便于 CI 或后续 diff）。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .dsh\skills\ad-order-spec-driven\scripts\check-contract.ps1 -Stage delivery -Strict -Markdown
#>
[CmdletBinding()]
param(
    [string]$Root = (Get-Location).Path,
    [ValidateSet('all', 'locate', 'domain', 'contract', 'skeleton', 'logic', 'delivery')]
    [string]$Stage = 'all',
    [switch]$Strict,
    [switch]$Markdown,
    [string]$JsonReport = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# ----------------------------------------------------------------------------
# 基础工具
# ----------------------------------------------------------------------------
$script:Findings = New-Object System.Collections.ArrayList
$script:GateMeta = [ordered]@{}

function Read-Text([string]$path) {
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

function Resolve-InRoot([string]$relative) {
    $p = Join-Path $Root $relative
    if (Test-Path -LiteralPath $p) { return (Get-Item -LiteralPath $p).FullName }
    return $null
}

function Add-Finding([string]$gate, [string]$level, [string]$message, [string]$location) {
    [void]$script:Findings.Add([pscustomobject]@{
            Gate     = $gate
            Level    = $level
            Message  = $message
            Location = $location
        })
}

function ConvertTo-Pascal([string]$snake) {
    $parts = $snake -split '_'
    $out = ''
    foreach ($p in $parts) {
        if ($p.Length -eq 0) { continue }
        $out += $p.Substring(0, 1).ToUpper() + $p.Substring(1).ToLower()
    }
    return $out
}

function ConvertTo-Camel([string]$snake) {
    $p = ConvertTo-Pascal $snake
    if ($p.Length -eq 0) { return $p }
    return $p.Substring(0, 1).ToLower() + $p.Substring(1)
}

function Get-RelPath([string]$full) {
    if (-not $full) { return '' }
    $r = $Root.TrimEnd('\', '/')
    if ($full.StartsWith($r, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($r.Length).TrimStart('\', '/')
    }
    return $full
}

# ----------------------------------------------------------------------------
# 解析器：SQL
# ----------------------------------------------------------------------------
function Get-SqlTables([string]$sql) {
    $tables = @{}
    $m = [regex]::Matches($sql, '(?is)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\(')
    for ($k = 0; $k -lt $m.Count; $k++) {
        $name = $m[$k].Groups[1].Value
        $start = $m[$k].Index + $m[$k].Length
        $depth = 1
        $i = $start
        while ($i -lt $sql.Length -and $depth -gt 0) {
            if ($sql[$i] -eq '(') { $depth++ }
            elseif ($sql[$i] -eq ')') { $depth-- }
            $i++
        }
        if ($i -le $start) { continue }
        $body = $sql.Substring($start, $i - $start - 1)
        $cols = New-Object System.Collections.ArrayList
        foreach ($line in ($body -split "`n")) {
            $t = $line.Trim()
            if ($t.Length -eq 0) { continue }
            if ($t -match '^(PRIMARY|UNIQUE|KEY|INDEX|CONSTRAINT|FOREIGN|FULLTEXT|SPATIAL)\b') { continue }
            if ($t -match '^`?([A-Za-z_]\w*)`?\s+[A-Za-z]') {
                [void]$cols.Add($Matches[1])
            }
        }
        $tables[$name] = $cols
    }
    return $tables
}

# 提取 DML 中引用到的列，用于和 DDL 对账
function Get-SqlColumnWrites([string]$sql) {
    $refs = New-Object System.Collections.ArrayList
    foreach ($m in [regex]::Matches($sql, '(?is)INSERT\s+INTO\s+`?(\w+)`?\s*\(([^)]*)\)')) {
        $table = $m.Groups[1].Value
        foreach ($c in ($m.Groups[2].Value -split ',')) {
            $name = $c.Trim().Trim('`')
            if ($name -match '^\w+$') {
                [void]$refs.Add([pscustomobject]@{ Table = $table; Column = $name; Kind = 'INSERT'; Rewrite = $false })
            }
        }
    }
    foreach ($m in [regex]::Matches($sql, '(?is)\bUPDATE\s+`?(\w+)`?\s+SET\s+(.+?)\s+WHERE\b')) {
        $table = $m.Groups[1].Value
        $assign = $m.Groups[2].Value
        foreach ($m2 in [regex]::Matches($assign, '`?(\w+)`?\s*=\s*(?!#)')) {
            [void]$refs.Add([pscustomobject]@{ Table = $table; Column = $m2.Groups[1].Value; Kind = 'UPDATE'; Rewrite = $false })
        }
    }
    return $refs
}

# ----------------------------------------------------------------------------
# 解析器：Java
# ----------------------------------------------------------------------------
function Get-JavaEntities([string]$dir) {
    $res = @{}
    if (-not (Test-Path -LiteralPath $dir)) { return $res }
    foreach ($f in (Get-ChildItem -LiteralPath $dir -Filter *.java -File)) {
        $t = Read-Text $f.FullName
        $fields = New-Object System.Collections.ArrayList
        foreach ($mm in [regex]::Matches($t, '(?m)^\s*private\s+[\w<>\[\],\.\s]+\s+(\w+)\s*;')) {
            [void]$fields.Add($mm.Groups[1].Value)
        }
        $consts = [ordered]@{}
        foreach ($mm in [regex]::Matches($t, '(?m)^\s*public\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"\s*;')) {
            $consts[$mm.Groups[1].Value] = $mm.Groups[2].Value
        }
        $res[$f.BaseName] = [pscustomobject]@{ File = $f.FullName; Fields = $fields; Consts = $consts }
    }
    return $res
}

function Get-MethodBlocks([string]$text) {
    $res = New-Object System.Collections.ArrayList
    $rx = '(?m)^[ \t]*(?:public|private|protected)\s+[^;{}=]*?\b(\w+)\s*\([^)]*\)\s*(?:throws\s+[\w\.,\s]+)?\{'
    foreach ($x in [regex]::Matches($text, $rx)) {
        $name = $x.Groups[1].Value
        $start = $x.Index + $x.Length - 1
        $depth = 0
        $i = $start
        while ($i -lt $text.Length) {
            $c = $text[$i]
            if ($c -eq '{') { $depth++ }
            elseif ($c -eq '}') {
                $depth--
                if ($depth -eq 0) { break }
            }
            $i++
        }
        if ($i -ge $text.Length) { continue }
        $body = $text.Substring($start, $i - $start + 1)
        $line = ($text.Substring(0, $x.Index) -split "`n").Count
        [void]$res.Add([pscustomobject]@{ Name = $name; Body = $body; Line = $line; File = '' })
    }
    return $res
}

function Get-ControllerRoutes([string]$file) {
    $text = Read-Text $file
    $base = ''
    $bm = [regex]::Match($text, '@RequestMapping\(\s*"([^"]*)"\s*\)')
    if ($bm.Success) { $base = $bm.Groups[1].Value }
    $lines = $text -split "`n"
    $routes = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $m = [regex]::Match($lines[$i], '@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:"([^"]*)"|value\s*=\s*"([^"]*)")?\s*\)')
        if (-not $m.Success) { continue }
        $verb = $m.Groups[1].Value.ToUpper()
        $path = $m.Groups[2].Value
        if ([string]::IsNullOrEmpty($path)) { $path = $m.Groups[3].Value }
        $roles = @()
        $handler = ''
        $limit = [Math]::Min($i + 12, $lines.Count - 1)
        for ($j = $i + 1; $j -le $limit; $j++) {
            $rm = [regex]::Match($lines[$j], '@RequireRole\(\s*\{([^}]*)\}\s*\)')
            if ($rm.Success) {
                $roles = @($rm.Groups[1].Value -split ',' | ForEach-Object { $_.Trim().Trim('"') } | Where-Object { $_.Length -gt 0 })
            }
            $hm = [regex]::Match($lines[$j], '\bpublic\s+[^\(]*?\b(\w+)\s*\(')
            if ($hm.Success) { $handler = $hm.Groups[1].Value; break }
            if ($lines[$j] -match '\{\s*$') { break }
        }
        $full = $base + $path
        $full = $full -replace '//', '/'
        if (-not $full.StartsWith('/')) { $full = '/' + $full }
        [void]$routes.Add([pscustomobject]@{
                Verb    = $verb
                Path    = $full
                Roles   = $roles
                Handler = $handler
                Line    = $i + 1
            })
    }
    return $routes
}

function Get-FrontendApiPaths([string]$dir) {
    $res = New-Object System.Collections.ArrayList
    if (-not (Test-Path -LiteralPath $dir)) { return $res }
    $rx = 'request\.(get|post|put|delete)\(\s*[`"''"]([^`"''"]+)'
    foreach ($f in (Get-ChildItem -LiteralPath $dir -Recurse -File -Include *.js, *.ts, *.vue)) {
        $t = Read-Text $f.FullName
        foreach ($m in [regex]::Matches($t, $rx)) {
            $p = $m.Groups[2].Value -replace '\$\{(\w+)\}', '{$1}'
            [void]$res.Add([pscustomobject]@{
                    Verb = $m.Groups[1].Value.ToUpper()
                    Path = $p
                    File = $f.FullName
                })
        }
    }
    return $res
}

function Get-DocApiRoutes([string]$file) {
    $res = New-Object System.Collections.ArrayList
    if (-not $file -or -not (Test-Path -LiteralPath $file)) { return $res }
    $t = Read-Text $file
    # 支持 | GET | /api/x | 与 | GET/POST | /api/x | 两种写法
    foreach ($m in [regex]::Matches($t, '(?m)^\|\s*((?:GET|POST|PUT|DELETE)(?:\s*/\s*(?:GET|POST|PUT|DELETE))*)\s*\|\s*(/[^|\s]+)\s*\|')) {
        $rpath = $m.Groups[2].Value.Trim()
        foreach ($v in ($m.Groups[1].Value -split '/')) {
            [void]$res.Add([pscustomobject]@{ Verb = $v.Trim().ToUpper(); Path = $rpath })
        }
    }
    return $res
}

function Get-FrontendStatusMaps([string]$file) {
    $res = @{}
    if (-not (Test-Path -LiteralPath $file)) { return $res }
    $t = Read-Text $file
    foreach ($m in [regex]::Matches($t, '(?s)export\s+const\s+(\w+)\s*=\s*\{(.*?)\n\}')) {
        $name = $m.Groups[1].Value
        $keys = New-Object System.Collections.ArrayList
        foreach ($k in [regex]::Matches($m.Groups[2].Value, '(?m)^\s*([A-Z][A-Z0-9_]*)\s*:')) {
            [void]$keys.Add($k.Groups[1].Value)
        }
        $res[$name] = $keys
    }
    return $res
}

function Get-WriteLogActions([string]$text) {
    $res = New-Object System.Collections.ArrayList
    $i = 0
    while (($i = $text.IndexOf('writeLog(', $i)) -ge 0) {
        $len = [Math]::Min(420, $text.Length - $i)
        $window = $text.Substring($i, $len)
        $caps = @([regex]::Matches($window, '"([A-Z][A-Z_]{2,})"') | ForEach-Object { $_.Groups[1].Value })
        if ($caps.Count -ge 2) {
            $line = ($text.Substring(0, $i) -split "`n").Count
            [void]$res.Add([pscustomobject]@{ Biz = $caps[0]; Action = $caps[1]; Line = $line })
        }
        $i += 9
    }
    return $res
}

function Get-ContractJson([string]$path) {
    if (-not $path -or -not (Test-Path -LiteralPath $path)) { return $null }
    try {
        return (Read-Text $path | ConvertFrom-Json)
    }
    catch {
        Add-Finding 'G1' 'FAIL' ("契约 JSON 解析失败：" + $_.Exception.Message) (Get-RelPath $path)
        return $null
    }
}

function Test-RouteMatch([string]$a, [string]$b) {
    if ($a -eq $b) { return $true }
    $na = $a -replace '\{[^}]+\}', '{}' -replace '/\*\*$', '/*'
    $nb = $b -replace '\{[^}]+\}', '{}' -replace '/\*\*$', '/*'
    return $na -eq $nb
}

# ----------------------------------------------------------------------------
# 定位输入
# ----------------------------------------------------------------------------
$Root = (Resolve-Path -LiteralPath $Root).Path

$pathSchema = Resolve-InRoot 'sql/schema.sql'
$pathData = Resolve-InRoot 'sql/data.sql'
$pathEntity = Resolve-InRoot 'backend/src/main/java/com/course/adorder/entity'
$pathMapperXml = Resolve-InRoot 'backend/src/main/resources/mapper'
$pathController = Resolve-InRoot 'backend/src/main/java/com/course/adorder/controller/ApiController.java'
$pathService = Resolve-InRoot 'backend/src/main/java/com/course/adorder/service'
$pathFrontendSrc = Resolve-InRoot 'frontend/src'
$pathConstants = Resolve-InRoot 'frontend/src/utils/constants.js'
$pathApiIndex = Resolve-InRoot 'frontend/src/api'

$contractDir = Join-Path $Root 'docs-framework/contracts'
$pathStateMachine = Join-Path $contractDir 'state-machine.json'
$pathPermissions = Join-Path $contractDir 'permissions.json'
$pathAudit = Join-Path $contractDir 'audit-actions.json'
$pathAcceptance = Join-Path $contractDir 'acceptance.json'

$docApi = Join-Path $Root 'docs-framework/04_API.md'
if (-not (Test-Path -LiteralPath $docApi)) {
    $fallback = Resolve-InRoot 'README.md'
    if ($fallback) { $docApi = $fallback }
}

$hasContracts = (Test-Path -LiteralPath $pathStateMachine) -or (Test-Path -LiteralPath $pathPermissions)

# ----------------------------------------------------------------------------
# 数据准备
# ----------------------------------------------------------------------------
$sql = ''
if ($pathSchema) { $sql = Read-Text $pathSchema }
$tables = Get-SqlTables $sql
$entities = Get-JavaEntities $pathEntity
$routes = if ($pathController) { Get-ControllerRoutes $pathController } else { @() }
$frontendApis = Get-FrontendApiPaths $pathApiIndex
$docRoutes = Get-DocApiRoutes $docApi
$feMaps = Get-FrontendStatusMaps $pathConstants

$stateContract = Get-ContractJson $pathStateMachine
$permContract = Get-ContractJson $pathPermissions
$auditContract = Get-ContractJson $pathAudit

$stageAll = @('locate', 'domain', 'contract', 'skeleton', 'logic', 'delivery')

function Test-Stage([string]$gateStage) {
    if ($Stage -eq 'all') {
        if ($gateStage -eq 'skeleton') { return $false }  # 骨架阶段语义相反，只有显式指定才跑
        return $true
    }
    if ($gateStage -eq $Stage) { return $true }
    if ($Stage -eq 'delivery' -and $gateStage -in @('logic', 'contract', 'domain')) { return $true }
    if ($Stage -eq 'logic' -and $gateStage -eq 'domain') { return $true }
    if ($Stage -eq 'contract' -and $gateStage -eq 'domain') { return $true }
    return $false
}

# ----------------------------------------------------------------------------
# G0 现状盘点
# ----------------------------------------------------------------------------
if ((Test-Stage 'locate')) {
    $views = @()
    $pv = Resolve-InRoot 'frontend/src/views'
    if ($pv) { $views = @(Get-ChildItem -LiteralPath $pv -Filter *.vue -File) }
    $parts = @()
    $parts += ("表 " + $tables.Count)
    $parts += ("接口 " + @($routes).Count)
    $parts += ("实体 " + $entities.Count)
    if ($views.Count -gt 0) { $parts += ("页面 " + $views.Count) }
    Add-Finding 'G0' 'INFO' ("现状：" + ($parts -join ' / ')) '.'

    if ($tables.Count -eq 0) { Add-Finding 'G0' 'FAIL' '未解析到任何建表语句，检查 sql/schema.sql 是否存在或语法是否被改动' 'sql/schema.sql' }
    if (@($routes).Count -eq 0) { Add-Finding 'G0' 'FAIL' '未解析到任何路由，检查 ApiController 的注解写法' (Get-RelPath $pathController) }
}

# ----------------------------------------------------------------------------
# G1 文档与契约齐备性
# ----------------------------------------------------------------------------
if ((Test-Stage 'contract') -or (Test-Stage 'delivery')) {
    $required = @(
        @{ Path = 'docs-framework/01_PRD.md'; Name = 'PRD' },
        @{ Path = 'docs-framework/02_Architecture.md'; Name = '架构' },
        @{ Path = 'docs-framework/03_Database.md'; Name = '数据库' },
        @{ Path = 'docs-framework/04_API.md'; Name = 'API 契约' }
    )
    foreach ($r in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $Root $r.Path))) {
            $level = if ($Strict) { 'FAIL' } else { 'WARN' }
            Add-Finding 'G1' $level ("缺少人读文档：" + $r.Path + "（" + $r.Name + "）") $r.Path
        }
    }
    $flowDir = Join-Path $Root 'docs-framework/flow'
    if (-not (Test-Path -LiteralPath $flowDir)) {
        $level = if ($Strict) { 'FAIL' } else { 'WARN' }
        Add-Finding 'G1' $level '缺少场景文档目录 docs-framework/flow/' 'docs-framework/flow/'
    }

    $contracts = @(
        @{ Path = $pathStateMachine; Name = 'state-machine.json' },
        @{ Path = $pathPermissions; Name = 'permissions.json' },
        @{ Path = $pathAudit; Name = 'audit-actions.json' },
        @{ Path = $pathAcceptance; Name = 'acceptance.json' }
    )
    foreach ($c in $contracts) {
        if (-not (Test-Path -LiteralPath $c.Path)) {
            $level = if ($Strict) { 'FAIL' } else { 'WARN' }
            Add-Finding 'G1' $level ("缺少机器契约：" + $c.Name + "（建议从 references/templates/contracts/ 复制到 docs-framework/contracts/ 后按现状修订）") ('docs-framework/contracts/' + $c.Name)
        }
    }

    # 占位符扫描
    $placeholderRx = '(?i)\bTBD\b|\u5F85\u8865\u5145|\u5F85\u5B9A|\.\.\.\u7701\u7565|remaining code|<!--\s*TODO'
    $docTargets = @()
    $dDir = Join-Path $Root 'docs-framework'
    if (Test-Path -LiteralPath $dDir) { $docTargets += @(Get-ChildItem -LiteralPath $dDir -Recurse -File -Include *.md) }
    $readme = Resolve-InRoot 'README.md'
    if ($readme) { $docTargets += (Get-Item -LiteralPath $readme) }
    foreach ($f in $docTargets) {
        $t = Read-Text $f.FullName
        foreach ($m in [regex]::Matches($t, $placeholderRx)) {
            $line = ($t.Substring(0, $m.Index) -split "`n").Count
            Add-Finding 'G1' 'FAIL' ("文档仍有占位符「" + $m.Value + "」，交付前必须写实") ((Get-RelPath $f.FullName) + ":" + $line)
        }
    }
}

# ----------------------------------------------------------------------------
# G2 数据层对账：DDL ↔ 实体 ↔ Mapper
# ----------------------------------------------------------------------------
if ((Test-Stage 'contract') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) {
    foreach ($tbl in ($tables.Keys | Sort-Object)) {
        $cols = $tables[$tbl]
        $entityName = ConvertTo-Pascal $tbl
        $candidate = $null
        if ($entities.ContainsKey($entityName)) {
            $candidate = $entityName
        }
        else {
            foreach ($k in $entities.Keys) {
                if ($entityName.EndsWith($k)) { $candidate = $k; break }
            }
        }
        if (-not $candidate) {
            Add-Finding 'G2' 'WARN' ("表 " + $tbl + " 找不到对应实体类（期望 " + $entityName + "），若是纯统计表请忽略") 'sql/schema.sql'
            continue
        }
        $e = $entities[$candidate]
        $fieldSet = @{}
        foreach ($f in $e.Fields) { $fieldSet[$f] = $true }
        foreach ($c in $cols) {
            $camel = ConvertTo-Camel $c
            if (-not $fieldSet.ContainsKey($camel)) {
                Add-Finding 'G2' 'FAIL' ("表 " + $tbl + " 的列 " + $c + " 在实体 " + $candidate + " 中没有对应字段 " + $camel + "（SELECT * 映射会丢值）") (Get-RelPath $e.File)
            }
        }
        $colCamel = @{}
        foreach ($c in $cols) { $colCamel[(ConvertTo-Camel $c)] = $true }
        foreach ($f in $e.Fields) {
            if (-not $colCamel.ContainsKey($f)) {
                Add-Finding 'G2' 'WARN' ("实体 " + $candidate + " 的字段 " + $f + " 在表 " + $tbl + " 中找不到同名列（若是计算字段请加注释说明）") (Get-RelPath $e.File)
            }
        }
    }

    # Mapper XML 的 DML 列是否都在 DDL 里
    if ($pathMapperXml) {
        foreach ($xml in (Get-ChildItem -LiteralPath $pathMapperXml -Filter *.xml -File)) {
            $t = Read-Text $xml.FullName
            foreach ($ref in (Get-SqlColumnWrites $t)) {
                if (-not $tables.ContainsKey($ref.Table)) {
                    Add-Finding 'G2' 'FAIL' ("Mapper 中引用了不存在的表 " + $ref.Table) (Get-RelPath $xml.FullName)
                    continue
                }
                if (($tables[$ref.Table] -notcontains $ref.Column)) {
                    Add-Finding 'G2' 'FAIL' ($ref.Kind + " 语句里出现 DDL 中不存在的列 " + $ref.Table + "." + $ref.Column) (Get-RelPath $xml.FullName)
                }
            }
            foreach ($m in [regex]::Matches($t, 'resultType="([\w\.]+)"')) {
                $full = $m.Groups[1].Value
                $cls = $full.Split('.')[-1]
                if ($full -match '^(java|javax|jdk|com\.fasterxml|org\.apache|org\.mybatis)\.') { continue }
                if (@('map', 'int', 'integer', 'long', 'string', 'boolean', 'double', 'float', 'short', 'byte', 'bigdecimal', 'list', 'object') -contains $cls.ToLower()) { continue }
                if (-not $entities.ContainsKey($cls)) {
                    Add-Finding 'G2' 'FAIL' ("resultType 指向的实体类不存在：" + $full) (Get-RelPath $xml.FullName)
                }
            }
        }
    }
}

# ----------------------------------------------------------------------------
# G3 接口三方对账：文档 ↔ Controller ↔ 前端 api 层
# ----------------------------------------------------------------------------
if ((Test-Stage 'contract') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) {
    $controllerPaths = @($routes | ForEach-Object { $_.Path })

    if ($docRoutes.Count -eq 0) {
        $level = if ($Strict) { 'FAIL' } else { 'WARN' }
        Add-Finding 'G3' $level '未找到接口清单（期望 docs-framework/04_API.md 或 README.md 中有「方法 | 路径」表格），无法做文档对账'
    }
    else {
        foreach ($cr in $routes) {
            $hit = $false
            foreach ($d in $docRoutes) {
                if ($d.Verb -eq $cr.Verb -and (Test-RouteMatch $d.Path $cr.Path)) { $hit = $true; break }
            }
            if (-not $hit) {
                Add-Finding 'G3' 'FAIL' ("接口未在文档登记： " + $cr.Verb + " " + $cr.Path + "（" + $cr.Handler + "）") ((Get-RelPath $pathController) + ":" + $cr.Line)
            }
        }
        foreach ($d in $docRoutes) {
            $hit = $false
            foreach ($cr in $routes) {
                if ($d.Verb -eq $cr.Verb -and (Test-RouteMatch $d.Path $cr.Path)) { $hit = $true; break }
            }
            if (-not $hit) {
                Add-Finding 'G3' 'FAIL' ("文档登记了但代码没有实现： " + $d.Verb + " " + $d.Path) (Get-RelPath $docApi)
            }
        }
    }

    foreach ($fa in $frontendApis) {
        if ($fa.Path.EndsWith('/')) {
            Add-Finding 'G3' 'INFO' ("前端使用动态拼接路径 " + $fa.Path + "，无法静态对账，靠 check-api.ps1 覆盖") (Get-RelPath $fa.File)
            continue
        }
        $full = '/api' + $fa.Path
        $hit = $false
        foreach ($cr in $routes) {
            if ($cr.Verb -eq $fa.Verb -and (Test-RouteMatch $cr.Path $full)) { $hit = $true; break }
        }
        if (-not $hit) {
            Add-Finding 'G3' 'FAIL' ("前端调用了后端不存在的接口： " + $fa.Verb + " " + $fa.Path) (Get-RelPath $fa.File)
        }
    }
}

# ----------------------------------------------------------------------------
# G4 权限对账：契约 ↔ 入口注解 ↔ 服务层归属校验
# ----------------------------------------------------------------------------
if ((Test-Stage 'contract') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) {
    if (-not $permContract) {
        $level = if ($Strict) { 'FAIL' } else { 'WARN' }
        Add-Finding 'G4' $level '缺少 permissions.json，权限矩阵未采纳，越权缺口无法自动对账'
    }
    else {
        $serviceCache = @{}
        foreach ($sr in $permContract.routes) {
            $verb, $p = $sr.api.Split(' ')
            $found = $null
            foreach ($cr in $routes) {
                if ($cr.Verb -eq $verb.ToUpper() -and (Test-RouteMatch $cr.Path $p)) { $found = $cr; break }
            }
            if (-not $found) {
                Add-Finding 'G4' 'FAIL' ("权限契约登记了代码中不存在的路由： " + $sr.api) 'docs-framework/contracts/permissions.json'
                continue
            }
            $contractRoles = @($sr.roles)
            $codeRoles = @($found.Roles)
            switch ($sr.guard) {
                'public' {
                    if ($codeRoles.Count -gt 0) {
                        Add-Finding 'G4' 'WARN' ("契约声明公开接口，但代码加了角色限制：" + $sr.api) ((Get-RelPath $pathController) + ":" + $found.Line)
                    }
                }
                'login' {
                    if ($codeRoles.Count -gt 0) {
                        Add-Finding 'G4' 'WARN' ("契约声明仅需登录，但代码限制了角色（" + ($codeRoles -join ',') + "）：" + $sr.api) ((Get-RelPath $pathController) + ":" + $found.Line)
                    }
                }
                'annotation' {
                    $a = ($contractRoles | Sort-Object) -join ','
                    $b = ($codeRoles | Sort-Object) -join ','
                    if ($a -ne $b) {
                        Add-Finding 'G4' 'FAIL' ("角色不一致：" + $sr.api + " 契约=[" + $a + "] 代码=[" + $b + "]") ((Get-RelPath $pathController) + ":" + $found.Line)
                    }
                }
                'service' {
                    $check = $sr.serviceCheck
                    if (-not $check) {
                        Add-Finding 'G4' 'FAIL' ("guard=service 但未声明 serviceCheck：" + $sr.api) 'docs-framework/contracts/permissions.json'
                        continue
                    }
                    $parts = $check.Split('#')
                    $svcName, $methodName = $parts[0], $parts[1]
                    $svcFile = $null
                    if ($pathService) {
                        $cand = Join-Path $pathService ($svcName + '.java')
                        if (Test-Path -LiteralPath $cand) { $svcFile = $cand }
                    }
                    if (-not $svcFile) {
                        Add-Finding 'G4' 'FAIL' ("serviceCheck 指向的服务类不存在：" + $svcName) 'docs-framework/contracts/permissions.json'
                        continue
                    }
                    if (-not $serviceCache.ContainsKey($svcFile)) {
                        $serviceCache[$svcFile] = Get-MethodBlocks (Read-Text $svcFile)
                    }
                    $blocks = @($serviceCache[$svcFile] | Where-Object { $_.Name -eq $methodName })
                    if ($blocks.Count -eq 0) {
                        Add-Finding 'G4' 'FAIL' ("serviceCheck 指向的方法不存在：" + $check) (Get-RelPath $svcFile)
                        continue
                    }
                    $body = $blocks[0].Body
                    $hasOwnerCheck = ($body -match 'getAssigneeId\(\)') -or ($body -match 'countByOrderAndAssignee\(') -or ($body -match 'FORBIDDEN')
                    if (-not $hasOwnerCheck) {
                        Add-Finding 'G4' 'FAIL' ("声明有归属校验但方法内找不到校验代码：" + $check) ((Get-RelPath $svcFile) + ":" + $blocks[0].Line)
                    }
                }
                default {
                    Add-Finding 'G4' 'WARN' ("未知 guard 取值「" + $sr.guard + "」：" + $sr.api) 'docs-framework/contracts/permissions.json'
                }
            }
        }
        # 代码里有、契约没登记
        foreach ($cr in $routes) {
            $hit = $false
            foreach ($sr in $permContract.routes) {
                $verb, $p = $sr.api.Split(' ')
                if ($cr.Verb -eq $verb.ToUpper() -and (Test-RouteMatch $cr.Path $p)) { $hit = $true; break }
            }
            if (-not $hit) {
                Add-Finding 'G4' 'FAIL' ("路由未在权限矩阵登记： " + $cr.Verb + " " + $cr.Path) ((Get-RelPath $pathController) + ":" + $cr.Line)
            }
        }
    }
}

# ----------------------------------------------------------------------------
# G5 状态机对账
# ----------------------------------------------------------------------------
if ((Test-Stage 'domain') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) {
    if ($stateContract) {
        foreach ($key in $stateContract.entities.PSObject.Properties.Name) {
            $ent = $stateContract.entities.$key
            $declared = @($ent.statuses | ForEach-Object { $_.code })
            $cls = $ent.javaClass
            if ($entities.ContainsKey($cls)) {
                $constVals = @($entities[$cls].Consts.Values)
                foreach ($s in $declared) {
                    if ($constVals -notcontains $s) {
                        Add-Finding 'G5' 'FAIL' ("契约声明的状态 " + $s + " 在 " + $cls + " 中没有对应常量") (Get-RelPath $entities[$cls].File)
                    }
                }
                foreach ($v in $constVals) {
                    if ($declared -notcontains $v) {
                        Add-Finding 'G5' 'FAIL' ("实体常量 " + $cls + "." + $v + " 未在状态机契约登记") (Get-RelPath $entities[$cls].File)
                    }
                }
            }
            else {
                Add-Finding 'G5' 'FAIL' ("状态机契约引用的实体类不存在：" + $cls) 'docs-framework/contracts/state-machine.json'
            }

            $feMapName = $ent.frontendMap
            if ($feMapName) {
                if (-not $feMaps.ContainsKey($feMapName)) {
                    Add-Finding 'G5' 'FAIL' ("前端缺少状态映射 " + $feMapName + "，页面会显示空白标签") (Get-RelPath $pathConstants)
                }
                else {
                    $feKeys = @($feMaps[$feMapName])
                    foreach ($s in $declared) {
                        if ($feKeys -notcontains $s) {
                            Add-Finding 'G5' 'FAIL' ("状态 " + $s + " 在前端映射 " + $feMapName + " 中缺失") (Get-RelPath $pathConstants)
                        }
                    }
                    foreach ($k in $feKeys) {
                        if ($declared -notcontains $k) {
                            Add-Finding 'G5' 'FAIL' ("前端映射 " + $feMapName + " 出现契约未定义的状态 " + $k) (Get-RelPath $pathConstants)
                        }
                    }
                }
            }

            # DDL 默认值必须是合法状态
            if ($tables.ContainsKey($ent.table) -and $ent.initial) {
                $tblBody = [regex]::Match($sql, '(?is)CREATE\s+TABLE\s+`?' + [regex]::Escape($ent.table) + '`?\s*\((.*?)\)\s*ENGINE')
                if ($tblBody.Success) {
                    $dm = [regex]::Match($tblBody.Groups[1].Value, "status\s+VARCHAR\(\d+\)\s+NOT\s+NULL\s+DEFAULT\s+'([^']+)'")
                    if ($dm.Success -and ($declared -notcontains $dm.Groups[1].Value)) {
                        Add-Finding 'G5' 'FAIL' ("表 " + $ent.table + " 的 status 默认值 " + $dm.Groups[1].Value + " 不是合法状态") 'sql/schema.sql'
                    }
                }
            }

            # 迁移声明的接口与留痕动作必须存在
            foreach ($tr in $ent.transitions) {
                if ($tr.api) {
                    $verb, $p = $tr.api.Split(' ')
                    $hit = $false
                    foreach ($cr in $routes) {
                        if ($cr.Verb -eq $verb.ToUpper() -and (Test-RouteMatch $cr.Path $p)) { $hit = $true; break }
                    }
                    if (-not $hit) {
                        Add-Finding 'G5' 'FAIL' ("迁移 " + $tr.id + " 声明的接口不存在：" + $tr.api) 'docs-framework/contracts/state-machine.json'
                    }
                }
                if ($tr.from -and ($declared -notcontains $tr.from)) {
                    Add-Finding 'G5' 'FAIL' ("迁移 " + $tr.id + " 的 from 状态未定义：" + $tr.from) 'docs-framework/contracts/state-machine.json'
                }
                if ($tr.to -and ($declared -notcontains $tr.to)) {
                    Add-Finding 'G5' 'FAIL' ("迁移 " + $tr.id + " 的 to 状态未定义：" + $tr.to) 'docs-framework/contracts/state-machine.json'
                }
            }
        }
    }
    else {
        # 没有契约时，退化为「实体常量 ↔ 前端映射」对账，仍能抓到漏改标签
        foreach ($cls in @('MaterialOrder', 'MaterialTask')) {
            if (-not $entities.ContainsKey($cls)) { continue }
            $constVals = @($entities[$cls].Consts.Values)
            $mapName = if ($cls -eq 'MaterialOrder') { 'ORDER_STATUS' } else { 'TASK_STATUS' }
            if ($feMaps.ContainsKey($mapName)) {
                $feKeys = @($feMaps[$mapName])
                foreach ($v in $constVals) {
                    if ($feKeys -notcontains $v) {
                        Add-Finding 'G5' 'FAIL' ("后端状态常量 " + $cls + "." + $v + " 在前端 " + $mapName + " 中没有标签，页面会显示空白") (Get-RelPath $pathConstants)
                    }
                }
                foreach ($k in $feKeys) {
                    if ($constVals -notcontains $k) {
                        Add-Finding 'G5' 'FAIL' ("前端 " + $mapName + " 的状态 " + $k + " 后端没有对应常量") (Get-RelPath $pathConstants)
                    }
                }
            }
            else {
                $level = if ($Strict) { 'FAIL' } else { 'WARN' }
                Add-Finding 'G5' $level ("未找到前端状态映射 " + $mapName) (Get-RelPath $pathConstants)
            }
        }
        $level = if ($Strict) { 'FAIL' } else { 'WARN' }
        Add-Finding 'G5' $level '缺少 state-machine.json，迁移矩阵与终态约束无法自动校验'
    }
}

# ----------------------------------------------------------------------------
# G6 留痕对账
# ----------------------------------------------------------------------------
if ((Test-Stage 'logic') -or (Test-Stage 'delivery')) {
    $registered = @()
    if ($auditContract) { $registered = @($auditContract.actions | ForEach-Object { $_.code }) }

    $allActions = New-Object System.Collections.ArrayList
    if ($pathService) {
        foreach ($sf in (Get-ChildItem -LiteralPath $pathService -Filter *.java -File)) {
            $t = Read-Text $sf.FullName
            foreach ($a in (Get-WriteLogActions $t)) {
                [void]$allActions.Add([pscustomobject]@{ Action = $a.Action; Biz = $a.Biz; File = $sf.FullName; Line = $a.Line })
            }
            # 写状态必须同方法留痕
            foreach ($mb in (Get-MethodBlocks $t)) {
                $writesState = ($mb.Body -match '\.updateStatus\(') -or ($mb.Body -match '\.updateAssignee\(') -or (($mb.Body -match '\.setStatus\(') -and ($mb.Body -match '\.update\('))
                if ($writesState -and -not ($mb.Body -match 'writeLog\(')) {
                    Add-Finding 'G6' 'FAIL' ("方法 " + $mb.Name + " 改变了业务状态但没有写操作历史（留痕缺失）") ((Get-RelPath $sf.FullName) + ":" + $mb.Line)
                }
            }
        }
    }

    if ($auditContract) {
        foreach ($a in $allActions) {
            if ($registered -notcontains $a.Action) {
                Add-Finding 'G6' 'FAIL' ("动作码 " + $a.Action + " 未在 audit-actions.json 注册") ((Get-RelPath $a.File) + ":" + $a.Line)
            }
        }
        foreach ($code in $registered) {
            if (-not ($allActions | Where-Object { $_.Action -eq $code })) {
                Add-Finding 'G6' 'WARN' ("注册表里的动作 " + $code + " 在代码中从未被写入（可能是死码或漏实现）") 'docs-framework/contracts/audit-actions.json'
            }
        }
    }
    else {
        $level = if ($Strict) { 'FAIL' } else { 'WARN' }
        Add-Finding 'G6' $level ("缺少 audit-actions.json，动作码是否齐全无法对账；当前代码共写入 " + $allActions.Count + " 处留痕")
    }
}

# ----------------------------------------------------------------------------
# G7 禁项扫描
# ----------------------------------------------------------------------------
if ((Test-Stage 'skeleton') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) {
    $targets = @()
    if ($pathService) {
        $backendRoot = Resolve-InRoot 'backend/src/main/java'
        if ($backendRoot) { $targets += @(Get-ChildItem -LiteralPath $backendRoot -Recurse -File -Include *.java) }
    }
    if ($pathFrontendSrc) { $targets += @(Get-ChildItem -LiteralPath $pathFrontendSrc -Recurse -File -Include *.js, *.vue) }

    $strictPatterns = @(
        @{ Rx = '\balert\s*\('; Msg = '使用 alert() 弹窗，必须改为 Element Plus 的消息组件' },
        @{ Rx = '\bconsole\.log\s*\('; Msg = '残留 console.log 调试输出' },
        @{ Rx = '\bSystem\.out\.println\s*\('; Msg = '使用 System.out.println，必须改用日志框架' },
        @{ Rx = 'printStackTrace\s*\('; Msg = '直接打印堆栈，必须改用日志框架' },
        @{ Rx = '\bTODO\b|\bFIXME\b'; Msg = '残留 TODO/FIXME，交付前必须处理或转成文档里的已知限制' },
        @{ Rx = '\.\.\.\u7701\u7565|remaining code|\u5176\u4F59\u4EE3\u7801\u7565'; Msg = '存在省略占位，必须补全实现' }
    )
    foreach ($f in $targets) {
        $t = Read-Text $f.FullName
        foreach ($p in $strictPatterns) {
            foreach ($m in [regex]::Matches($t, $p.Rx)) {
                $line = ($t.Substring(0, $m.Index) -split "`n").Count
                Add-Finding 'G7' 'FAIL' ($p.Msg + '：' + $m.Value) ((Get-RelPath $f.FullName) + ":" + $line)
            }
        }
    }

    # 骨架阶段专属：空实现是预期行为
    if ($Stage -eq 'skeleton') {
        $stubCount = 0
        foreach ($f in $targets) {
            $t = Read-Text $f.FullName
            $stubCount += ([regex]::Matches($t, 'UnsupportedOperationException')).Count
        }
        Add-Finding 'G7' 'INFO' ("骨架阶段检测到 " + $stubCount + " 处待实现空壳（这是预期的，点亮一个减一个）")
    }

    # 配置类禁项
    $appYml = Resolve-InRoot 'backend/src/main/resources/application.yml'
    if ($appYml) {
        $t = Read-Text $appYml
        if ($t -match 'password:\s*"(?!\$\{)[^"]+"') {
            Add-Finding 'G7' 'WARN' '数据库密码明文写在 application.yml，生产部署必须改为环境变量' (Get-RelPath $appYml)
        }
        if ($t -match 'jwt-secret:\s*"(?!\$\{)[^"]+"') {
            Add-Finding 'G7' 'WARN' 'JWT 密钥硬编码在 application.yml，生产部署必须改为环境变量' (Get-RelPath $appYml)
        }
    }
    if ($pathMapperXml) {
        foreach ($xml in (Get-ChildItem -LiteralPath $pathMapperXml -Filter *.xml -File)) {
            # 去掉 XML 注释再扫描：注释里提到 SELECT * 不算代码问题
            $t = [regex]::Replace((Read-Text $xml.FullName), '(?s)<!--.*?-->', '')
            if ($t -match 'SELECT\s+\*') {
                Add-Finding 'G7' 'WARN' 'Mapper 使用 SELECT *，字段变更会静默失配，建议显式列名' (Get-RelPath $xml.FullName)
            }
        }
    }
    if ($pathFrontendSrc) {
        foreach ($f in (Get-ChildItem -LiteralPath $pathFrontendSrc -Recurse -File -Include *.vue, *.js)) {
            if ($f.Name -eq 'request.js') { continue }
            $t = Read-Text $f.FullName
            if ($t -match "from\s+['`"]axios['`"]") {
                Add-Finding 'G7' 'FAIL' '组件直接引用 axios，必须统一走 src/api/ 封装层' (Get-RelPath $f.FullName)
            }
        }
    }
}

# ----------------------------------------------------------------------------
# 汇总输出
# ----------------------------------------------------------------------------
$gateNames = [ordered]@{
    'G0' = '现状盘点'
    'G1' = '文档与契约齐备'
    'G2' = '数据层对账'
    'G3' = '接口三方对账'
    'G4' = '权限对账'
    'G5' = '状态机对账'
    'G6' = '留痕对账'
    'G7' = '禁项扫描'
}

$exitCode = 0
$failCount = @($script:Findings | Where-Object { $_.Level -eq 'FAIL' }).Count
$warnCount = @($script:Findings | Where-Object { $_.Level -eq 'WARN' }).Count
if ($failCount -gt 0) { $exitCode = 1 }

Write-Output ''
Write-Output ('=== ad-order 契约体检  stage=' + $Stage + '  root=' + $Root + ' ===')
Write-Output ''

function Test-GateRan([string]$gate) {
    switch ($gate) {
        'G0' { return (Test-Stage 'locate') }
        'G1' { return ((Test-Stage 'contract') -or (Test-Stage 'delivery')) }
        'G2' { return ((Test-Stage 'contract') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) }
        'G3' { return ((Test-Stage 'contract') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) }
        'G4' { return ((Test-Stage 'contract') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) }
        'G5' { return ((Test-Stage 'domain') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) }
        'G6' { return ((Test-Stage 'logic') -or (Test-Stage 'delivery')) }
        'G7' { return ((Test-Stage 'skeleton') -or (Test-Stage 'logic') -or (Test-Stage 'delivery')) }
        default { return $false }
    }
}

$scoreRows = @()
foreach ($g in $gateNames.Keys) {
    $items = @($script:Findings | Where-Object { $_.Gate -eq $g })
    if ($items.Count -eq 0) {
    
    }
    $f = @($items | Where-Object { $_.Level -eq 'FAIL' }).Count
    $w = @($items | Where-Object { $_.Level -eq 'WARN' }).Count
    $ranGate = Test-GateRan $g
    $verdict = if ($f -gt 0) { 'FAIL' } elseif ($w -gt 0) { 'WARN' } elseif ($ranGate) { 'PASS' } else { '-' }
    $scoreRows += [pscustomobject]@{ Gate = $g; Name = $gateNames[$g]; Verdict = $verdict; Fails = $f; Warns = $w }

    if ($items.Count -eq 0) { continue }
    $mark = if ($f -gt 0) { '[FAIL]' } elseif ($w -gt 0) { '[WARN]' } else { '[PASS]' }
    Write-Output ($mark + ' ' + $g + ' ' + $gateNames[$g])
    foreach ($it in $items) {
        if ($it.Level -eq 'INFO' -and $f -eq 0 -and $w -eq 0) {
            Write-Output ('       ' + $it.Message)
        }
        elseif ($it.Level -ne 'INFO') {
            $loc = if ($it.Location) { '  @' + $it.Location } else { '' }
            Write-Output ('       [' + $it.Level + '] ' + $it.Message + $loc)
        }
    }
}

Write-Output ''
Write-Output '--- 记分卡 ---'
foreach ($r in $scoreRows) {
    Write-Output ('{0} {1,-20} {2,-6} fail={3} warn={4}' -f $r.Gate, $r.Name, $r.Verdict, $r.Fails, $r.Warns)
}
Write-Output ''
Write-Output ('合计：FAIL=' + $failCount + ' WARN=' + $warnCount)
if (-not $hasContracts) {
    Write-Output '提示：尚未采纳 docs-framework/contracts/ 机器契约，G4/G5/G6 降级执行；交付前请按 references/02-contracts.md 补齐。'
}
Write-Output ('退出码 ' + $exitCode)

if ($Markdown) {
    Write-Output ''
    Write-Output '<!-- 可粘贴到 docs/test-record.md -->'
    Write-Output ''
    Write-Output '| 门禁 | 内容 | 结论 | 失败 | 警告 |'
    Write-Output '| --- | --- | --- | --- | --- |'
    foreach ($r in $scoreRows) {
        Write-Output ('| ' + $r.Gate + ' | ' + $r.Name + ' | ' + $r.Verdict + ' | ' + $r.Fails + ' | ' + $r.Warns + ' |')
    }
    Write-Output ('| 合计 | stage=' + $Stage + ' | ' + $(if ($failCount -eq 0) { '通过' } else { '未通过' }) + ' | ' + $failCount + ' | ' + $warnCount + ' |')
}

if ($JsonReport) {
    $report = [pscustomobject]@{
        stage     = $Stage
        root      = $Root
        strict    = [bool]$Strict
        exitCode  = $exitCode
        failCount = $failCount
        warnCount = $warnCount
        gates     = $scoreRows
        findings  = $script:Findings
    }
    $json = $report | ConvertTo-Json -Depth 6
    $outPath = $JsonReport
    if (-not [System.IO.Path]::IsPathRooted($outPath)) { $outPath = Join-Path $Root $outPath }
    [System.IO.File]::WriteAllText($outPath, $json, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output ('报告已写入：' + $outPath)
}

exit $exitCode
