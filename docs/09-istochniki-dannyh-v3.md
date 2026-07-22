# Doc 09 — Источники данных: бесплатные API и проверенные запросы

**Статус:** v3 — добавлен Kraken (спот + Futures) как рабочая биржа; эндпоинты проверены живыми запросами
**Дата проверки:** v1 — разведка; v2 — 2026-07-17, прогон коллекторов; v3 — 2026-07-18, проверка Kraken public API

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
| 3 | Funding history (рынок в целом) | C3, S5 | OKX public API (**только ~3 мес!**) | Binance fapi (глубокая история), Bybit | ✅ OKX, ⚠️ глубина — см. §4.6 |
| 4 | Funding текущий + предиктивный | C3, док. 08 | Binance `premiumIndex` / OKX `funding-rate` | Kraken tickers (поле fundingRatePrediction) | ⚠️ |
| 5 | Open Interest история | C3, док. 08 | OKX rubik | Binance futures/data (30 дней!), Coinglass | ✅ прогон: OKX пишет |
| 6 | Ликвидации (WS + история) | док. 08 | WS Binance `!forceOrder@arr` | Bybit WS `allLiquidation`; история: архив Binance Vision | ⚠️ WS из песочницы не проверить (geo) |
| 7 | Taker buy/sell (aggressor delta) | док. 08 | поле в klines Binance | OKX rubik `taker-volume` | ✅ (оба) |
| 8 | L2 order book | док. 08 (forward), док. 06 MM | WS бирж, только live-запись | — | не тестировался |
| 9 | MVRV (+Z-score), realized price | C2, S6 | Coin Metrics Community API | bitcoin-data.com, checkonchain | ✅ прогон: 2015→, но **без CapRealUSD** (§2.4) |
| 10 | Supply in profit, LTH net position | C2 | нет надёжного бесплатного | Glassnode ($), CryptoQuant ($), Dune (free tier) | ❌ отложено — док. 01 допускает старт только на MVRV |
| 11 | Капитализация топ-100, история | C5, S2, вселенная бэктеста | CoinGecko free (лимит 365 дней!) | CoinPaprika; собственные суточные снапшоты | ⚠️ см. §4; прогон: снапшоты CoinPaprika пишутся |
| 12 | Macro: ставка, баланс ФРС, DXY | C4 | FRED `fredgraph.csv` (без ключа) | FRED API (бесплатный ключ) | ✅ |
| 13 | Экономкалендарь (CPI/NFP/FOMC) | M1 | ForexFactory JSON (**только thisweek**) | FRED release calendar | ✅ прогон: 99 событий |
| 14 | Токен-разлоки | S5, M1 | DefiLlama emissions API | CryptoRank, Tokenomist ($) | ⚠️ |
| 15 | Новости RSS | M2 | CoinDesk, Cointelegraph + §2.8 | — | ✅ прогон: 130 записей |
| 16 | Цены стейблов (депег-монитор) | S4, док. 03 | тикеры тех же бирж | CoinPaprika | ✅ |
| **17** | **Kraken Futures: funding (часовой), OI, mark price, спецификации контрактов** | **S1-lite (торговая площадка шорт-ноги!), проверка лотности (записка 10 §6.4)** | **Kraken public API — §2.9** | — | **✅ v3: проверено живыми запросами, geo-блока нет** |

---

## 2. Проверенные источники: эндпоинты и примеры

### 2.1. Binance spot — зеркало `data-api.binance.vision` ✅

Официальное зеркало публичных market-data эндпоинтов **без geo-ограничений** и без ключа (v2: подтверждено прогоном, 33k дневных свечей 2019→). Те же пути и веса, что `api.binance.com` (лимит 6000 weight/мин на IP).

```
GET https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=2
```

Ключевое: **поле с индексом 9 = taker buy base volume** — готовый aggressor delta для док. 08 §2. Интервалы 1s…1M, история с 2017, пагинация `startTime/endTime`, limit ≤ 1000. Закрывает потоки #1, #2, #7, #16.

### 2.2. Binance Vision bulk-архив (для бэктеста) ⚠️

`https://data.binance.vision/` — суточные/месячные zip-дампы: spot и futures klines, `fundingRate`, `metrics` (включая OI), `aggTrades`, `bookDepth`, `liquidationSnapshot`. Шаблон:

```
https://data.binance.vision/data/futures/um/daily/liquidationSnapshot/BTCUSDT/BTCUSDT-liquidationSnapshot-2026-07-14.zip
https://data.binance.vision/data/futures/um/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-2026-06.zip
https://data.binance.vision/data/spot/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-2026-06.zip
```

Из песочницы скачивание не прошло. **Проверить с рабочей машины**: глубину `liquidationSnapshot` и `metrics`. Месячные дампы `fundingRate` — кандидат на глубокую историю funding рынка (§4.6).

### 2.3. OKX public API ✅

Без ключа, лимит ~20 req/2s на эндпоинт. Проверены:

```
GET https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1D&limit=2
GET https://www.okx.com/api/v5/public/funding-rate-history?instId=BTC-USDT-SWAP&limit=3
GET https://www.okx.com/api/v5/rubik/stat/contracts/open-interest-history?instId=BTC-USDT-SWAP&period=1D&limit=3
GET https://www.okx.com/api/v5/rubik/stat/taker-volume?ccy=BTC&instType=SPOT&period=1D
```

Пагинация `before/after`. Предиктивный funding: `GET /api/v5/public/funding-rate?instId=BTC-USDT-SWAP` (поле `nextFundingRate`).

**v2, важно: `funding-rate-history` хранит только ~3 последних месяца.** Backfill глубже невозможен — глубина только через Binance fapi или bulk-архив (§2.2).

### 2.4. Coin Metrics Community API ✅ — on-chain бесплатно (с оговоркой v2)

```
GET https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=CapMVRVCur,PriceUSD&frequency=1d&page_size=3
```

**v2: `CapRealUSD` исключена из community-тарифа** (403, валит весь запрос — метрики идут одним списком). Рабочий набор: `CapMVRVCur, CapMrktCurUSD, SplyCur, PriceUSD` — полная история btc с 2015-01-01, eth с 2015-07-30.

Realized cap восстанавливается арифметически (MVRV = MC/RC):

```
CapRealUSD = CapMrktCurUSD / CapMVRVCur
realized price = CapRealUSD / SplyCur
MVRV Z-score = (CapMrktCurUSD − CapRealUSD) / rolling_std(CapMrktCurUSD)
```

C2 (стартовая версия) и триггеры S6 (кроме supply in profit) закрыты. Лимит: ~10 req / 6 сек. Лаг публикации ~1 сутки — совпадает с моделью `available_at = +24ч` (док. 04 §2.1).

### 2.5. FRED (макро) ✅

CSV без ключа:

```
GET https://fred.stlouisfed.org/graph/fredgraph.csv?id=DFF&cosd=2026-07-01
```

Серии для C4: `DFF` (ставка), `WALCL` (баланс ФРС), `DTWEXBGS` (broad dollar index — прокси DXY), `CPIAUCSL`. Полноценный JSON-API — бесплатный ключ, 120 req/мин.

### 2.6. ForexFactory экономкалендарь ✅ (v2: только thisweek)

```
GET https://nfs.faireconomy.media/ff_calendar_thisweek.json
```

**v2: `nextweek` и `lastweek` удалены (404).** События видны при смене недели у FF — для окон запрета M1 (часы вокруг события) достаточно. Соответствие таблице M1 (док. 05): `impact=High & country=USD → HIGH`. Опрашивать раз в час. Прогон 2026-07-17: 99 событий, идемпотентно.

### 2.7. CoinPaprika ✅ — капитализации без ключа

```
GET https://api.coinpaprika.com/v1/tickers?limit=3
```

Суточный снапшот топ-200 пишется. Free: ~20k вызовов/мес; годится для **суточных снапшотов топ-100 начиная с сегодня**, не для ретроспективы (§4).

### 2.8. Новостные RSS ✅

Все четыре фида живые: `https://www.coindesk.com/arc/outboundfeeds/rss/`, `https://cointelegraph.com/rss`, `https://www.theblock.co/rss.xml`, `https://decrypt.co/feed`. Кандидаты на расширение: блоги Binance/Coinbase, RSS SEC/CFTC.

### 2.9. Kraken public API ✅ (v3 — проверено 2026-07-18, без ключа, без geo-блока)

Работает даже из облачной песочницы (США) — единственная из наших торговых бирж без geo-ограничений на публичные данные.

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
| `api.binance.com`, `fapi.binance.com` | funding history (глубокая), `premiumIndex`, `openInterestHist`, WS `!forceOrder@arr`, WS depth | бесплатно, без ключа | проверить с прод-машины (geo 451 в песочнице). Аккаунт-сервисы Binance в ЕС свёрнуты (док. 12), но публичные data-API работают |
| Bybit `api.bybit.com` | kline, funding, OI, WS `allLiquidation` | бесплатно | то же (geo 403 в песочнице) |
| CoinGecko | капитализации, `market_chart` | demo-ключ, 30 req/мин, **история ≤ 365 дней** | завести ключ |
| Coinglass | агрегированный OI, история ликвидаций | free-ключ | завести ключ, оценить лимиты |
| DefiLlama `api.llama.fi/emissions` | календарь разлоков | бесплатно | проверить с прод-машины |
| Dune | supply in profit, LTH через SQL | free tier | отложено вместе с C2-подметриками |
| Glassnode / CryptoQuant | полный C2 | платно | решение после валидации детектора на MVRV-only |

---

## 4. Критичные ограничения глубины (важно для бэктеста)

1. **OI:** Binance `openInterestHist` — 30 дней. OKX rubik глубже. Коллектор OI работает **с первого дня** и пишет свою историю.
2. **Ликвидации:** live-поток Binance урезан; историю дают только архив `liquidationSnapshot` (§2.2, проверить) или платный Coinglass. Bybit WS полнее — вторым источником.
3. **Исторические капитализации топ-100** (survivorship-free вселенная): самый проблемный бесплатный пункт. Решить до этапа 4 (S2), этапы 0–3 не блокирует.
4. **Funding до 2020:** качать пер-биржево (ставки различаются).
5. **L2 стакан:** только собственная запись с момента запуска.
6. **v2 — funding OKX: глубина ~3 месяца.** Коллектор работает постоянно; глубокая история рынка — Binance fapi с прод-машины и/или месячные дампы bulk-архива.
7. **v3 — funding Kraken: глубина ~12 месяцев часовых ставок через v4-эндпоинт** — для S1-lite этого достаточно без bulk-архивов. Но ставки Kraken ≠ ставкам Binance/OKX (другая формула, часовой цикл, другой поток клиентов): **бэктест и живые решения S1-lite — только на данных Kraken**; данные OKX/Binance остаются для C3 (режимный фон рынка) и S5.

---

## 5. Коллекторы этапа 0: статус

Все пишут в SQLite (`data/crypto.db`) с двумя временами `ts` + `available_at` (док. 04 §1). Идемпотентность подтверждена; изоляция сбоев работает.

| Приоритет | Коллектор | Источник | Частота | Статус |
|---|---|---|---|---|
| 1 | `collector_ohlcv` | data-api.binance.vision | D1 раз в сутки; 1m догрузка | ✅ 33k свечей |
| 2 | `collector_funding` | OKX (+Binance fapi где доступен) | каждые 8ч | ✅ OKX 568 ставок |
| 3 | `collector_oi` | OKX rubik + Binance 5m rolling | каждые 5 мин | ✅ OKX пишет |
| 4 | `collector_onchain` | Coin Metrics Community | раз в сутки | ✅ 33k значений 2015→ |
| 5 | `collector_universe` | CoinPaprika | раз в сутки | ✅ снапшот 200 монет |
| 6 | `collector_macro` | FRED | раз в неделю | ⚠️ прокси-дефект песочницы; перепроверить локально |
| 7 | `collector_calendar` | ForexFactory thisweek | раз в час | ✅ 99 событий |
| 8 | `collector_news_rss` | RSS-пул §2.8 | каждые 10–15 мин | ✅ 130 записей, 4 фида |
| 9 | `collector_liq_ws` | WS Binance + Bybit | постоянно | ⚠️ поток не проверить (geo) |
| **10** | **`collector_kraken`** | **Kraken public API (§2.9)** | **funding: раз в час; tickers (OI/mark/predict): каждые 5 мин; instruments: раз в сутки; спот-OHLC D1: раз в сутки** | **⬜ v3: эндпоинты проверены, коллектор к реализации. Первый запуск — сразу backfill ~12 мес часового funding по PF_XBTUSD и PF_ETHUSD** |

Открытые действия: (a) с прод-машины проверить Binance fapi / Bybit / bulk-архив / DefiLlama; (b) завести бесплатные ключи CoinGecko demo, FRED, Coinglass; (c) решить вопрос ретро-капитализаций (§4.3); **(d) v3: реализовать `collector_kraken` — приоритетно, история funding нужна к неделям 4–6 плана (док. 11)**.
