#!/bin/bash
# Забор невосполнимых данных с серверов на локальный диск (25.10.2026). Запускается раз в сутки
# заданием Windows вместе с scripts/data-check.sh; можно и руками: bash scripts/data-pull.sh
#
# Что и откуда — deploy/DATA-INVENTORY.md. Качается только то, чего локально нет или что
# отличается размером; сжатое проверяется (gzip -t / sha256), битое удаляется и берётся снова.
# Сервер ничего не теряет от этого скрипта: только ls, scp и (раз в неделю) VACUUM INTO в /tmp.
#
# ⚠️ Хранение на серверах короткое: инкременты симуляции на micro2 — 3 суток, захват книг
# Бинанса и Kraken на micro — 45, инкременты стенда на micro — 60. Пропуск забора дольше
# этого — потеря навсегда (принцип 4).
set -u
ROOT=${DATA_ARCHIVE:-/c/data-archive}
KEYDIR=${KEYDIR:-$HOME/.ssh/data-keys}
LOG=$ROOT/checks/pull-$(date +%Y%m%d).log
mkdir -p "$ROOT"/{bot-accounting,revx-inc,revx-sim-inc,binance-book,kraken-book,journal-archive,crypto-db,checks} "$ROOT"/binance-deriv/{perp,perpbook,spot,oi} "$ROOT"/binance-depth/{spot,perp,spotsnap,perpsnap} "$KEYDIR"
exec > >(tee -a "$LOG") 2>&1

# Ключи: ssh под Windows не берёт файл с широкими правами, поэтому — копия без CR с chmod 600.
key() {  # $1 имя, $2 исходный путь
  local k=$KEYDIR/$1
  [ -s "$k" ] || { tr -d '\r' < "$2" > "$k"; chmod 600 "$k"; }
  echo "$k"
}
KMICRO=$(key micro /d/servers/oracle/instance-20260722-1110/ssh-key-2026-07-22.key)
KMICRO2=$(key micro2 /d/servers/oracle/instance-20260819-2319/ssh-key-2026-08-19.key)
KARM=$(key arm "${ARM_KEY_SRC:-/d/servers/oracle/arm1/private.key}")
MICRO=ubuntu@89.168.115.160; MICRO2=ubuntu@92.5.1.233; ARM=ubuntu@130.61.31.216
SSH_OPTS=(-o LogLevel=ERROR -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=accept-new)

FAIL=0
note() { echo "$(date +%H:%M:%S) $*"; }

# $1 ключ, $2 хост, $3 удалённый каталог, $4 маска, $5 локальный каталог, $6 проверка (gz|sha|none)
pull() {
  local k=$1 h=$2 rdir=$3 mask=$4 ldir=$5 how=$6 n=0 bad=0
  local list
  list=$(ssh -i "$k" "${SSH_OPTS[@]}" "$h" "cd $rdir 2>/dev/null || exit 3; stat -c '%s %n' $mask 2>/dev/null; exit 0") || { note "ОШИБКА: $h:$rdir не читается"; FAIL=1; return; }
  while read -r size name; do
    [ -n "$name" ] || continue
    local f=$ldir/$name
    [ -f "$f" ] && [ "$(stat -c %s "$f")" = "$size" ] && continue
    scp -q -i "$k" "${SSH_OPTS[@]}" "$h:$rdir/$name" "$f.part" || { note "ОШИБКА: не скачался $name"; FAIL=1; rm -f "$f.part"; continue; }
    if [ "$how" = gz ] && ! gzip -t "$f.part" 2>/dev/null; then note "ОШИБКА: битый $name"; rm -f "$f.part"; FAIL=1; bad=$((bad+1)); continue; fi
    if [ "$how" = sha ]; then
      scp -q -i "$k" "${SSH_OPTS[@]}" "$h:$rdir/$name.sha256" "$f.sha256" 2>/dev/null
      if [ -s "$f.sha256" ] && [ "$(sha256sum "$f.part" | cut -c1-64)" != "$(cut -c1-64 "$f.sha256")" ]; then
        note "ОШИБКА: sha256 не сошёлся у $name"; rm -f "$f.part"; FAIL=1; bad=$((bad+1)); continue
      fi
    fi
    mv -f "$f.part" "$f"; n=$((n+1))
  done <<< "$list"
  note "$h:$rdir → $ldir: новых $n, битых $bad, всего локально $(ls "$ldir" | grep -vc '\.sha256$')"
}

note "=== забор данных, корень $ROOT"
pull "$KMICRO"  $MICRO  '~/revx-backups'          'revx-inc-*.db.gz'        "$ROOT/revx-inc"        gz
pull "$KMICRO2" $MICRO2 '~/revx-sim-backups'      'revx-sim-inc-*.db.gz'    "$ROOT/revx-sim-inc"    sha
pull "$KMICRO"  $MICRO  '~/binance-book'          'book-*.jsonl.gz'         "$ROOT/binance-book"    gz
pull "$KMICRO"  $MICRO  '~/kraken-book'           'book-*.jsonl.gz'         "$ROOT/kraken-book"     gz
pull "$KARM"    $ARM    '~/revx-journal-archive'  '*.db.gz'                 "$ROOT/journal-archive" gz
# Запись деривативов и спота Бинанса (шаг Б док. 197, с 25.10.2026): на micro хранится 45 суток.
for d in perp perpbook spot oi; do
  pull "$KMICRO" $MICRO "~/binance-deriv/$d" 'book-*.jsonl.gz' "$ROOT/binance-deriv/$d" gz
done
# Полный стакан Бинанса (bnb-depth, с 12.11.2026): изменения и снимки спота и перпа BTC/ETH/SOL, ~1 ГБ/сут сжатого.
# ⚠️ На micro хранится только 7 суток: пропуск забора дольше недели — дыра навсегда.
for d in spot perp spotsnap perpsnap; do
  pull "$KMICRO" $MICRO "~/binance-depth/$d" 'book-*.jsonl.gz' "$ROOT/binance-depth/$d" gz
done

# Каждый день — снимок УЧЁТА ботов с bot-arm (17.11.2026): exec_fill / exec_event / exec_state каждого
# журнала (не чистятся, живут только на ARM), плюс alloc.db (реестр и пул), venue.db, hedge_paper.db.
# Запросы и котировки сюда не идут — они уходят в journal-archive. exec_open_order не берём: он
# восстанавливается из запросов, а весит ~300 МБ на бота. Читается через mode=ro, 0.2 с на журнал.
mkdir -p "$ROOT/bot-accounting"
a=acct-$(date +%Y%m%d)
if [ ! -s "$ROOT/bot-accounting/$a.tar.gz" ]; then
  if ssh -i "$KARM" "${SSH_OPTS[@]}" $ARM "set -e; rm -rf /tmp/$a; mkdir /tmp/$a; cd ~
      for f in revx-exec*/state/exec*.db; do case \$f in *manual-test*) continue;; esac
        o=/tmp/$a/\$(dirname \$(dirname \$f))--\$(basename \$f)
        nice -n 19 sqlite3 \$o \"ATTACH 'file:/home/ubuntu/\$f?mode=ro' AS s; CREATE TABLE exec_fill AS SELECT * FROM s.exec_fill; CREATE TABLE exec_event AS SELECT * FROM s.exec_event; CREATE TABLE exec_state AS SELECT * FROM s.exec_state;\"
      done
      for f in alloc venue hedge_paper; do nice -n 19 sqlite3 \"file:/home/ubuntu/revx-shared/\$f.db?mode=ro\" \"VACUUM INTO '/tmp/$a/\$f.db'\"; done
      cd /tmp && nice -n 19 tar -czf $a.tar.gz $a && rm -rf /tmp/$a" \
     && scp -q -i "$KARM" "${SSH_OPTS[@]}" "$ARM:/tmp/$a.tar.gz" "$ROOT/bot-accounting/$a.tar.gz.part" \
     && tar -tzf "$ROOT/bot-accounting/$a.tar.gz.part" >/dev/null; then
    mv -f "$ROOT/bot-accounting/$a.tar.gz.part" "$ROOT/bot-accounting/$a.tar.gz"
    note "учёт ботов: $a.tar.gz ($(du -h "$ROOT/bot-accounting/$a.tar.gz" | cut -f1), $(tar -tzf "$ROOT/bot-accounting/$a.tar.gz" | grep -c '\.db$') баз)"
    # таблицы учёта только растут, поэтому свежий снимок содержит всё; старые — от порчи.
    # Держим 30 последних суток и первый снимок каждого месяца.
    ls "$ROOT/bot-accounting"/acct-*.tar.gz | sort | head -n -30 | while read -r f; do
      case $(basename "$f") in acct-??????01.tar.gz) ;; *) rm -f "$f";; esac; done
  else
    note "ОШИБКА: снимок учёта ботов не снят"; FAIL=1; rm -f "$ROOT/bot-accounting/$a.tar.gz.part"
  fi
  ssh -i "$KARM" "${SSH_OPTS[@]}" $ARM "rm -rf /tmp/$a /tmp/$a.tar.gz" 2>/dev/null
fi

# Раз в неделю — снимок базы слоя данных (её копирует и Litestream в B2; локальная — на случай,
# если пропадёт и бакет). VACUUM INTO берёт согласованный срез без перезапусков, как у .backup.
last=$(ls -t "$ROOT/crypto-db"/crypto-*.db.gz 2>/dev/null | head -1)
if [ -z "$last" ] || [ $(( $(date +%s) - $(stat -c %Y "$last") )) -gt $((6*86400)) ]; then
  s=crypto-$(date +%Y%m%d).db
  if ssh -i "$KMICRO" "${SSH_OPTS[@]}" $MICRO "rm -f /tmp/$s /tmp/$s.gz; nice -n 19 ionice -c3 sqlite3 ~/crypto-data/data/crypto.db \"VACUUM INTO '/tmp/$s'\" && nice -n 19 gzip -1 /tmp/$s" \
     && scp -q -i "$KMICRO" "${SSH_OPTS[@]}" "$MICRO:/tmp/$s.gz" "$ROOT/crypto-db/$s.gz.part" && gzip -t "$ROOT/crypto-db/$s.gz.part"; then
    mv -f "$ROOT/crypto-db/$s.gz.part" "$ROOT/crypto-db/$s.gz"; note "снимок crypto.db: $s.gz ($(du -h "$ROOT/crypto-db/$s.gz" | cut -f1))"
    ls -t "$ROOT/crypto-db"/crypto-*.db.gz | tail -n +5 | xargs -r rm -f      # держим четыре недели
  else
    note "ОШИБКА: снимок crypto.db не снят"; FAIL=1; rm -f "$ROOT/crypto-db/$s.gz.part"
  fi
  ssh -i "$KMICRO" "${SSH_OPTS[@]}" $MICRO "rm -f /tmp/$s /tmp/$s.gz" 2>/dev/null
fi

note "локальный диск: свободно $(df -h "$ROOT" | awk 'NR==2{print $4}')"
note "=== забор кончился, ошибок: $FAIL"
exit $FAIL
