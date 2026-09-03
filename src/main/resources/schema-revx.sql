-- Стенд проверки маркет-мейкинга на Revolut X (ТЗ — docs/63-tz-dlya-claude-code-stend-proverki.md).
--
-- Отдельный файл БД (data/revx.db), НЕ crypto.db. Две причины:
--   1) объём: 46 символов * 17 280 снимков/сут = ~0.8 млн строк/сут, ~1-1.5 ГБ за неделю;
--   2) crypto.db непрерывно реплицируется Litestream в B2 (free-лимит 10 ГБ) — revx.db
--      в репликацию НЕ добавлять (deploy/SERVERS.md).
--
-- Все времена — epoch millis UTC. Два времени у каждой записи (принцип 1 CLAUDE.md):
--   событие      = server_ts_ms (книга) / ts_ms (сделка)
--   available_at = t_recv_ms / ingest_ms — момент получения нами.
-- Симулятор читает ТОЛЬКО по available_at: это же и запрет ТЗ §4.6 п.4 на будущие данные.

-- Спецификации пар (/api/1.0/public/configuration/pairs).
-- API отдаёт словарь с ключом вида 'ETH/USDC'; в пути книги тот же символ идёт
-- через дефис ('ETH-USDC') — храним канонический слэш-вид.
CREATE TABLE IF NOT EXISTS revx_pair (
    symbol               TEXT    PRIMARY KEY,   -- 'ETH/USDC'
    base                 TEXT    NOT NULL,
    quote                TEXT    NOT NULL,
    base_step            REAL    NOT NULL,
    quote_step           REAL    NOT NULL,
    min_order_size       REAL,
    max_order_size       REAL,
    min_order_size_quote REAL,
    max_order_size_quote REAL,
    status               TEXT    NOT NULL,      -- 'active' | иное
    first_seen_ms        INTEGER NOT NULL,      -- появление пары (ТЗ §3.4)
    last_seen_ms         INTEGER NOT NULL       -- последний раз видели активной
);

-- Снимок книги, одна строка = одна нога. Обе ноги пары связаны snap_id и
-- запрашиваются одним батчем (ТЗ §3.3): без одновременности базис не измерить.
-- Уровни разложены по колонкам (5 на сторону — потолок API).
CREATE TABLE IF NOT EXISTS revx_book (
    symbol       TEXT    NOT NULL,
    t_sent_ms    INTEGER NOT NULL,              -- момент отправки запроса
    snap_id      INTEGER NOT NULL,              -- общий у двух ног одного батча
    leg          TEXT    NOT NULL,              -- 'usdc' | 'usd'
    t_recv_ms    INTEGER NOT NULL,              -- = available_at
    server_ts_ms INTEGER,                       -- metadata.timestamp биржи
    skew_ms      INTEGER,                       -- |t_recv второй ноги - t_recv этой|
    flags        INTEGER NOT NULL DEFAULT 0,    -- битовая маска, см. BookFlags
    bp1 REAL, bq1 REAL, bp2 REAL, bq2 REAL, bp3 REAL, bq3 REAL, bp4 REAL, bq4 REAL, bp5 REAL, bq5 REAL,
    ap1 REAL, aq1 REAL, ap2 REAL, aq2 REAL, ap3 REAL, aq3 REAL, ap4 REAL, aq4 REAL, ap5 REAL, aq5 REAL,
    n_bid        INTEGER NOT NULL,              -- сколько уровней реально отдали
    n_ask        INTEGER NOT NULL,
    PRIMARY KEY (symbol, t_sent_ms)
);

CREATE INDEX IF NOT EXISTS idx_revx_book_snap ON revx_book(snap_id);
CREATE INDEX IF NOT EXISTS idx_revx_book_recv ON revx_book(symbol, t_recv_ms);

-- Лента сделок (/api/1.0/public/trades/all, пагинация по cursor).
-- side биржа отдаёт сама; агрессор это или сторона мейкера — не документировано,
-- поэтому поле хранится как есть, а сверка с книгой делается на чтении.
CREATE TABLE IF NOT EXISTS revx_trade (
    trade_id  TEXT    PRIMARY KEY,
    symbol    TEXT    NOT NULL,
    ts_ms     INTEGER NOT NULL,
    price     REAL    NOT NULL,
    qty       REAL    NOT NULL,
    side      TEXT,
    ingest_ms INTEGER NOT NULL                  -- = available_at
);

CREATE INDEX IF NOT EXISTS idx_revx_trade_symbol_ts ON revx_trade(symbol, ts_ms);

-- Журнал аномалий. ТЗ §8: аномалию не чинить на лету — она обязана попасть
-- в журнал и в отчёт. symbol NOT NULL DEFAULT '' — иначе SQLite пустит NULL в PK.
CREATE TABLE IF NOT EXISTS revx_anomaly (
    ts_ms  INTEGER NOT NULL,
    symbol TEXT    NOT NULL DEFAULT '',
    kind   TEXT    NOT NULL,                    -- ask_order | crossed_book | skew | http_429 | pair_new | pair_gone | ...
    detail TEXT,
    PRIMARY KEY (ts_ms, symbol, kind)
);

-- Поминутная телеметрия потоков → аптайм и доля 429 в разделе «Данные» отчёта (ТЗ §5.1).
CREATE TABLE IF NOT EXISTS revx_uptime (
    minute_ms INTEGER NOT NULL,
    stream    TEXT    NOT NULL,                 -- 'book' | 'trades' | 'pairs'
    requests  INTEGER NOT NULL DEFAULT 0,
    ok        INTEGER NOT NULL DEFAULT 0,
    http_429  INTEGER NOT NULL DEFAULT 0,
    failures  INTEGER NOT NULL DEFAULT 0,
    records   INTEGER NOT NULL DEFAULT 0,       -- записанных снимков/сделок
    PRIMARY KEY (minute_ms, stream)
);

-- Запись прогона симулятора. ТЗ §2: без git-хэша, полного конфига, диапазона
-- данных и версии модели исполнения прогон НЕДЕЙСТВИТЕЛЕН — иначе через неделю
-- нельзя ответить, каким кодом и на каких данных получено число в отчёте.
CREATE TABLE IF NOT EXISTS revx_run (
    run_id        TEXT    PRIMARY KEY,
    created_ms    INTEGER NOT NULL,
    git_hash      TEXT    NOT NULL,          -- с суффиксом -dirty, если дерево грязное
    model_version TEXT    NOT NULL,
    label         TEXT    NOT NULL,          -- 'базовый', 'maker 0.02%', 'контроль: buy&hold', ...
    symbol        TEXT    NOT NULL,
    data_from_ms  INTEGER NOT NULL,
    data_to_ms    INTEGER NOT NULL,
    config_json   TEXT    NOT NULL,
    result_json   TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_revx_run_symbol ON revx_run(symbol, created_ms);

-- Эмпирический замер лимита запросов (ТЗ §3.2): по ступени на строку.
CREATE TABLE IF NOT EXISTS revx_probe (
    run_ms    INTEGER NOT NULL,
    level_rps REAL    NOT NULL,
    attempt   INTEGER NOT NULL,                 -- 1 = ступень, 2 = подтверждение после паузы
    requests  INTEGER NOT NULL,
    ok        INTEGER NOT NULL,
    http_429  INTEGER NOT NULL,
    other     INTEGER NOT NULL,
    p50_ms    INTEGER,
    p95_ms    INTEGER,
    verdict   TEXT,                             -- 'clean' | 'throttled' | 'sustained'
    PRIMARY KEY (run_ms, level_rps, attempt)
);

-- Марк-цена перпа Kraken по минутам (док. 142 §8).
--
-- Кэш, а не источник: ряд приходит с их charts API, но прогон симулятора обязан
-- быть воспроизводимым и не зависеть от сети (ТЗ §7). Первый прогон наполняет,
-- последующие читают. Ключ — перп и минута, поэтому повторная загрузка того же
-- окна ничего не дублирует (принцип 2 из CLAUDE.md).
CREATE TABLE IF NOT EXISTS revx_perp_mark (
    perp   TEXT    NOT NULL,                    -- символ Kraken, например PF_XBTUSD
    ts_ms  INTEGER NOT NULL,                    -- начало минуты
    mark   REAL    NOT NULL,
    PRIMARY KEY (perp, ts_ms)
);
