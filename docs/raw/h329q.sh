#!/bin/bash
# 13.11 (док. 244, этап 7): гибрид — уровень 0 нынешний бот (функция, продажа как была), покупка × множитель состояния по кривой
# [0.25, 1.5]; уровни 1–2 — глубокие ступени кривой (25–35, 40–60 б.п.) только в тихий/средний час. tools191, не больше 6 java.
cd /c/revx-xrp
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools191.jar FALL=lq5m1m LH=1 LLV=3"
run() { env $B $1 bash h327.sh $2 NF 100000 0 0.95 > h329-$3.out 2>&1; echo "$(date +%H:%M) $3"; }
for W in w2830 w0610; do for T in 0.63 0.85 lot; do
  run "LADDER=$T SOLO=a,c,b" $W $W-$T-acb &
  run "LADDER=$T SOLO=e,d,f" $W $W-$T-edf & wait
done; done
echo DONE > h329.done
