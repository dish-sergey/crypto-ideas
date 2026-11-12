#!/bin/bash
# 13.11 (док. 245): глубокие прострелы (медиана глубины свипов продавцов за 15 мин ≥ 30 б.п., fall-sw30) и вместе с ликвидациями
# (fall-sw30lq), BTC и ETH, одиночные, три окна. tools194, по три запуска по две монеты — 6 java.
cd /c/revx-xrp
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools194.jar"
for W in w2830 w0610 wall; do for G in a,c e,d; do
  env $B FALL=sw30 FW=15 SOLO=$G bash h325.sh $W NF 100000 0 0.95 > h334-$W-sw15-${G//,/}.out 2>&1 &
  env $B FALL=sw30 FW=20 FLM=0.25 SOLO=$G bash h325.sh $W NF 100000 0 0.95 > h334-$W-sw20q-${G//,/}.out 2>&1 &
  env $B FALL=sw30lq FW=20 FLM=0.25 SOLO=$G bash h325.sh $W NF 100000 0 0.95 > h334-$W-u20q-${G//,/}.out 2>&1 & wait
  echo "$(date +%H:%M) $W $G"
done; done
echo DONE > h334.done
