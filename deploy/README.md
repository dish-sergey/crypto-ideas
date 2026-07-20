# Деплой (Docker)

Слой данных как контейнер: планировщик коллекторов + WS-ликвидации, с
непрерывным бэкапом БД в Cloudflare R2 через Litestream.

```
deploy/
  Dockerfile          multi-stage: Liberica JDK 25 (сборка) → JRE 25 (runtime)
  docker-compose.yml  сервисы app + litestream (litestream под профилем backup)
  litestream.yml      репликация SQLite → R2
  .env.example        R2-креды (скопировать в .env)
  snapshot.sh         опциональный холодный снапшот-«якорь» (VACUUM INTO + gzip)
```

**Данные живут на хосте** (`../data` = `data/` в корне репо), не в образе.
Образ пересобирается на каждый деплой; невосполнимая БД — в томе.

## Предпосылки

- Docker Desktop (Windows/Mac) или Docker Engine + compose-плагин (Linux).
- Больше ничего: Java 25 ставить на хост не нужно, она внутри образа.

> Если тег базового образа не найдётся (`bellsoft/liberica-*:25`), поправь тег
> в `Dockerfile` на актуальный. Litestream при недоступности `:0.3` — `:latest`.

## Локальный smoke-test (без бэкапа)

Из папки `deploy/`:

```
docker compose up --build
```

Поднимется только `app` (Litestream под профилем `backup` не активен, `.env` не
нужен). В логах ожидаем:

- `SQLite открыт: /app/data/crypto.db`
- `liq-bybit запущен` (Binance-поток отсюда молчит — это ожидаемо)
- срабатывания коллекторов по расписанию (oi через ~20с, news через ~15с…)

Остановить: `Ctrl-C`, затем `docker compose down`. БД останется в `../data`.

Разовый прогон (one-shot, отработал и вышел):

```
docker compose run --rm app --collect=all
docker compose run --rm app --backfill=ohlcv --symbols=BTCUSDT --interval=1m --from=2023-01-01
```

## Включить бэкап (R2 + Litestream)

1. В дашборде Cloudflare R2 создать бакет (напр. `crypto-ideas-backup`) и S3
   API-токен (Access Key ID + Secret).
2. `cp .env.example .env` и заполнить `R2_ENDPOINT`, `R2_BUCKET`, ключи.
3. Поднять с бэкапом:

   ```
   docker compose --profile backup up -d --build
   ```

Litestream начнёт непрерывно реплицировать `crypto.db` в R2 (суточные снапшоты,
WAL за 72ч → восстановление на любую точку за трое суток). Проверка:
`docker logs crypto-litestream`.

Восстановление на новом хосте:

```
docker run --rm --env-file .env -v "$(pwd)/../data:/app/data" \
  -v "$(pwd)/litestream.yml:/etc/litestream.yml:ro" \
  litestream/litestream:0.3 restore -config /etc/litestream.yml /app/data/crypto.db
```

## Передеплой без пробелов (make-before-break)

Все записи идемпотентны (upsert по естественному PK), а `busy_timeout=5000`
позволяет двум процессам писать в один WAL-файл. Поэтому новый контейнер можно
поднять, **не гася старый** — дублей не будет, окно потери ликвидаций = 0
(остальное — OI/свечи/funding — и так догоняется на старте инкрементально).

```
# 1. собрать новый образ
docker compose build

# 2. поднять второй контейнер параллельно работающему app
docker run -d --name crypto-app-blue -v "$(pwd)/../data:/app/data" crypto-ideas:latest

# 3. убедиться, что новый жив (идут WS-фреймы, открылась БД)
docker logs -f crypto-app-blue      # ждём 'liq-bybit запущен' и тики коллекторов

# 4. погасить старый и снять blue, затем штатно пересоздать app из нового образа
docker compose stop app && docker rm crypto-app
docker rename crypto-app-blue crypto-app        # либо: docker rm -f crypto-app-blue && docker compose up -d
```

Проще (ценой ~секунд простоя ликвидаций) — `docker compose up -d --build`:
compose пересоздаст контейнер, короткий разрыв WS. На раннем этапе с редкими
деплоями это допустимо; make-before-break — когда разрыв недопустим.

> Когда фич станет много и `app` начнёт часто перезапускаться под новый код —
> вынести WS-ликвидации + OI в отдельную роль (`--role=capture`), которая
> деплоится редко и всегда через overlap, а `batch`-часть (всё восстановимое) —
> как угодно часто. Пока одна нода — не нужно.

## Опциональный холодный снапшот

```
./snapshot.sh                 # → ./snapshots/crypto-YYYYMMDD-HHMM.db.gz
```

Самодостаточный файл на случай ручного восстановления; Litestream это не
заменяет, а дополняет.
