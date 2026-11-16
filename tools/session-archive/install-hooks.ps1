#!/usr/bin/env pwsh
# Install session-archive git hooks into the current repository (copies files; no git config change).
# Usage: powershell -ExecutionPolicy Bypass -File tools/session-archive/install-hooks.ps1
$ErrorActionPreference = 'Stop'

$root = (git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
$hooksDir = (git -C $root rev-parse --git-path hooks).Trim()
if (-not [System.IO.Path]::IsPathRooted($hooksDir)) {
    $hooksDir = Join-Path $root $hooksDir
}
New-Item -ItemType Directory -Force -Path $hooksDir | Out-Null

foreach ($name in @('pre-commit', 'pre-push')) {
    $source = Join-Path $root ".githooks/$name"
    if (-not (Test-Path $source)) {
        throw "missing hook source: $source"
    }
    $target = Join-Path $hooksDir $name
    Copy-Item -Force $source $target
    Write-Host "installed $name -> $target"
}

Write-Host 'Session archive gate installed: commit requires an active session + passing tests; push requires finish + committed docs.'
Write-Host 'Self-check: python tools/session-archive/gate.py self-test'
