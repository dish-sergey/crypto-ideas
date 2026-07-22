# Doc 09 — Источники данных: бесплатные API и проверенные запросы

**Статус:** v2 — обновлено по живым прогонам коллекторов crypto-ideas (этап 0 реализован)
**Дата проверки:** 2026-07-17 (v1 — разведка эндпоинтов; v2 — прогон реального кода против API, данные в `data/crypto.db`)

## Изменения в v2 (по результатам живых прогонов)

1. **ForexFactory: `ff_calendar_nextweek.json` удалён на их стороне (404).** Остался только `ff_calendar_thisweek.json`. Коллектор переведён на thisweek-only; горизонт упреждения событий — от нескольких часов до недели, для окон M1 достаточно (§2.6).
2. **Coin Metrics Community больше не отдаёт `CapRealUSD`** — HTTP 403 «not available with supplied credentials». Realized cap выводится арифметически: `CapRealUSD = CapMrktCurUSD / CapMVRVCur` (т.к. MVRV = MC/RC). MVRV Z-score по-прежнему считаем, формула в §2.4.
3. **OKX funding-rate-history отдаёт только ~3 последних месяца** — проверено пагинацией: запросы старше границы возвращают пусто. Глубокая история funding — только Binance fapi (недоступен из облачной песочницы, geo 451; с рабочей машины — проверить). Funding теперь в списке частично невосполнимых данных (§4).
4. Уточнена причина недоступности Binance/Bybit из песочницы: это **geo-блокировка (HTTP 451/403 restricted location)** облачного контейнера (США), а не прокси. С машины в Европе должны работать.
5. Статусы коллекторов этапа 0 после прогона — §5.

---

## 1. Сводная карта: что нужно → где взять бесплатно

| # | Данные | Потребители | Основной источник | Резерв | Статус |
|---|--------|-------------|-------------------|--------|--------|
| 1 | OHLCV D1/H1, топ-100, с 2019 | C1, C5, S2, S3, бэктест | Binance `data-api.binance.vision` | OKX, ccxt | ✅ прогон: 33k свечей 2019→ |
| 2 | OHLCV 1m/5m | док. 08 (sweep), S7/S8 | то же + bulk-архив Binance Vision | OKX | ✅ прогон: 1m BTC/ETH |
| 3 | Funding history | C3, S1, S5 | OKX public API (**только ~3 мес!**) | Binance fapi (глубокая история), Bybit | ✅ OKX, ⚠️ глубина — см. §4.6 |
| 4 | Funding текущий + предиктивный | S1, док. 08 | Binance `premiumIndex` / OKX `funding-rate` | — | ⚠️ |
| 5 | Open Interest история | C3, док. 08 | OKX rubik | Binance futures/data (глубина 30 дней!), Coinglass | ✅ прогон: OKX пишет |
| 6 | Ликвидации (WS + история) | док. 08 | WS Binance `!forceOrder@arr` | Bybit WS `allLiquidation`; история: архив Binance Vision `liquidationSnapshot` | ⚠️ WS из песочницы не проверить (geo) |
| 7 | Taker buy/sell (aggressor delta) | док. 08 | поле в klines Binance | OKX rubik `taker-volume` | ✅ (оба) |
| 8 | L2 order book | док. 08 (forward), док. 06 MM | WS бирж, только live-запись | — | не тестировался |
| 9 | MVRV (+Z-score), realized price | C2, S6 | Coin Metrics Community API | bitcoin-data.com, checkonchain | ✅ прогон: 2015→, но **без CapRealUSD** (§2.4) |
| 10 | Supply in profit, LTH net position | C2 | нет надёжного бесплатного | Glassnode ($), CryptoQuant ($), Dune (free tier) | ❌ отложено — док. 01 допускает старт только на MVRV |
| 11 | Капитализация топ-100, история | C5, S2, вселенная бэктеста | CoinGecko free (лимит 365 дней!) | CoinPaprika; собственные суточные снапшоты со дня запуска | ⚠️ см. §4; прогон: снапшоты CoinPaprika пишутся |
| 12 | Macro: ставка, баланс ФРС, DXY | C4 | FRED `fredgraph.csv` (без ключа) | FRED API (бесплатный ключ) | ✅ (из песочницы Java-клиент падает на прокси — env-issue, curl работает) |
| 13 | Экономкалендарь (CPI/NFP/FOMC) | M1 | ForexFactory JSON (**только thisweek**) | FRED release calendar | ✅ прогон: 99 событий |
| 14 | Токен-разлоки | S5, M1 | DefiLlama emissions API | CryptoRank (страницы), Tokenomist ($) | ⚠️ |
| 15 | Новости RSS | M2 | CoinDesk, Cointelegraph + список §2.8 | — | ✅ прогон: 130 записей, все 4 фида |
| 16 | Цены стейблов (депег-монитор) | S4, док. 03 | тикеры тех же бирж | CoinPaprika | ✅ |

---

## 2. Проверенные источники: эндпоинты и примеры

### 2.1. Binance spot — зеркало `data-api.binance.vision` ✅

Официальное зеркало публичных market-data эндпоинтов **без geo-ограничений** и без ключа — работает даже из geo-заблокированной песочницы (v2: подтверждено прогоном, 33k дневных свечей 2019→ и минутки скачаны без ошибок). Те же пути и веса, что `api.binance.com` (лимит 6000 weight/мин на IP).

```
GET https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=2
```

Ключевое: **поле с индексом 9 = taker buy base volume** — это готовый aggressor delta для док. 08 §2 (sell-объём = total − taker buy). Интервалы 1s…1M, история с 2017, пагинация `startTime/endTime`, limit ≤ 1000. Закрывает потоки #1, #2, #7, #16 (тикеры стейблов — тот же хост, `/api/v3/ticker/price`).

### 2.2. Binance Vision bulk-архив (для бэктеста) ⚠️

`https://data.binance.vision/` — суточные/месячные zip-дампы: spot и futures klines, `fundingRate`, `metrics` (включая OI), `aggTrades`, `bookDepth`, `liquidationSnapshot`. Шаблон:

```
https://data.binance.vision/data/futures/um/daily/liquidationSnapshot/BTCUSDT/BTCUSDT-liquidationSnapshot-2026-07-14.zip
https://data.binance.vision/data/futures/um/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-2026-06.zip
https://data.binance.vision/data/spot/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-2026-06.zip
```

Из песочницы скачивание не прошло. **Проверить с рабочей машины**: глубину `liquidationSnapshot` и `metrics` — если ликвидации там лежат с 2020+, это решает главную проблему бэктеста док. 08 бесплатно. v2: месячные дампы `fundingRate` — теперь ещё и основной кандидат на глубокую историю funding (см. §4.6).

### 2.3. OKX public API ✅

Без ключа, лимит ~20 req/2s на эндпоинт. Проверены:

```
GET https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1D&limit=2
GET https://www.okx.com/api/v5/public/funding-rate-history?instId=BTC-USDT-SWAP&limit=3
GET https://www.okx.com/api/v5/rubik/stat/contracts/open-interest-history?instId=BTC-USDT-SWAP&period=1D&limit=3
GET https://www.okx.com/api/v5/rubik/stat/taker-volume?ccy=BTC&instType=SPOT&period=1D
```

Пагинация `before/after`. Rubik OI поддерживает периоды 5m/1H/1D. Предиктивный funding: `GET /api/v5/public/funding-rate?instId=BTC-USDT-SWAP` (поле `nextFundingRate`).

**v2, важно: `funding-rate-history` хранит только ~3 последних месяца.** Проверено пагинацией назад: страница с `after=<ts −3 мес>` возвращает пусто (284 ставки × 8ч ≈ 94 дня на инструмент). Backfill глубже невозможен в принципе — OKX закрывает только «тёплую» историю; глубина — Binance fapi `/fapi/v1/fundingRate` или bulk-архив (§2.2).

### 2.4. Coin Metrics Community API ✅ — on-chain бесплатно (с оговоркой v2)

```
GET https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=CapMVRVCur,PriceUSD&frequency=1d&page_size=3
```

**v2: `CapRealUSD` исключена из community-тарифа** — запрос с ней возвращает HTTP 403 «Requested metric 'CapRealUSD' … is not available with supplied credentials» и валит весь запрос (метрики запрашиваются одним списком). Рабочий набор: `CapMVRVCur, CapMrktCurUSD, SplyCur, PriceUSD` — прогон дал полную историю btc с 2015-01-01, eth с 2015-07-30 (~33k значений).

Realized cap восстанавливается арифметически (MVRV = MC/RC):

```
CapRealUSD = CapMrktCurUSD / CapMVRVCur
realized price = CapRealUSD / SplyCur
MVRV Z-score = (CapMrktCurUSD − CapRealUSD) / rolling_std(CapMrktCurUSD)
```

Все компоненты по-прежнему в наличии — C2 (стартовая версия) и триггеры S6 (кроме supply in profit) закрыты. Лимит: ~10 req / 6 сек. Лаг публикации ~1 сутки — совпадает с моделью `available_at = +24ч` (док. 04 §2.1).

### 2.5. FRED (макро) ✅

CSV без ключа:

```
GET https://fred.stlouisfed.org/graph/fredgraph.csv?id=DFF&cosd=2026-07-01
```

Серии для C4: `DFF` (ставка), `WALCL` (баланс ФРС), `DTWEXBGS` (broad dollar index — прокси DXY), `CPIAUCSL`. Проверены DFF и DTWEXBGS. Полноценный JSON-API — бесплатный ключ, 120 req/мин. v2: из облачной песочницы Java HttpClient получает RST_STREAM через её TLS-прокси (curl тот же URL работает) — это дефект окружения, не источника; с рабочей машины перепроверить, ожидается ОК.

### 2.6. ForexFactory экономкалендарь ✅ (v2: только thisweek)

```
GET https://nfs.faireconomy.media/ff_calendar_thisweek.json
```

**v2: `ff_calendar_nextweek.json` и `ff_calendar_lastweek.json` удалены (404).** Работает только thisweek. Следствие: события становятся видны при смене недели у FF — запас до ближайшего события от нескольких часов (события понедельника) до недели; для окон запрета M1 (часы вокруг события) этого достаточно. Поля прежние: `title, country, date, impact (High/Medium/Low), forecast, previous` — CPI/PPI/FOMC размечены `High`, соответствие таблице M1 (док. 05): `impact=High & country=USD → HIGH`. Файл кэшируется — опрашивать раз в час, не чаще. Прогон 2026-07-17: 99 событий записано, идемпотентно (INSERT OR IGNORE, `available_at` не обновляется при повторах).

### 2.7. CoinPaprika ✅ — капитализации без ключа

```
GET https://api.coinpaprika.com/v1/tickers?limit=3
```

Прогон: суточный снапшот топ-200 (rank, market_cap, supply, цена) пишется. Free: ~20k вызовов/мес, исторические тикеры на free tier ограничены — годится для **суточных снапшотов топ-100 начиная с сегодня**, не для ретроспективы (см. §4).

### 2.8. Новостные RSS ✅

Прогон: все четыре фида живые, 130 записей — `https://www.coindesk.com/arc/outboundfeeds/rss/`, `https://cointelegraph.com/rss`, `https://www.theblock.co/rss.xml`, `https://decrypt.co/feed`. Кандидаты на расширение пула M2: блоги Binance/Coinbase, RSS SEC/CFTC press releases.

---

## 3. Не проверено из песочницы / требует ключа

v2: причина недоступности Binance/Bybit из облачной песочницы — geo-блокировка (451/403 restricted location, контейнер в США), не прокси. С машины/сервера в Европе ожидается работа.

| Источник | Что даёт | Условия | Действие |
|----------|----------|---------|----------|
| `api.binance.com`, `fapi.binance.com` | funding history (глубокая — критично после v2 §2.3!), `premiumIndex`, `futures/data/openInterestHist`, WS `!forceOrder@arr`, WS depth | бесплатно, без ключа | проверить с прод-машины (geo 451 в песочнице) |
| Bybit `api.bybit.com` | kline, funding, OI, WS `allLiquidation` (полный поток ликвидаций — важно для док. 08 §8) | бесплатно | то же (geo 403 в песочнице) |
| CoinGecko | капитализации, `market_chart` | demo-ключ бесплатно, 30 req/мин, **история ≤ 365 дней на free** | завести ключ |
| Coinglass | агрегированный OI, история ликвидаций | free-ключ с урезанными лимитами | завести ключ, оценить хватает ли free tier |
| DefiLlama `api.llama.fi/emissions` | календарь разлоков | бесплатно | проверить с прод-машины |
| Dune | supply in profit, LTH через SQL | free tier | отложено вместе с C2-подметриками |
| Glassnode / CryptoQuant | полный C2 | платно | решение после валидации детектора на MVRV-only |

---

## 4. Критичные ограничения глубины (важно для бэктеста)

1. **OI:** Binance `openInterestHist` хранит только **30 дней**. OKX rubik глубже, но гранулярность ≤ 5m. Вывод: коллектор OI запускается **с первого дня проекта** и пишет свою историю; ретроспектива — из архива Binance Vision `metrics` (проверить глубину) или Coinglass.
2. **Ликвидации:** live-поток Binance урезан (≤1 событие/сек — учтено в док. 08 §8). Историю для бэктеста дают только архив `liquidationSnapshot` (§2.2, проверить) или платный Coinglass. Bybit WS полнее — вторым источником.
3. **Исторические капитализации топ-100 (survivorship-free вселенная, док. 04 §2.2):** CoinGecko free — 365 дней, CoinPaprika free — снапшоты только вперёд. Ретроспектива с 2019 включая мёртвые токены — самый проблемный бесплатный пункт. Варианты: разовая выгрузка CoinGecko paid, Dune, архивные датасеты. Решить до этапа 4 (S2), для этапов 0–3 не блокирует.
4. **Funding до 2020:** качать пер-биржево, не агрегатом (ставки различаются — док. 04 §1).
5. **L2 стакан:** исторических данных бесплатно нет — только собственная запись с момента запуска (док. 06 фаза 0, док. 08 §2).
6. **v2 — funding OKX: глубина ~3 месяца** (§2.3). `--backfill=funding-okx` даёт только тёплую историю; окно уезжает вперёд каждый день. Следствия: (a) funding переходит в разряд частично невосполнимых — коллектор должен работать постоянно, как OI; (b) глубокая история — Binance fapi с прод-машины (разово) и/или месячные дампы `fundingRate` из bulk-архива (§2.2); (c) до её загрузки бэктест стратегий на funding (C3, S1, S5) ограничен глубиной ~3 мес + накопленным.

---

## 5. Коллекторы этапа 0: статус после живого прогона (2026-07-17)

Все пишут в SQLite (`data/crypto.db`) с двумя временами `ts` + `available_at` (док. 04 §1). Идемпотентность подтверждена повторными прогонами (счётчики строк не растут). Изоляция сбоев работает и в планировщике, и в CLI `--collect=all` (падение одного источника логируется, остальные собираются; exit code 1).

| Приоритет | Коллектор | Источник | Частота | Статус прогона |
|---|---|---|---|---|
| 1 | `collector_ohlcv` | data-api.binance.vision | D1 раз в сутки; 1m догрузка | ✅ 33k свечей: D1 2019→ (5 симв.), 1m BTC/ETH |
| 2 | `collector_funding` | OKX (+Binance fapi где доступен) | каждые 8ч | ✅ OKX 568 ставок; Binance — geo из песочницы |
| 3 | `collector_oi` | OKX rubik + Binance 5m rolling | каждые 5 мин | ✅ OKX пишет; Binance — geo |
| 4 | `collector_onchain` | Coin Metrics Community | раз в сутки | ✅ 33k значений 2015→ (без CapRealUSD) |
| 5 | `collector_universe` | CoinPaprika | раз в сутки | ✅ снапшот 200 монет |
| 6 | `collector_macro` | FRED | раз в неделю | ⚠️ из песочницы — прокси-дефект; перепроверить локально |
| 7 | `collector_calendar` | ForexFactory thisweek | раз в час | ✅ 99 событий |
| 8 | `collector_news_rss` | RSS-пул §2.8 | каждые 10–15 мин | ✅ 130 записей, 4 фида |
| 9 | `collector_liq_ws` | WS Binance + Bybit | постоянно | ⚠️ запускается, поток не проверить (geo) |

Открытые действия: (a) с прод-машины проверить Binance fapi / Bybit / bulk-архив / DefiLlama и **разово снять глубокую историю funding с Binance**; (b) завести бесплатные ключи CoinGecko demo, FRED, Coinglass; (c) решить вопрос ретро-капитализаций (§4.3).
