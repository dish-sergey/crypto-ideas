#!/usr/bin/env bash
# Чистка базы симуляции на micro2: держим две недели, остальное удаляем.
#
# Запускается ПО КРОНУ НА САМОЙ micro2.
#
# 🔑 ГЛАВНОЕ ПРАВИЛО: ЧИСТКА НЕ ОБГОНЯЕТ ВЫГРУЗКУ. Удаляются только строки,
# которые уже попали в инкремент (`rowid <= курсор бэкапа`). Если бэкап сломался
# и стоит третью неделю, чистка сама остановится на его курсоре и НИЧЕГО не
# потеряет — вместо этого разрастётся диск, а это чинится и видно.
# Книгу задним числом не восстановить ничем (принцип 4), поэтому порядок именно
# такой: сначала сохранено, потом удалено.
#
# ⚠️ VACUUM НЕ ДЕЛАЕТСЯ, И ЭТО НАМЕРЕННО. Файл после DELETE не уменьшится, но
# освободившиеся страницы SQLite переиспользует, и рост остановится. VACUUM
# переписывает базу целиком — на живом сборщике это часы блокировок.
#
# ⚠️ Удаляем ПАРТИЯМИ с паузой: один DELETE на миллионы строк держит
# писательский замок и роняет темп сбора.
set -euo pipefail

DB="${DB:-/home/ubuntu/revx/data/revx-sim.db}"
CURSOR="${CURSOR:-/home/ubuntu/revx/data/sim-backup-cursor.txt}"
RETAIN_DAYS="${RETAIN_DAYS:-14}"
BATCH="${BATCH:-20000}"
PAUSE="${PAUSE:-2}"
LOCK="/tmp/revx-sim-prune.lock"

exec 9>"$LOCK"
if ! flock -n 9; then
    echo "$(date -u +%FT%TZ) предыдущая чистка ещё идёт — выхожу"
    exit 0
fi

if [ ! -f "$CURSOR" ]; then
    echo "$(date -u +%FT%TZ) курсора бэкапа нет — НИЧЕГО не чищу: значит ни одна строка ещё не сохранена"
    exit 0
fi
read -r book_cur trade_cur < "$CURSOR"
cutoff=$(( ( $(date -u +%s) - RETAIN_DAYS * 86400 ) * 1000 ))

before=$(du -h "$DB" | cut -f1)
echo "$(date -u +%FT%TZ) чистка: хранить $RETAIN_DAYS сут, курсор книги $book_cur, сделок $trade_cur, база $before"

# Книга: по времени приёма, но не дальше того, что выгружено.
while :; do
    n=$(sqlite3 "$DB" "
        DELETE FROM revx_book WHERE rowid IN (
            SELECT rowid FROM revx_book
            WHERE t_recv_ms < $cutoff AND rowid <= $book_cur LIMIT $BATCH);
        SELECT changes();")
    [ "${n:-0}" -eq 0 ] && break
    echo "$(date -u +%FT%TZ)   книга: удалено $n"
    sleep "$PAUSE"
done

# Сделки: то же самое по ingest_ms (время, когда строка стала доступна нам).
while :; do
    n=$(sqlite3 "$DB" "
        DELETE FROM revx_trade WHERE rowid IN (
            SELECT rowid FROM revx_trade
            WHERE ingest_ms < $cutoff AND rowid <= $trade_cur LIMIT $BATCH);
        SELECT changes();")
    [ "${n:-0}" -eq 0 ] && break
    echo "$(date -u +%FT%TZ)   сделки: удалено $n"
    sleep "$PAUSE"
done

after=$(du -h "$DB" | cut -f1)
oldest=$(sqlite3 -readonly "$DB" "SELECT datetime(min(t_recv_ms)/1000,'unixepoch') FROM revx_book;")
echo "$(date -u +%FT%TZ) чистка закончена: было $before, стало $after, самый старый снимок $oldest"
