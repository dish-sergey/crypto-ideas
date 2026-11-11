#!/usr/bin/env bash
# Полный стакан Бинанса BTC / ETH / SOL (12.11.2026, вопрос владельца: сколько в книге НЕПОДВИЖНЫХ заявок, которые
# поглощают поток, и правда ли отскок начинается, когда их ставят; пилот на книге Revolut — docs/239, d277/Static242).
#
# Зачем полный, а не depth5/depth20: 20 уровней BTC — это пара базисных пунктов, а неподвижные деньги стоят в
# 25–100 б.п. от середины. Книгу восстанавливаем офлайн: СНИМОК (REST, глубина 5000 у спота / 1000 у перпа) плюс поток
# ИЗМЕНЕНИЙ (diff depth, `U`/`u` у спота, `U`/`u`/`pu` у перпа) — стандартная схема Бинанса «local order book».
#   ~/binance-depth/spot      — btcusdt/ethusdt/solusdt@depth@1000ms (спот, изменения раз в секунду)
#   ~/binance-depth/perp      — то же у перпа @depth@500ms
#   ~/binance-depth/spotsnap  — снимок спота limit=5000 раз в 15 мин (вес 250 × 3)
#   ~/binance-depth/perpsnap  — снимок перпа limit=1000 раз в 15 мин (вес 20 × 3)
# Строка — «мс приёма JSON», как у bnb-deriv; сегодняшний файл открыт, вчерашний жмёт bnb-prune.sh, хранение 45 суток.
#
# ⚠️ История стакана в архив Бинанса не попадает (bookDepth — только суммы по полосам ±1…5%), поэтому каждый час без
# записи — дыра навсегда (принцип 4). Грабли websocat — см. bnb-collect.sh: FIFO-держатель подписки, -E с пингами,
# дата по каждой строке.
set -u
ROOT="${ROOT:-$HOME/binance-depth}"
WS="$HOME/bin/websocat"
PING_INTERVAL="${PING_INTERVAL:-20}"
PING_TIMEOUT="${PING_TIMEOUT:-30}"
SNAP_SEC="${SNAP_SEC:-900}"

stamp() {  # $1 — каталог
    mkdir -p "$1"
    perl -MTime::HiRes=time -MPOSIX=strftime -ne '
        BEGIN { $dir = shift @ARGV; $cur = "" }
        $t = time; $d = strftime("%Y-%m-%d", gmtime($t));
        if ($d ne $cur) { close F if $cur; open(F, ">>", "$dir/book-$d.jsonl") or die "$!"; select((select(F), $| = 1)[0]); $cur = $d }
        printf F "%d %s", $t * 1000, $_;
    ' "$1"
}

ws_loop() {  # $1 — адрес, $2 — подписка, $3 — каталог
    local url=$1 subs=$2 dir=$3 fifo holder
    while true; do
        fifo="$(mktemp -u "${TMPDIR:-/tmp}/bnbdp-sub.XXXXXX")"
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

snap_loop() {  # $1 — адрес до ?symbol=, $2 — limit, $3 — каталог
    local base=$1 lim=$2 dir=$3
    while true; do
        for s in BTCUSDT ETHUSDT SOLUSDT; do
            r=$(curl -s --max-time 20 "$base?symbol=$s&limit=$lim")
            case "$r" in *lastUpdateId*) printf '{"s":"%s","snap":%s}\n' "$s" "$r" ;; *) echo "$(date -u +%FT%TZ) $dir: снимок $s не получен: ${r:0:120}" >&2 ;; esac
        done
        sleep "$SNAP_SEC"
    done | stamp "$dir"
}

# Сначала поток, потом снимок: изменения до снимка нужны, чтобы склеить книгу с его lastUpdateId.
ws_loop "wss://stream.binance.com:9443/ws" \
    '"btcusdt@depth@1000ms","ethusdt@depth@1000ms","solusdt@depth@1000ms"' "$ROOT/spot" &
ws_loop "wss://fstream.binance.com/public/stream" \
    '"btcusdt@depth@500ms","ethusdt@depth@500ms","solusdt@depth@500ms"' "$ROOT/perp" &
sleep 10
snap_loop "https://api.binance.com/api/v3/depth" 5000 "$ROOT/spotsnap" &
snap_loop "https://fapi.binance.com/fapi/v1/depth" 1000 "$ROOT/perpsnap" &
wait
