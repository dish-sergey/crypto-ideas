#!/bin/bash
# Док. 246 §2: хвост журналов для сторожа задним числом (только чтение, без revx.db). С 10.11 19:00 UTC.
set -e
T0=1794066922000
O=~/tmp7d; mkdir -p $O; cd $O
declare -A J=( [a]=/home/ubuntu/revx-exec/state/exec.db [b]=/home/ubuntu/revx-exec-b/state/exec-b-sol.db [c]=/home/ubuntu/revx-exec-c/state/exec-c-eth2.db [d]=/home/ubuntu/revx-exec-d/state/exec-d-eth2.db [e]=/home/ubuntu/revx-exec-e/state/exec-e-btc.db [f]=/home/ubuntu/revx-exec-f/state/exec-f-sol.db )
Q="nice -n 19 ionice -c3 sqlite3 -readonly -separator ,"
for b in a b c d e f; do
  j=${J[$b]}
  $Q $j "select ts_ms,fair,bid,ask,inventory,quotable,ifnull(pressure,''),ifnull(ref_own,''),ifnull(ref_bnb,''),replace(ifnull(reason,''),',',';') from exec_quote where ts_ms>=$T0" | gzip -1 > q-$b.csv.gz
  $Q $j "select ts_ms,venue_id,side,qty,price,fair,ifnull(level,'') from exec_fill where ts_ms>=$T0" > f-$b.csv
  nice -n 19 sqlite3 -readonly -separator '|' $j "select ts_ms,kind,replace(replace(ifnull(detail,''),char(10),' '),'|','/') from exec_event where ts_ms>=$T0 and kind not in ('venue_reads')" | gzip -1 > ev-$b.txt.gz
  echo "$b done $(date -u +%T)"
done
ls -la; echo ALLDONE
