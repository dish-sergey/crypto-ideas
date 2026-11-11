#!/usr/bin/env bash
# Сжать вчерашний захват Бинанса и убрать старое.
# ⚠️ Сегодняшний файл НЕ ТРОГАЕМ: в него пишет живая служба.
set -u
# ⚠️ Каталог берётся из окружения: тем же скриптом жмётся захват Kraken
# (`DIR=$HOME/kraken-book`), чтобы не держать две копии одной логики.
DIR="${DIR:-$HOME/binance-book}"
TODAY=$(date -u +%Y-%m-%d)
for f in "$DIR"/book-*.jsonl; do
  [ -e "$f" ] || continue
  case "$f" in *"$TODAY"*) continue;; esac
  nice -n 19 gzip -f "$f"
done
# Срок хранения — KEEP суток (по умолчанию 45). Полный стакан (binance-depth, ~1 ГБ/сут сжатого) держится 7:
# он каждый день уезжает на C:\data-archive (scripts/data-pull.sh).
find "$DIR" -name 'book-*.jsonl.gz' -mtime +"${KEEP:-45}" -delete
