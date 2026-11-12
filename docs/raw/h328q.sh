#!/bin/bash
# 13.11 (док. 244, этап 7): лестница по кривой (revx.exec.ladder-file, tools190). Класс 4 — состояние «ликвидации» (fall-file lq5m1m,
# при лестнице бид сигналом не отводится — только класс ступеней). Привязка цены риска: lot — пустой бот в среднем часе ставит лот,
# 0.63 / 0.85 — 63% / 85% капитала. nf — ближняя ступень берёт цену из функции отступа. Не больше 6 java (два запуска по три).
cd /c/revx-xrp
while [ ! -f h326.done ]; do sleep 60; done
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools190.jar FALL=lq5m1m"
run() { env $B $1 bash h327.sh $2 NF 100000 0 0.95 > h328-$3.out 2>&1; echo "$(date +%H:%M) $3"; }
run "LADDER=0.63 LNF=1 SOLO=c" w2830 probe
for W in w2830 w0610; do
  run "LADDER=0.63 LNF=1 SOLO=a,c,b" $W $W-063nf-acb &
  run "LADDER=0.63 LNF=1 SOLO=e,d,f" $W $W-063nf-edf & wait
  run "LADDER=lot LNF=1 SOLO=a,c,b" $W $W-lotnf-acb &
  run "LADDER=lot LNF=1 SOLO=e,d,f" $W $W-lotnf-edf & wait
  run "LADDER=0.85 LNF=1 SOLO=a,c,b" $W $W-085nf-acb &
  run "LADDER=0.85 LNF=1 SOLO=e,d,f" $W $W-085nf-edf & wait
  run "LADDER=0.63 SOLO=a,c,b" $W $W-063-acb &
  run "LADDER=0.63 SOLO=e,d,f" $W $W-063-edf & wait
done
echo DONE > h328.done
