#!/usr/bin/env bash
# Чистка базы стенда на ARM: снести уже вывезенные и достаточно старые строки.
#
# ЗАЧЕМ. С 10.09.2026 книга по трём торгуемым парам собирается на 20 уровней
# вместо пяти, и база растёт примерно на 525 МБ в сутки против прежних 250.
# На диске ARM свободно 19 ГБ — без чистки это чуть больше месяца до отказа
# записи, а отказ записи здесь означает дыру в данных навсегда (принцип 4:
# книгу задним числом не восстановить).
#
# ГЛАВНАЯ ЗАЩИТА — КУРСОР ВЫГРУЗКИ. Удаляется только то, что УЖЕ уехало в
# инкрементную копию на micro: `rowid <= курсор` из backup-cursor.txt. Курсор
# двигает revx-backup.sh, и только после успешного scp. Поэтому даже если
# выгрузка неделю не работала, чистка не тронет ни одной невывезенной строки —
# она просто ничего не удалит. Проверка по возрасту идёт ВТОРЫМ условием, а не
# первым: возраст без курсора удалил бы данные, которых больше нигде нет.
#
# ⚠️ VACUUM НЕ ДЕЛАЕТСЯ, И ЭТО НАМЕРЕННО. Файл после DELETE не уменьшится, но
# освободившиеся страницы SQLite переиспользует под новые вставки — размер
# выходит на полку, а нам ровно это и нужно. Полный VACUUM на базе в гигабайты
# держит писателя и повторяет инцидент 25-26.08.2026, когда `sqlite3 .backup`
# провисел 34 часа и стоил 33 часов книги безвозвратно. Место ради места того
# не стоит.
#
# Запускать С MICRO (ключ есть только в направлении micro -> ARM), после
# выгрузки: cron `45 4 * * *`, то есть через полтора часа после `15 3`.
set -euo pipefail

ARM_HOST="${ARM_HOST:-130.61.31.216}"
ARM_KEY="${ARM_KEY:-$HOME/.ssh/armkey}"
RETAIN_DAYS="${RETAIN_DAYS:-21}"     # 21 сутки ~ 11 ГБ на полке при 19 ГБ свободных
BATCH="${BATCH:-50000}"              # строк за один DELETE
PAUSE="${PAUSE:-2}"                  # пауза между партиями, секунд
DRY_RUN="${DRY_RUN:-0}"
REMOTE_TIMEOUT="${REMOTE_TIMEOUT:-3600}"

ssh_opts=(-i "$ARM_KEY" -o BatchMode=yes -o ConnectTimeout=20)

ssh "${ssh_opts[@]}" "ubuntu@$ARM_HOST" \
    "flock -n /tmp/revx-prune.lock timeout -k 60 $REMOTE_TIMEOUT env \
     RETAIN_DAYS=$RETAIN_DAYS BATCH=$BATCH PAUSE=$PAUSE DRY_RUN=$DRY_RUN bash -s" <<'REMOTE'
set -euo pipefail
DB="$HOME/revx/data/revx.db"
CUR="$HOME/revx/data/backup-cursor.txt"

if [ ! -f "$CUR" ]; then
    echo "$(date -u +%FT%TZ) курсора выгрузки нет — ничего не удаляю" >&2
    exit 0
fi
read -r book_cur trade_cur < "$CUR"
cutoff=$(( ( $(date -u +%s) - RETAIN_DAYS * 86400 ) * 1000 ))

size_before=$(stat -c %s "$DB")
echo "$(date -u +%FT%TZ) чистка: хранить $RETAIN_DAYS сут, курсор книги $book_cur, сделок $trade_cur"

prune() {
    table="$1"; ts_column="$2"; cursor="$3"
    doomed=$(sqlite3 -readonly "$DB" \
        "select count(*) from $table where rowid <= $cursor and $ts_column < $cutoff;")
    echo "  $table: под удаление $doomed строк"
    if [ "$DRY_RUN" = "1" ] || [ "$doomed" = "0" ]; then
        return
    fi
    # ⚠️ Партиями с паузой: один DELETE на миллионы строк держит писательский
    # замок минутами, а на том конце коллектор пишет книгу раз в секунду.
    # Потерянный снимок невосполним, сэкономленная минута — нет.
    while :; do
        gone=$(sqlite3 "$DB" \
            "delete from $table where rowid in (select rowid from $table
                where rowid <= $cursor and $ts_column < $cutoff limit $BATCH);
             select changes();")
        [ "$gone" = "0" ] && break
        sleep "$PAUSE"
    done
    echo "  $table: удалено"
}

prune revx_book  t_recv_ms "$book_cur"
prune revx_trade ts_ms     "$trade_cur"

size_after=$(stat -c %s "$DB")
free_pages=$(sqlite3 -readonly "$DB" 'pragma freelist_count;')
page_size=$(sqlite3 -readonly "$DB" 'pragma page_size;')
echo "$(date -u +%FT%TZ) готово: файл $((size_before/1048576)) -> $((size_after/1048576)) МБ, \
свободных страниц $free_pages (= $((free_pages * page_size / 1048576)) МБ под переиспользование)"
df -h "$HOME" | tail -1
REMOTE
