#!/bin/bash
# Запись разрыва резерва площадки раз в минуту (25.11.2026): почему растёт запертый USDC — площадка запирает чаще, чем отпускает.
# Только чтение ~/revx-shared/venue.db (читатель площадки). Строка: время_мс,валюта,резерв,видимо,разрыв,свободно,всего
DB=/home/ubuntu/revx-shared/venue.db; OUT=/home/ubuntu/revx-bnb/gap-log.csv
[ -f "$OUT" ] || echo "ts_ms,currency,reserved,visible,gap,available,total" > "$OUT"
sqlite3 -csv "file:$DB?mode=ro" "SELECT CAST(strftime('%s','now') AS INTEGER)*1000, r.currency, round(b.reserved,4), round(r.visible,4), round(r.gap,4), round(b.available,4), round(b.total,4) FROM reserve_gap r JOIN balance b ON b.currency=r.currency WHERE r.currency IN ('USDC','BTC','ETH','SOL')" >> "$OUT"
