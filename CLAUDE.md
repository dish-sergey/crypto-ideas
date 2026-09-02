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
./gradlew bootRun --args='--s5-dry-run=once'                    # S5: дайджест ближайших разлоков в Telegram и выход
./gradlew bootRun --args='--s5-dry-run'                         # S5: интерактивный dry-run (живой фид + марки Kraken + мок-исполнение)
./gradlew bootRun --args='--s5-kraken-check'                    # S5: read-only проверка ключа Kraken (без ордеров)
./gradlew bootRun --args='--s5-demo'                            # S5: показ жизненного цикла сделки в Telegram (без ордеров)
./gradlew bootRun --args='--s5-live'                            # S5: LIVE — РЕАЛЬНЫЕ ордера на Kraken Futures
./gradlew bootRun --args='--report=regime-dash --out=reports/dash'  # весь HTML-дашборд режима: index.html (меню) + 5 графиков
./gradlew bootRun --args='--theory=s5-import'                    # ТЗ 65/66: датасет событий S5 в data/theory.db (разово, сеть)
./gradlew bootRun --args='--theory=s5-premium --out=reports/theory' # док. 131: премия S5 на слитых событиях, разрезы Kraken/годы/порог
./gradlew bootRun --args='--theory=basis-history'                  # ТЗ 67: непрерывная минутная история спот+перп для базиса (сеть)
./gradlew bootRun --args='--theory=basis-stress'                   # ТЗ 67: то же по самым волатильным дням (стресс-выборка)
./gradlew bootRun --args='--theory=alloc --out=reports/theory'   # ТЗ 65: стенд аллокации поверх пула S1–S12
./gradlew bootRun --args='--theory=kelly --out=reports/theory'   # ТЗ 66: размер позиции S5 (Kelly с усадкой)
./gradlew bootRun --args='--theory=ou --out=reports/theory'      # ТЗ 67: калибровка и допуск OU (предтест блока B)
./gradlew bootRun --args='--theory=band --out=reports/theory'    # ТЗ 68: полоса бездействия и граница разорения
./gradlew bootRun --args='--theory=verify --out=reports/theory'  # ТЗ 72: доверификация базиса без прореживания + сверка констант S5
./gradlew bootRun --args='--revx-screen --hours=96 --to=2026-09-01T09:00:00Z --revx.db-path=data/revx-oos.db'  # скрининг всех 23 пар: спред, поток, разрешение хеджа, δ*
./gradlew bootRun --args='--revx-exec-report --journal=data/exec/ea.db --out=reports/revx_exec_a.md'           # живой журнал: разложение + пошлина через markout
```

Дашборд режима: `--report=regime-dash` рендерит `index.html` (меню с карточками версий:
текущее состояние, сколько дней в нём, лента за год) плюс `regime-v5.html` (прод), `regime-v3.html`,
`regime-v1.html`, `regime-all.html`, `regime-compare.html`. Отдельные графики по-прежнему доступны
как `--report=regime-v5|regime-v3|regime|regime-all|regime-compare [--out=...]`, меню — `--report=regime-index`.
**Период выбирается на клиенте**: данные вшиты в страницу целиком, кнопки 1М/3М/6М/YTD/1Г/2Г/Всё,
поля дат, ◀/▶ (и стрелки клавиатуры) режут окно без перегенерации; окно пишется в hash
(`regime-v5.html#w=30`, `#w=2024-01-01..2024-06-30`) — ссылкой можно делиться. Общий код окна —
`src/main/resources/regime-window.js`, он инлайнится в каждый отчёт вместо `__WINDOW_JS__`.

S5 dry-run читает токен/chat_id из `telegram/s5_bot.txt` (gitignored) либо env
`S5_TELEGRAM_TOKEN`/`S5_TELEGRAM_CHAT`. Деньги виртуальные (MockExchange), данные и Telegram — реальные.
`--s5-live` дополнительно читает ключи Kraken из `kraken/keys.txt` (gitignored) либо env
`S5_KRAKEN_KEY`/`S5_KRAKEN_SECRET`; исполнение настоящее, но ордер только после ручного подтверждения
(Approval Gate) + стоп −30%. **Live и dry-run с одним ботом одновременно НЕ запускать** (один потребитель
Telegram getUpdates, иначе 409). На micro — сервис `s5-live` (см. deploy/SERVERS.md).

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
- **DefiLlama `api.llama.fi/emissions` стал платным** (HTTP 402), **но корпус
  разлоков бесплатен через CDN их же фронтенда** (проверено 02.09.2026, см. `s5/`):
  `https://defillama-datasets.llama.fi/emissionsProtocolsList` (372 протокола) и
  `https://defillama-datasets.llama.fi/emissions/{slug}` — без ключа и лимитов,
  с форвардными событиями до 2032. Покрывает 113 из 274 перпов Kraken. Грабли:
  `gecko_id` бывает мусорным (маппить по `metadata.token` = `coingecko:<id>`);
  `timestamp` иногда строка; у `unlockType:"linear"` в `noOfTokens` лежит смена
  скорости `[было, стало]`, а не объём; часть адаптеров пишет линейный вестинг
  ежедневными микро-`cliff` (Celestia — 0.02% циркуляции в день), поэтому событие
  S5 отбирается по размеру, а не по типу. Платные альтернативы для оставшихся 35
  монет: CryptoRank `/v2/currencies/{id}/vesting` (Pro), Tokenomist, DropsTab.
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
- **Revolut X: авторизованный API снимает почти все ограничения публичного.**
  Лимиты рыночных данных с ключом — **100 запросов/с и 1000/мин** против 1/с
  публично, поэтому стенд работает по ним (`revx.auth.*`). Ключ Ed25519: пара
  генерируется локально (`openssl genpkey -algorithm ed25519`), публичный
  регистрируется в веб-приложении Revolut X, оно выдаёт 64-символьный API-ключ;
  подписывается склейка `timestamp + МЕТОД + путь_от_/api + query + тело`,
  заголовки `X-Revx-API-Key` / `X-Revx-Timestamp` / `X-Revx-Signature`. Ключ
  выпущен **только на чтение**, лежит на bot-arm в `~/revx/keys/` (в git не
  попадает). Грабли авторизованных эндпоинтов (проверено 19.08.2026):
  - **другая схема ответа**: книга отдаёт `p`/`q`/`no` вместо `price`/`quantity`/`count`,
    сделки — `tid`/`tdt`/`p`/`q`/`s` вместо `id`/`timestamp`/…; парсеры понимают обе;
  - **символ сделок в ПУТИ**: `/api/1.0/trades/all/{symbol}`. Тот же адрес с
    `?symbol=` отвечает `401 Unauthenticated access` — выглядит как проблема подписи;
  - **id сделки без дефисов** (`d307f978621b…`) против UUID публичного пути —
    без нормализации одна сделка попадает в базу дважды;
  - узкое место с ключом — **не лимит, а задержка ответа** (~200 мс): обход 23 пар
    в один поток занимал 9.5 с при периоде 5 с, лечится пулом воркеров;
  - **торговые лимиты** (для будущего этапа): `POST /orders` — 10/с и **1000/сутки**,
    `PUT /orders/{id}` (replace) — 10/с **без суточного лимита**, `DELETE` — 100/с и
    1000/мин. **Формат и поведение проверены живой заявкой 27.08.2026:**
    - `POST /api/1.0/orders` — тело `{client_order_id (UUID), symbol:"BTC-USDC",
      side:"buy|sell", order_configuration:{limit:{base_size, price,
      execution_instructions:["post_only"]}}}`. **`post_only` СУЩЕСТВУЕТ** — вопрос
      ТЗ §6 закрыт, ценовой предохранитель вместо флага не нужен;
    - `PUT /api/1.0/orders/{venue_order_id}` — требует **новый** `client_order_id`
      каждый раз и возвращает **новый `venue_order_id`**: замена создаёт другую
      заявку, состояние надо перечитывать из ответа, а не помнить;
    - `DELETE /api/1.0/orders/{venue_order_id}` — без тела, ответ **204 без тела**;
    - `GET /api/1.0/orders/active` — открытые заявки, `GET /api/1.0/balances` — остатки.
    - ⚠️ **имя поля с идентификатором РАЗНОЕ:** постановка и замена отдают
      `venue_order_id`, список активных — `id`. Уборка, искавшая только
      `venue_order_id`, не нашла собственную заявку и оставила её в книге.
    - ⚠️ **422 на replace НЕ значит, что замены не было** (найдено на живом
      исполнителе 30.08.2026, док. 111). Приходит
      `Cannot replace an order that is not in the 'NEW' state`, а сама заявка при
      этом уже `status: cancelled, reject_reason: replaced` — то есть наследник
      создан, а его идентификатор не пришёл никуда. Заявка остаётся в книге без
      хозяина, её резерв делает инвентарь неотчуждаемым, и попытки продать дают
      `Insufficient balance of ₿0` (766 отказов подряд = весь суточный лимит
      постановок). **Судьбу заявки нельзя выводить из её собственного статуса —
      только из `GET /orders/active`.**
    - ⚠️ **Проверять остаток надо по `available`, а не по `total`:** средства под
      стоящей заявкой в `total` видны, а поставить на них нельзя. Своя же заявка
      доступное расширяет — replace резерв возвращает.
    - Минимум заявки связывает `min_order_size_quote` = **0.1 USDC**
      (`min_order_size` = 1e-8 BTC пренебрежим). Остаток от частичного
      исполнения бывает мельче и постановкой не проходит.
    Перевыставление обязано идти через replace: схема «отменить и поставить
    заново» упирается в 1000/сутки уже при наблюдённой активности. **1000/сутки —
    это лимит на ВСЕЛЕННУЮ, а не на темп:** постановки тратятся после исполнений,
    измерено BTC 578 + ETH 132 = 71% суток (док. 74), то есть торговать можно
    две-три пары, а не 20 из ТЗ §3.4.
- **Revolut X (публичный путь) — ограничен МИНИМАЛЬНЫЙ ИНТЕРВАЛ между
  запросами (~1 с), а не средний темп.** Замеры 19.08.2026: одиночные запросы по
  1 req/s — чисто, с 1.25 req/s — устойчивый 429; но два запроса **встык** дают
  429 на втором при любой паузе между парами (проверено на 2.5/4/6 с — всегда
  ровно 50%). Поэтому у клиента стенда бакет с **ёмкостью 1** (никаких залпов),
  рабочий темп 0.8 req/s, а две ноги пары идут последовательно со skew ~1.25 с —
  одновременный опрос с одного IP невозможен. Лимит **не пер-эндпоинтный**:
  размазывать нагрузку по символам бесполезно. В 429 приходит `Retry-After` в
  **миллисекундах** (375/531/919), вопреки спеке; на подтверждённой перегрузке
  прилетало 27000. Каталог пар отдаётся **словарём** с ключом `ETH/USDC`, а в пути
  книги символ пишется через дефис (`ETH-USDC`). Массив `asks` — **в убывающем
  порядке** (324 снимка из 324), `asks[0]` это худший аск: наивное чтение завышает
  спред в полтора раза. Лента сделок отдаёт поле `side`. Публичные endpoint — без
  ключа, `region=EEA` доступен и с локальной машины.
- **Шаг контракта перпа Kraken у каждой пары свой** (`contractValueTradePrecision`
  из `instruments`), и он решает, возможен ли хедж вообще. При потолке инвентаря
  в $20 (живой бот) в него укладывается 2.6 ступени у BTC, 8.1 у ETH, **19.2 у
  SOL** — то есть на живом масштабе хедж не грубоват, а невозможен ни на одной из
  трёх пар. Прогоны ETH/SOL из док. 125 шли с BTC-шагом 0.0001 на все пары, то
  есть с разрешением на два порядка тоньше настоящего. Карта шагов —
  `revx.sim.hedge-steps`, снимается командой `--revx-screen`.
- **Целевой шорт округляется ВНИЗ** (`revx.sim.hedge-round-down=true`).
  Округление «к ближайшему» на грубом шаге переворачивает позицию: измерено на
  живом масштабе BTC — нетто-шорт 5e−5 BTC = 20% потолка в обратную сторону.
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
- `ws/LiquidationWsCollector` — ликвидации с 3 бирж: Binance !forceOrder@arr
  (весь рынок), OKX liquidation-orders (все SWAP), Bybit allLiquidation (все ~758
  linear-перпов — список тянется с REST на каждом коннекте, батчи по 100, форс-
  реконнект раз в 6ч для новых листингов); пер-биржевой ping, daemon-потоки
- `scheduling/CollectorScheduler` — расписание (док. 09 §5)
- `cli/CliRunner` — one-shot команды; `cli/CliMode` — переключатель режимов
- `revx/sim/ScreenReport` — `--revx-screen`: вся вселенная разом (спред, поток,
  разрешение хеджа по instruments Kraken, фондирование, `δ*` по ленте)
- `revx/exec/ExecReport` — `--revx-exec-report`: разложение живого журнала и
  пошлина `c = отступ − захват − markout`

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
