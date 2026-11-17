#!/bin/bash
# Восстановить рабочие дни стенда из переносимой копии (новая машина, другой диск, потерянный каталог):
#   bash scripts/days-open.sh 2026-08-27 2026-11-16
# Из $DAYS/archive/YYYYMMDD.tgz в $DAYS/YYYYMMDD/ и сжатие NTFS (LZX). Уже лежащие дни не трогает.
# В обычной работе не нужен: scripts/days-build.sh сам кладёт рабочий день сжатым (17.11.2026).
set -u
DAYS=${DAYS:-/d/revx-days}
from=$1; to=${2:-$1}; d=$from; n=0
while [[ "$d" < "$to" || "$d" == "$to" ]]; do
  tag=$(date -d "$d" +%Y%m%d)
  if [ ! -d "$DAYS/$tag" ]; then
    if [ -s "$DAYS/archive/$tag.tgz" ]; then
      (cd "$DAYS" && tar -xzf "archive/$tag.tgz") \
        && cmd //c "compact /c /exe:lzx /s:$(cygpath -w "$DAYS/$tag") /i /q" > /dev/null && n=$((n+1))
    else
      echo "НЕТ дня $d — его копии нет и в архиве"
    fi
  fi
  d=$(date -d "$d +1 day" +%F)
done
echo "восстановлено дней: $n"
