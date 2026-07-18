# crypto-ideas — слой данных торговой системы

Коллекторы бесплатных источников данных по спецификации (Google Drive
«Trading Bot — Спецификация», док. 09). Хранилище — SQLite (`data/crypto.db`),
у каждой записи время события + `available_at` для честного бэктеста.

## Быстрый старт

```bash
./gradlew test                       # юнит-тесты парсеров
./gradlew bootRun                    # постоянный режим: планировщик + WS-ликвидации
```

Разовые команды:

```bash
./gradlew bootRun --args='--collect=all'
./gradlew bootRun --args='--backfill=ohlcv --symbols=BTCUSDT,ETHUSDT --interval=1d --from=2019-01-01'
./gradlew bootRun --args='--backfill=ohlcv --symbols=BTCUSDT --interval=1m --from=2023-01-01'
./gradlew bootRun --args='--backfill=funding-okx'   # внимание: OKX хранит только ~3 мес
./gradlew bootRun --args='--backfill=onchain --from=2015-07-01'
```

Первый запуск: сначала `--backfill=...` для истории, затем постоянный `bootRun`.
Важно: OI, ликвидации и funding OKX (глубже ~3 мес) ретроспективно недоступны —
планировщик должен работать постоянно с первого дня (док. 09 §4).
Известные ограничения источников — в `CLAUDE.md`.

Настройки (символы, метрики, фиды) — `src/main/resources/application.properties`.
Подробности архитектуры — `CLAUDE.md`.
