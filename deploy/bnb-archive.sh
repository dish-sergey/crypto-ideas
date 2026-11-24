#!/bin/bash
# Архив цены Бинанса (revx-bnb-feed пишет bnb.db и держит только 3 ч): раз в 10 мин докопировать новые строки
# в ~/revx-bnb/bnb-hist.db (хранение 30 суток); с ключом daily — выгрузить вчерашние сутки в daily/bnb-tick-ДАТА.csv.gz
# (30 суток), их забирает data-pull.sh на локальный компьютер (C:\data-archive\bnb-tick). 24.11.2026.
set -e
H=/home/ubuntu/revx-bnb/bnb-hist.db; SRC=/home/ubuntu/revx-shared/bnb.db; D=/home/ubuntu/revx-bnb/daily
sqlite3 "$H" <<SQL
PRAGMA busy_timeout=5000;
CREATE TABLE IF NOT EXISTS bnb_tick (symbol TEXT NOT NULL, ts_ms INTEGER NOT NULL, bid REAL, ask REAL, PRIMARY KEY (symbol, ts_ms));
ATTACH 'file:$SRC?mode=ro' AS s;
INSERT OR IGNORE INTO bnb_tick SELECT symbol, ts_ms, bid, ask FROM s.bnb_tick
  WHERE ts_ms > coalesce((SELECT max(ts_ms) FROM bnb_tick), 0) - 600000;
DELETE FROM bnb_tick WHERE ts_ms < (strftime('%s','now') - 30*86400) * 1000;
SQL
if [ "$1" = daily ]; then
  mkdir -p "$D"; day=$(date -u -d yesterday +%F); a=$(date -u -d "$day" +%s)000; b=$(date -u -d "$day + 1 day" +%s)000
  f="$D/bnb-tick-$day.csv"
  sqlite3 -csv "$H" "SELECT symbol, ts_ms, bid, ask FROM bnb_tick WHERE ts_ms >= $a AND ts_ms < $b ORDER BY symbol, ts_ms" > "$f.part"
  mv "$f.part" "$f" && gzip -f "$f"
  find "$D" -name 'bnb-tick-*.csv.gz' -mtime +30 -delete
fi
