#!/usr/bin/env bash
# Тревога по ТЕМПУ захвата bookTicker Бинанса.
#
# ЗАЧЕМ. 16.09.2026 в 18:01 websocat умер, а служба осталась `active`: конвейер
# ждал суточного `sleep`, и захват молчал 23 часа. Никто не узнал, потому что
# смотреть было нечем — ни одной проверки на этот сбор не существовало. Данные
# за это окно не восстановить: bookTicker спота Бинанс в архив не отдаёт
# (только сделки и свечи), то есть каждый пропущенный час — дыра навсегда
# (принцип 4).
#
# Причина того сбоя исправлена (FIFO вместо `sleep`, `--ping-timeout`, `-E`), но
# правило то же, что у проверки темпа стенда: проверять надо не «жива ли
# служба», а «идут ли данные». Служба была жива всё время.
#
# Запускается ПО КРОНУ НА MICRO — там же лежит файл захвата и токен бота.
# Ничего не чинит сам: перезапуск автоматикой опаснее, чем разбудить человека.

set -euo pipefail

DIR="${DIR:-$HOME/binance-book}"
# Порог по ВОЗРАСТУ последней строки, а не по числу сообщений: поток
# bookTicker неровный (66/с в движении, единицы в затишье), а вот пауза
# дольше пары минут не бывает штатной — проверено живым обрывом 17.09.2026:
# восстановление заняло 3.3 секунды.
MAX_AGE_SECONDS="${MAX_AGE_SECONDS:-300}"
STATE="${STATE:-$HOME/.bnb-rate-state}"
# Ярлык в тревоге: тот же скрипт сторожит и захват Kraken (LABEL, DIR, STATE).
LABEL="${LABEL:-ЗАХВАТ БИНАНСА}"
SERVICE="${SERVICE:-bnb-book}"
BOT_FILE="${BOT_FILE:-$HOME/s5/telegram/s5_bot.txt}"

alert() {
    local text="$1"
    echo "$(date -u +%FT%TZ) ТРЕВОГА: $text"
    # Бот тот же, что у S5, но только ОТПРАВКА: getUpdates отсюда не зовётся,
    # поэтому конфликта с живым слушателем S5 нет (см. deploy/SERVERS.md).
    if [ -r "$BOT_FILE" ]; then
        local token chat
        token=$(grep -iE '^[[:space:]]*(access_token|token|s5_telegram_token)[[:space:]]*:' "$BOT_FILE" \
            | head -1 | cut -d: -f2- | tr -d ' \r\n')
        chat=$(grep -iE '^[[:space:]]*[^:]*chat[^:]*[[:space:]]*:' "$BOT_FILE" \
            | head -1 | cut -d: -f2- | tr -d ' \r\n')
        if [ -z "$token" ] || [ -z "$chat" ]; then
            echo "не разобрать $BOT_FILE — тревога только в лог"
            return
        fi
        curl -sS -o /dev/null --max-time 20 \
            --data-urlencode "chat_id=$chat" \
            --data-urlencode "text=🔧 $LABEL (это НЕ S5): $text" \
            "https://api.telegram.org/bot$token/sendMessage" || true
    fi
}

previous=$(cat "$STATE" 2>/dev/null || echo ok)

# Самый свежий незажатый файл: обычно сегодняшний, но на границе суток свежим
# может быть и вчерашний, если поток в этот момент затих.
newest=$(ls -t "$DIR"/book-*.jsonl 2>/dev/null | head -1 || true)
if [ -z "$newest" ]; then
    [ "$previous" = "nofile" ] || alert "в $DIR нет ни одного открытого файла захвата — служба $SERVICE не пишет вовсе"
    echo nofile > "$STATE"
    exit 0
fi

# Возраст берётся из ОТМЕТКИ В СТРОКЕ, а не из mtime файла: mtime обновляет
# любая запись, а нам важно, что записано именно сообщение с биржи.
last_ms=$(tail -1 "$newest" | cut -d' ' -f1)
case "$last_ms" in
    ''|*[!0-9]*)
        [ "$previous" = "garbage" ] || alert "последняя строка $newest без отметки времени — формат захвата сломан"
        echo garbage > "$STATE"
        exit 0
        ;;
esac

age=$(( ( $(date -u +%s%3N) - last_ms ) / 1000 ))

if [ "$age" -gt "$MAX_AGE_SECONDS" ]; then
    [ "$previous" = "stale" ] || alert "тишина $((age / 60)) мин (порог $((MAX_AGE_SECONDS / 60))): последняя строка $(date -u -d "@$((last_ms / 1000))" +%FT%TZ) в $(basename "$newest"). Проверить: systemctl status $SERVICE, ps -C websocat"
    echo stale > "$STATE"
    exit 0
fi

if [ "$previous" != "ok" ]; then
    alert "захват восстановился: последняя строка $age с назад"
fi
echo ok > "$STATE"
echo "$(date -u +%FT%TZ) захват в норме: последняя строка $age с назад ($(basename "$newest"))"
