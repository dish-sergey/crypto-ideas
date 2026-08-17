# Серверы и сервисы (runbook)

Что где крутится, чтобы не путаться при добавлении инстансов. Секретов тут нет —
только расположение файлов и команды.

## Инстансы

| Роль | Инстанс | Спека | Регион | IP | Доступ |
|---|---|---|---|---|---|
| **micro** (сбор + ловушка) | `instance-2026...` | Oracle Always Free AMD (E2.1.Micro), 1 CPU / 1 ГБ (+2 ГБ swap), 41 ГБ, Ubuntu 20.04 | eu-frankfurt-1 | `89.168.115.160` | `ssh -i D:\servers\oracle\ssh-key-2026-07-22.key ubuntu@89.168.115.160` |
| **arm** (мощный, компьют) | `bot-arm` — ещё **не пойман** | Oracle Always Free ARM A1.Flex, 2 CPU / 12 ГБ (fallback 1/6) | eu-frankfurt-1 | — | будет: `ssh -i ~/.ssh/armkey ubuntu@<IP>` **с micro** |

> Ключевая причина, почему сбор на micro: из Frankfurt (ЕС) доступны `fapi.binance.com`
> и `api.bybit.com`, заблокированные с локальной машины/US-песочницы. Значит отсюда
> берутся Binance funding/OI/ликвидации. micro — хорошее постоянное место для сбора.

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
- **Что:** `litestream replicate` — непрерывная репликация `crypto.db` в B2, суточные снапшоты, retention 72ч (PITR 3 суток).
- **Версия:** Litestream **v0.3.13** (демон `replicate`; v0.5.x — это VFS-`.so`, не годится).
- **Юнит:** `/etc/systemd/system/litestream.service`
- **Конфиг:** `/etc/litestream.yml` (ссылается на env-переменные).
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
- **Список снапшотов:** `sudo bash -c 'set -a; . /etc/litestream.env; litestream snapshots -config /etc/litestream.yml ~/crypto-data/data/crypto.db'`
- **Восстановить БД:** `sudo bash -c 'set -a; . /etc/litestream.env; litestream restore -config /etc/litestream.yml -o /tmp/restored.db ~/crypto-data/data/crypto.db'`

### 3. `arm-catch` — ловушка мощного ARM
- **Что:** `~/arm_catch.sh` в цикле пытается запустить бесплатный A1.Flex (сначала 2/12, потом 1/6, по 3 зонам Frankfurt) через OCI instance principal. Поймает — `exit 0`, сервис встаёт сам (без дублей).
- **Юнит:** `/etc/systemd/system/arm-catch.service`
- **OCI CLI:** `~/bin/oci` → симлинк на venv **Python 3.11** (`~/lib/oci-py311`, ставился через `uv`; PATH в юните через `Environment=PATH=...`).
- **Ключ для пойманного ARM:** `~/.ssh/armkey` (+ `.pub`) — прописывается в новый инстанс.
- **Проверить:** `systemctl is-active arm-catch` · `journalctl -u arm-catch -f` (строки «занято (круг N)»)
- **Поймал?:** `cat ~/arm-catch-SUCCESS.log 2>/dev/null && echo ПОЙМАНО || echo "ещё ловим"`
- **Настройка политики (Oracle-консоль):** dynamic-group `arm-catcher-dg` + policy `arm-catcher-policy`; при Identity Domains имя группы с префиксом домена: `dynamic-group 'Default'/'arm-catcher-dg'`.

### 4. `dash` — HTML-дашборд детектора (только tailnet)
- **Что:** `python3 -m http.server 8088 --directory ~/dash --bind <tailscale-ip>` — раздаёт `~/dash/index.html` (визуализация режима). Привязан к tailnet-IP → публично **недоступен**, только устройства твоего Tailscale.
- **Юнит:** `/etc/systemd/system/dash.service`
- **Доступ:** `http://100.64.144.85:8088/` или `http://crypto-micro:8088/` (MagicDNS) с любого устройства в tailnet.

### 5. `detector-report.timer` — суточное обновление дашборда
- **Что:** раз в сутки 08:00 UTC гоняет `~/update-dashboard.sh` на боевой БД (`Persistent=true`).
  Скрипт делает backfill трёх версий детектора и рендерит 4 дашборда:
  - **v5 (ПРОД)** `close>SMA200` → `--backfill=regime-v5` + `--report=regime-v5 --out=~/dash/index.html` (главный).
  - **v3** (оси D/T) → `--report=regime-v3 --out=~/dash/regime-v3.html`.
  - **v1** (композит) → `--report=regime --out=~/dash/regime-v1.html`.
  - **общий** (v1/v3/v5) → `--report=regime-all --out=~/dash/regime-all.html`.
- **Юниты:** `/etc/systemd/system/detector-report.{service,timer}`, скрипт `~/update-dashboard.sh` (бэкап `.bak-v3`).
- **Проверить:** `systemctl list-timers detector-report.timer` · `journalctl -u detector-report`
- **Обновить вручную:** `sudo systemctl start detector-report.service` (или `bash ~/update-dashboard.sh` от ubuntu).
- **Дашборд:** `http://crypto-micro:8088/` = **v5 (прод)**; `.../regime-v3.html`, `.../regime-v1.html`, `.../regime-all.html`.
- **Откат:** `mv app.jar.bak-v3 app.jar` + `update-dashboard.sh.bak-v3` (jar менялся атомарным mv, без рестарта crypto-data).

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

### Tailscale (приватная сеть)
- Mesh-VPN (WireGuard). Micro = `crypto-micro` (100.64.144.85). Даёт приватный доступ к дашборду без открытия портов и без домена. Бесплатно (personal). Авторизация — по устройствам аккаунта `dish.sergey@`. Проверка: `tailscale status`.

---

## Прочее, установленное на micro
- `uv` (~/.local/bin) + standalone Python 3.11 — для oci-cli.
- `sqlite3` (apt) — инспекция БД.
- swap 2 ГБ (`/swapfile`, в `/etc/fstab`).
- Порты наружу **не открыты** — весь трафик исходящий.

## Секреты — где лежат (никогда в git)
- `/etc/litestream.env` (micro) — ключи B2. Копия: `D:\servers\backblaze\key.txt` (локально).
- OCI — через **instance principal**, ключей на диске нет.
- SSH к micro — `D:\servers\oracle\ssh-key-2026-07-22.key` (локально).

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
