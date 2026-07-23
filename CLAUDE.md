# crypto-ideas

Слой данных торговой системы (этап 0 дорожной карты). Спецификация — в Google
Drive «Trading Bot — Спецификация», ключевые документы: 00 (архитектура),
04 §1 (правила данных), 09 (источники данных — этот проект реализует его §5).

## Стек

- Java 25 (Liberica), Gradle 9.5 (Groovy DSL), Spring Boot 4.0.6 (без web)
- SQLite (org.xerial:sqlite-jdbc), Jackson, java.net.http (включая WebSocket)

## Запуск

```
./gradlew bootRun                                   # планировщик + WS-ликвидации
./gradlew bootRun --args='--collect=all'            # разовый сбор всего и выход
./gradlew bootRun --args='--collect=funding,oi'
./gradlew bootRun --args='--collect=kraken'                      # Kraken Futures: часовой funding + tickers
./gradlew bootRun --args='--backfill=ohlcv --symbols=BTCUSDT --interval=1m --from=2023-01-01'
./gradlew bootRun --args='--backfill=funding-okx'
./gradlew bootRun --args='--backfill=onchain --from=2015-07-01'
./gradlew bootRun --args='--backfill=oi-archive --symbols=BTCUSDT,ETHUSDT --from=2021-01-01'  # ретро-OI из bulk-архива
```

База: `data/crypto.db` (WAL). Схема: `src/main/resources/schema.sql`.

## Принципы (не нарушать)

1. **Два времени у каждой записи** (док. 04 §1): время события + `available_at`
   (когда данные стали бы доступны). Бэктест читает только по `available_at`.
   Правило по источникам: свечи → close_time; funding/OI → их ts; on-chain →
   день +30ч; macro → день +1 сутки; календарь/новости/ликвидации → момент
   получения нами.
2. **Идемпотентность**: все записи — upsert по естественному PK. Повторный
   запуск коллектора не создаёт дублей и ничего не ломает.
3. **Изоляция сбоев** (док. 01 §3): падение одного источника логируется и не
   трогает остальные. Binance fapi может быть недоступен из некоторых сетей —
   это ожидаемо, funding тогда идёт только с OKX.
4. **OI и ликвидации невосполнимы** (док. 09 §4): Binance отдаёт OI только за
   30 дней, история ликвидаций бесплатно недоступна. Планировщик должен работать
   постоянно; каждый простой — дыра в данных навсегда.

## Ограничения источников (проверено на живых API 2026-07-17/18)

- **OKX funding-rate-history отдаёт только ~3 последних месяца** — запрос
  старше отдаёт пусто. `--backfill=funding-okx` глубокую историю не даст;
  глубокий funding — только Binance fapi (там, где он доступен). Funding
  тоже частично невосполним — см. принцип 4. С локальной машины fapi работает:
  вся история с первой ставки (BTC 2019-09-10) уже снята обычным `--collect=funding`.
- **Coin Metrics Community больше не отдаёт `CapRealUSD`** (HTTP 403
  «not available with supplied credentials»). Метрика убрана из конфига;
  realized cap выводится арифметически: `CapRealUSD = CapMrktCurUSD / CapMVRVCur`.
- **ForexFactory: `ff_calendar_nextweek.json` удалён** (404), остался только
  `ff_calendar_thisweek.json`. Календарь собирается по текущей неделе.
- **FRED требует HTTP/1.1 и User-Agent `curl/...`** — Java HttpClient c HTTP/2
  получает RST_STREAM, а с collector/браузерным UA запрос молча висит до
  таймаута (Akamai). Оба обхода зашиты в `ApiClient` (глобальный HTTP/1.1 +
  пер-хостовый UA); не менять UA для fred.stlouisfed.org.
- **DefiLlama `api.llama.fi/emissions` стал платным** (HTTP 402) — бесплатных
  разлоков для S5 нет; альтернативы: CryptoRank (страницы), Tokenomist ($).
- **Bulk-архив `data.binance.vision` (листинг через S3 API)**: `metrics`
  (с OI) — daily с 2020-09-01; `fundingRate` — monthly с 2020-01;
  `bookDepth` — daily с 2023-01-01; **`liquidationSnapshot` удалён совсем**
  (папки нет) — история ликвидаций бесплатно недоступна, принцип 4 в силе.
- **Binance futures WS — рабочий путь `wss://fstream.binance.com/market/stream` + SUBSCRIBE**
  (combined-формат `{"stream":..,"data":{..}}`). Устаревшие `/ws/<stream>` и `/stream`
  отдают только ack `{"result":null,"id":1}` **без данных** — это была причина нулей
  по Binance-ликвидациям (исправлено 2026-07-23). `!forceOrder@arr` — объединённый
  UM+CM поток (поле `st`: 1=UM, 2=CM). С Frankfurt-micro (ЕС) работает; с локальной
  машины хост fstream раньше молчал (geo) — сбор ликвидаций перенесён на micro.
  Bybit WS `allLiquidation` — второй, полный рыночный источник. Диагностика WS —
  `websocat` (musl-бинарь с github vi/websocat).
- **Kraken Futures** — публичный API без geo-блока и ключа. Funding здесь
  **ЧАСОВОЙ** (не 8ч), берётся через `historicalfundingrates` **API v4** (v3 →
  404), ~12 мес в одном ответе. Ставки Kraken невзаимозаменяемы с OKX/Binance
  (док. 09 §4.7) — храним отдельно в `kraken_funding` / `kraken_ticker`.

## Структура

- `core/Db` — единственный writer-connection SQLite, upsert/batch/queryLong
- `core/ApiClient` — GET с пер-хостовым rate limit и ретраями (429/5xx/IO)
- `collectors/*` — 9 REST-коллекторов, интерфейс `Collector` (name + collect):
  ohlcv, funding, oi, onchain, universe, macro, calendar, news, kraken
- `collectors/OiArchiveImporter` — не Collector, а one-shot импортёр ретро-OI
  из bulk-архива Binance Vision (`--backfill=oi-archive`)
- `ws/LiquidationWsCollector` — Binance !forceOrder + Bybit allLiquidation,
  реконнект, отдельные daemon-потоки
- `scheduling/CollectorScheduler` — расписание (док. 09 §5)
- `cli/CliRunner` — one-shot команды; `cli/CliMode` — переключатель режимов

## Что дальше (по док. 09)

- Ретро-OI: импортёр `metrics` из bulk-архива **готов** (`--backfill=oi-archive`);
  осталось прогнать полный backfill 2021→ по BTC/ETH и топ-парам (проверен на 7 днях).
  liquidationSnapshot из архива удалён — для ликвидаций ретроспективы нет.
- Kraken-коллектор **готов** (часовой funding + tickers); осталось при желании —
  `instruments` (спецификации/лотность) и спот-OHLC (резерв), низкий приоритет.
- Разлоки для S5: DefiLlama emissions стал платным — найти замену
  (CryptoRank / Tokenomist) или отложить.
- Ретро-капитализации для survivorship-free вселенной (док. 09 §4.3) — решить
  до этапа 4 (S2).
- MVRV Z-score считается на чтении из onchain_daily, не хранится. CapRealUSD
  в базе нет (см. «Ограничения источников»), realized cap выводится как
  CapMrktCurUSD / CapMVRVCur.
