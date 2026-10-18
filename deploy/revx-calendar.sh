#!/bin/bash
# Календарь статистики для защиты ботов (NewsGuard, 17.10.2026). Крон на bot-arm раз в час:
#   7 * * * * /home/ubuntu/revx-calendar.sh >> /home/ubuntu/revx-calendar.log 2>&1
# Недельный файл ForexFactory → ~/revx-shared/calendar.json (ботам: -Drevx.exec.news-file=...).
# ⚠️ Без заголовка браузера источник молча висит. Битый или пустой ответ файл НЕ заменяет:
# лучше вчерашний календарь, чем никакого (сводка предупредит, если он старше 26 ч).
set -u
DST=/home/ubuntu/revx-shared/calendar.json
TMP=$(mktemp /home/ubuntu/revx-shared/.calendar.XXXXXX)
if timeout 30 curl -sf -A "Mozilla/5.0" -o "$TMP" https://nfs.faireconomy.media/ff_calendar_thisweek.json \
   && python3 -c "import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if isinstance(d,list) and len(d)>0 else 1)" "$TMP"; then
  chmod 644 "$TMP"
  mv -f "$TMP" "$DST"
  echo "$(date -u +%FT%TZ) ok $(stat -c %s "$DST") байт"
else
  rm -f "$TMP"
  echo "$(date -u +%FT%TZ) НЕ ОБНОВЛЁН — оставлен прежний"
fi
