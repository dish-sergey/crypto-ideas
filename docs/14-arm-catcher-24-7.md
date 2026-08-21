# ARM-ловушка 24/7 на micro-сервере (instance principal + systemd)

> Цель: чтобы AMD-micro сама, без твоего участия, круглосуточно пыталась поймать бесплатный ARM-инстанс `VM.Standard.A1.Flex` и остановилась, как только поймает. Переживает закрытый ноут, разрыв SSH и перезагрузку сервера.
>
> Данные окружения: tenancy = `ocid1.tenancy.oc1..aaaaaaaatol3guoblgvv4ywwjhukbcs4bvz75okrwwiofd2xrl5uy7lwy2oq`, регион Frankfurt, micro IP 89.168.115.160 (пользователь `ubuntu`).
>
> Делается один раз. Три части: A — консоль (3 мин), B — сервер (5 мин), C — проверка.

---

## Часть A. Консоль Oracle: разрешить micro создавать инстансы (один раз)

Нужно, чтобы сервер имел право запускать инстансы от твоего имени. Делается через Dynamic Group + Policy.

### A1. Dynamic Group

Меню → **Identity & Security → Domains → Default (domain) → Dynamic groups** → **Create dynamic group**.
(в старом интерфейсе: Identity & Security → **Dynamic Groups**)

- Name: `arm-catcher-dg`
- Description: любое
- Rule (matching rule) — вставь строку:

```
ALL {instance.compartment.id = 'ocid1.tenancy.oc1..aaaaaaaatol3guoblgvv4ywwjhukbcs4bvz75okrwwiofd2xrl5uy7lwy2oq'}
```

Create.

### A2. Policy

Меню → **Identity & Security → Policies** → убедись, что compartment = **root (dishsergey)** → **Create policy**.

- Name: `arm-catcher-policy`
- Compartment: root
- Включи «Show manual editor» и вставь:

```
Allow dynamic-group arm-catcher-dg to manage instance-family in tenancy
Allow dynamic-group arm-catcher-dg to use virtual-network-family in tenancy
Allow dynamic-group arm-catcher-dg to read instance-images in tenancy
Allow dynamic-group arm-catcher-dg to inspect compartments in tenancy
```

Create.

> Если на этапе B тест `oci ... --auth instance_principal` будет ругаться на права — временно замени всё это одной строкой `Allow dynamic-group arm-catcher-dg to manage all-resources in tenancy` (это твой личный аккаунт, риск минимальный), потом при желании сузишь.

> Если используются Identity Domains и dynamic-group не подхватывается в политике — попробуй в политике префикс имени домена: `... dynamic-group 'Default'/'arm-catcher-dg' ...`.

---

## Часть B. На самом сервере micro (SSH-сессия)

Зайди на сервер (MobaXterm → 89.168.115.160, пользователь `ubuntu`, ключ с `D:\servers\oracle`) и выполни блоки по порядку.

### B1. Поставить OCI CLI

```bash
sudo apt update && sudo apt install -y python3-pip
bash -c "$(curl -L https://raw.githubusercontent.com/oracle/oci-cli/master/scripts/install/install.sh)" -- --accept-all-defaults
exec $SHELL -l   # перезагрузить окружение, чтобы появилась команда oci
```

### B2. Проверить, что instance principal работает

```bash
oci iam availability-domain list --auth instance_principal \
  --compartment-id ocid1.tenancy.oc1..aaaaaaaatol3guoblgvv4ywwjhukbcs4bvz75okrwwiofd2xrl5uy7lwy2oq \
  --query 'data[].name' --raw-output
```
Должен вывести 3 зоны Frankfurt. Если ошибка про авторизацию/права — вернись к A2 (расширь политику), подожди минуту (политики применяются не мгновенно) и повтори.

### B3. Ключ для будущего ARM-сервера

На micro создаём ключ, который пропишется в пойманный ARM-инстанс:

```bash
ssh-keygen -t rsa -b 4096 -f ~/.ssh/armkey -N "" -q
ls ~/.ssh/armkey*
```
Приватный `~/.ssh/armkey` останется на micro — с неё потом и зайдёшь на ARM. (Позже при желании скопируешь его на ноут.)

### B4. Положить скрипт-ловушку

> **Актуальная версия скрипта — `deploy/arm_catch.sh` в репозитории (v2, 19.08.2026).**
> Ниже — исходный v1, оставлен для истории. Разница описана в разделе «v2: ловим второй инстанс».

```bash
cat > ~/arm_catch.sh <<'SCRIPT'
#!/usr/bin/env bash
set -u
AUTH="--auth instance_principal"
TEN="ocid1.tenancy.oc1..aaaaaaaatol3guoblgvv4ywwjhukbcs4bvz75okrwwiofd2xrl5uy7lwy2oq"
OCPUS=1; MEM_GB=6; NAME="bot-arm"; PAUSE=60
PUB="$HOME/.ssh/armkey.pub"

# Предохранитель: если A1 уже есть — ничего не делаем (защита от дублей)
EX=$(oci compute instance list $AUTH --compartment-id "$TEN" --all \
     --query "data[?\"shape\"=='VM.Standard.A1.Flex' && \"lifecycle-state\"!='TERMINATED'] | length(@)" \
     --raw-output 2>/dev/null)
if [ "${EX:-0}" != "0" ]; then echo "$(date) A1 уже есть ($EX). Выходим."; exit 0; fi

ADS=$(oci iam availability-domain list $AUTH --compartment-id "$TEN" --query 'data[].name' --raw-output \
      | python3 -c 'import sys,json;print(" ".join(json.load(sys.stdin)))')
SUB=$(oci network subnet list $AUTH --compartment-id "$TEN" --all \
      --query "data[?\"prohibit-public-ip-on-vnic\"==\`false\`].id | [0]" --raw-output)
IMG=$(oci compute image list $AUTH --compartment-id "$TEN" --operating-system "Canonical Ubuntu" \
      --operating-system-version "22.04" --shape "VM.Standard.A1.Flex" --query "data[0].id" --raw-output)
echo "$(date) ADs=$ADS"; echo "SUBNET=$SUB"; echo "IMAGE=$IMG"
read -ra ADARR <<< "$ADS"
[ -z "$SUB" ] || [ -z "$IMG" ] || [ ${#ADARR[@]} -eq 0 ] && { echo "Ресурсы не найдены — стоп."; exit 1; }

n=0
while true; do n=$((n+1))
  for AD in "${ADARR[@]}"; do
    OUT=$(oci compute instance launch $AUTH --availability-domain "$AD" --compartment-id "$TEN" \
      --shape "VM.Standard.A1.Flex" --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEM_GB}" \
      --image-id "$IMG" --subnet-id "$SUB" --assign-public-ip true \
      --ssh-authorized-keys-file "$PUB" --display-name "$NAME" 2>&1)
    if echo "$OUT" | grep -q '"lifecycle-state"'; then
      ID=$(echo "$OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])' 2>/dev/null)
      echo "$(date) ПОЙМАЛ в $AD (круг $n) id=$ID" | tee -a "$HOME/arm-catch-SUCCESS.log"
      exit 0
    elif echo "$OUT" | grep -qiE 'out of (host )?capacity'; then
      echo "$(date) [$AD] занято (круг $n)"
    else
      echo "$(date) [$AD] иной ответ: $(echo "$OUT" | head -1)"
    fi
  done
  sleep "$PAUSE"
done
SCRIPT
chmod +x ~/arm_catch.sh
```

### B5. Сделать сервис systemd (чтобы жил вечно и сам стартовал)

```bash
sudo tee /etc/systemd/system/arm-catch.service >/dev/null <<'UNIT'
[Unit]
Description=ARM A1 capacity catcher
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
User=ubuntu
ExecStart=/bin/bash /home/ubuntu/arm_catch.sh
Restart=on-failure
RestartSec=30
[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now arm-catch
```

Готово — ловушка запущена и будет работать сама, даже когда ты всё закроешь.

---

## Часть C. Проверка и что дальше

Смотреть, что происходит (живой лог):
```bash
journalctl -u arm-catch -f
```
Пойдут строчки «занято (круг N)» по трём зонам раз в минуту. Ctrl+C выходит из просмотра (сервис при этом продолжает работать).

**Когда поймает:** сервис сам остановится, а в файле `~/arm-catch-SUCCESS.log` появится строка с OCID нового ARM-инстанса. Проверить, поймалось ли (можно раз в день):
```bash
cat ~/arm-catch-SUCCESS.log 2>/dev/null && echo "--- ПОЙМАНО ---" || echo "ещё ловим"
systemctl is-active arm-catch    # inactive/failed после успеха = поймал; active = ещё ищет
```

**После поимки:**
- В консоли Oracle появится инстанс `bot-arm` с публичным IP.
- Зайти на него можно прямо с micro: `ssh -i ~/.ssh/armkey ubuntu@<IP_нового_ARM>`.
- Дальше переносим коллекторы/детектор на ARM (2 ядра / 12 ГБ), micro можно оставить под маржа-монитор или выключить.

**Остановить охоту вручную** (если передумал):
```bash
sudo systemctl disable --now arm-catch
```

**Уведомление в Telegram (опционально, позже):** когда заведём Telegram-бота для алертов детектора, добавим в скрипт одну строку `curl` на успехе — будет сразу пинговать в телефон. Пока просто проверяй лог.

---

## v2: ловим второй инстанс (19.08.2026)

**Что случилось:** 19.08.2026 09:42 UTC ловушка поймала **1/6** (`bot-arm`, AD-1, круг 3638,
IP `130.61.31.216`) и корректно встала. Но повторно её было не запустить: в v1 предохранитель
выходил, если существует **хоть один** A1 («A1 уже есть (1). Выходим.»), а имя инстанса было
захардкожено `bot-arm` — второй бы конфликтовал по смыслу.

**Что поправлено в v2 (`deploy/arm_catch.sh`):**

1. **Предохранитель считает бюджет, а не факт.** Скрипт суммирует `ocpus` / `memory-in-gbs` живых
   (не TERMINATED/TERMINATING) A1-инстансов и сравнивает с потолком. Ловит только те шейпы из
   `SHAPES`, которые влезают в остаток; если не влезает ничего — «бюджет Always Free исчерпан», exit 0.
2. **Имя автоинкрементное:** `bot-arm-<N+1>` по числу живых A1 (сейчас — `bot-arm-2`).
3. **Потолок по умолчанию 2 OCPU / 12 ГБ.** Oracle документирует Always Free ARM как 4/24, но в
   консоли/лимитах тенанта встречается 2/12 — берём консервативное значение, чтобы после второго
   1/6 ловушка сама встала и не долбила API. Поднять: `FREE_OCPUS=4 FREE_MEM=24` (env в юните).
4. **`LimitExceeded` / `QuotaExceeded` → выход** (раньше это падало в ветку «иной ответ» и цикл
   продолжался бесконечно). `out of capacity` — по-прежнему нормальная рабочая ситуация, ждём дальше.
5. **Ошибка чтения списка инстансов → `exit 1`** (systemd перезапустит через 30 с), а не тихий
   пропуск предохранителя.

**Параметры (env, можно задать в юните):** `SHAPES` (по умолчанию `1:6`, формат `"2:12 1:6"` —
по приоритету), `FREE_OCPUS`/`FREE_MEM`, `PAUSE` (30 с), `NAME`.

**Запуск на следующую поимку:**
```bash
scp deploy/arm_catch.sh ubuntu@89.168.115.160:/tmp/arm_catch.new
ssh ubuntu@89.168.115.160 'tr -d "\r" < /tmp/arm_catch.new > ~/arm_catch.sh && chmod +x ~/arm_catch.sh'
sudo systemctl start arm-catch      # на micro
journalctl -u arm-catch -f
```
Первые строки в логе показывают учёт: `занято A1: 1 OCPU / 6 ГБ (1 шт.), потолок 2/12` →
`ловим: 1:6 под именем bot-arm-2`. Проверить логику, ничего не запуская, можно заведомо
невлезающим шейпом: `SHAPES="9:99" bash ~/arm_catch.sh` — должен сказать «бюджет исчерпан».

**Ключ у второго инстанса тот же** (`~/.ssh/armkey` на micro), заходить так же — с micro.

---

## Заметка «почему так»

Cloud Shell не годится для 24/7 — засыпает через ~20 мин. Micro всегда онлайн, поэтому ловушка живёт на ней. Instance principal убирает возню с ключами API. systemd с `Restart=on-failure` перезапустит скрипт при сбое, но не после успеха (успех = выход 0), плюс предохранитель в начале скрипта не даёт создать второй A1, если один уже пойман. Нагрузка на micro мизерная — один `oci` вызов раз в 20 секунд, её основной работе (коллекторы) не мешает.
