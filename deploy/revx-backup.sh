#!/usr/bin/env bash
# Ночная копия базы стенда Revolut X с bot-arm на micro.
#
# Зачем: revx.db живёт только на ARM, а данные книги невосполнимы задним числом
# (ТЗ §3.5). Litestream сюда не годится — он реплицирует crypto.db на micro в B2
# с free-лимитом 10 ГБ, и подмешивать туда поток стенда нельзя.
#
# Запускается ПО КРОНУ НА MICRO (у micro есть ~/.ssh/armkey, обратного ключа нет).
# Снимок делается через sqlite3 .backup — копировать файл БД под нагрузкой нельзя,
# получится битый WAL.

set -euo pipefail

ARM_HOST="${ARM_HOST:-130.61.31.216}"
ARM_KEY="${ARM_KEY:-$HOME/.ssh/armkey}"
DEST_DIR="${DEST_DIR:-$HOME/revx-backups}"
KEEP_DAYS="${KEEP_DAYS:-7}"

mkdir -p "$DEST_DIR"
stamp=$(date -u +%Y%m%d)

ssh -i "$ARM_KEY" -o BatchMode=yes -o ConnectTimeout=20 "ubuntu@$ARM_HOST" \
    'set -e; rm -f /tmp/revx-snap.db /tmp/revx-snap.db.gz;
     sqlite3 ~/revx/data/revx.db ".backup /tmp/revx-snap.db";
     gzip -9 /tmp/revx-snap.db'

scp -i "$ARM_KEY" -o BatchMode=yes "ubuntu@$ARM_HOST:/tmp/revx-snap.db.gz" \
    "$DEST_DIR/revx-$stamp.db.gz"

ssh -i "$ARM_KEY" -o BatchMode=yes "ubuntu@$ARM_HOST" 'rm -f /tmp/revx-snap.db.gz'

# Ротация: держим последние KEEP_DAYS копий
find "$DEST_DIR" -name 'revx-*.db.gz' -mtime "+$KEEP_DAYS" -delete

echo "$(date -u +%FT%TZ) копия готова: $DEST_DIR/revx-$stamp.db.gz ($(du -h "$DEST_DIR/revx-$stamp.db.gz" | cut -f1))"
