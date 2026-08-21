#!/usr/bin/env bash
# Ловушка бесплатного ARM (VM.Standard.A1.Flex) на Oracle Always Free.
# Крутится на micro как systemd-сервис arm-catch (см. deploy/SERVERS.md §3).
#
# Отличие от v1: предохранитель считает БЮДЖЕТ Always Free (4 OCPU / 24 ГБ на тенант),
# а не «есть ли хоть один A1». Поэтому после пойманного 1/6 можно ловить следующий.
# Имя нового инстанса — bot-arm-<N+1>, дублей имён не будет.
set -u

AUTH="--auth instance_principal"
TEN="ocid1.tenancy.oc1..aaaaaaaatol3guoblgvv4ywwjhukbcs4bvz75okrwwiofd2xrl5uy7lwy2oq"
PUB="$HOME/.ssh/armkey.pub"
SUCCESS_LOG="$HOME/arm-catch-SUCCESS.log"
PAUSE="${PAUSE:-30}"

# Что ловим, по приоритету: "ocpus:memGB ocpus:memGB ...".
# По умолчанию — ещё один 1/6 (2/12 уже не берём, чтобы не выгрести весь остаток бюджета).
SHAPES="${SHAPES:-1:6}"

# Потолок Always Free по A1 на весь тенант. Oracle документирует 4 OCPU / 24 ГБ,
# но по факту тенанту может быть выдано меньше (2/12) — держим консервативные 2/12,
# чтобы после второго 1/6 ловушка сама встала. Поднять — env FREE_OCPUS/FREE_MEM.
# Если Oracle всё же ответит LimitExceeded — скрипт это ловит ниже и выходит.
FREE_OCPUS="${FREE_OCPUS:-2}"; FREE_MEM="${FREE_MEM:-12}"

# --- сколько бюджета уже занято пойманными A1 -------------------------------
RAW=$(oci compute instance list $AUTH --compartment-id "$TEN" --all 2>/dev/null)
USED=$(printf '%s' "$RAW" | python3 -c '
import sys, json
try:
    data = json.load(sys.stdin).get("data") or []
except Exception:
    sys.exit(2)
o = m = 0.0; n = 0
for i in data:
    if i.get("shape") != "VM.Standard.A1.Flex":
        continue
    if i.get("lifecycle-state") in ("TERMINATED", "TERMINATING"):
        continue
    c = i.get("shape-config") or {}
    o += c.get("ocpus") or 0
    m += c.get("memory-in-gbs") or 0
    n += 1
print("%d %d %d" % (round(o), round(m), n))
' 2>/dev/null)

if [ -z "${USED:-}" ]; then
  echo "$(date) не смог прочитать список инстансов (oci/права?) — выходим с ошибкой, systemd перезапустит"
  exit 1
fi
read -r U_OCPU U_MEM U_CNT <<< "$USED"
echo "$(date) занято A1: ${U_OCPU} OCPU / ${U_MEM} ГБ (${U_CNT} шт.), потолок ${FREE_OCPUS}/${FREE_MEM}"

# --- отбираем шейпы, которые ещё влезают в остаток бюджета -------------------
ATTEMPTS=()
for S in $SHAPES; do
  O="${S%%:*}"; M="${S##*:}"
  if [ $((U_OCPU + O)) -le "$FREE_OCPUS" ] && [ $((U_MEM + M)) -le "$FREE_MEM" ]; then
    ATTEMPTS+=("$S")
  else
    echo "$(date) шейп ${O}/${M} не влезает в остаток бюджета — пропускаем"
  fi
done
if [ ${#ATTEMPTS[@]} -eq 0 ]; then
  echo "$(date) бюджет Always Free исчерпан — ловить нечего. Выходим."
  exit 0
fi

NAME="${NAME:-bot-arm-$((U_CNT + 1))}"
echo "$(date) ловим: ${ATTEMPTS[*]} под именем $NAME"

# --- статические ресурсы -----------------------------------------------------
ADS=$(oci iam availability-domain list $AUTH --compartment-id "$TEN" --query 'data[].name' --raw-output \
      | python3 -c 'import sys,json;print(" ".join(json.load(sys.stdin)))')
SUB=$(oci network subnet list $AUTH --compartment-id "$TEN" --all \
      --query "data[?\"prohibit-public-ip-on-vnic\"==\`false\`].id | [0]" --raw-output)
IMG=$(oci compute image list $AUTH --compartment-id "$TEN" --operating-system "Canonical Ubuntu" \
      --operating-system-version "22.04" --shape "VM.Standard.A1.Flex" --query "data[0].id" --raw-output)
echo "$(date) ADs=$ADS"; echo "SUBNET=$SUB"; echo "IMAGE=$IMG"
read -ra ADARR <<< "$ADS"
if [ -z "$SUB" ] || [ -z "$IMG" ] || [ ${#ADARR[@]} -eq 0 ]; then
  echo "Ресурсы не найдены — стоп."; exit 1
fi

n=0
while true; do n=$((n+1))
  for SHAPE in "${ATTEMPTS[@]}"; do
    OCPUS="${SHAPE%%:*}"; MEM_GB="${SHAPE##*:}"
    for AD in "${ADARR[@]}"; do
      OUT=$(oci compute instance launch $AUTH --availability-domain "$AD" --compartment-id "$TEN" \
        --shape "VM.Standard.A1.Flex" --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEM_GB}" \
        --image-id "$IMG" --subnet-id "$SUB" --assign-public-ip true \
        --ssh-authorized-keys-file "$PUB" --display-name "$NAME" 2>&1)
      if echo "$OUT" | grep -q '"lifecycle-state"'; then
        ID=$(echo "$OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])' 2>/dev/null)
        echo "$(date) ПОЙМАЛ ${OCPUS}/${MEM_GB} в $AD (круг $n) name=$NAME id=$ID" | tee -a "$SUCCESS_LOG"
        exit 0
      elif echo "$OUT" | grep -qiE 'out of (host )?capacity'; then
        echo "$(date) [$AD ${OCPUS}/${MEM_GB}] занято (круг $n)"
      elif echo "$OUT" | grep -qiE 'LimitExceeded|QuotaExceeded|limit .* exceeded'; then
        # Бюджет кончился по мнению Oracle (например, посчитали иначе) — долбиться бессмысленно.
        echo "$(date) [$AD ${OCPUS}/${MEM_GB}] лимит тенанта исчерпан: $(echo "$OUT" | head -1). Выходим."
        exit 0
      else
        MSG=$(echo "$OUT" | grep -oE '"(code|message)": *"[^"]*"' | tr '\n' ' ')
        [ -z "$MSG" ] && MSG=$(echo "$OUT" | head -1)
        echo "$(date) [$AD ${OCPUS}/${MEM_GB}] иной ответ: $MSG"
      fi
    done
  done
  sleep "$PAUSE"
done
