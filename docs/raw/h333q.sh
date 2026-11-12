#!/bin/bash
# 13.11 (док. 245, вопрос владельца): база и правило w20m025 с живыми пределами постановок — BTC и ETH 160, SOL 175 на бота в сутки;
# общее ведро выключено (--no-budget). Одиночные прогоны. Ждёт h332.done. tools194, не больше 6 java на партию.
cd /c/revx-xrp
while [ ! -f h332.done ]; do sleep 60; done
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools194.jar"
R="FALL=lq5m1m FW=20 FLM=0.25"
for W in w2830 w0610 wall; do
  env $B SOLO=a,c,b bash h325.sh $W NF 160 0 0.95 > h333-$W-b160-acb.out 2>&1 &
  env $B SOLO=e,d,f bash h325.sh $W NF 160 0 0.95 > h333-$W-b160-edf.out 2>&1 & wait
  env $B SOLO=b bash h325.sh $W NF 175 0 0.95 > h333-$W-b175-b.out 2>&1 &
  env $B SOLO=f bash h325.sh $W NF 175 0 0.95 > h333-$W-b175-f.out 2>&1 &
  env $B $R SOLO=c bash h325.sh $W NF 160 0 0.95 > h333-$W-r160-c.out 2>&1 &
  env $B $R SOLO=d bash h325.sh $W NF 160 0 0.95 > h333-$W-r160-d.out 2>&1 &
  env $B $R SOLO=b bash h325.sh $W NF 175 0 0.95 > h333-$W-r175-b.out 2>&1 &
  env $B $R SOLO=f bash h325.sh $W NF 175 0 0.95 > h333-$W-r175-f.out 2>&1 & wait
  echo "$(date +%H:%M) $W"
done
echo DONE > h333.done
