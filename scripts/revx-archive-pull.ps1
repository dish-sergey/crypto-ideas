<#
.SYNOPSIS
    Забрать с micro инкременты базы стенда, которых ещё нет локально.

.DESCRIPTION
    ⚠️ ЛОКАЛЬНАЯ КОПИЯ — ЕДИНСТВЕННАЯ ПОСТОЯННАЯ. На micro инкременты живут
    60 суток и удаляются, на ARM с 10.09.2026 базу чистит revx-prune.sh
    (21 сутки). Книгу задним числом не восстановить ничем — принцип 4, — так
    что всё, что старше двух месяцев, существует ровно здесь.

    Скачивается только отсутствующее, поэтому запускать можно сколько угодно
    часто; раз в пару недель достаточно, чтобы ничего не потерять.

    Восстановление из инкрементов — процедура в deploy/revx-backup.sh.

.EXAMPLE
    pwsh scripts/revx-archive-pull.ps1
    pwsh scripts/revx-archive-pull.ps1 -Dest D:\revx-data\archive
#>
param(
    [string]$Dest = 'D:\revx-data\archive',
    [string]$MicroHost = '89.168.115.160',
    [string]$Key = 'D:\servers\oracle\instance-20260722-1110\ssh-key-2026-07-22.key'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Dest)) {
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Write-Host "создан $Dest"
}

# ⚠️ ssh на Windows отказывается от ключа, если права файла шире 600, а на пути
# Windows их не выставить. Поэтому работаем с копией во временной папке.
$temp = Join-Path $env:TEMP ('revx-archive-key-' + [guid]::NewGuid().ToString('N'))
try {
    (Get-Content -Raw $Key) -replace "`r`n", "`n" | Set-Content -NoNewline -Path $temp -Encoding ascii
    icacls $temp /inheritance:r /grant:r "$($env:USERNAME):(R)" | Out-Null

    $remote = ssh -i $temp -o BatchMode=yes -o StrictHostKeyChecking=no -o IdentitiesOnly=yes `
        "ubuntu@$MicroHost" 'ls -1 ~/revx-backups/'
    if (-not $remote) { throw "на micro пусто или нет доступа" }

    $have = Get-ChildItem $Dest -File | Select-Object -ExpandProperty Name
    $missing = $remote | Where-Object { $_ -and ($have -notcontains $_) }

    Write-Host "на micro файлов: $($remote.Count), локально: $($have.Count), забрать: $($missing.Count)"
    foreach ($f in $missing) {
        Write-Host "  <- $f"
        scp -i $temp -o BatchMode=yes -o StrictHostKeyChecking=no -o IdentitiesOnly=yes `
            "ubuntu@${MicroHost}:~/revx-backups/$f" (Join-Path $Dest $f)
    }

    $size = (Get-ChildItem $Dest -File | Measure-Object -Property Length -Sum).Sum / 1GB
    Write-Host ("архив: {0} файлов, {1:N2} ГБ" -f (Get-ChildItem $Dest -File).Count, $size)
}
finally {
    Remove-Item $temp -Force -ErrorAction SilentlyContinue
}
