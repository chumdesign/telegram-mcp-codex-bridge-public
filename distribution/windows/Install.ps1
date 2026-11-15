#requires -Version 7.0
[CmdletBinding()]
param([int]$Port = 17433, [string[]]$FileRoot = @())
$ErrorActionPreference = 'Stop'
$python = (Get-Command python -ErrorAction Stop).Source
& $python -c 'import sys; assert sys.version_info >= (3,11), "Python 3.11+ is required"'
if ($LASTEXITCODE -ne 0) { throw 'Установите Python 3.11+ и повторите установку.' }
$arguments = @((Join-Path $PSScriptRoot 'bridge.py'), 'setup', '--register-codex', '--port', [string]$Port)
foreach ($root in $FileRoot) { $arguments += @('--root', $root) }
& $python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Установка не завершена. Прочитайте сообщение выше.' }
