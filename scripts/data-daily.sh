#!/bin/bash
# Задание Windows crypto-data-daily (25.10.2026): забор на локальный диск, потом проверка и отчёт в Telegram.
cd "$(dirname "$0")/.." || exit 1
bash scripts/data-pull.sh
bash scripts/data-check.sh
