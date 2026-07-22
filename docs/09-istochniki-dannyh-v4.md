# Doc 09 — Источники данных: бесплатные API и проверенные запросы

**Статус:** v4 — полная ревизия всех источников живыми запросами: два источника деградировали (DefiLlama платный, liquidationSnapshot исчез), bulk-архив Binance открылся из облака
**Дата проверки:** v1 — разведка; v2 — 2026-07-17, прогон коллекторов; v3 — 2026-07-18, Kraken; v4 — 2026-07-18, ревизия всех API

## Изменения в v4 (ревизия всех источников, 2026-07-18)

Прогнаны живыми запросами все эндпоинты §2 + отложенные из §3. Итог: ядро (Binance Vision, OKX, Coin Metrics, FRED, ForexFactory, CoinPaprika, Kraken) работает без изменений; два источника деградировали; одна крупная позитивная находка.

1. **DefiLlama emissions стал платным.** `api.llama.fi/emissions` → HTTP 402 «Upgrade to the paid API plan». Поток #14 (календарь разлоков для S5/M1) потерял основной бесплатный источник. Альтернативы: парсинг страниц CryptoRank, Tokenomist ($), ручное ведение крупных событий. Не блокирует: S5 в исследовательской очереди (док. 11), для M1 разлоки — LOW-приоритет.
2. **`liquidationSnapshot` исчез из bulk-архива Binance.** Датасета больше нет в S3-листинге `futures/um/daily/`, все файлы — 404 (в v2 значился как «проверить глубину»). История ликвидаций для бэктеста док. 08 бесплатно **недоступна вовсе**: остаются собственная WS-запись (Bybit `allLiquidation` с EU-машины) с момента запуска + Coinglass (платно/лимитно). Бэктест sweep-детектора становится forward-first: копим свою историю.
3. **Bulk-архив `data.binance.vision` теперь доступен из облачной песочницы** (в v2 не работал — видимо, чинили на их стороне). Проверено скачиванием и распаковкой: **(а)** `fundingRate` месячные — глубина минимум с 2020-01 → глубокая история funding рынка закрыта прямо из облака, прод-машина для этого больше не нужна (§4.6 закрыт); **(б)** `metrics` суточные — с 2021-01, внутри `sum_open_interest`, `sum_open_interest_value` + long/short ratios → ретро-OI закрыт (§4.1 закрыт); **(в)** spot/futures klines 1m месячные — работают; **(г)** бонус: `bookDepth` суточные — частичная ретроспектива глубины стакана, которой по v2 «не существовало» (пригодится док. 06/08).
4. **CoinDesk RSS переехал:** старый URL отдаёт 308 → рабочий адрес без слэша на конце: `https://www.coindesk.com/arc/outboundfeeds/rss`. Коллектору включить follow-redirects. Остальные три фида без изменений.
5. Перепроверено без изменений: OKX funding history — по-прежнему ~3 месяца (запрос на 4 мес назад — пусто); Coin Metrics `CapRealUSD` — по-прежнему 403 (обход через MVRV действует); ForexFactory `nextweek` — по-прежнему 404; `fapi.binance.com` — 451 и `api.bybit.com` — 403 из песочницы (geo, как и было); FRED CSV работает из песочницы через curl.

## Изменения в v3

1. **Kraken добавлен как рабочая биржа** (KYC пройден, Futures доступны через PEDSL-CY — см. док. 12 v3). Для S1-lite решение о входе принимается по ставкам площадки, где стоит шорт → нужен собственный сбор данных Kraken Futures.
2. **Все нужные эндпоинты Kraken публичны, без ключа, и работают даже из облачной песочницы (США)** — geo-блока нет, в отличие от Binance/Bybit. Проверено запросами 2026-07-18.
3. **Funding на Kraken Futures — ЧАСОВОЙ** (не 8-часовой, как на Binance/OKX). Эндпоинт `historicalfundingrates` (API **v4**, не v3!) отдаёт ~12 месяцев часовых ставок с полями `fundingRate` (абсолютный) и `relativeFundingRate` (относительный за час). Следствия: (а) EMA7 из правил S1 считается по часовым ставкам; (б) сравнение с 8-часовыми биржами — только через годовую нормализацию; (в) глубина ~12 мес закрывает потребность S1-lite без bulk-архивов.
4. Новый коллектор `collector_kraken` — см. §5.

## Изменения в v2 (история, 2026-07-17)

1. **ForexFactory: `ff_calendar_nextweek.json` удалён на их стороне (404).** Остался только `ff_calendar_thisweek.json`. Коллектор переведён на thisweek-only; горизонт упреждения событий — от нескольких часов до недели, для окон M1 достаточно (§2.6).
2. **Coin Metrics Community больше не отдаёт `CapRealUSD`** — HTTP 403. Realized cap выводится арифметически: `CapRealUSD = CapMrktCurUSD / CapMVRVCur`. MVRV Z-score по-прежнему считаем, формула в §2.4.
3. **OKX funding-rate-history отдаёт только ~3 последних месяца** — проверено пагинацией. Глубокая история funding — только Binance fapi (недоступен из облачной песочницы, geo 451; с рабочей машины — проверить). Funding в списке частично невосполнимых данных (§4).
4. Причина недоступности Binance/Bybit из песочницы: **geo-блокировка (HTTP 451/403 restricted location)** облачного контейнера (США), не прокси.
5. Статусы коллекторов этапа 0 после прогона — §5.

---

## 1. Сводная карта: что нужно → где взять бесплатно

| # | Данные | Потребители | Основной источник | Резерв | Статус |
|---|--------|-------------|-------------------|--------|--------|
| 1 | OHLCV D1/H1, топ-100, с 2019 | C1, C5, S2, S3, бэктест | Binance `data-api.binance.vision` | OKX, ccxt, Kraken spot | ✅ прогон: 33k свечей 2019→ |
| 2 | OHLCV 1m/5m | док. 08 (sweep), S7/S8 | то же + bulk-архив Binance Vision | OKX | ✅ прогон: 1m BTC/ETH |
| 3 | Funding history (рынок в целом) | C3, S5 | OKX public API (**только ~3 мес!**) | **v4: bulk-архив `fundingRate` с 2020-01 — работает из облака (§2.2)** | ✅ глубина закрыта — §4.6 |
| 4 | Funding текущий + предиктивный | C3, док. 08 | Binance `premiumIndex` / OKX `funding-rate` | Kraken tickers (поле fundingRatePrediction) | ⚠️ |
| 5 | Open Interest история | C3, док. 08 | OKX rubik | **v4: ретро — bulk-архив `metrics` с 2021-01 (§2.2)**; Coinglass | ✅ прогон + ретро закрыто |
| 6 | Ликвидации (WS + история) | док. 08 | WS Binance `!forceOrder@arr` + Bybit WS `allLiquidation` (live-запись с первого дня!) | история: только Coinglass ($) — **v4: архив liquidationSnapshot удалён Binance** | ⚠️ ретроспективы бесплатно больше нет |
| 7 | Taker buy/sell (aggressor delta) | док. 08 | поле в klines Binance | OKX rubik `taker-volume` | ✅ (оба) |
| 8 | L2 order book | док. 08 (forward), док. 06 MM | WS бирж, только live-запись | v4: частично — `bookDepth` из архива (§2.2) | ⚠️ |
| 9 | MVRV (+Z-score), realized price | C2, S6 | Coin Metrics Community API | bitcoin-data.com, checkonchain | ✅ прогон: 2015→, но **без CapRealUSD** (§2.4) |
| 10 | Supply in profit, LTH net position | C2 | нет надёжного бесплатного | Glassnode ($), CryptoQuant ($), Dune (free tier) | ❌ отложено — док. 01 допускает старт только на MVRV |
| 11 | Капитализация топ-100, история | C5, S2, вселенная бэктеста | CoinGecko free (лимит 365 дней!) | CoinPaprika; собственные суточные снапшоты | ⚠️ см. §4; прогон: снапшоты CoinPaprika пишутся |
| 12 | Macro: ставка, баланс ФРС, DXY | C4 | FRED `fredgraph.csv` (без ключа) | FRED API (бесплатный ключ) | ✅ |
| 13 | Экономкалендарь (CPI/NFP/FOMC) | M1 | ForexFactory JSON (**только thisweek**) | FRED release calendar | ✅ прогон: 99 событий |
| 14 | Токен-разлоки | S5, M1 | ~~DefiLlama emissions~~ **v4: платный (402)** | CryptoRank (парсинг), Tokenomist ($), ручное ведение | ❌ бесплатного API-источника нет |
| 15 | Новости RSS | M2 | CoinDesk (v4: новый URL, §2.8), Cointelegraph + §2.8 | — | ✅ v4: 4/4 фида живы |
| 16 | Цены стейблов (депег-монитор) | S4, док. 03 | тикеры тех же бирж | CoinPaprika | ✅ |
| **17** | **Kraken Futures: funding (часовой), OI, mark price, спецификации контрактов** | **S1-lite (торговая площадка шорт-ноги!), проверка лотности (записка 10 §6.4)** | **Kraken public API — §2.9** | — | **✅ v3: проверено живыми запросами, geo-блока нет** |

---

## 2. Проверенные источники: эндпоинты и примеры

### 2.1. Binance spot — зеркало `data-api.binance.vision` ✅

Официальное зеркало публичных market-data эндпоинтов **без geo-ограничений** и без ключа (v2: подтверждено прогоном, 33k дневных свечей 2019→; v4: перепроверено). Те же пути и веса, что `api.binance.com` (лимит 6000 weight/мин на IP).

```
GET https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=2
```

Ключевое: **поле с индексом 9 = taker buy base volume** — готовый aggressor delta для док. 08 §2. Интервалы 1s…1M, история с 2017, пагинация `startTime/endTime`, limit ≤ 1000. Закрывает потоки #1, #2, #7, #16.

### 2.2. Binance Vision bulk-архив (для бэктеста) ✅ (v4 — работает из облака, состав изменился)

`https://data.binance.vision/` — суточные/месячные zip-дампы. **v4: скачивание из песочницы работает** (проверено загрузкой и распаковкой). Актуальный состав `futures/um/daily/` по S3-листингу: `aggTrades, bookDepth, bookTicker, indexPriceKlines, klines, markPriceKlines, metrics, premiumIndexKlines, trades` — **`liquidationSnapshot` из архива удалён** (все файлы 404).

Проверенные рабочие шаблоны:

```
# funding рынка, месячные, глубина минимум с 2020-01 (CSV: calc_time, funding_interval_hours, last_funding_rate)
https://data.binance.vision/data/futures/um/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-2020-01.zip
# OI + long/short ratios, суточные, с 2021-01 (CSV: sum_open_interest, sum_open_interest_value, ...)
https://data.binance.vision/data/futures/um/daily/metrics/BTCUSDT/BTCUSDT-metrics-2021-01-01.zip
# минутки спота, месячные
https://data.binance.vision/data/spot/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-2026-06.zip
# глубина стакана (снапшоты уровней), суточные — частичная L2-ретроспектива
https://data.binance.vision/data/futures/um/daily/bookDepth/BTCUSDT/BTCUSDT-bookDepth-2026-07-14.zip
```

Листинг каталогов: `https://s3-ap-northeast-1.amazonaws.com/data.binance.vision?delimiter=/&prefix=data/futures/um/daily/`. Разовый backfill funding+metrics по топ-парам — теперь задача на вечер прямо из облака.

### 2.3. OKX public API ✅

Без ключа, лимит ~20 req/2s на эндпоинт. Проверены (v4: перепроверено, всё работает):

```
GET https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1D&limit=2
GET https://www.okx.com/api/v5/public/funding-rate-history?instId=BTC-USDT-SWAP&limit=3
GET https://www.okx.com/api/v5/rubik/stat/contracts/open-interest-history?instId=BTC-USDT-SWAP&period=1D&limit=3
GET https://www.okx.com/api/v5/rubik/stat/taker-volume?ccy=BTC&instType=SPOT&period=1D
```

Пагинация `before/after`. Предиктивный funding: `GET /api/v5/public/funding-rate?instId=BTC-USDT-SWAP` (поле `nextFundingRate`).

**v2, важно: `funding-rate-history` хранит только ~3 последних месяца** (v4: перепроверено — запрос на 4 месяца назад возвращает пусто). Глубина — через bulk-архив (§2.2).

### 2.4. Coin Metrics Community API ✅ — on-chain бесплатно (с оговоркой v2)

```
GET https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=CapMVRVCur,PriceUSD&frequency=1d&page_size=3
```

**v2: `CapRealUSD` исключена из community-тарифа** (403, валит весь запрос — метрики идут одним списком; v4: перепроверено, по-прежнему 403). Рабочий набор: `CapMVRVCur, CapMrktCurUSD, SplyCur, PriceUSD` — полная история btc с 2015-01-01, eth с 2015-07-30 (v4: свежие значения приходят).

Realized cap восстанавливается арифметически (MVRV = MC/RC):

```
CapRealUSD = CapMrktCurUSD / CapMVRVCur
realized price = CapRealUSD / SplyCur
MVRV Z-score = (CapMrktCurUSD − CapRealUSD) / rolling_std(CapMrktCurUSD)
```

C2 (стартовая версия) и триггеры S6 (кроме supply in profit) закрыты. Лимит: ~10 req / 6 сек. Лаг публикации ~1 сутки — совпадает с моделью `available_at = +24ч` (док. 04 §2.1).

### 2.5. FRED (макро) ✅

CSV без ключа (v4: работает из песочницы через curl):

```
GET https://fred.stlouisfed.org/graph/fredgraph.csv?id=DFF&cosd=2026-07-01
```

Серии для C4: `DFF` (ставка), `WALCL` (баланс ФРС), `DTWEXBGS` (broad dollar index — прокси DXY), `CPIAUCSL`. Полноценный JSON-API — бесплатный ключ, 120 req/мин.

### 2.6. ForexFactory экономкалендарь ✅ (v2: только thisweek)

```
GET https://nfs.faireconomy.media/ff_calendar_thisweek.json
```

**v2: `nextweek` и `lastweek` удалены (404)** (v4: перепроверено — 404). События видны при смене недели у FF — для окон запрета M1 достаточно. Соответствие таблице M1 (док. 05): `impact=High & country=USD → HIGH`. Опрашивать раз в час.

### 2.7. CoinPaprika ✅ — капитализации без ключа

```
GET https://api.coinpaprika.com/v1/tickers?limit=3
```

Суточный снапшот топ-200 пишется (v4: работает). Free: ~20k вызовов/мес; годится для **суточных снапшотов топ-100 начиная с сегодня**, не для ретроспективы (§4).

### 2.8. Новостные RSS ✅

Все четыре фида живые (v4). **CoinDesk переехал**: `https://www.coindesk.com/arc/outboundfeeds/rss` (без слэша на конце; старый URL — 308). Остальные без изменений: `https://cointelegraph.com/rss`, `https://www.theblock.co/rss.xml`, `https://decrypt.co/feed`. Коллектору включить follow-redirects. Кандидаты на расширение: блоги Binance/Coinbase, RSS SEC/CFTC.

### 2.9. Kraken public API ✅ (v3 — проверено 2026-07-18, без ключа, без geo-блока)

Работает даже из облачной песочницы (США) — единственная из наших торговых бирж без geo-ограничений на публичные данные. (v4: перепроверено.)

```
# Часовая история funding (~12 мес), ВНИМАНИЕ: путь v4
GET https://futures.kraken.com/derivatives/api/v4/historicalfundingrates?symbol=PF_XBTUSD
# → rates[]: {timestamp, fundingRate (абс.), relativeFundingRate (отн. за час)}

# Тикеры всех перпов: markPrice, bid/ask, vol24h, openInterest, fundingRate + prediction
GET https://futures.kraken.com/derivatives/api/v3/tickers

# Спецификации контрактов: tickSize, contractSize, marginLevels, maxPositionSize
GET https://futures.kraken.com/derivatives/api/v3/instruments

# Спот-свечи (резерв к Binance/OKX; максимум 720 баров на запрос)
GET https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=1440
```

Проверено фактически: `historicalfundingrates` на `PF_XBTUSD` отдал часовые ставки с июля 2025 (v3-путь возвращает 404 — только v4); `tickers` и `instruments` отвечают полным списком; спот-OHLC работает.

Заметки для имплементации:
- Символы: `PF_XBTUSD`, `PF_ETHUSD` — линейные perpetual (multi-collateral); `PI_*` — старые инверсные, для S1-lite не нужны.
- **Funding часовой** → EMA7 по часовым ставкам; для сравнения с OKX/Binance (8ч) нормализовать в годовые.
- `instruments` — прямой вход для проверки лотности/минималок на нашем номинале (записка 10 §6.4, док. 12 §4в).
- Приватные эндпоинты (баланс, ордера) — тот же хост, аутентификация ключом; для коллекторов не нужны.

---

## 3. Не проверено из песочницы / требует ключа

| Источник | Что даёт | Условия | Действие |
|----------|----------|---------|----------|
| `api.binance.com`, `fapi.binance.com` | `premiumIndex`, `openInterestHist`, WS `!forceOrder@arr`, WS depth | бесплатно, без ключа | проверить с прод-машины (v4: по-прежнему geo 451 из песочницы). Аккаунт-сервисы Binance в ЕС свёрнуты (док. 12), но публичные data-API работают |
| Bybit `api.bybit.com` | kline, funding, OI, WS `allLiquidation` | бесплатно | то же (v4: по-прежнему geo 403 из песочницы) |
| CoinGecko | капитализации, `market_chart` | demo-ключ, 30 req/мин, **история ≤ 365 дней** | завести ключ |
| Coinglass | агрегированный OI, история ликвидаций | free-ключ | завести ключ, оценить лимиты — v4: значимость выросла (единственная ретроспектива ликвидаций) |
| ~~DefiLlama `api.llama.fi/emissions`~~ | календарь разлоков | **v4: платный (HTTP 402)** | источник исключён; замена — CryptoRank/Tokenomist/ручное (см. §1 #14) |
| Dune | supply in profit, LTH через SQL | free tier | отложено вместе с C2-подметриками |
| Glassnode / CryptoQuant | полный C2 | платно | решение после валидации детектора на MVRV-only |

---

## 4. Критичные ограничения глубины (важно для бэктеста)

1. **OI:** ✅ закрыт в v4 — суточные `metrics` из bulk-архива дают ретро-OI Binance с 2021-01 (§2.2). Коллектор OI продолжает писать живую историю (архив суточный, живые данные — 5m).
2. **Ликвидации:** ⛔ ухудшилось в v4 — архив `liquidationSnapshot` удалён Binance, бесплатной ретроспективы больше нет. Только собственная WS-запись с момента запуска (Bybit `allLiquidation` с EU-машины — полный поток) + Coinglass ($). Бэктест док. 08 — forward-first.
3. **Исторические капитализации топ-100** (survivorship-free вселенная): самый проблемный бесплатный пункт. Решить до этапа 4 (S2), этапы 0–3 не блокирует.
4. **Funding до 2020:** качать пер-биржево (ставки различаются).
5. **L2 стакан:** только собственная запись с момента запуска; v4: частичная ретроспектива — суточные `bookDepth` из bulk-архива (§2.2).
6. **v2 — funding OKX: глубина ~3 месяца** (v4: перепроверено, так и есть). ✅ Глубина рынка закрыта в v4: месячные дампы `fundingRate` bulk-архива с 2020-01 доступны из облака (§2.2) — Binance fapi для этого больше не нужен.
7. **v3 — funding Kraken: глубина ~12 месяцев часовых ставок через v4-эндпоинт** — для S1-lite этого достаточно без bulk-архивов. Но ставки Kraken ≠ ставкам Binance/OKX (другая формула, часовой цикл, другой поток клиентов): **бэктест и живые решения S1-lite — только на данных Kraken**; данные OKX/Binance остаются для C3 (режимный фон рынка) и S5.

---

## 5. Коллекторы этапа 0: статус

Все пишут в SQLite (`data/crypto.db`) с двумя временами `ts` + `available_at` (док. 04 §1). Идемпотентность подтверждена; изоляция сбоев работает.

| Приоритет | Коллектор | Источник | Частота | Статус |
|---|---|---|---|---|
| 1 | `collector_ohlcv` | data-api.binance.vision | D1 раз в сутки; 1m догрузка | ✅ 33k свечей |
| 2 | `collector_funding` | OKX (+bulk-архив для глубины — v4) | каждые 8ч; разовый backfill из архива | ✅ OKX 568 ставок; v4: архив открылся |
| 3 | `collector_oi` | OKX rubik + Binance 5m rolling | каждые 5 мин | ✅ OKX пишет |
| 4 | `collector_onchain` | Coin Metrics Community | раз в сутки | ✅ 33k значений 2015→ |
| 5 | `collector_universe` | CoinPaprika | раз в сутки | ✅ снапшот 200 монет |
| 6 | `collector_macro` | FRED | раз в неделю | ✅ v4: работает из песочницы через curl |
| 7 | `collector_calendar` | ForexFactory thisweek | раз в час | ✅ 99 событий |
| 8 | `collector_news_rss` | RSS-пул §2.8 | каждые 10–15 мин | ✅ 4 фида; v4: обновить URL CoinDesk + follow-redirects |
| 9 | `collector_liq_ws` | WS Binance + Bybit | постоянно | ⚠️ v4: приоритет ↑ — после удаления архива это ЕДИНСТВЕННЫЙ источник истории ликвидаций; запускать с EU-машины как можно раньше |
| **10** | **`collector_kraken`** | **Kraken public API (§2.9)** | **funding: раз в час; tickers (OI/mark/predict): каждые 5 мин; instruments: раз в сутки; спот-OHLC D1: раз в сутки** | **⬜ v3: эндпоинты проверены, коллектор к реализации. Первый запуск — сразу backfill ~12 мес часового funding по PF_XBTUSD и PF_ETHUSD** |

Открытые действия (v4): (a) **разовый backfill из bulk-архива прямо из облака**: fundingRate 2020→ и metrics/OI 2021→ по BTC/ETH и топ-парам; (b) обновить URL CoinDesk в `collector_news_rss`; (c) поднять приоритет `collector_liq_ws` (единственный источник истории ликвидаций) — запуск с EU-машины; (d) реализовать `collector_kraken` (v3, приоритетно); (e) завести бесплатные ключи CoinGecko demo, FRED, Coinglass; (f) решить вопрос ретро-капитализаций (§4.3); (g) для разлоков S5 (когда дойдёт очередь) — выбрать замену DefiLlama.
