# Серверы и сервисы (runbook)

Что где крутится, чтобы не путаться при добавлении инстансов. Секретов тут нет —
только расположение файлов и команды.

## Инстансы

| Роль | Инстанс | Спека | Регион | IP | Доступ |
|---|---|---|---|---|---|
| **micro** (сбор + ловушка) | `instance-2026...` | Oracle Always Free AMD (E2.1.Micro), 1 CPU / 1 ГБ (+2 ГБ swap), 41 ГБ, Ubuntu 20.04 | eu-frankfurt-1 | `89.168.115.160` | `ssh -i D:\servers\oracle\instance-20260722-1110\ssh-key-2026-07-22.key ubuntu@89.168.115.160` |
| **arm1** (компьют) | `bot-arm` — **пойман 19.08.2026 09:42 UTC** | Oracle Always Free ARM A1.Flex, **1 CPU / 6 ГБ**, Ubuntu 22.04 | eu-frankfurt-1 | `130.61.31.216` | `ssh -i ~/.ssh/armkey ubuntu@130.61.31.216` **с micro** (копия ключа: `D:\servers\oracle\arm1\private.key`) |
| **arm2** (второй 1/6) | `bot-arm-2` — **ловим** | Oracle Always Free ARM A1.Flex, 1 CPU / 6 ГБ | eu-frankfurt-1 | — | будет: `ssh -i ~/.ssh/armkey ubuntu@<IP>` **с micro** (тот же ключ) |

> Ключевая причина, почему сбор на micro: из Frankfurt (ЕС) доступны `fapi.binance.com`
> и `api.bybit.com`, заблокированные с локальной машины/US-песочницы. Значит отсюда
> берутся Binance funding/OI/ликвидации. micro — хорошее постоянное место для сбора.

> ⚠️ **Локальная копия ключа ARM** (`D:\servers\oracle\arm1\private.key`) приехала из
> консоли Oracle **без завершающего перевода строки** — openssh на это отвечает
> `error in libcrypto: unsupported` / `invalid format`, что выглядит как «битый ключ».
> Лечится дописыванием `\n` в конец файла (сделано 19.08.2026). Права на файл —
> только чтение для владельца, иначе ssh его игнорирует.
>
> ⚠️ **И то же самое из-за CRLF** (26.08.2026): файл лежит с виндовыми переводами
> строк, openssh отвечает ровно тем же `error in libcrypto: unsupported`. Рабочая
> копия делается на месте, а оригинал не трогается:
> `tr -d '\r' < D:\servers\oracle\arm1\private.key > /tmp/armkey && chmod 600 /tmp/armkey`.
> Ключ к micro (`ssh-key-2026-07-22.key`) — тот же случай.

---

## Сервисы на micro (systemd, все `enabled` — переживают ребут)

### 1. `crypto-data` — слой данных (сбор)
- **Что:** `java -Xms64m -Xmx256m -jar ~/crypto-data/app.jar` (без аргументов = планировщик 9 коллекторов + WS-ликвидации Binance/Bybit).
- **Юнит:** `/etc/systemd/system/crypto-data.service`
- **Рантайм:** Liberica **JRE 25 glibc** в `~/jre25` (musl-вариант НЕ запускается на Ubuntu!).
- **БД:** `~/crypto-data/data/crypto.db` (SQLite WAL). `WorkingDirectory=~/crypto-data`.
- **Проверить:** `systemctl status crypto-data` · `journalctl -u crypto-data -f`
- **Обновить jar:** собрать локально (`./gradlew bootJar`) → `scp build/libs/crypto-ideas-1.0-SNAPSHOT.jar ubuntu@89.168.115.160:~/crypto-data/app.jar` → `sudo systemctl restart crypto-data`.
  - **Без перезапуска (если сбор не менялся, а нужен только новый детектор для cron):** scp во временный файл → **атомарный `mv app.jar.new app.jar`**. Работающий JVM держит старый inode (сбор не прерывается, без дыры в WS-ликвидациях), а cron берёт новый jar. Так v3 и заводили 2026-08-04 (бэкап старого — `app.jar.bak-jul24`).
- **NB:** `onchain` (cron 07:00 UTC) и `macro` (cron пн 07:30) на старте не бегут. На свежей БД сеять вручную: `cd ~/crypto-data && ~/jre25/bin/java -jar app.jar --collect=macro --backfill=onchain --from=2015-01-01`.

### 2. `litestream` — бэкап БД → Backblaze B2
- **Что:** `litestream replicate` — непрерывная репликация в B2, суточные снапшоты, retention 72ч (PITR 3 суток).
  **Две БД** в одном процессе (`dbs:` с двумя entry): `crypto-data/data/crypto.db` (префикс `crypto-ideas`) и
  **`~/s5/state/s5.db`** (префикс `s5-trade`, добавлено 2026-08-22 — аудит live-сделок S5, нельзя терять).
- **Версия:** Litestream **v0.3.13** (демон `replicate`; v0.5.x — это VFS-`.so`, не годится).
- **Юнит:** `/etc/systemd/system/litestream.service`
- **Конфиг:** `/etc/litestream.yml` (ссылается на env-переменные; бэкап прежнего — `.bak-pre-s5`).
- **Секреты B2:** `/etc/litestream.env` (права `600 root`, читает systemd как root). Копия ключей у тебя в `D:\servers\backblaze\key.txt`. В git НЕ коммитим.
- **B2:** бакет `ideas-backup-dish7` (id `1003dc43b957910c9ffd0915`), регион `eu-central-003`, endpoint `s3.eu-central-003.backblazeb2.com`.
- **⚠️ Скрытые версии (ловушка Litestream+B2):** Litestream по retention постоянно удаляет WAL-сегменты,
  а B2 по умолчанию хранит удалённые версии вечно → бакет пухнет (2026-08-17: живых данных 602 МБ / 22к
  объектов, а B2 показывал **8 ГБ** — ~7.4 ГБ скрытых версий, близко к free-лимиту 10 ГБ). **Фикс поставлен:**
  B2 lifecycle rule `daysFromHidingToDeleting=1` (скрытые версии удаляются через сутки) — задано через
  `b2_update_bucket` (ключ из `key.txt` имеет `writeBucketLifecycleRules`). Разовая очистка накопленного —
  `rclone cleanup :b2:ideas-backup-dish7` (b2-backend, креды `RCLONE_B2_ACCOUNT`/`RCLONE_B2_KEY` из `key.txt`,
  файл в CRLF — стрипнуть `\r`). Проверить размеры: `rclone size :b2:<bucket>` (живые) vs `--b2-versions` (все).
- **Проверить:** `systemctl status litestream` · `journalctl -u litestream -f`
- **Список снапшотов:** `sudo bash -c 'set -a; . /etc/litestream.env; litestream snapshots -config /etc/litestream.yml <ПУТЬ_К_БД>'` (crypto.db или `~/s5/state/s5.db`).
- **Восстановить БД:** `sudo bash -c 'set -a; . /etc/litestream.env; litestream restore -config /etc/litestream.yml -o /tmp/restored.db <ПУТЬ_К_БД>'` — проверять под sudo (restore пишет root-owned файл): `sudo sqlite3 /tmp/restored.db "PRAGMA integrity_check;"`. Восстановление s5.db проверено 2026-08-22 (integrity ok).

### 3. `arm-catch` — ловушка ARM
- **Что:** `~/arm_catch.sh` в цикле пытается запустить бесплатный A1.Flex по 3 зонам Frankfurt через OCI instance principal. Поймает — `exit 0`, сервис встаёт сам.
- **Исходник в git:** `deploy/arm_catch.sh` (на micro лежит копия; старая версия — `~/arm_catch.sh.bak-v1`).
- **v2 (19.08.2026, после первой поимки):** предохранитель считает **бюджет** Always Free, а не «есть ли хоть один A1»: суммирует OCPU/RAM живых A1 и сравнивает с потолком. Поэтому после пойманного 1/6 ловушка едет дальше. Имя нового инстанса — `bot-arm-<N+1>` (дублей имён нет). Ответ `LimitExceeded/QuotaExceeded` → выход (не долбимся).
- **Настройки (env, можно задать в юните):** `SHAPES` (по умолчанию `1:6`; формат `"2:12 1:6"` — по приоритету), `FREE_OCPUS`/`FREE_MEM` (по умолчанию **2/12** — консервативно, т.к. Oracle документирует 4/24, но тенанту может быть выдано меньше), `PAUSE` (30 с), `NAME`.
- **Юнит:** `/etc/systemd/system/arm-catch.service`
- **OCI CLI:** `~/bin/oci` → симлинк на venv **Python 3.11** (`~/lib/oci-py311`, ставился через `uv`; PATH в юните через `Environment=PATH=...`).
- **Ключ для пойманного ARM:** `~/.ssh/armkey` (+ `.pub`) — прописывается в новый инстанс.
- **Проверить:** `systemctl is-active arm-catch` · `journalctl -u arm-catch -f` (строки «занято (круг N)»)
- **Поймал?:** `cat ~/arm-catch-SUCCESS.log 2>/dev/null && echo ПОЙМАНО || echo "ещё ловим"` (лог накопительный — по строке на поимку; `systemctl is-active` = `inactive` после успеха)
- **Запустить на следующую поимку:** обновить `~/arm_catch.sh` из `deploy/arm_catch.sh` (при изменениях) → `sudo systemctl start arm-catch`. Если в остаток бюджета ничего не влезает, скрипт сам напишет «бюджет Always Free исчерпан» и выйдет.
- **Настройка политики (Oracle-консоль):** dynamic-group `arm-catcher-dg` + policy `arm-catcher-policy`; при Identity Domains имя группы с префиксом домена: `dynamic-group 'Default'/'arm-catcher-dg'`.

### 4. `dash` — HTML-дашборд детектора (только tailnet)
- **Что:** `python3 -m http.server 8088 --directory ~/dash --bind <tailscale-ip>` — раздаёт `~/dash/` (визуализация режима; `index.html` — меню со всеми графиками). Привязан к tailnet-IP → публично **недоступен**, только устройства твоего Tailscale.
- **Юнит:** `/etc/systemd/system/dash.service`
- **Доступ:** `http://100.64.144.85:8088/` или `http://crypto-micro:8088/` (MagicDNS) с любого устройства в tailnet.

### 5. `detector-report.timer` — суточное обновление дашборда
- **Что:** раз в сутки 08:00 UTC гоняет `~/update-dashboard.sh` на боевой БД (`Persistent=true`).
  Скрипт делает backfill трёх версий детектора и рендерит дашборд **одной командой**:
  - `--backfill=regime-v5` (+ `regime-v3`, `regime`) → затем `--report=regime-dash --out=~/dash`.
  - Это кладёт в `~/dash`: `index.html` (**меню** — карточки версий с текущим состоянием и лентой
    за год), `regime-v5.html` (**ПРОД**, `close>SMA200`), `regime-v3.html` (оси D/T),
    `regime-v1.html` (композит), `regime-all.html` (три ленты). `regime-compare.html` (v1 vs v2)
    на micro **не рендерится**: таблица `regime_daily_v2` там пустая — карточка просто не появляется
    в меню (штатное поведение, не ошибка).
  - Период на каждом графике переключается **на клиенте** (1М/3М/6М/YTD/1Г/2Г/Всё, произвольные
    даты, ◀/▶ или стрелки), окно попадает в hash — `regime-v5.html#w=30`, `#w=2024-01-01..2024-06-30`.
  - **Миграция сделана 20.08.2026:** `index.html` теперь меню, а не v5 (сам v5 — `regime-v5.html`);
    четыре вызова `--report=...` в `~/update-dashboard.sh` заменены одним `--report=regime-dash`.
    Бэкапы: `~/update-dashboard.sh.bak-dash`, `~/crypto-data/app.jar.bak-dash` (jar подменён
    атомарным `mv`, без рестарта `crypto-data`).
- **Юниты:** `/etc/systemd/system/detector-report.{service,timer}`, скрипт `~/update-dashboard.sh` (бэкап `.bak-v3`).
- **Проверить:** `systemctl list-timers detector-report.timer` · `journalctl -u detector-report`
- **Обновить вручную:** `sudo systemctl start detector-report.service` (или `bash ~/update-dashboard.sh` от ubuntu).
- **Дашборд:** `http://crypto-micro:8088/` = **меню**; с него ссылки на v5 (прод), v3, v1, общий, сравнение
  (и быстрые периоды 1М/3М/6М/1Г прямо с карточки).
- **Откат:** `mv app.jar.bak-dash app.jar` + `cp update-dashboard.sh.bak-dash update-dashboard.sh`
  (более старые бэкапы: `app.jar.bak-v3`, `update-dashboard.sh.bak-v3`). Jar менялся атомарным mv, без рестарта crypto-data.

### 6. `s5-live` — S5 LIVE (реальные ордера Kraken Futures) + Telegram-бот
- **Что:** `~/jre25/bin/java -Xms32m -Xmx192m -jar ~/s5/app.jar --s5-live` — оркестратор S5 против живого
  фида разлоков (DefiLlama) + **реальное исполнение на Kraken Futures** (`KrakenFuturesExchange` +
  `KrakenFundingSource`). Деньги настоящие. Защита: ручной Approval Gate (ордер только по кнопке),
  стоп −30%, фильтр дорогого шорта, лимит экспозиции ≤81%, проверка средств+мин.ордера. На старте
  `recover()` усыновляет позиции с биржи. Telegram: пуши, эскалация 24/12/3/1ч, кнопки, `/status`,
  `/positions`. Копия jar `~/s5/app.jar`, `WorkingDirectory=~/s5`.
- **Юнит:** `/etc/systemd/system/s5-live.service` (enabled). Restart=on-failure.
- **Секреты:** `~/s5/telegram/s5_bot.txt` (бот `@k7pQ2m_note_bot`) + `~/s5/kraken/keys.txt`
  (api_key/api_secret, права Kraken Futures General=Full, Withdrawal=No). Оба 600, gitignored. Либо env
  `S5_TELEGRAM_*` / `S5_KRAKEN_*`. В git НЕ коммитим.
- **Проверить:** `systemctl status s5-live` · `journalctl -u s5-live -f` (старт ~2–3 мин: CoinGecko +
  скан ~370 протоколов DefiLlama, потом «слушатель запущен»; нет `authenticationError` = ключ ок).
- **Обновить jar:** `./gradlew bootJar` → `scp build/libs/crypto-ideas-1.0-SNAPSHOT.jar
  ubuntu@89.168.115.160:~/s5/app.jar` → `sudo systemctl restart s5-live`.
- **⚠️ Один потребитель Telegram getUpdates:** `s5-dryrun` и `s5-live` с одним ботом ОДНОВРЕМЕННО нельзя
  (409 conflict). Запущен `s5-live`; `s5-dryrun` остановлен и disabled (переключение обратно — наоборот).
- **Read-only проверка ключа без ордеров:** `--s5-kraken-check`. Демо цикла без ордеров: `--s5-demo`.
- **NB:** тяжёлый скан фида — раз в день; ежеминутно — напоминания + реальные стопы. С пустым счётом
  входы отклоняются по нехватке средств (сейф). Пополнить — перевод в Futures(flex)-кошелёк на pro.kraken.com.

### 7. `revx-backup` (cron на micro) — ИНКРЕМЕНТНАЯ копия базы стенда с ARM
- **Что:** `15 3 * * * /home/ubuntu/revx-backup.sh >> ~/revx-backup.log` — копируются
  только новые строки (`rowid > курсор`) в `/tmp/revx-inc.db` **на ARM**, gzip, забор
  на micro в `~/revx-backups/revx-inc-YYYYMMDD-HHMM.db.gz`, хранение 60 суток.
  Скрипт в репо: `deploy/revx-backup.sh` (там же процедура восстановления).
- **Курсор:** `~/revx/data/backup-cursor.txt` на ARM (`<max rowid книги> <max rowid сделок>`),
  двигается только после успешного `scp`. Нет файла → выгрузится вся база.
- **⚠️ ПОЧЕМУ НЕ `.backup` (инцидент 25–26.08.2026):** `sqlite3 .backup` перезапускает
  копирование при каждой записи источника, и на базе >1 ГБ не заканчивается никогда:
  20.08 — 6 с, 24.08 — **21 мин** (6 разрывов в данных), 25.08 **не завершился**,
  провисел 34 часа, поверх встал второй. Сбор упал с 720 до 18 снимков/час,
  **потеряно ~33 часа книги безвозвратно**. Сервис при этом был `active`, 429 ноль,
  health-файл обновлялся — мониторинг смотрел на факт записи, а не на темп.
  Разбор — `docs/74-stend-revolut-x-chistoe-okno-i-otchet-5.md`.
- **Почему с micro, а не с ARM:** ключ есть только в направлении micro → ARM (`~/.ssh/armkey`).
- **Почему не Litestream:** он реплицирует `crypto.db` в B2 с free-лимитом 10 ГБ; поток
  стенда туда подмешивать нельзя. `revx.db` в Litestream **не добавлять**.
- **Проверить:** `ls -la ~/revx-backups/` · `tail ~/revx-backup.log`. Первый инкремент
  26.08.2026 — 2.6 суток данных, 49 МБ, **26 секунд**. Полные копии до 24.08
  (`revx-2026082*.db.gz`) — база цепочки, удалять их нельзя.
- **Если сбор просел:** `ps -eo pid,etime,args | grep [s]qlite3` на ARM — не висит ли
  снимок; `uptime` (load >3 на одном ядре = кто-то грызёт диск); темп —
  `select strftime('%m-%d %H',t_sent_ms/1000,'unixepoch') h, count(*) from revx_book
  where symbol='BTC/USDC' group by h` (норма 720/час).

### 8. `revx-rate-check` (cron на micro) — тревога по ТЕМПУ сбора стенда
- **Что:** `*/15 * * * * /home/ubuntu/revx-rate-check.sh >> ~/revx-rate-check.log` —
  считает снимки BTC/USDC за последние 10 минут на ARM и будит в Telegram, если их
  меньше 72 при норме 120. Скрипт в репо: `deploy/revx-rate-check.sh`.
- **Зачем:** инцидент 25–26.08.2026 (зависший `.backup`) уронил сбор с 720 снимков
  в час до 18 и держал 33 часа, а все существовавшие проверки показывали «здоров»:
  сервис `active`, рестартов ноль, 429 ноль, health-файл обновлялся. Проверять надо
  темп, а не факт записи.
- **Куда будит:** бот S5 (`~/s5/telegram/s5_bot.txt`), но **только отправка** —
  `getUpdates` отсюда не вызывается, конфликта с живым слушателем S5 нет. Текст
  всегда с префиксом «🔧 СТЕНД revx (это НЕ S5)», чтобы не путать с торговлей.
- **Антиспам:** состояние в `~/.revx-rate-state`, одна тревога на эпизод плюс одна
  на восстановление.
- **Ничего не чинит сам:** гасить сбор автоматикой опаснее, чем разбудить человека.
- **Проверить:** `tail ~/revx-rate-check.log` · тестовая тревога —
  `MIN_ROWS=99999 bash ~/revx-rate-check.sh` (пришлёт сообщение в Telegram).

### Tailscale (приватная сеть)
- Mesh-VPN (WireGuard). Micro = `crypto-micro` (100.64.144.85). Даёт приватный доступ к дашборду без открытия портов и без домена. Бесплатно (personal). Авторизация — по устройствам аккаунта `dish.sergey@`. Проверка: `tailscale status`.

---

## Сервисы на bot-arm (ARM, 130.61.31.216)

### 1. `revx-collect` — сбор данных стенда Revolut X
- **Что:** `~/jre25/bin/java -Xms32m -Xmx160m -jar ~/revx/app.jar --revx-collect` —
  ярусный сбор книг и лент сделок по 23 USDC-парам (ТЗ — `docs/63-...`, план —
  `plans/2026-08-19-revx-mm-stand.md`). Ордера не отправляет, ключей не хранит.
- **Юнит:** `/etc/systemd/system/revx-collect.service` (enabled, Restart=always).
  Исходник в репо: `deploy/revx-collect.service`.
- **Рантайм:** Liberica **JRE 25 aarch64** в `~/jre25` (ставился с github bell-sw, 25.0.4+9).
- **БД:** `~/revx/data/revx.db` (SQLite WAL), `WorkingDirectory=~/revx`. Рост ~1 МБ/час.
- **Почему на ARM, а не на micro:** на micro свободно ~260 МБ при трёх JVM, на ARM — 5.2 ГБ.
  Регион тот же (Frankfurt), площадка оттуда отвечает за ~110 мс.
- **Проверить:** `systemctl status revx-collect` · `journalctl -u revx-collect -f` ·
  `cat ~/revx/data/revx-health.txt` (время последней записи + доля 429).
- **Обновить jar:** `./gradlew bootJar` → `scp build/libs/crypto-ideas-1.0-SNAPSHOT.jar
  ubuntu@130.61.31.216:~/revx/app.jar` → `sudo systemctl restart revx-collect`.
- **Ключ API (только чтение):** `~/revx/keys/private.pem` (600, Ed25519) +
  `~/revx/keys/api_key.txt` (600, 64 символа) + `public.pem` — он зарегистрирован
  в веб-приложении Revolut X, там же прописан IP-whitelist на `130.61.31.216`.
  В git не попадают. С ключом лимиты 100 req/с и 1000/мин, сбор идёт по всем
  23 парам раз в 5 с; без ключа сервис не падает, а переходит на публичные
  эндпоинты с 0.8 req/s и ярусным расписанием.
- **Быстрый ярус (с 27.08.2026):** `BTC/USDC` + `BTC/USD` опрашиваются **раз в
  секунду**, остальные 22 пары — как прежде раз в 5 с. Причина: задержка
  котирования оказалась самым дорогим измеренным параметром (док. 88), а левую
  часть кривой в симуляции не получить — данные собраны с шагом 5 с.
  Настройки — `revx.fast-pairs` и `revx.fast-book-period-ms`; пусто = выключено.
  Нагрузка после включения: **11.18 req/s (671/мин) при лимите площадки 1000/мин**,
  потолок бакета поднят с 12 до 14 req/s. Бюджет проверяется в коде до старта:
  если расчёт превышает потолок, ярус сам выключается с предупреждением — рисковать
  429 по всей вселенной ради одной пары нельзя. Рост базы: ~185 → ~230 МБ/сутки.
- **⚠️ Публичный лимит ~1 req/s на IP** и запрет запросов встык (см. CLAUDE.md).
  Второй сборщик с того же адреса запускать нельзя — начнутся 429.
- **NB:** в `~/revx/data/` лежит пустой `crypto.db` — его создаёт основной бин `Db`
  при любом запуске приложения; коллекторы слоя данных в режиме `--revx-collect`
  не работают (расписание выключено через `CliMode`). 10 МБ мусора там появились
  19.08.2026 от случайно запущенного вручную экземпляра без аргументов — можно
  удалить при следующем рестарте сервиса.

### 2. `revx-exec` — микро-live Revolut X (РЕАЛЬНЫЕ ордера)

- **Что:** `java -jar ~/revx-exec/app.jar --revx-exec` — котирование BTC/USDC
  заявками по ~$1. **Это измерительный прибор, а не стратегия:** меряется доля
  исполнений, предсказанных моделью (прогноз — 104 в сутки и 6.0% с отрицательным
  захватом, док. 91 §3). P&L на таких размерах не значим.
- **Юнит:** `/etc/systemd/system/revx-exec.service`, исходник — `deploy/revx-exec.service`.
- **⚠️ `Restart=no` намеренно.** Перезапуск после падения не должен сам
  возобновлять торговлю. Котирование в любом случае стартует **ВЫКЛЮЧЕННЫМ** и
  включается только командой `/start` в Telegram. **После каждого деплоя надо
  заново дать `/start`.**
- **⚠️ Отступ живого ≠ базис симуляции (с 01.09.2026).** `revx.exec.offset` =
  **10 б.п.**, `revx.sim.offset` = 14 б.п. Живое стоит на рабочей точке,
  измеренной вне выборки (док. 113 §5: вершина «край × оборот» и формула
  `δ* = c + 1/κ = 10.62` сошлись), базис симуляции оставлен ради сопоставимости
  доков 74–113. **Сверять живое надо со СТУПЕНЬЮ 0.1% лестницы отступа**, а не с
  базовым прогоном. Расхождение печатается при старте и пишется в `exec_event`.
- **Секреты:** `/etc/revx-exec.env` (600 root) — торговый ключ, base64 приватного
  ключа Ed25519, токен бота, chat_id. В каталоге приложения ключей нет.
- **База стенда открывается `mode=ro`:** разделение стенда и исполнителя (ТЗ §0)
  держится на флаге драйвера. Справедливая цена читается оттуда же, откуда её
  берёт симулятор, — иначе расхождение можно списать на разные данные.
- **Журнал:** `~/revx-exec/state/exec.db` — каждый запрос и ответ (ТЗ §6).
- **Бот:** `@dYdns3_rev_bot`, ОТДЕЛЬНЫЙ от S5. Два потребителя `getUpdates` на
  одном токене дают 409 — не запускать вручную `getUpdates` этим токеном, пока
  сервис живёт. Команды: `/status`, `/stats`, `/start`, `/stop`, `/panic`,
  `/limits`. Параметров бот не меняет.
- **Аварийно:** `/panic` в чате, либо `sudo systemctl stop revx-exec`
  (`ExecStopPost` добивает заявки), либо вручную:
  `cd ~/revx-exec && sudo bash -c 'set -a; . /etc/revx-exec.env; set +a; exec sudo -u ubuntu -E ~/jre25/bin/java -jar app.jar --revx-panic'`
- **Проверить:** `systemctl status revx-exec` · `journalctl -u revx-exec -f` ·
  `sqlite3 -readonly ~/revx-exec/state/exec.db "select method,path,status,count(*) from exec_request group by 1,2,3;"`

---

## Прочее, установленное на micro
- `uv` (~/.local/bin) + standalone Python 3.11 — для oci-cli.
- `sqlite3` (apt) — инспекция БД.
- swap 2 ГБ (`/swapfile`, в `/etc/fstab`).
- Порты наружу **не открыты** — весь трафик исходящий.

## Секреты — где лежат (никогда в git)
- `/etc/litestream.env` (micro) — ключи B2. Копия: `D:\servers\backblaze\key.txt` (локально).
- OCI — через **instance principal**, ключей на диске нет.
- SSH к micro — `D:\servers\oracle\instance-20260722-1110\ssh-key-2026-07-22.key` (локально).
- SSH к bot-arm — `D:\servers\oracle\arm1\private.key` (локально) и `~/.ssh/armkey` на micro.

---

## Когда поймается ARM (`bot-arm`) — план

1. В Oracle-консоли появится инстанс `bot-arm` с публичным IP. Зайти **с micro**:
   `ssh -i ~/.ssh/armkey ubuntu@<IP_ARM>`.
2. `arm-catch` на micro сам остановится (поймал). Больше не трогать.
3. **Разделение ролей** (предложение):
   - **micro** остаётся сборщиком (`crypto-data` + `litestream`) — из ЕС geo идеален, менять не нужно.
   - **ARM** (2 ядра / 12 ГБ) — под тяжёлый компьют: детектор режима, бэктест-движок, стратегии.
   - Если решим перенести и сбор на ARM — восстановить БД из B2 (`litestream restore`), поднять там `crypto-data` + `litestream`, на micro сбор остановить. НО: держать сбор в одном месте (не дублировать запись в одну БД с двух хостов).
4. Дописать сюда сервисы ARM по факту (заполнить таблицу инстансов + раздел сервисов).
