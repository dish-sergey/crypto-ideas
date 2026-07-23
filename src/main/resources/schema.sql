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

CREATE INDEX IF NOT EXISTS idx_candles_avail ON candles(available_at);
CREATE INDEX IF NOT EXISTS idx_liq_symbol_ts ON liquidations(symbol, ts);
CREATE INDEX IF NOT EXISTS idx_news_pub ON news_items(published_ts);
