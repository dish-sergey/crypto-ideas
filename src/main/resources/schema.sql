-- Общее правило (док. 04 §1): у каждой записи два времени —
-- время события и available_at (когда данные стали бы доступны).
-- Бэктест читает только по available_at.
-- Все времена — epoch millis UTC.

CREATE TABLE IF NOT EXISTS candles (
    symbol           TEXT    NOT NULL,
    interval         TEXT    NOT NULL,          -- '1d' | '1m'
    open_time        INTEGER NOT NULL,
    open             REAL    NOT NULL,
    high             REAL    NOT NULL,
    low              REAL    NOT NULL,
    close            REAL    NOT NULL,
    volume           REAL    NOT NULL,
    quote_volume     REAL,
    trades           INTEGER,
    taker_buy_volume REAL,                      -- aggressor delta: sell = volume - taker_buy
    close_time       INTEGER NOT NULL,
    available_at     INTEGER NOT NULL,          -- = close_time
    PRIMARY KEY (symbol, interval, open_time)
);

CREATE TABLE IF NOT EXISTS funding (
    exchange     TEXT    NOT NULL,              -- 'okx' | 'binance'
    symbol       TEXT    NOT NULL,              -- нормализованный: BTCUSDT
    funding_time INTEGER NOT NULL,
    rate         REAL    NOT NULL,
    available_at INTEGER NOT NULL,              -- = funding_time
    PRIMARY KEY (exchange, symbol, funding_time)
);

CREATE TABLE IF NOT EXISTS open_interest (
    exchange     TEXT    NOT NULL,
    symbol       TEXT    NOT NULL,
    ts           INTEGER NOT NULL,
    oi_contracts REAL,                          -- базовая валюта / контракты
    oi_usd       REAL,
    available_at INTEGER NOT NULL,              -- = ts
    PRIMARY KEY (exchange, symbol, ts)
);

CREATE TABLE IF NOT EXISTS onchain_daily (
    asset        TEXT    NOT NULL,              -- 'btc'
    day          TEXT    NOT NULL,              -- 'YYYY-MM-DD'
    metric       TEXT    NOT NULL,              -- 'CapMVRVCur', ...
    value        REAL,
    available_at INTEGER NOT NULL,              -- день + лаг публикации ~30ч (док. 04 §2.1)
    PRIMARY KEY (asset, day, metric)
);

CREATE TABLE IF NOT EXISTS macro_series (
    series_id    TEXT    NOT NULL,              -- 'DFF', 'WALCL', ...
    day          TEXT    NOT NULL,
    value        REAL,
    available_at INTEGER NOT NULL,              -- день + 1 сутки
    PRIMARY KEY (series_id, day)
);

CREATE TABLE IF NOT EXISTS universe_snapshot (
    snap_day       TEXT    NOT NULL,            -- 'YYYY-MM-DD'
    coin_id        TEXT    NOT NULL,            -- 'btc-bitcoin' (coinpaprika id)
    rank           INTEGER NOT NULL,
    symbol         TEXT    NOT NULL,
    market_cap_usd REAL,
    price_usd      REAL,
    volume_24h_usd REAL,
    available_at   INTEGER NOT NULL,            -- момент снапшота
    PRIMARY KEY (snap_day, coin_id)
);

CREATE TABLE IF NOT EXISTS calendar_events (
    source       TEXT    NOT NULL,              -- 'forexfactory'
    event_ts     INTEGER NOT NULL,
    country      TEXT,
    title        TEXT    NOT NULL,
    impact       TEXT,                          -- High | Medium | Low | Holiday
    forecast     TEXT,
    previous     TEXT,
    available_at INTEGER NOT NULL,              -- момент первой загрузки события
    PRIMARY KEY (source, event_ts, country, title)
);

CREATE TABLE IF NOT EXISTS news_items (
    source       TEXT    NOT NULL,              -- хост фида
    guid         TEXT    NOT NULL,
    published_ts INTEGER,
    title        TEXT,
    url          TEXT,
    available_at INTEGER NOT NULL,              -- момент получения нами
    PRIMARY KEY (source, guid)
);

CREATE TABLE IF NOT EXISTS liquidations (
    exchange     TEXT    NOT NULL,              -- 'binance' | 'bybit'
    symbol       TEXT    NOT NULL,
    ts           INTEGER NOT NULL,              -- время события на бирже
    side         TEXT    NOT NULL,              -- BUY | SELL (сторона ордера ликвидации)
    price        REAL,
    qty          REAL,
    available_at INTEGER NOT NULL,              -- момент получения из WS
    PRIMARY KEY (exchange, symbol, ts, side, price, qty)
);

-- Kraken Futures funding — ЧАСОВОЙ (док. 09 §2.9, §4.7): не 8-часовой, как OKX/Binance.
-- Держим отдельно: для S1-lite решения принимаются по ставкам площадки шорт-ноги.
CREATE TABLE IF NOT EXISTS kraken_funding (
    symbol       TEXT    NOT NULL,              -- 'PF_XBTUSD'
    funding_time INTEGER NOT NULL,
    rate         REAL    NOT NULL,              -- fundingRate (абсолютный, за час)
    rel_rate     REAL,                          -- relativeFundingRate (относительный за час)
    available_at INTEGER NOT NULL,              -- = funding_time
    PRIMARY KEY (symbol, funding_time)
);

-- Снапшот тикера Kraken Futures (каждые 5 мин): mark, текущий и предиктивный funding, OI.
-- Закрывает предиктивный funding (док. 09 §1 #4) и живую историю OI Kraken.
CREATE TABLE IF NOT EXISTS kraken_ticker (
    symbol        TEXT    NOT NULL,
    ts            INTEGER NOT NULL,             -- момент снапшота (получен нами)
    mark_price    REAL,
    funding_rate  REAL,                         -- текущая часовая ставка
    funding_pred  REAL,                         -- fundingRatePrediction
    open_interest REAL,                         -- в контрактах
    vol24h        REAL,
    available_at  INTEGER NOT NULL,             -- = ts
    PRIMARY KEY (symbol, ts)
);

-- Детектор режима (док. 01): суточная разметка {BULL,RANGE,BEAR,TRANSITION} + компоненты.
-- available_at = конец дня (когда расчёт стал бы доступен бэктесту).
CREATE TABLE IF NOT EXISTS regime_daily (
    day              TEXT    NOT NULL,          -- 'YYYY-MM-DD'
    c1               REAL,                      -- тренд
    c2               REAL,                      -- on-chain (MVRV)
    c3               REAL,                      -- деривативы
    c4               REAL,                      -- макро
    c5               REAL,                      -- breadth
    score            REAL,
    state            TEXT,                      -- BULL|RANGE|BEAR|TRANSITION
    confidence       REAL,
    days_in_state    INTEGER,
    leverage_warning INTEGER,                   -- 0/1
    available_at     INTEGER NOT NULL,
    PRIMARY KEY (day)
);

-- Детектор режима v2 (док. 01-detektor-rezhima-v2): вместо скаляра score — три
-- ортогональные оси (D/T/S) плюс модификаторы, состояние из набора
-- BULL/BEAR/RANGE/TRANSITION/CRASH. available_at = конец дня. Живёт рядом с
-- regime_daily (v1) для сравнения. NB: без разделителя операторов в тексте
-- комментариев — Db.initSchema делит схему по нему (как в комментариях выше).
CREATE TABLE IF NOT EXISTS regime_daily_v2 (
    day              TEXT    NOT NULL,          -- 'YYYY-MM-DD'
    d                REAL,                      -- direction ∈[−1,+1]
    t                REAL,                      -- trendiness ∈[0,1]
    s                REAL,                      -- stress ∈[0,1]
    cycle_phase      TEXT,                      -- ACCUMULATION|EARLY|MID|LATE|EUPHORIA
    macro_flag       INTEGER,                   -- −1|0|+1
    breadth          REAL,                      -- доля топ-100 > SMA200 ∈[0,1]
    leverage_warning INTEGER,                   -- 0/1
    state            TEXT,                      -- BULL|BEAR|RANGE|TRANSITION|CRASH
    confidence       REAL,
    days_in_state    INTEGER,
    available_at     INTEGER NOT NULL,
    PRIMARY KEY (day)
);

-- Детектор режима v3 (док. 01-detektor-rezhima-v3): ось S и состояние CRASH
-- удалены по итогам диагностики залипания (18-v2). Две оси D/T, четыре состояния
-- BULL/BEAR/RANGE/TRANSITION, vol_z перенесён в модификатор stress_level
-- (по умолчанию не влияет на экспозицию, флаг stress_multiplier_applied).
CREATE TABLE IF NOT EXISTS regime_daily_v3 (
    day                       TEXT    NOT NULL,   -- 'YYYY-MM-DD'
    d                         REAL,               -- direction ∈[−1,+1]
    t                         REAL,               -- trendiness ∈[0,1]
    cycle_phase               TEXT,               -- ACCUMULATION|EARLY|MID|LATE|EUPHORIA
    macro_flag                INTEGER,            -- −1|0|+1
    breadth                   REAL,               -- доля топ-100 > SMA200 ∈[0,1]
    leverage_warning          INTEGER,            -- 0/1
    stress_level              REAL,               -- vol_z-модификатор ∈[0,1], не валидирован
    stress_multiplier_applied INTEGER,            -- 0/1, применялся ли множитель фактически
    state                     TEXT,               -- BULL|BEAR|RANGE|TRANSITION
    confidence                REAL,
    days_in_state             INTEGER,
    available_at              INTEGER NOT NULL,
    PRIMARY KEY (day)
);

-- Детектор режима v5 (док. 01-detektor-rezhima-v5, 22 §A): финал — состояние по
-- одному правилу close>SMA200 (BULL) иначе BEAR, без гистерезиса и dwell. Оси D/T/S
-- и состояния RANGE/TRANSITION/CRASH удалены. Модификаторы сохранены, состояние не
-- меняют. confidence — непроверенная эвристика, для размера капитала НЕ используется.
-- stress_level — диагностический (флаг stress_level_is_diagnostic).
CREATE TABLE IF NOT EXISTS regime_daily_v5 (
    day                       TEXT    NOT NULL,   -- 'YYYY-MM-DD'
    state                     TEXT,               -- BULL|BEAR
    confidence                REAL,               -- непроверенная эвристика
    dist_atr                  REAL,               -- (close − SMA200)/ATR90
    cycle_phase               TEXT,               -- ACCUMULATION|EARLY|MID|LATE|EUPHORIA
    macro_flag                INTEGER,            -- −1|0|+1
    breadth                   REAL,               -- доля топ-100 > SMA200 ∈[0,1]
    leverage_warning          INTEGER,            -- 0/1
    stress_level              REAL,               -- диагностический ∈[0,1]
    stress_level_is_diagnostic INTEGER,           -- всегда 1
    days_in_state             INTEGER,            -- может быть 1 (гистерезиса нет)
    sources_failed            INTEGER,
    available_at              INTEGER NOT NULL,
    PRIMARY KEY (day)
);

CREATE INDEX IF NOT EXISTS idx_candles_avail ON candles(available_at);
CREATE INDEX IF NOT EXISTS idx_liq_symbol_ts ON liquidations(symbol, ts);
CREATE INDEX IF NOT EXISTS idx_news_pub ON news_items(published_ts);
