<#
  Двусторонняя синхронизация docs/ <-> Google Drive (папка «Trading Bot — Спецификация»)
  через rclone remote `gdrive` (scope=drive, root_folder_id = папка доков).

  Использование:
    powershell -File scripts/docs-sync.ps1            # обычный bisync (правки в обе стороны)
    powershell -File scripts/docs-sync.ps1 -Resync    # пересобрать базовую линию (после сбоя/расхождений)

  Синкаются только *.md (лишние .docx на Drive игнорируются).
  Токен rclone лежит в %APPDATA%\rclone\rclone.conf (вне репозитория).
  NB: до 2026 rclone отключит общий client_id — тогда завести свой (rclone.org/drive/#making-your-own-client-id).
#>
param([switch]$Resync)

$ErrorActionPreference = "Stop"
$rclone = (Get-Command rclone -ErrorAction SilentlyContinue).Source
if (-not $rclone) {
    $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
    $rclone = (Get-Command rclone -ErrorAction SilentlyContinue).Source
}
if (-not $rclone) { throw "rclone не найден в PATH" }

$docs = Join-Path (Split-Path $PSScriptRoot -Parent) "docs"
$args = @($docs, "gdrive:", "--include", "*.md", "--create-empty-src-dirs")
if ($Resync) { $args += "--resync" }

& $rclone bisync @args
if ($LASTEXITCODE -ne 0) { throw "bisync завершился с кодом $LASTEXITCODE (при расхождениях запусти с -Resync)" }
Write-Host "docs синхронизированы (bisync ok)"
