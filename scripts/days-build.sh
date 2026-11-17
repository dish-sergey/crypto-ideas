#!/bin/bash
# Дневные файлы стенда (17.11.2026, DayStore): для каждого дня, которого ещё нет в $DAYS, собирает базу
# из копий стенда за d и d+1 (копии режутся в 03:15, плюс разгон опоры 30 мин из d−1), пишет день
# (рынок BTC/ETH/SOL, опора, затыки, каталог пар), жмёт в архив и стирает временную базу.
# Заодно докачивает секундные свечи Бинанса (USDC и USDT по BTC/ETH/SOL), без которых прогон падает.
#
# Запуск — после ежедневного забора (scripts/data-daily.sh); руками: bash scripts/days-build.sh [первый_день]
# Повторный запуск безопасен: готовые дни пропускаются.
#
#   $DAYS/archive/YYYYMMDD.tgz — переносимая копия (~8 МБ): LZX живёт только на NTFS этой машины
#   $DAYS/YYYYMMDD/            — рабочий день, сжат NTFS (LZX, ~25 МБ на диске), прогоны читают его напрямую
set -u
export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/liberica-25.0.3}
INC=${INC:-/c/data-archive/revx-inc}
DAYS=${DAYS:-/d/revx-days}
BNB=${BNB:-/d/revx-data/bnb1s}
JAR=${JAR:-/c/revx-xrp/tools-days.jar}
FIRST=${1:-2026-11-07}
mkdir -p "$DAYS/archive" "$DAYS/tmp" "$BNB"
log() { echo "$(date +%F' '%T) $*"; }
lzx() { cmd //c "compact /c /exe:lzx /s:$(cygpath -w "$1") /i /q" > /dev/null || log "ОШИБКА: не сжат $1"; }
inc_of() { ls "$INC"/revx-inc-$(date -d "$1" +%Y%m%d)-*.db.gz 2>/dev/null | head -1; }

FAIL=0
d=$FIRST
last=$(date -u -d "yesterday" +%F)
while [[ "$d" < "$last" || "$d" == "$last" ]]; do
  tag=$(date -d "$d" +%Y%m%d)
  nxt=$(date -d "$d +1 day" +%F)
  # Бинанс 1 с: дневные архивы появляются на следующие сутки
  for p in BTCUSDC ETHUSDC SOLUSDC BTCUSDT ETHUSDT SOLUSDT; do
    f=$BNB/$p-1s-$d.zip
    [ -s "$f" ] && continue
    curl -sf -o "$f.part" "https://data.binance.vision/data/spot/daily/klines/$p/1s/$p-1s-$d.zip" \
      && unzip -tq "$f.part" >/dev/null && mv -f "$f.part" "$f" || { rm -f "$f.part"; log "Бинанс $p $d ещё нет"; }
  done
  if [ -s "$DAYS/archive/$tag.tgz" ]; then d=$nxt; continue; fi
  a=$(inc_of "$d"); b=$(inc_of "$nxt")
  if [ -z "$a" ] || [ -z "$b" ]; then log "день $d: нет копий стенда (${a:-нет $d}, ${b:-нет $nxt}) — жду"; d=$nxt; continue; fi
  w=$DAYS/tmp/$tag; rm -rf "$w"; mkdir -p "$w/parts"
  cp "$a" "$b" "$w/parts/"
  log "день $d: сборка из $(basename "$a") + $(basename "$b")"
  if "$JAVA_HOME/bin/java" -Xmx4g -jar "$JAR" --revx-assemble --from-dir="$w/parts" --out="$w/day.db" > "$w/assemble.log" 2>&1 \
     && "$JAVA_HOME/bin/java" -Xmx4g -jar "$JAR" --revx-day-prep --from=$d --to=$d --days-dir="$DAYS" \
          --revx.exec.stand-db="$w/day.db" > "$w/prep.log" 2>&1 \
     && grep -q "день $d: готов" "$w/prep.log"; then
    (cd "$DAYS" && tar -czf "archive/$tag.tgz.part" "$tag" && mv -f "archive/$tag.tgz.part" "archive/$tag.tgz")
    log "день $d: готов, архив $(du -h "$DAYS/archive/$tag.tgz" | cut -f1)"
    rm -rf "$w"
    # рабочий день жмётся сжатием NTFS (LZX, ~25 МБ из ~430): чтение прозрачное, mmap работает,
    # одновременные прогоны делят страницы в памяти. Файлы дня после подготовки не меняются.
    lzx "$DAYS/$tag"
  else
    log "ОШИБКА: день $d не подготовлен, журналы в $w"; FAIL=1
    rm -f "$w/day.db" "$w/day.db-wal" "$w/day.db-shm"
  fi
  d=$nxt
done
exit $FAIL
