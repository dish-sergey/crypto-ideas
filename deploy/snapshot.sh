#!/usr/bin/env sh
# Опционально. Холодный самодостаточный снапшот БД + gzip (WAL-safe через
# VACUUM INTO — не просто cp, иначе потеряешь -wal). Litestream уже даёт
# непрерывную репликацию и суточные снапшоты в R2; это — «якорь» для ручного
# восстановления одним файлом. Повесить на cron/таймер по желанию.
#
#   ./snapshot.sh [OUT_DIR]     (по умолчанию ./snapshots)
set -eu

STAMP=$(date -u +%Y%m%d-%H%M)
OUT_DIR=${1:-./snapshots}
mkdir -p "$OUT_DIR"

# VACUUM INTO читает живую БД в read-транзакции → консистентная копия без стопа app.
docker run --rm \
  -v "$(pwd)/../data:/data" \
  -v "$(pwd)/$OUT_DIR:/out" \
  alpine:3.20 sh -c \
  "apk add --no-cache sqlite >/dev/null && \
   sqlite3 /data/crypto.db \"VACUUM INTO '/out/crypto-$STAMP.db'\" && \
   gzip -f /out/crypto-$STAMP.db"

echo "snapshot: $OUT_DIR/crypto-$STAMP.db.gz"
