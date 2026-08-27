#!/usr/bin/env bash
# Тревога по ТЕМПУ сбора стенда Revolut X, а не по факту записи.
#
# Зачем отдельная проверка. 25-26.08.2026 зависший ночной снимок уронил сбор с 720
# снимков в час до 18 и держал так 33 часа. Всё это время сервис был `active`,
# рестартов ноль, 429 ноль, health-файл обновлялся раз в секунду — то есть все
# существовавшие проверки показывали «здоров». Потеряно окно, которое не
# восстановить. Проверять надо не «пишем ли», а «пишем ли достаточно часто».
#
# Запускается ПО КРОНУ НА MICRO (там же лежит ключ к ARM и токен бота).
# Ничего не чинит сам: гасить сбор автоматикой опаснее, чем разбудить человека.

set -euo pipefail

ARM_HOST="${ARM_HOST:-130.61.31.216}"
ARM_KEY="${ARM_KEY:-$HOME/.ssh/armkey}"
SYMBOL="${SYMBOL:-BTC/USDC}"
WINDOW_SECONDS="${WINDOW_SECONDS:-600}"
# Норма — 12 снимков в минуту (период 5 с). Порог 60% нормы: ловит деградацию,
# но не срабатывает на штатных паузах вроде рестарта сервиса.
MIN_ROWS="${MIN_ROWS:-72}"
STATE="${STATE:-$HOME/.revx-rate-state}"
BOT_FILE="${BOT_FILE:-$HOME/s5/telegram/s5_bot.txt}"

alert() {
    local text="$1"
    echo "$(date -u +%FT%TZ) ТРЕВОГА: $text"
    # Бот тот же, что у S5 — но только ОТПРАВКА. getUpdates отсюда не вызывается,
    # поэтому конфликта с живым слушателем S5 не возникает (см. deploy/SERVERS.md).
    if [ -r "$BOT_FILE" ]; then
        local token chat
        # Формат файла — `ключ: значение`, и сам токен содержит ':', поэтому берётся
        # всё после ПЕРВОГО двоеточия (так же читает S5TelegramConfig).
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
            --data-urlencode "text=🔧 СТЕНД revx (это НЕ S5): $text" \
            "https://api.telegram.org/bot$token/sendMessage" || true
    fi
}

query="select count(*) from revx_book where symbol='$SYMBOL'
       and t_sent_ms > (strftime('%s','now') - $WINDOW_SECONDS) * 1000;"

if ! rows=$(ssh -i "$ARM_KEY" -o BatchMode=yes -o ConnectTimeout=20 "ubuntu@$ARM_HOST" \
        "sqlite3 -readonly ~/revx/data/revx.db \"$query\"" 2>/dev/null); then
    rows=""
fi

previous=$(cat "$STATE" 2>/dev/null || echo ok)

if [ -z "$rows" ]; then
    [ "$previous" = "unreachable" ] || alert "ARM не отвечает или база недоступна"
    echo unreachable > "$STATE"
    exit 0
fi

if [ "$rows" -lt "$MIN_ROWS" ]; then
    # Повторно не будим: одна тревога на эпизод, вторая — когда восстановится.
    [ "$previous" = "low" ] || alert "темп сбора $rows снимков за $((WINDOW_SECONDS/60)) мин при норме $((WINDOW_SECONDS/5)) (порог $MIN_ROWS). Проверить: ps -eo pid,etime,args | grep sqlite3 на ARM"
    echo low > "$STATE"
    exit 0
fi

if [ "$previous" != "ok" ]; then
    alert "темп восстановился: $rows снимков за $((WINDOW_SECONDS/60)) мин"
fi
echo ok > "$STATE"
echo "$(date -u +%FT%TZ) темп в норме: $rows за $((WINDOW_SECONDS/60)) мин"
