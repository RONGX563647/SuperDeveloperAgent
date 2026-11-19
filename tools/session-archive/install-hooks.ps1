#!/usr/bin/env pwsh
# 把仓库内的 .githooks 设为唯一钩子来源（取代"复制到 .git/hooks"）。
# 复制会有两个老问题：新克隆默认裸奔、钩子内容与 .githooks 源文件悄悄漂移。
# 用 core.hooksPath 后钩子随仓库走，改一处即全线生效。
# Usage: powershell -ExecutionPolicy Bypass -File tools/session-archive/install-hooks.ps1
$ErrorActionPreference = 'Stop'

$root = (git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
$hooks = Join-Path $root '.githooks'
if (-not (Test-Path $hooks)) {
    throw "missing hook source: $hooks"
}

git -C $root config core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) {
    throw 'git config core.hooksPath 失败'
}

# 升级路径：旧版本把钩子复制到了 .git/hooks，留着会与 .githooks 双份生效/漂移。
$legacy = (git -C $root rev-parse --git-path hooks).Trim()
if (-not [System.IO.Path]::IsPathRooted($legacy)) {
    $legacy = Join-Path $root $legacy
}
foreach ($name in @('pre-commit', 'pre-push')) {
    $old = Join-Path $legacy $name
    if (Test-Path $old) {
        Remove-Item -Force $old
        Write-Host "removed legacy copy: $old"
    }
}

Write-Host 'core.hooksPath=.githooks 已生效（钩子随仓库走，新克隆执行同一条命令即可）'
Write-Host '自检：python tools/session-archive/gate.py doctor'
