#!/bin/bash
# 13.11 (док. 245): h335 на капитале как у живых — лот и потолок ×1.75 ($130), стоп $7, нынешняя функция (LK=1.75 LKOLD=1), глубина опоры 10 своих лотов (DKX=10). Три окна, не больше 6 java.
# (BTC без правила) — без предела постановок и с живыми пределами (BTC/ETH 160, SOL 175). Три окна. Ждёт h335.done. Не больше 6 java.
cd /c/revx-xrp
while [ ! -f h335.done ]; do sleep 60; done
while tasklist 2>/dev/null | grep -qi "java.exe"; do sleep 30; done
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools195.jar XD=-Dx.v=195 XT=v195 LK=1.75 LKOLD=1 DKX=10"
R="FALL=lq5m1m FW=20 FLM=0.25"
run() { local o=$1; shift; env $B "$@" > h336-$o.out 2>&1; }
for W in w2830 w0610 wall; do
  run $W-base-acb SOLO=a,c,b bash h325.sh $W NF 100000 0 0.95 &
  run $W-base-edf SOLO=e,d,f bash h325.sh $W NF 100000 0 0.95 & wait
  run $W-r-cb $R SOLO=c,b bash h325.sh $W NF 100000 0 0.95 &
  run $W-r-df $R SOLO=d,f bash h325.sh $W NF 100000 0 0.95 &
  run $W-c160-ac SOLO=a,c bash h325.sh $W NF 160 0 0.95 & wait
  run $W-c160-ed SOLO=e,d bash h325.sh $W NF 160 0 0.95 &
  run $W-c175-b SOLO=b bash h325.sh $W NF 175 0 0.95 &
  run $W-c175-f SOLO=f bash h325.sh $W NF 175 0 0.95 &
  run $W-rc160-c $R SOLO=c bash h325.sh $W NF 160 0 0.95 &
  run $W-rc160-d $R SOLO=d bash h325.sh $W NF 160 0 0.95 & wait
  run $W-rc175-b $R SOLO=b bash h325.sh $W NF 175 0 0.95 &
  run $W-rc175-f $R SOLO=f bash h325.sh $W NF 175 0 0.95 & wait
  echo "$(date +%H:%M) $W"
done
echo DONE > h336.done
