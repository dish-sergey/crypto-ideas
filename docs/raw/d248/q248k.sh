# Док. 248: базы стенда и мысленные заявки за пропуски 18–28.10, 31.10, 04–06.11 (+ 06.11 из w0610) — для скользящего обучения общей функции. Ждёт сборки w0714f.
cd /c/revx-xrp
:
J=~/.jdks/liberica-25.0.3/bin/java; CP="$(cat d246/cp.txt)"
log() { echo "$(date +%H:%M) $*" >> q248.log; }
mm() { # окно база_рынка t0 t1 каталог журнал_тег
  local W=$1 DB=$2 t0=$3 t1=$4 O=$5 TG=$6; mkdir -p $O
  for cb in BTC:a ETH:c SOL:b; do c=${cb%:*}; b=${cb#*:}; wb=C:/revx-xrp/hp/j-$W-d$TG-$c/bot-$b.db
    ( $J -Xmx3g --enable-native-access=ALL-UNNAMED -cp "$CP" d221/FillProb.java $DB $c/USDC $wb $t0 $t1 $O/fp-$c.csv 2> $O/fp-$c.err
      $J -Xmx3g --enable-native-access=ALL-UNNAMED -cp "$CP" d222/MovingMentalV.java $DB $c/USDC $wb $O/mmv-$c.csv 1000 31.5 2>&1 | grep -v SLF4J
      $J -Xmx3g --enable-native-access=ALL-UNNAMED -cp "$CP" d222/MergeV.java $O/fp-$c.csv $O/mmv-$c.csv $wb $DB $c/USDC $O/fpv-$c.csv 2>&1 | grep -v SLF4J ) &
  done; wait; log "мысленные $W: $(wc -l $O/fpv-*.csv | tail -1)"; }
for spec in "w3101 1101" "w0406 1105 1106"; do set -- $spec; W=$1; shift
  [ $(df -k /d | awk 'NR==2{print int($4/1048576)}') -lt 8 ] && { log "СТОП место D"; exit 1; }
  I=D:/revx-data/d248/inc-$W; mkdir -p $I; for d in "$@"; do cp -n /c/data-archive/revx-inc/revx-inc-2026$d-0315.db.gz $I/; done
  log "сборка $W"; [ -f D:/revx-data/d248/$W.db ] || nice $J -Xmx3g -jar tools200.jar --revx-assemble --from-dir=$I --out=D:/revx-data/d248/$W.db > D:/revx-data/d248/$W.asm.log 2>&1
  rm -rf $I
  log "база $W"; env NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all TOOLS=tools200.jar SOLO=a,c,b XD=-Dx.v=200 XT=v200 bash h325.sh $W NF 100000 0 0.95 > q248j-$W.out 2>&1
  F=$(grep "^  $W)" h325.sh | sed 's/.*F=\([^;]*\);.*/\1/'); T=$(grep "^  $W)" h325.sh | sed 's/.* T=\([^;]*\);.*/\1/')
  mm $W D:/revx-data/d248/$W.db $F $T d222/${W}f NF100000r0.95Hv5allv200oacbn
done
mm w0610 C:/revx-xrp/w0610.db 2026-11-06T04:00:00Z 2026-11-07T04:00:00Z d222/w0610f NF100000r0.95Hv5allv195oacbn
log "мысленные все готовы"; date > q248j.done
