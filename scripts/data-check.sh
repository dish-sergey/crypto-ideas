#!/bin/bash
# Ежедневная проверка сбора данных (25.10.2026): живы ли сборщики, свежи ли последние записи,
# сделались ли бэкапы, хватает ли дисков, дошёл ли забор на локальный диск. Только чтение.
# Что проверяется и почему — deploy/DATA-INVENTORY.md. Отчёт — в C:/data-archive/checks и в
# Telegram сводного бота (rev_info_bot): одна строка «всё в порядке» или список того, что сломано.
#   bash scripts/data-check.sh          — проверить и отправить
#   NOTIFY=0 bash scripts/data-check.sh — только напечатать
set -u
ROOT=${DATA_ARCHIVE:-/c/data-archive}
KEYDIR=${KEYDIR:-$HOME/.ssh/data-keys}
REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/checks/check-$(date +%Y%m%d-%H%M).txt
mkdir -p "$ROOT/checks"
SSH_OPTS=(-o LogLevel=ERROR -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=accept-new)
MICRO=ubuntu@89.168.115.160; MICRO2=ubuntu@92.5.1.233; ARM=ubuntu@130.61.31.216

# Удалённая часть: печатает строки «OK|FAIL|WARN;что;подробно». Пороги — в DATA-INVENTORY.md.
# fresh <имя> <возраст_с> <порог_с> — общий для всех серверов вывод свежести.
COMMON='now=$(date +%s)
fresh() { if [ -z "$2" ] || [ "$2" = "-" ]; then echo "FAIL;$1;нет данных"; elif [ "$2" -le "$3" ]; then echo "OK;$1;$(($2/60)) мин назад"; else echo "FAIL;$1;последняя запись $(($2/60)) мин назад (порог $(($3/60)))"; fi; }
svc() { for u in "$@"; do s=$(systemctl is-active $u); [ "$s" = active ] && echo "OK;служба $u;active" || echo "FAIL;служба $u;$s"; done; }
disk() { p=$(df --output=pcent ~ | tail -1 | tr -dc 0-9); f=$(df -h --output=avail ~ | tail -1 | tr -d " "); [ $p -lt 85 ] && echo "OK;диск;свободно $f ($p% занято)" || echo "FAIL;диск;свободно $f ($p% занято)"; }
age_ms() { [ -n "$1" ] && [ "$1" != "" ] && echo $(( now - $1/1000 )) || echo -; }
age_file() { f=$(ls -t $1 2>/dev/null | head -1); [ -n "$f" ] && echo $(( now - $(stat -c %Y "$f") )) || echo -; }
Q() { timeout 300 nice -n 19 ionice -c3 sqlite3 -readonly "$@" 2>/dev/null; }'

MICRO_CHECK='
svc crypto-data litestream bnb-book kr-book bnb-deriv s5-live
D=~/crypto-data/data/crypto.db
for ex in binance bybit okx; do fresh "ликвидации $ex" $(age_ms $(Q $D "select max(ts) from liquidations where exchange=\"$ex\"")) 3600; done
fresh "свечи" $(age_ms $(Q $D "select max(open_time) from candles")) 7200
fresh "открытый интерес" $(age_ms $(Q $D "select max(ts) from open_interest")) 3600
fresh "фандинг" $(age_ms $(Q $D "select max(available_at) from funding")) 43200
fresh "Kraken тикеры" $(age_ms $(Q $D "select max(ts) from kraken_ticker")) 7200
fresh "новости" $(age_ms $(Q $D "select max(available_at) from news_items")) 43200
fresh "календарь (загрузка)" $(age_ms $(Q $D "select max(available_at) from calendar_events")) 172800
d=$(Q $D "select max(day) from onchain_daily"); [ -n "$d" ] && fresh "ончейн" $(( now - $(date -d "$d" +%s) )) 259200 || echo "FAIL;ончейн;нет данных"
fresh "захват книги Бинанса" $(age_file "$HOME/binance-book/book-*") 600
fresh "захват книги Kraken" $(age_file "$HOME/kraken-book/book-*") 600
for d in perp perpbook spot oi; do fresh "запись Бинанса: $d" $(age_file "$HOME/binance-deriv/$d/book-*") 600; done
fresh "инкремент стенда (revx-backup)" $(age_file "$HOME/revx-backups/revx-inc-*.db.gz") 93600
s=$(stat -c %s $(ls -t ~/revx-backups/revx-inc-*.db.gz | head -1)); [ $s -gt 20000000 ] && echo "OK;размер инкремента;$((s/1000000)) МБ" || echo "FAIL;размер инкремента;$((s/1000000)) МБ — подозрительно мало"
e=$(sudo journalctl -u litestream --since "-24h" --no-pager 2>/dev/null | grep -ciE "error|fail"); [ "${e:-0}" -lt 20 ] && echo "OK;litestream за сутки;ошибок $e" || echo "WARN;litestream за сутки;ошибок $e"
disk'

ARM_CHECK='
svc revx-collect revx-venue revx-bnb-feed revx-info
R=~/revx/data/revx.db
fresh "книга Revolut X" $(age_ms $(Q $R "select max(t_recv_ms) from revx_book")) 300
fresh "лента Revolut X" $(age_ms $(Q $R "select max(ingest_ms) from revx_trade")) 1800
fresh "цена Бинанса для ботов (bnb.db)" $(age_ms $(Q ~/revx-shared/bnb.db "select max(ts_ms) from bnb_tick")) 300
fresh "читатель площадки (venue.db)" $(age_file "$HOME/revx-shared/venue.db*") 900
fresh "архив журналов ботов" $(age_file "$HOME/revx-journal-archive/*.db.gz") 93600
fresh "календарь для ботов" $(age_file "$HOME/revx-shared/calendar.json") 7200
disk'

MICRO2_CHECK='
svc revx-sim
fresh "книга симуляции (5 альтов)" $(age_ms $(Q ~/revx/data/revx-sim.db "select max(t_recv_ms) from revx_book")) 300
fresh "инкремент симуляции" $(age_file "$HOME/revx-sim-backups/revx-sim-inc-*.db.gz") 93600
disk'

run() {  # $1 метка, $2 ключ, $3 хост, $4 проверки
  local r
  r=$(ssh -i "$2" "${SSH_OPTS[@]}" "$3" "bash -s" <<< "$COMMON
$4" 2>/dev/null) || r="FAIL;связь;сервер не ответил"
  [ -n "$r" ] || r="FAIL;связь;пустой ответ"
  sed "s/^/$1;/" <<< "$r"
}

{
run micro   "$KEYDIR/micro"  $MICRO  "$MICRO_CHECK"
run bot-arm "$KEYDIR/arm"    $ARM    "$ARM_CHECK"
run micro2  "$KEYDIR/micro2" $MICRO2 "$MICRO2_CHECK"
# Локальный забор: сегодняшний лог без ошибок и свежие файлы.
lp=$(ls -t "$ROOT"/checks/pull-*.log 2>/dev/null | head -1)
if [ -z "$lp" ]; then echo "local;FAIL;забор на диск;ни одного запуска"
elif [ $(( $(date +%s) - $(stat -c %Y "$lp") )) -gt 129600 ]; then echo "local;FAIL;забор на диск;последний $(basename "$lp")"
elif grep -q 'ОШИБКА' "$lp"; then echo "local;FAIL;забор на диск;$(grep -c 'ОШИБКА' "$lp") ошибок в $(basename "$lp")"
else echo "local;OK;забор на диск;$(basename "$lp") без ошибок"; fi
for d in revx-inc revx-sim-inc binance-book kraken-book journal-archive binance-deriv/perp binance-deriv/perpbook binance-deriv/spot binance-deriv/oi; do
  f=$(ls -t "$ROOT/$d" 2>/dev/null | grep -v sha256 | head -1)
  if [ -z "$f" ]; then echo "local;FAIL;копия $d;пусто"; else a=$(( ($(date +%s) - $(stat -c %Y "$ROOT/$d/$f")) / 3600 )); [ $a -le 50 ] && echo "local;OK;копия $d;свежий файл $a ч назад" || echo "local;FAIL;копия $d;свежий файл $a ч назад"; fi
done
p=$(df --output=pcent "$ROOT" | tail -1 | tr -dc 0-9); [ $p -lt 90 ] && echo "local;OK;локальный диск;занято $p%" || echo "local;FAIL;локальный диск;занято $p%"
} > "$OUT"

bad=$(grep -cE '^[^;]*;(FAIL|WARN);' "$OUT")
column -t -s';' "$OUT"
echo "--- сломано: $bad, отчёт $OUT"

if [ "${NOTIFY:-1}" = 1 ]; then
  T=$REPO/telegram/revx_bot.txt
  tok=$(awk '/rev_info_bot/{f=1} f&&/access_token/{print $2; exit}' "$T")
  chat=$(awk '/rev_info_bot/{f=1} f&&/chat_id/{print $2; exit}' "$T")
  if [ "$bad" = 0 ]; then msg="✅ Данные: всё в порядке ($(grep -c ';OK;' "$OUT") проверок)."
  else msg="⚠️ Данные: сломано $bad
$(grep -E ';(FAIL|WARN);' "$OUT" | awk -F';' '{printf "• %s · %s: %s\n", $1, $3, $4}')"; fi
  [ -n "$tok" ] && curl -s -o /dev/null --max-time 20 "https://api.telegram.org/bot$tok/sendMessage" \
     --data-urlencode "chat_id=$chat" --data-urlencode "text=$msg" || echo "Telegram: не отправлено"
fi
exit 0
