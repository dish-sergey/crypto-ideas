#!/bin/bash
# 13.11 (док. 244): h323 wall и h324 заново на tools189.jar (кэш опоры, журнал пачками, тики столбцами; результат тот же до цента).
# Не больше 6 java: каждый запуск h325.sh — по процессу на монету (3), одновременно два запуска.
# Первый запуск wall один — построит/подхватит кэш опоры, дальше остальные из кэша.
cd /c/revx-xrp
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools189.jar"
run() { env $B $1 bash h325.sh $2 NF 100000 0 0.95 > h326-$3.out 2>&1; echo "$(date +%H:%M) $3"; }
run "FALL=lq5m1m FW=20 FLM=0.25 SOLO=a,c,b" wall wall-w20m025-acb
run "FALL=lq5m1m FW=20 FLM=0.25 SOLO=e,d,f" wall wall-w20m025-edf &
run "FALL=lq5m1m FW=1 FLM=0.25 SOLO=a,c,b" wall wall-w1m025-acb & wait
run "FALL=lq5m1m FW=1 FLM=0.25 SOLO=e,d,f" wall wall-w1m025-edf &
run "FALL=lq5m1m FW=30 SOLO=a,c,b" wall wall-w30-acb & wait
run "FALL=lq5m1m FW=30 SOLO=e,d,f" wall wall-w30-edf &
run "FALL=lq5cf300s30 FW=20 FLM=0.25 SOLO=a,c,b" w0610 w0610-cf-acb & wait
run "FALL=lq5cf300s30 FW=20 FLM=0.25 SOLO=e,d,f" w0610 w0610-cf-edf &
run "FALL=lq5cf300s30 FW=20 FLM=0.25 SOLO=a,c,b" w2830 w2830-cf-acb & wait
run "FALL=lq5cf300s30 FW=20 FLM=0.25 SOLO=e,d,f" w2830 w2830-cf-edf &
run "FALL=lq5cf300s30 FW=20 FLM=0.25 SOLO=a,c,b" wall wall-cf-acb & wait
run "FALL=lq5cf300s30 FW=20 FLM=0.25 SOLO=e,d,f" wall wall-cf-edf
echo DONE > h326.done
