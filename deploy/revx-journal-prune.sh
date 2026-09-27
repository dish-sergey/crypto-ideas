#!/bin/bash
# Срок хранения ЖУРНАЛОВ БОТОВ (bot-arm, cron ubuntu, раз в сутки). 27.09.2026.
#
# Зачем: почти весь журнал — лог каждого запроса к площадке (exec_request) и тики
# котировщика (exec_quote). Торгующий бот прибавляет ~55 МБ/сутки (один уровень)
# и ~90 МБ (три уровня), шесть — ~0.45 ГБ/сутки; без срока диск ARM кончается за
# недели, а отказ записи сборщика — дыра навсегда (принцип 4).
#
# Что делает, по каждому живому журналу:
#   1. строки exec_request и exec_quote старше KEEP_DAYS копирует в АРХИВ
#      ~/revx-journal-archive/<бот>-<дата>.db и сверяет число строк;
#   2. только после совпадения удаляет их из журнала ПАЧКАМИ (BATCH строк на
#      транзакцию, пауза между пачками) — бот ждёт замок (busy_timeout в
#      ExecJournal), а не падает;
#   3. жмёт архив gzip.
#
# ⚠️ НЕ ТРОГАЕТ: exec_fill, exec_event, exec_state, exec_open_order — из них
# восстанавливается позиция и учёт; они маленькие.
# ⚠️ СОХРАНЯЕТ тики exec_quote за час до события position_seed: ExecJournal.seed()
# берёт оттуда цену входа затравки, и без них поменялся бы реализованный доход.
# ⚠️ Файл журнала НЕ уменьшается (VACUUM требует остановить бота): освобождённые
# страницы переиспользуются новыми записями, то есть журнал просто перестаёт расти.
#
# Архив для приборов разбора (--revx-hold-check, --revx-replay и т.п.) по окнам
# старше срока: gunzip и читать как обычный журнал (схема та же, таблиц две).
set -u
KEEP_DAYS=${KEEP_DAYS:-14}
BATCH=${BATCH:-5000}
ARCH=${ARCH:-/home/ubuntu/revx-journal-archive}
JOURNALS=${JOURNALS:-"/home/ubuntu/revx-exec/state/exec.db
/home/ubuntu/revx-exec-b/state/exec-b-sol.db
/home/ubuntu/revx-exec-c/state/exec-c-eth.db
/home/ubuntu/revx-exec-d/state/exec-d-eth.db
/home/ubuntu/revx-exec-e/state/exec-e-btc.db
/home/ubuntu/revx-exec-f/state/exec-f-sol.db"}

mkdir -p "$ARCH"
now=$(date -u +%s)
cutoff=$(( (now - KEEP_DAYS * 86400) * 1000 ))
day=$(date -u +%Y%m%d)
echo "$(date -u +%FT%TZ) чистка журналов: хранить ${KEEP_DAYS} сут (граница $(date -u -d @$((cutoff/1000)) +%FT%TZ))"

for j in $JOURNALS; do
  [ -f "$j" ] || { echo "  $j: нет файла"; continue; }
  name="$(basename "$(dirname "$(dirname "$j")")")-$(basename "$j" .db)"
  seed=$(sqlite3 -readonly "$j" "SELECT COALESCE(MIN(ts_ms), 0) FROM exec_event WHERE kind = 'position_seed'")
  keep="NOT (ts_ms BETWEEN $((seed - 3600000)) AND $seed)"
  nreq=$(sqlite3 -readonly "$j" "SELECT COUNT(*) FROM exec_request WHERE ts_ms < $cutoff")
  nq=$(sqlite3 -readonly "$j" "SELECT COUNT(*) FROM exec_quote WHERE ts_ms < $cutoff AND $keep")
  if [ "$nreq" = 0 ] && [ "$nq" = 0 ]; then
    echo "  $name: старше срока нет"
    continue
  fi
  arch="$ARCH/$name-$day.db"
  rm -f "$arch"
  # 1. Архив. Журнал подключён только на чтение: WAL читателем бота не блокирует.
  sqlite3 "$arch" "ATTACH 'file:$j?mode=ro' AS j;
    CREATE TABLE exec_request AS SELECT * FROM j.exec_request WHERE ts_ms < $cutoff;
    CREATE TABLE exec_quote AS SELECT * FROM j.exec_quote WHERE ts_ms < $cutoff AND $keep;" || {
      echo "  $name: ⚠️ архив не записан — журнал НЕ трогаю"; rm -f "$arch"; continue; }
  areq=$(sqlite3 "$arch" "SELECT COUNT(*) FROM exec_request")
  aq=$(sqlite3 "$arch" "SELECT COUNT(*) FROM exec_quote")
  if [ "$areq" -lt "$nreq" ] || [ "$aq" -lt "$nq" ]; then
    echo "  $name: ⚠️ в архиве меньше строк ($areq/$nreq, $aq/$nq) — журнал НЕ трогаю"
    continue
  fi
  # 2. Удаление пачками, строго по той же границе.
  t0=$(date +%s)
  for spec in "exec_request|ts_ms < $cutoff" "exec_quote|ts_ms < $cutoff AND $keep"; do
    t="${spec%%|*}"; cond="${spec#*|}"
    total=0
    while :; do
      n=$(sqlite3 "$j" "PRAGMA busy_timeout=10000;
        DELETE FROM $t WHERE rowid IN (SELECT rowid FROM $t WHERE $cond LIMIT $BATCH);
        SELECT changes();" | tail -1)
      [ -z "$n" ] && { echo "  $name/$t: ⚠️ пачка не прошла, стоп"; break; }
      total=$((total + n))
      [ "$n" -lt "$BATCH" ] && break
      sleep 0.2
    done
    echo "  $name/$t: удалено $total"
  done
  # 3. Сжать архив.
  nice -n 19 gzip -f "$arch"
  echo "  $name: архив $(du -h "$arch.gz" | cut -f1), за $(( $(date +%s) - t0 )) с; файл журнала $(du -h "$j" | cut -f1)"
done
df -h / | tail -1
