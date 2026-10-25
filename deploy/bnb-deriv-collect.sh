#!/usr/bin/env bash
# Посекундная запись деривативов и спота Бинанса по BTC, ETH, SOL (док. 197 §5, шаг Б; 25.10.2026).
#
# Зачем. Чтобы через 2–3 недели иметь свою разметку каскадов рядом с живыми исполнениями
# ботов: поток сделок перпа и спота (перевес тейкеров по секундам), марк-цена и фандинг,
# верх книги перпа и открытый интерес. Ликвидации пишет сборщик слоя данных (`crypto-data`,
# таблица `liquidations`), здесь их нет. Архив Бинанса отдаёт сделки и свечи задним числом,
# но не марк-цену по секундам, не книгу перпа и не открытый интерес чаще 5 минут.
#
# Где. На micro, а не на bot-arm: у bot-arm одно ядро и живые боты, лишняя нагрузка там
# бьёт по торговле. Время приёма — по NTP, для сверки с исполнениями хватает.
#
# Как. Четыре независимых цикла, каждый в свой каталог `~/binance-deriv/<поток>/`,
# файл `book-ГГГГ-ММ-ДД.jsonl`, строка «мс_приёма JSON». Имя `book-*` — чтобы теми же
# `bnb-prune.sh` (жмёт вчерашний, чистит старше 45 суток) и `bnb-rate-check.sh` (тревога
# по тишине) обходиться без новых скриптов: им передаётся DIR.
#
# Устройство WebSocket-цикла — как в `bnb-collect.sh` (там же история граблей): подписка
# отдельным сообщением через FIFO, `--ping-timeout` и `-E`, новая сессия через 2 с после
# обрыва. Отличие одно: метку времени и выбор файла по дате делает ОДИН процесс perl на весь
# поток, а не вызов `date` на каждое сообщение — сделки идут в разы чаще bookTicker.
set -u
ROOT="${ROOT:-$HOME/binance-deriv}"
WS="$HOME/bin/websocat"
PING_INTERVAL="${PING_INTERVAL:-20}"
PING_TIMEOUT="${PING_TIMEOUT:-30}"

# Метка приёма и файл по дате (UTC) для каждой строки; flush после каждой, чтобы обрыв
# службы не терял буфер.
stamp() {  # $1 — каталог
    mkdir -p "$1"
    perl -MTime::HiRes=time -MPOSIX=strftime -ne '
        BEGIN { $dir = shift @ARGV; $cur = "" }
        $t = time; $d = strftime("%Y-%m-%d", gmtime($t));
        if ($d ne $cur) { close F if $cur; open(F, ">>", "$dir/book-$d.jsonl") or die "$!"; select((select(F), $| = 1)[0]); $cur = $d }
        printf F "%d %s", $t * 1000, $_;
    ' "$1"
}

ws_loop() {  # $1 — адрес, $2 — подписка (через запятую, в кавычках), $3 — каталог
    local url=$1 subs=$2 dir=$3 fifo holder
    while true; do
        fifo="$(mktemp -u "${TMPDIR:-/tmp}/bnbd-sub.XXXXXX")"
        mkfifo "$fifo" || { sleep 5; continue; }
        ( echo "{\"method\":\"SUBSCRIBE\",\"params\":[$subs],\"id\":1}"; while :; do sleep 10; done ) > "$fifo" &
        holder=$!
        "$WS" -t -E --ping-interval "$PING_INTERVAL" --ping-timeout "$PING_TIMEOUT" "$url" < "$fifo" | stamp "$dir"
        kill "$holder" 2>/dev/null; wait "$holder" 2>/dev/null
        rm -f "$fifo"
        echo "$(date -u +%FT%TZ) $dir: сессия закончилась, переподключаюсь" >&2
        sleep 2
    done
}

# Открытый интерес по REST раз в 15 с (вес 1 на запрос, три запроса — ничтожно против лимита
# 2400/мин). Пустой или ошибочный ответ не пишется — дыру покажет сторож тишины.
oi_loop() {
    local dir="$ROOT/oi"
    while true; do
        for s in BTCUSDT ETHUSDT SOLUSDT; do
            r=$(curl -s --max-time 5 "https://fapi.binance.com/fapi/v1/openInterest?symbol=$s")
            case "$r" in *openInterest*) printf '%s\n' "$r" ;; esac
        done
        sleep 15
    done | stamp "$dir"
}

ws_loop "wss://fstream.binance.com/market/stream" \
    '"btcusdt@aggTrade","ethusdt@aggTrade","solusdt@aggTrade","btcusdt@markPrice@1s","ethusdt@markPrice@1s","solusdt@markPrice@1s"' \
    "$ROOT/perp" &
# ⚠️ Книга перпа — на адресе /public: на /market подписка на depth молча не отдаёт ничего
# (проверено 25.10.2026), как в своё время /stream без SUBSCRIBE.
ws_loop "wss://fstream.binance.com/public/stream" \
    '"btcusdt@depth5@500ms","ethusdt@depth5@500ms","solusdt@depth5@500ms"' \
    "$ROOT/perpbook" &
# Спот: USDT — основной объём для перевеса, USDC — пара, по которой считает опора ботов.
ws_loop "wss://stream.binance.com:9443/ws" \
    '"btcusdt@aggTrade","ethusdt@aggTrade","solusdt@aggTrade","btcusdc@aggTrade","ethusdc@aggTrade","solusdc@aggTrade"' \
    "$ROOT/spot" &
oi_loop &
wait
