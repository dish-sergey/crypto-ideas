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
  gzip -f "$f"
done
find "$DIR" -name 'book-*.jsonl.gz' -mtime +45 -delete
