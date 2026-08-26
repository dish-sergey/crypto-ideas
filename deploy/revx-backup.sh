#!/usr/bin/env bash
# Ночная копия базы стенда Revolut X с bot-arm на micro — ИНКРЕМЕНТАМИ.
#
# Зачем: revx.db живёт только на ARM, а книга невосполнима задним числом (ТЗ §3.5).
# Litestream сюда не годится — он реплицирует crypto.db в B2 с free-лимитом 10 ГБ,
# поток стенда туда подмешивать нельзя.
#
# Запускается ПО КРОНУ НА MICRO (у micro есть ~/.ssh/armkey, обратного ключа нет).
#
# ⚠️ ПОЧЕМУ НЕ `.backup` (инцидент 25–26.08.2026)
# `sqlite3 .backup` копирует постранично и, если ИСТОЧНИК меняет другой процесс,
# начинает копирование ЗАНОВО. Коллектор пишет по нескольку раз в секунду, поэтому
# при размере базы больше ~1 ГБ снимок не заканчивается никогда: 20.08 он занял 6 с,
# 24.08 — 21 минуту, 25.08 не завершился вовсе. Два таких процесса грызли диск
# 34 часа и уронили темп сбора с 720 снимков/час до 18 — сутки с лишним данных
# по книге потеряны безвозвратно. Плюс живой читатель не даёт чекпойнтить WAL,
# и он раздулся до 250 МБ.
#
# Отсюда три правила, которые нельзя убирать:
#   1. Копируем ТОЛЬКО НОВЫЕ строки (`rowid > курсор`) — один последовательный
#      проход по хвосту таблицы вместо всей базы. Работа не растёт с размером БД.
#   2. Жёсткий `timeout` на удалённой стороне: зависший снимок умирает сам.
#   3. `flock -n` с обеих сторон: следующий крон не встаёт в очередь к висящему.
# Курсор двигается ТОЛЬКО после успешного scp — оборванная копия просто повторится.
#
# Восстановление: пустая база по schema-revx.sql, затем инкременты ПО ПОРЯДКУ
#   for f in revx-inc-*.db.gz; do gunzip -c $f > /tmp/i.db
#     sqlite3 restored.db "attach '/tmp/i.db' as i;
#       insert or replace into revx_book select * from i.revx_book;
#       insert or replace into revx_trade select * from i.revx_trade;"
#   done
# Мелкие таблицы (pair/anomaly/uptime/probe/run) лежат в каждом инкременте целиком —
# достаточно взять их из последнего.

set -euo pipefail

ARM_HOST="${ARM_HOST:-130.61.31.216}"
ARM_KEY="${ARM_KEY:-$HOME/.ssh/armkey}"
DEST_DIR="${DEST_DIR:-$HOME/revx-backups}"
KEEP_DAYS="${KEEP_DAYS:-60}"        # инкремент — единственная копия своих строк
REMOTE_TIMEOUT="${REMOTE_TIMEOUT:-1800}"

mkdir -p "$DEST_DIR"
stamp=$(date -u +%Y%m%d-%H%M)
ssh_opts=(-i "$ARM_KEY" -o BatchMode=yes -o ConnectTimeout=20)

# Снимок хвоста на ARM: под flock (не стакаться), под timeout (не висеть),
# под nice/ionice (не мешать сбору).
ssh "${ssh_opts[@]}" "ubuntu@$ARM_HOST" \
    "flock -n /tmp/revx-backup.lock timeout -k 60 $REMOTE_TIMEOUT bash -s" <<'REMOTE'
set -euo pipefail
DB="$HOME/revx/data/revx.db"
CUR="$HOME/revx/data/backup-cursor.txt"
OUT=/tmp/revx-inc.db

book_cur=0; trade_cur=0
[ -f "$CUR" ] && read -r book_cur trade_cur < "$CUR"
book_max=$(sqlite3 -readonly "$DB" 'select coalesce(max(rowid),0) from revx_book;')
trade_max=$(sqlite3 -readonly "$DB" 'select coalesce(max(rowid),0) from revx_trade;')

rm -f "$OUT" "$OUT.gz"
nice -n 19 ionice -c3 sqlite3 "$DB" <<SQL
attach '$OUT' as d;
create table d.revx_book    as select * from main.revx_book    where rowid > $book_cur  and rowid <= $book_max;
create table d.revx_trade   as select * from main.revx_trade   where rowid > $trade_cur and rowid <= $trade_max;
create table d.revx_pair    as select * from main.revx_pair;
create table d.revx_anomaly as select * from main.revx_anomaly;
create table d.revx_uptime  as select * from main.revx_uptime;
create table d.revx_probe   as select * from main.revx_probe;
create table d.revx_run     as select * from main.revx_run;
SQL
nice -n 19 ionice -c3 gzip -6 "$OUT"
echo "$book_max $trade_max" > "$CUR.new"
echo "снимок: строк книги $((book_max-book_cur)), сделок $((trade_max-trade_cur))"
REMOTE

scp "${ssh_opts[@]}" "ubuntu@$ARM_HOST:/tmp/revx-inc.db.gz" "$DEST_DIR/revx-inc-$stamp.db.gz"

# Курсор двигаем только теперь — копия точно доехала.
ssh "${ssh_opts[@]}" "ubuntu@$ARM_HOST" \
    'mv ~/revx/data/backup-cursor.txt.new ~/revx/data/backup-cursor.txt; rm -f /tmp/revx-inc.db.gz'

find "$DEST_DIR" -name 'revx-inc-*.db.gz' -mtime "+$KEEP_DAYS" -delete

echo "$(date -u +%FT%TZ) инкремент готов: $DEST_DIR/revx-inc-$stamp.db.gz ($(du -h "$DEST_DIR/revx-inc-$stamp.db.gz" | cut -f1))"
