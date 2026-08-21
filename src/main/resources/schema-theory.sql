-- База счётных стендов «теории оптимальности» (ТЗ docs/65–68).
-- Отдельный файл (data/theory.db): это оффлайн-стенды, их прогоны не должны
-- ехать в Litestream-реплику crypto.db и не должны мешать коллекторам.
-- Все времена — epoch millis UTC (требование §2 всех четырёх ТЗ).

-- Запись прогона. БЕЗ НЕЁ ПРОГОН НЕДЕЙСТВИТЕЛЕН (§2 «Воспроизводимость»):
-- git-хэш, полный конфиг, диапазон данных, версия алгоритма, seed, результат.
CREATE TABLE IF NOT EXISTS theory_run (
    run_id        TEXT    NOT NULL PRIMARY KEY,
    created_ms    INTEGER NOT NULL,
    git_hash      TEXT    NOT NULL,
    module        TEXT    NOT NULL,          -- 'alloc' | 'kelly' | 'ou' | 'band'
    algo_version  TEXT    NOT NULL,
    label         TEXT,
    data_from_ms  INTEGER,
    data_to_ms    INTEGER,
    seed          INTEGER,
    config_json   TEXT    NOT NULL,
    result_json   TEXT
);

-- ---------------------------------------------------------------------------
-- Датасет событий S5 (разлоки). Общий для ТЗ 65 (кривая S5 в пуле) и ТЗ 66
-- (сайзинг). Импортируется один раз (--theory=s5-import), дальше все прогоны
-- идут оффлайн и воспроизводимы: живой fapi/CoinGecko в счётный контур не ходят.
-- ---------------------------------------------------------------------------

-- Разлок: cliff-событие из кэша DefiLlama emissions (источник стал платным,
-- CLAUDE.md «Ограничения источников» — работаем с уже снятым кэшем).
CREATE TABLE IF NOT EXISTS s5_event (
    base          TEXT    NOT NULL,          -- базовый актив, 'ARB'
    unlock_day    TEXT    NOT NULL,          -- 'YYYY-MM-DD', день разблокировки
    pct_supply    REAL    NOT NULL,          -- доля циркулирующего supply
    tokens        REAL,                      -- штук токенов в разлоке
    source        TEXT    NOT NULL,          -- 'defillama-cache'
    imported_ms   INTEGER NOT NULL,
    PRIMARY KEY (base, unlock_day)
);

-- Дневная цена перпа Binance по активам событий (для окна входа/выхода и стопа).
CREATE TABLE IF NOT EXISTS s5_price (
    base          TEXT    NOT NULL,
    day           TEXT    NOT NULL,
    open          REAL    NOT NULL,
    high          REAL    NOT NULL,
    low           REAL    NOT NULL,
    close         REAL    NOT NULL,
    imported_ms   INTEGER NOT NULL,
    PRIMARY KEY (base, day)
);

-- Суточная сумма funding-ставок перпа (шорт получает rate>0, платит rate<0).
CREATE TABLE IF NOT EXISTS s5_funding_daily (
    base          TEXT    NOT NULL,
    day           TEXT    NOT NULL,
    rate_sum      REAL    NOT NULL,
    imported_ms   INTEGER NOT NULL,
    PRIMARY KEY (base, day)
);

-- Соответствие coingecko-id → тикер перпа (чтобы не ходить в CoinGecko повторно).
CREATE TABLE IF NOT EXISTS s5_symbol_map (
    gecko_id      TEXT    NOT NULL PRIMARY KEY,
    base          TEXT    NOT NULL,
    imported_ms   INTEGER NOT NULL
);
