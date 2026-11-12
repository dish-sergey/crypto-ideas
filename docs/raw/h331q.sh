#!/bin/bash
# 13.11 (док. 245): П5 — снятие бидов на рывке Бинанса (−20 б.п. за 2 с, возврат через 30 с без нового рывка), одиночные, три окна;
# П3 — лестница лота ×1.75 / ×3 / ×5 (LKOLD: нынешняя функция) без правила и с правилом w20m025 у ETH и SOL (BTC — без правила).
# tools194, не больше 6 java: каждая пара запусков — до 6 процессов.
cd /c/revx-xrp
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools194.jar"
J="-Drevx.exec.jump-bp=20 -Drevx.exec.jump-window-ms=2000 -Drevx.exec.jump-calm-ms=30000 -Drevx.exec.jump-bid-only=true"
run() { env $B "$@" > h331-$TAGO.out 2>&1; echo "$(date +%H:%M) $TAGO"; }
for W in w2830 w0610 wall; do
  TAGO=$W-j20-acb run XD="$J" XT=j20 SOLO=a,c,b bash h325.sh $W NF 100000 0 0.95 &
  TAGO=$W-j20-edf run XD="$J" XT=j20 SOLO=e,d,f bash h325.sh $W NF 100000 0 0.95 & wait
done
for W in w2830 w0610 wall; do for K in 1.75 3 5; do
  TAGO=$W-k$K-acb run LK=$K LKOLD=1 SOLO=a,c,b bash h325.sh $W NF 100000 0 0.95 &
  TAGO=$W-k$K-edf run LK=$K LKOLD=1 SOLO=e,d,f bash h325.sh $W NF 100000 0 0.95 & wait
  TAGO=$W-k$K-cb-r run LK=$K LKOLD=1 FALL=lq5m1m FW=20 FLM=0.25 SOLO=c,b bash h325.sh $W NF 100000 0 0.95 &
  TAGO=$W-k$K-df-r run LK=$K LKOLD=1 FALL=lq5m1m FW=20 FLM=0.25 SOLO=d,f bash h325.sh $W NF 100000 0 0.95 & wait
done; done
echo DONE > h331.done
