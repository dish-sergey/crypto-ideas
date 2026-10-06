#!/usr/bin/env bash
# Выжимка ЗАТЫКОВ ПЛОЩАДКИ из журналов шести ботов в ~/revx/data/stalls.db (03.10.2026).
#
# Зачем: стенд с 03.10.2026 проигрывает затыки по реальному расписанию
# (-Drevx.sim.stall-schedule), а единственный их источник — время ответа площадки
# на запросы ботов в exec_request. Журналы чистятся через 14 суток
# (revx-journal-prune.sh), и без этой выжимки расписание пропадало бы вместе с ними.
#
# Пишем ОТДЕЛЬНУЮ базу, а не revx.db: там единственный писатель — сборщик, и
# блокировка с нашей стороны грозит отказом записи книги (принцип 4).
# Журналы открываются ТОЛЬКО на чтение (mode=ro). Ночной бэкап с micro
# (deploy/revx-backup.sh) кладёт обе таблицы в инкремент целиком.
#
#   revx_stall        — каждый медленный (> 1 с) PUT/POST/DELETE: время, бот, ответ;
#   revx_stall_cover  — по часам и ботам: сколько было замен вообще. Час без замен
#                       значит «не наблюдали», а не «затыков не было» (24–26.09
#                       боты стояли).
#
# Крон на bot-arm: 5 * * * * (каждый час пересчитывает последний учтённый час).
set -euo pipefail

OUT="$HOME/revx/data/stalls.db"
exec 9>/tmp/revx-stall.lock
flock -n 9 || exit 0

declare -A J=( [a]=revx-exec/state/exec.db [b]=revx-exec-b/state/exec-b-sol.db
               [c]=revx-exec-c/state/exec-c-xrp.db [d]=revx-exec-d/state/exec-d-xrp.db
               [e]=revx-exec-e/state/exec-e-btc.db [f]=revx-exec-f/state/exec-f-sol.db )

sqlite3 "$OUT" <<'SQL'
create table if not exists revx_stall (
    ts_ms integer not null, bot text not null, method text not null,
    status integer, latency_ms integer,
    primary key (ts_ms, bot, method));
create table if not exists revx_stall_cover (
    hour integer not null, bot text not null, puts integer not null, slow integer not null,
    primary key (hour, bot));
SQL

for b in a b c d e f; do
    j="$HOME/${J[$b]}"
    [ -f "$j" ] || continue
    # С начала последнего учтённого часа: он мог быть неполным.
    from=$(sqlite3 "$OUT" "select coalesce(max(hour), 0) from revx_stall_cover where bot = '$b';")
    from_ms=$(( from * 3600000 ))
    nice -n 19 ionice -c3 sqlite3 "$OUT" <<SQL
attach 'file:$j?mode=ro' as j;
insert or replace into revx_stall
    select ts_ms, '$b', method, status, latency_ms from j.exec_request
    where ts_ms >= $from_ms and method in ('PUT', 'POST', 'DELETE') and latency_ms > 1000;
insert or replace into revx_stall_cover
    select ts_ms / 3600000, '$b', sum(method = 'PUT'),
           sum(latency_ms > 1000 and method in ('PUT', 'POST', 'DELETE'))
    from j.exec_request where ts_ms >= $from_ms group by 1;
SQL
done
