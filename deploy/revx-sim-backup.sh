#!/usr/bin/env bash
# Ночной инкремент базы симуляции на micro2 + уборка уже забранных файлов.
#
# Запускается ПО КРОНУ НА САМОЙ micro2 (в отличие от revx-backup.sh, который
# ходит с micro на bot-arm по ssh): источник и приёмник здесь на одной машине.
#
# ⚠️ ПОЧЕМУ НЕ `.backup` И НЕ `cp` (инцидент 25–26.08.2026 на bot-arm)
# `sqlite3 .backup` копирует постранично и начинает ЗАНОВО, если источник в это
# время меняют. Сборщик пишет десять раз в секунду, поэтому снимок не
# заканчивается никогда: тогда два таких процесса грызли диск 34 часа и уронили
# темп сбора с 720 снимков в час до 18 — сутки данных потеряны безвозвратно.
# Здесь копируются ТОЛЬКО НОВЫЕ строки (`rowid > курсор`): один последовательный
# проход по хвосту таблицы, работа не растёт с размером базы.
#
# ⚠️ Источник открывается ТОЛЬКО НА ЧТЕНИЕ (`mode=ro`) — писатель не блокируется.
#
# Курсор двигается ТОЛЬКО после успешной упаковки: оборванный запуск просто
# повторится на следующую ночь и захватит тот же хвост.
set -euo pipefail

DB="${DB:-/home/ubuntu/revx/data/revx-sim.db}"
OUT="${OUT:-/home/ubuntu/revx-sim-backups}"
CURSOR="${CURSOR:-/home/ubuntu/revx/data/sim-backup-cursor.txt}"
KEEP_UNPULLED="${KEEP_UNPULLED:-3}"   # столько свежих файлов не трогаем никогда
LOCK="/tmp/revx-sim-backup.lock"

exec 9>"$LOCK"
if ! flock -n 9; then
    echo "$(date -u +%FT%TZ) предыдущий запуск ещё идёт — выхожу"
    exit 0
fi

mkdir -p "$OUT"
book_cur=0
trade_cur=0
if [ -f "$CURSOR" ]; then
    read -r book_cur trade_cur < "$CURSOR"
fi

stamp=$(date -u +%Y%m%d-%H%M)
tmp="/tmp/revx-sim-inc-$stamp.db"
rm -f "$tmp"

# ⚠️ Границы берутся ОДНИМ запросом и запоминаются ДО копирования: сборщик пишет
# всё время, и если взять max(rowid) после копирования, курсор уедет за пределы
# того, что реально попало в файл, — дыра в архиве, которую никто не заметит.
read -r book_max trade_max <<EOF
$(sqlite3 "file:$DB?mode=ro" "SELECT coalesce(max(rowid),0) FROM revx_book;
                              SELECT coalesce(max(rowid),0) FROM revx_trade;" | tr '\n' ' ')
EOF

if [ "$book_max" -le "$book_cur" ] && [ "$trade_max" -le "$trade_cur" ]; then
    echo "$(date -u +%FT%TZ) новых строк нет (книга $book_cur, сделки $trade_cur)"
else
    sqlite3 "$tmp" "
        ATTACH \"file:$DB?mode=ro\" AS src;
        CREATE TABLE revx_book  AS SELECT * FROM src.revx_book
            WHERE rowid > $book_cur  AND rowid <= $book_max;
        CREATE TABLE revx_trade AS SELECT * FROM src.revx_trade
            WHERE rowid > $trade_cur AND rowid <= $trade_max;"
    rows=$(sqlite3 "$tmp" "SELECT (SELECT count(*) FROM revx_book)||' '||(SELECT count(*) FROM revx_trade);")
    gzip -f "$tmp"
    mv "$tmp.gz" "$OUT/revx-sim-inc-$stamp.db.gz"
    sha256sum "$OUT/revx-sim-inc-$stamp.db.gz" | awk '{print $1}' \
        > "$OUT/revx-sim-inc-$stamp.db.gz.sha256"
    echo "$book_max $trade_max" > "$CURSOR"
    size=$(du -h "$OUT/revx-sim-inc-$stamp.db.gz" | cut -f1)
    echo "$(date -u +%FT%TZ) инкремент готов: revx-sim-inc-$stamp.db.gz ($size), строк: $rows"
fi

# УБОРКА: удаляем только то, что локальная машина уже забрала и пометила.
#
# ⚠️ Метку `.pulled` ставит СКАЧАВШАЯ сторона и только после сверки sha256 —
# сервер сам решить, что файл сохранён, не может. Плюс последние
# $KEEP_UNPULLED файлов не удаляются никогда, даже помеченные: если локальная
# копия окажется битой, хвост ещё можно будет забрать заново.
cd "$OUT"
mapfile -t all < <(ls -1 revx-sim-inc-*.db.gz 2>/dev/null | sort)
count=${#all[@]}
removed=0
for (( i = 0; i < count - KEEP_UNPULLED; i++ )); do
    f="${all[$i]}"
    if [ -f "$f.pulled" ]; then
        rm -f "$f" "$f.sha256" "$f.pulled"
        removed=$((removed + 1))
    fi
done
kept=$(ls -1 revx-sim-inc-*.db.gz 2>/dev/null | wc -l)
unpulled=$(ls -1 revx-sim-inc-*.db.gz 2>/dev/null | while read -r f; do
    [ -f "$f.pulled" ] || echo "$f"; done | wc -l)
echo "$(date -u +%FT%TZ) уборка: удалено $removed, осталось $kept (не забрано локально: $unpulled)"
