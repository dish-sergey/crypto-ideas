#!/bin/bash
# Док. 244: окно 06–10.11 (опора из одиночного прогона базы, боты a/c/b) и маркаут без вычета часа (fpvr) для всех окон.
cd /c/revx-xrp/d222/f60
J=~/.jdks/liberica-25.0.3/bin/java; CP="$(cat $TEMP/cp.txt)"
declare -A BOT=([BTC]=a [ETH]=c [SOL]=b)
mkdir -p w0610; mkdir -p wb0610
for c in BTC ETH SOL; do cp /c/revx-xrp/hp/j-w0610-dNF100000r0.95Hv5alloacbn-$c/bot-${BOT[$c]}.db wb0610/wb-$c.db; done
for c in BTC ETH SOL; do (
  $J -Xmx3g --enable-native-access=ALL-UNNAMED -cp "$CP" FillProb60.java C:/revx-xrp/w0610.db $c/USDC wb0610/wb-$c.db 2026-11-06T00:00:00Z 2026-11-10T12:00:00Z w0610/fp-$c.csv 2> w0610/fp-$c.err
  $J -Xmx3g --enable-native-access=ALL-UNNAMED -cp "$CP" MovingMentalV60.java C:/revx-xrp/w0610.db $c/USDC wb0610/wb-$c.db w0610/mmv-$c.csv 1000 31.5 2>&1 | grep -v SLF4J
  $J -Xmx3g --enable-native-access=ALL-UNNAMED -cp "$CP" MergeV60.java w0610/fp-$c.csv w0610/mmv-$c.csv wb0610/wb-$c.db C:/revx-xrp/w0610.db $c/USDC w0610/fpv-$c.csv 2>&1 | grep -v SLF4J
  echo "$(date +%H:%M) w0610 $c" ) & done; wait
mr() { w=$1; DB=$2; WB=$3
  for c in BTC ETH SOL; do $J -Xmx3g -Dmerge.hour-adj=false --enable-native-access=ALL-UNNAMED -cp "$CP" MergeV60.java $w/fp-$c.csv $w/mmv-$c.csv $WB/wb-$c.db $DB $c/USDC $w/fpvr-$c.csv 2>&1 | grep -v SLF4J & done; wait; echo "$(date +%H:%M) raw $w"; }
mr w0610 C:/revx-xrp/w0610.db wb0610
mr w2830 C:/revx-xrp/w2830.db ../w2830f
mr wall D:/revx-data/wall.db ..
echo DONE60B; touch build60b.done
