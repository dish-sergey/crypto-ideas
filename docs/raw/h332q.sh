#!/bin/bash
# 13.11 (док. 245, П4): w20m025 с состоянием «ликвидации» по Binance+Bybit+OKX (порог $2.21 млн за 5 мин по доле времени 1.9%),
# окна 28–30.10 и 06–10.11 (на wall Binance нет). Ждёт h331.done. tools194, не больше 6 java.
cd /c/revx-xrp
while [ ! -f h331.done ]; do sleep 60; done
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools194.jar FALL=lq5bnb FW=20 FLM=0.25"
for W in w2830 w0610; do
  env $B SOLO=a,c,b bash h325.sh $W NF 100000 0 0.95 > h332-$W-acb.out 2>&1 &
  env $B SOLO=e,d,f bash h325.sh $W NF 100000 0 0.95 > h332-$W-edf.out 2>&1 & wait
  echo "$(date +%H:%M) $W"
done
echo DONE > h332.done
