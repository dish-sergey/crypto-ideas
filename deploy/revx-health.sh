#!/usr/bin/env bash
# Быстрая сводка по ботам после перехода на читатель площадки (этап 2, 27.09.2026).
# Только чтение баз. Запуск: bash ~/revx-health.sh [часов назад, по умолчанию 6]
H=${1:-6}
NOW=$(date +%s%3N); FROM=$((NOW - H*3600*1000))
echo "== $(date -u '+%F %T') UTC, окно $H ч"
declare -A J=( [a]=revx-exec/state/exec.db [b]=revx-exec-b/state/exec-b-sol.db [c]=revx-exec-c/state/exec-c-eth2.db
               [d]=revx-exec-d/state/exec-d-eth2.db [e]=revx-exec-e/state/exec-e-btc.db [f]=revx-exec-f/state/exec-f-sol.db )
declare -A U=( [a]=revx-exec [b]=revx-exec-b [c]=revx-exec-c [d]=revx-exec-d [e]=revx-exec-e [f]=revx-exec-f )
printf "%-2s %-7s %-4s %7s %6s %5s %9s %5s %5s  %s\n" бот служба поток тик_с котир сдел позиция книга пропущ события
for b in a b c d e f; do
  db=~/${J[$b]}; u=${U[$b]}
  pid=$(systemctl show -p MainPID --value $u); st=$(systemctl is-active $u)
  thr=$(grep -l revx-quote-loop /proc/$pid/task/*/comm 2>/dev/null | wc -l)
  q="SELECT (($NOW-max(ts_ms))/1000), (SELECT quotable FROM exec_quote ORDER BY ts_ms DESC LIMIT 1) FROM exec_quote;"
  read age qt < <(sqlite3 -separator ' ' "file:$db?mode=ro" "$q")
  fills=$(sqlite3 "file:$db?mode=ro" "SELECT count(*) FROM exec_fill WHERE ts_ms>$FROM")
  pos=$(sqlite3 "file:$db?mode=ro" "SELECT printf('%.6f',value) FROM exec_state WHERE key='position'")
  led=$(sqlite3 "file:$db?mode=ro" "SELECT count(*) FROM exec_event WHERE ts_ms>$FROM AND kind='ledger_fill'")
  miss=$(sqlite3 "file:$HOME/revx-shared/venue.db?mode=ro" "SELECT count(*) FROM shadow_diff WHERE bot='$b' AND resolved_ms IS NULL")
  ev=$(sqlite3 -separator '=' "file:$db?mode=ro" "SELECT kind,count(*) FROM exec_event WHERE ts_ms>$FROM AND kind NOT IN ('fill','replace','place') GROUP BY kind ORDER BY 2 DESC LIMIT 8" | tr '\n' ' ')
  printf "%-2s %-7s %-4s %7s %6s %5s %9s %5s %5s  %s\n" $b $st $thr "$age" "$qt" $fills "$pos" $led $miss "$ev"
done
echo "-- читатель:"
sqlite3 -separator ' ' "file:$HOME/revx-shared/venue.db?mode=ro" \
  "SELECT name, (($NOW-last_ok_ms)/1000)||' с назад', 'циклов '||cycles, 'ошибок '||errors, 'троттл '||throttled, coalesce(last_error,'') FROM heartbeat"
echo "-- не записано ботами (shadow_diff, открытые):"
sqlite3 -separator ' ' "file:$HOME/revx-shared/venue.db?mode=ro" \
  "SELECT bot,symbol,side,traded,booked,datetime(tdt/1000,'unixepoch') FROM shadow_diff WHERE resolved_ms IS NULL"
echo "-- разрыв резерва (последние):"
sqlite3 -separator ' ' "file:$HOME/revx-shared/venue.db?mode=ro" "SELECT * FROM reserve_gap ORDER BY rowid DESC LIMIT 4" 2>/dev/null
echo "-- бумажный хедж: событий за окно"
sqlite3 -separator ' ' "file:$HOME/revx-shared/hedge_paper.db?mode=ro" \
  "SELECT bot, rule, count(*) FROM paper_event WHERE ts_ms>$FROM GROUP BY bot, rule" 2>/dev/null
