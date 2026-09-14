#!/usr/bin/env bash
# Перечислить инкременты для локальной выгрузки: имя|sha256|состояние.
#
# Отдельным файлом на сервере намеренно: та же команда, собранная строкой на
# стороне PowerShell, теряла кавычки по дороге через ssh и выполняла sha256 как
# команду. Скрипт на месте — и цитировать нечего.
set -euo pipefail
OUT="${1:-/home/ubuntu/revx-sim-backups}"
cd "$OUT" 2>/dev/null || exit 0
for f in revx-sim-inc-*.db.gz; do
    [ -e "$f" ] || continue
    sha=""
    [ -f "$f.sha256" ] && sha=$(cat "$f.sha256")
    state=new
    [ -f "$f.pulled" ] && state=pulled
    echo "$f|$sha|$state"
done
