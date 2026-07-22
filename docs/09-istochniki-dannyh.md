# Doc 09 — Источники данных: бесплатные API и проверенные запросы

**Статус:** результаты разведки, к имплементации этапа 0 (док. 00 §6)
**Дата проверки:** 2026-07-17. Помеченные ✅ эндпоинты реально вызваны, ответы получены. Помеченные ⚠️ — заблокированы прокси тестовой среды (не самим сервисом); проверить с рабочей машины/сервера перед имплементацией.

---

## 1. Сводная карта: что нужно → где взять бесплатно

| # | Данные | Потребители | Основной источник | Резерв | Статус |
|---|--------|-------------|-------------------|--------|--------|
| 1 | OHLCV D1/H1, топ-100, с 2019 | C1, C5, S2, S3, бэктест | Binance `data-api.binance.vision` | OKX, ccxt | ✅ |
| 2 | OHLCV 1m/5m | док. 08 (sweep), S7/S8 | то же + bulk-архив Binance Vision | OKX | ✅ |
| 3 | Funding history (с 2020) | C3, S1, S5 | OKX public API | Binance fapi, Bybit | ✅ (OKX) |
| 4 | Funding текущий + предиктивный | S1, док. 08 | Binance `premiumIndex` / OKX `funding-rate` | — | ⚠️ |
| 5 | Open Interest история | C3, док. 08 | OKX rubik | Binance futures/data (глубина 30 дней!), Coinglass | ✅ (OKX) |
| 6 | Ликвидации (WS + история) | док. 08 | WS Binance `!forceOrder@arr` | Bybit WS `allLiquidation`; история: архив Binance Vision `liquidationSnapshot` | ⚠️ WS из песочницы не проверить |
| 7 | Taker buy/sell (aggressor delta) | док. 08 | поле в klines Binance | OKX rubik `taker-volume` | ✅ (оба) |
| 8 | L2 order book | док. 08 (forward), док. 06 MM | WS бирж, только live-запись | — | не тестировался |
| 9 | MVRV (+Z-score), realized price | C2, S6 | Coin Metrics Community API | bitcoin-data.com, checkonchain | ✅ |
| 10 | Supply in profit, LTH net position | C2 | нет надёжного бесплатного | Glassnode ($), CryptoQuant ($), Dune (free tier) | ❌ отложено — док. 01 допускает старт только на MVRV |
| 11 | Капитализация топ-100, история | C5, S2, вселенная бэктеста | CoinGecko free (лимит 365 дней!) | CoinPaprika; собственные суточные снапшоты со дня запуска | ⚠️ см. §4 |
| 12 | Macro: ставка, баланс ФРС, DXY | C4 | FRED `fredgraph.csv` (без ключа) | FRED API (бесплатный ключ) | ✅ |
| 13 | Экономкалендарь (CPI/NFP/FOMC) | M1 | ForexFactory JSON | FRED release calendar | ✅ |
| 14 | Токен-разлоки | S5, M1 | DefiLlama emissions API | CryptoRank (страницы), Tokenomist ($) | ⚠️ |
| 15 | Новости RSS | M2 | CoinDesk, Cointelegraph + список §2.8 | — | ✅ |
| 16 | Цены стейблов (депег-монитор) | S4, док. 03 | тикеры тех же бирж | CoinPaprika | ✅ |

---

## 2. Проверенные источники: эндпоинты и примеры

### 2.1. Binance spot — зеркало `data-api.binance.vision` ✅

Официальное зеркало публичных market-data эндпоинтов **без geo-ограничений** и без ключа. Те же пути и веса, что `api.binance.com` (лимит 6000 weight/мин на IP).

```
GET https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=2
```

Проверенный ответ (фрагмент):

```json
[[1779926400000,"74449.31","74590.77","72582.82","73617.51","21274.02",
  1780012799999,"1560808525.50",3799713,"11285.37","828070542.05","0"]]
```

Ключевое: **поле с индексом 9 = taker buy base volume** — это готовый aggressor delta для док. 08 §2 (sell-объём = total − taker buy). Интервалы 1s…1M, история с 2017, пагинация `startTime/endTime`, limit ≤ 1000. Закрывает потоки #1, #2, #7, #16 (тикеры стейблов — тот же хост, `/api/v3/ticker/price`).

### 2.2. Binance Vision bulk-архив (для бэктеста) ⚠️

`https://data.binance.vision/` — суточные/месячные zip-дампы: spot и futures klines, `fundingRate`, `metrics` (включая OI), `aggTrades`, `bookDepth`, `liquidationSnapshot`. Шаблон:

```
https://data.binance.vision/data/futures/um/daily/liquidationSnapshot/BTCUSDT/BTCUSDT-liquidationSnapshot-2026-07-14.zip
https://data.binance.vision/data/futures/um/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-2026-06.zip
https://data.binance.vision/data/spot/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-2026-06.zip
```

Из песочницы скачивание не прошло (прокси). **Проверить с рабочей машины**: глубину `liquidationSnapshot` и `metrics` — если ликвидации там лежат с 2020+, это решает главную проблему бэктеста док. 08 бесплатно.

### 2.3. OKX public API ✅

Без ключа, лимит ~20 req/2s на эндпоинт. Проверены:

```
GET https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1D&limit=2
GET https://www.okx.com/api/v5/public/funding-rate-history?instId=BTC-USDT-SWAP&limit=3
GET https://www.okx.com/api/v5/rubik/stat/contracts/open-interest-history?instId=BTC-USDT-SWAP&period=1D&limit=3
GET https://www.okx.com/api/v5/rubik/stat/taker-volume?ccy=BTC&instType=SPOT&period=1D
```

Фрагмент funding-ответа: `{"fundingRate":"-0.0000592","fundingTime":"1781596800000",...}`. Пагинация `before/after`. Rubik OI поддерживает периоды 5m/1H/1D — 5m покрывает требование док. 08 «шаг ≤ 1m» лишь частично (см. §4). Предиктивный funding: `GET /api/v5/public/funding-rate?instId=BTC-USDT-SWAP` (поле `nextFundingRate`) — то же семейство API, отдельно не гонялся.

### 2.4. Coin Metrics Community API ✅ — on-chain бесплатно

```
GET https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=CapMVRVCur,PriceUSD&frequency=1d&page_size=3
```

Проверенный ответ: `{"asset":"btc","time":"2026-07-15","CapMVRVCur":"1.2245","PriceUSD":"64765.05"}`.

Полезные бесплатные метрики: `CapMVRVCur` (MVRV), `CapRealUSD` (realized cap → realized price = CapRealUSD / SplyCur), `CapMrktCurUSD`, `SplyCur`, `PriceUSD`. **MVRV Z-score считаем сами:** `(CapMrktCurUSD − CapRealUSD) / rolling_std(CapMrktCurUSD)` — все компоненты в наличии. Это закрывает C2 (стартовая версия) и все три триггера S6, кроме supply in profit. Лимит: ~10 req / 6 сек. Лаг публикации ~1 сутки — совпадает с моделью `available_at = +24ч` (док. 04 §2.1).

### 2.5. FRED (макро) ✅

CSV без ключа:

```
GET https://fred.stlouisfed.org/graph/fredgraph.csv?id=DFF&cosd=2026-07-01
```

Серии для C4: `DFF` (ставка), `WALCL` (баланс ФРС), `DTWEXBGS` (broad dollar index — прокси DXY), `CPIAUCSL`. Проверены DFF и DTWEXBGS. Полноценный JSON-API — бесплатный ключ, 120 req/мин.

### 2.6. ForexFactory экономкалендарь ✅

```
GET https://nfs.faireconomy.media/ff_calendar_thisweek.json   (также nextweek)
```

Проверен: JSON с полями `title, country, date, impact (High/Medium/Low), forecast, previous` — CPI/PPI/FOMC размечены `High`. Прямое соответствие таблице M1 (док. 05): `impact=High & country=USD → HIGH`. Файл кэшируется — опрашивать раз в час, не чаще.

### 2.7. CoinPaprika ✅ — капитализации без ключа

```
GET https://api.coinpaprika.com/v1/tickers?limit=3
```

Проверен: rank, market_cap, supply, цена. Free: ~20k вызовов/мес, исторические тикеры на free tier ограничены — годится для **суточных снапшотов топ-100 начиная с сегодня**, не для ретроспективы (см. §4).

### 2.8. Новостные RSS ✅

Проверены: `https://www.coindesk.com/arc/outboundfeeds/rss/`, `https://cointelegraph.com/rss`. Добавить в пул M2 (не проверялись, стандартные): `https://www.theblock.co/rss.xml`, `https://decrypt.co/feed`, блоги Binance/Coinbase, RSS SEC/CFTC press releases.

---

## 3. Не проверено из песочницы / требует ключа

| Источник | Что даёт | Условия | Действие |
|----------|----------|---------|----------|
| `api.binance.com`, `fapi.binance.com` | funding history, `premiumIndex` (предиктивный funding), `futures/data/openInterestHist`, WS `!forceOrder@arr` (ликвидации), WS depth | бесплатно, без ключа | проверить с прод-машины; блокировка была на уровне прокси песочницы |
| Bybit `api.bybit.com` | kline, funding, OI, WS `allLiquidation` (полный поток ликвидаций, в отличие от урезанного Binance — важно для док. 08 §8) | бесплатно | то же |
| CoinGecko | капитализации, `market_chart` | demo-ключ бесплатно, 30 req/мин, **история ≤ 365 дней на free** | завести ключ |
| Coinglass | агрегированный OI, история ликвидаций | free-ключ с урезанными лимитами | завести ключ, оценить хватает ли free tier |
| DefiLlama `api.llama.fi/emissions` | календарь разлоков | бесплатно | проверить с прод-машины |
| Dune | supply in profit, LTH через SQL | free tier | отложено вместе с C2-подметриками |
| Glassnode / CryptoQuant | полный C2 | платно | решение после валидации детектора на MVRV-only |

---

## 4. Критичные ограничения глубины (важно для бэктеста)

1. **OI:** Binance `openInterestHist` хранит только **30 дней**. OKX rubik глубже, но гранулярность ≤ 5m. Вывод: коллектор OI запускается **с первого дня проекта** и пишет свою историю; ретроспектива — из архива Binance Vision `metrics` (проверить глубину) или Coinglass.
2. **Ликвидации:** live-поток Binance урезан (≤1 событие/сек — уже учтено в док. 08 §8, нормировка на собственный фид). Историю для бэктеста дают только архив `liquidationSnapshot` (§2.2, проверить) или платный Coinglass. Bybit WS полнее — вторым источником.
3. **Исторические капитализации топ-100 (survivorship-free вселенная, док. 04 §2.2):** CoinGecko free — 365 дней, CoinPaprika free — снапшоты только вперёд. Ретроспектива с 2019 включая мёртвые токены (LUNA, FTT) — самый проблемный бесплатный пункт. Варианты: разовая выгрузка через CoinGecko paid (один месяц подписки), Dune, или архивные датасеты (Kaggle-дампы CoinGecko/CMC — проверить качество). Решить до этапа 4 (S2), для этапов 0–3 не блокирует.
4. **Funding до 2020:** у OKX/Binance API история есть, но по каждой бирже отдельно (ставки различаются — док. 04 §1); качать пер-биржево, не агрегатом.
5. **L2 стакан:** исторических данных нет бесплатно вообще — только собственная запись с момента запуска (уже отражено в док. 06 фаза 0 и док. 08 §2).

---

## 5. Порядок имплементации коллекторов (этап 0)

Все пишут в хранилище с двумя временами `ts` + `available_at` (док. 04 §1).

| Приоритет | Коллектор | Источник | Частота |
|---|---|---|---|
| 1 | `collector_ohlcv` (D1 + 1m, BTC/ETH/топ-50) | data-api.binance.vision + bulk-архив | D1 раз в сутки; 1m — догрузка пачками |
| 2 | `collector_funding` | OKX + Binance fapi (+Bybit) | каждые 8ч + история разово |
| 3 | `collector_oi` | OKX rubik 5m + Binance 5m rolling | каждые 5 мин (своя история!) |
| 4 | `collector_onchain` | Coin Metrics Community | раз в сутки |
| 5 | `collector_universe` (снапшот топ-100 капов) | CoinPaprika / CoinGecko | раз в сутки |
| 6 | `collector_macro` | FRED | раз в неделю |
| 7 | `collector_calendar` | ForexFactory JSON | раз в час |
| 8 | `collector_news_rss` | RSS-пул §2.8 | каждые 10–15 мин |
| 9 | `collector_liq_ws` (forward-only) | WS Binance + Bybit | постоянное соединение, отдельный процесс |

Открытые действия перед стартом кода: (a) проверить с прод-машины Binance fapi / Bybit / bulk-архив / DefiLlama; (b) завести бесплатные ключи CoinGecko demo, FRED, Coinglass; (c) решить вопрос ретро-капитализаций (§4.3).
