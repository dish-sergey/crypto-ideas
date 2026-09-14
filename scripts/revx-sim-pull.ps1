<#
.SYNOPSIS
    Забрать с micro2 инкременты базы симуляции и разрешить серверу их удалить.
.DESCRIPTION
    ⚠️ ЛОКАЛЬНАЯ КОПИЯ — ЕДИНСТВЕННАЯ ПОСТОЯННАЯ. На micro2 живая база чистится
    через 14 суток (revx-sim-prune.sh), а инкременты удаляются, как только эта
    машина подтвердит, что забрала их. Книгу задним числом не восстановить
    ничем (принцип 4), поэтому порядок жёсткий:

        скачали → сверили sha256 → поставили метку .pulled → сервер удалил

    Метку ставит ТОЛЬКО эта сторона и только после сверки: сервер сам решить,
    что файл сохранён, не может. Последние три файла сервер не удаляет никогда,
    даже помеченные, — на случай, если локальная копия окажется битой.

    Скачивается только отсутствующее, поэтому запускать можно сколько угодно
    часто. Раз в неделю достаточно: на сервере две недели запаса.
.EXAMPLE
    pwsh scripts/revx-sim-pull.ps1
    pwsh scripts/revx-sim-pull.ps1 -Dest D:\revx-data\sim-archive -WhatIf
#>
param(
    [string]$Dest = 'D:\revx-data\sim-archive',
    [string]$MicroHost = '92.5.1.233',
    [string]$KeyPath = 'D:\servers\oracle\instance-20260819-2319\ssh-key-2026-08-19.key',
    [string]$Remote = '/home/ubuntu/revx-sim-backups',
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'

# ⚠️ Ключ из консоли Oracle приезжает с CRLF и без завершающего перевода строки,
# и openssh отвечает на это «error in libcrypto: unsupported», что выглядит как
# битый ключ. Работаем с очищенной копией, оригинал не трогаем (см. SERVERS.md).
$key = Join-Path $env:TEMP 'revx-sim-pull.key'
# ⚠️ Копия с прошлого запуска лежит с правами «только чтение» (мы их сами и
# ставим ниже, иначе openssh ключ игнорирует), и перезапись падает с «Access to
# the path is denied». Поэтому сначала возвращаем себе права и удаляем.
if (Test-Path $key) {
    icacls $key /grant:r "$($env:USERNAME):(F)" | Out-Null
    Remove-Item $key -Force
}
(Get-Content -Raw $KeyPath) -replace "`r", '' | Set-Content -NoNewline -Path $key -Encoding ascii
Add-Content -Path $key -Value "`n" -NoNewline -Encoding ascii
icacls $key /inheritance:r /grant:r "$($env:USERNAME):(R)" | Out-Null

if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Path $Dest | Out-Null }

$ssh = @('-i', $key, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new', "ubuntu@$MicroHost")

# Список того, что лежит на сервере, вместе с контрольными суммами: одним
# заходом, чтобы не дёргать ssh на каждый файл.
#
# ⚠️ Перечисление живёт СКРИПТОМ НА СЕРВЕРЕ (`revx-sim-list.sh`), а не строкой
# здесь: та же команда, собранная на стороне PowerShell, теряла кавычки по
# дороге через ssh, и удалённый bash выполнял контрольную сумму как команду
# («8307bf23…: command not found»). Цитировать нечего — нечему и ломаться.
$listing = & ssh @ssh "bash /home/ubuntu/revx-sim-list.sh $Remote"
if (-not $listing) { Write-Host 'на сервере инкрементов нет'; return }

$pulled = 0
$skipped = 0
foreach ($line in $listing) {
    $name, $sha, $state = $line.Trim() -split '\|'
    if (-not $name) { continue }
    $local = Join-Path $Dest $name

    if ((Test-Path $local) -and $state -eq 'pulled') { $skipped++; continue }

    if (-not (Test-Path $local)) {
        Write-Host "качаю $name"
        if ($WhatIf) { continue }
        & scp -i $key -o BatchMode=yes "ubuntu@${MicroHost}:$Remote/$name" $local
        if ($LASTEXITCODE -ne 0) { throw "scp $name завершился с кодом $LASTEXITCODE" }
    }

    # СВЕРКА. Без неё метка .pulled разрешила бы серверу удалить файл, который
    # доехал оборванным, — и это была бы потеря без следов.
    $mine = (Get-FileHash -Algorithm SHA256 -Path $local).Hash.ToLower()
    if ($sha -and $mine -ne $sha.ToLower()) {
        Remove-Item $local
        throw "$name не сошёлся по sha256 (сервер $sha, локально $mine) — файл удалён, повторите запуск"
    }
    if (-not $sha) { Write-Warning "${name}: на сервере нет .sha256, сверить не с чем — метку НЕ ставлю"; continue }

    if ($state -ne 'pulled') {
        if ($WhatIf) { Write-Host "пометил бы $name"; continue }
        & ssh @ssh "touch $Remote/$name.pulled"
        if ($LASTEXITCODE -ne 0) { throw "не удалось поставить метку на $name" }
    }
    $pulled++
}

Write-Host "готово: забрано и помечено $pulled, уже было $skipped"
Write-Host "локальный архив: $Dest"
Write-Host 'сервер удалит помеченные файлы в ближайшую ночь, кроме трёх последних'
