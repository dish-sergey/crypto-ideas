#!/bin/bash
# Док. 244: мысленные заявки с сеткой до 60 б.п. и размером снятия (свип ±2 с). FillProb60 → MovingMentalV60 (лаг 1 с, лот $31.5) → MergeV60.
cd /c/revx-xrp/d222/f60
J=~/.jdks/liberica-25.0.3/bin/java; CP="$(cat $TEMP/cp.txt)"
run() { # окно база журналы t0 t1
  w=$1; DB=$2; WB=$3; mkdir -p $w
  for c in BTC ETH SOL; do (
    $J -Xmx3g --enable-native-access=ALL-UNNAMED -cp "$CP" FillProb60.java $DB $c/USDC $WB/wb-$c.db $4 $5 $w/fp-$c.csv 2> $w/fp-$c.err
    $J -Xmx3g --enable-native-access=ALL-UNNAMED -cp "$CP" MovingMentalV60.java $DB $c/USDC $WB/wb-$c.db $w/mmv-$c.csv 1000 31.5 2>&1 | grep -v SLF4J
    $J -Xmx3g --enable-native-access=ALL-UNNAMED -cp "$CP" MergeV60.java $w/fp-$c.csv $w/mmv-$c.csv $WB/wb-$c.db $DB $c/USDC $w/fpv-$c.csv 2>&1 | grep -v SLF4J
    echo "$(date +%H:%M) $w $c готово" ) & done; wait
}
run w2830 C:/revx-xrp/w2830.db ../w2830f 2026-10-28T04:00:00Z 2026-10-31T00:00:00Z
run w0104 C:/revx-xrp/w0104.db ../w0104f 2026-11-01T04:00:00Z 2026-11-04T03:00:00Z
run wall D:/revx-data/wall.db .. 2026-09-15T04:00:00Z 2026-10-18T00:00:00Z
echo "$(date +%H:%M) BUILD60 DONE"; touch build60.done
