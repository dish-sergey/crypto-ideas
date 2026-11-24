# LZX-compress (WOF, compact /exe:lzx) files in a folder older than N days and larger than 64 KB (2026-11-17).
# Used for stand run outputs: j-* journals (SQLite, ~2.5x) and logs. Already compressed files are skipped.
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI and Cyrillic breaks parsing.
#   powershell -NoProfile -File scripts\lzx-old.ps1 -Dir C:\revx-xrp\hp -Days 2
param([Parameter(Mandatory = $true)][string]$Dir, [int]$Days = 2)
if (-not (Test-Path $Dir)) { Write-Output "no folder $Dir"; exit 0 }
$cut = (Get-Date).AddDays(-$Days)
$files = @(Get-ChildItem -LiteralPath $Dir -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $cut -and $_.Length -gt 65536 } | ForEach-Object { $_.FullName })
for ($i = 0; $i -lt $files.Count; $i += 40) {
    $batch = $files[$i..([Math]::Min($i + 39, $files.Count - 1))]
    & compact.exe /c /exe:lzx /i /q @batch | Out-Null
}
Write-Output ("{0} {1}: files older than {2} days - {3}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Dir, $Days, $files.Count)
