# h321 (12.11) = h318 на tools187: + revx.exec.storm-empty-bp (надбавка цене запаса пустого бида в бурю)
# h318 (11.11, просьба владельца) = h313 + env SOLO (буквы ботов через запятую, напр. a,c,b): в процессе монеты только эти боты, один бот на процесс; TAG + o<буквы>.
# h313 (11.11, док. 240) = h309 + env XD/XT: произвольные -D к процессу и метка
# h307 (10.11, план 239) = h305 на tools184 + окно w0610 (06–10.11) + env FTR: запись слагаемых функции в <FTR>-<монета>.csv
#!/bin/bash
# h287 (04.11) = h286 на tools177 + env SH: сдвиг отступа функции, б.п., меньше нуля — ближе (мелкий лот)
# h286 (04.11) = h282 на tools176: env L10=1 — шесть ботов по 10 лотов при том же потолке, цена запаса в % потолка, глубина опоры в тех же долларах (док. 236).
# h282 (04.11) = h279 на tools175 + RWB (бид на сигнал роста, меньше нуля — ближе); по стене: падение бид +X аск −X, рост аск +X бид −X. TAG + B.
# h279 (03.11) = h278 на tools174 + мягкий отвод: env FALL=p95x90 FW (бид на сигнал падения), FWA (аск на сигнал падения), RISE=p95x90 RW (аск на сигнал роста). TAG + w/a/R.
# h278 (03.11) = h276 на tools172, ВСЕГДА с гибридной опорой в групповом стенде (-Drevx.sim.group-hybrid=true), TAG + H. Окно wall1 — проверка на 20–21.09.
# h276 (02.11) = h275 на tools170 + двойная опора (env FARF=1 → -Drevx.exec.far-func=true, TAG + F2): бид от меньшей, аск от большей из (опора, Бинанс × базис) внутри функции.
# h275 (02.11) = h272 на tools169 + сигнал падения из файла: env FALL (p90|p95|p99 → d274/fall/fall-<p>.txt), бид отводится; TAG + f<p>.
# h272 (02.11) = h269 на tools168 + стоп по просадке: env DDS (% потолка — распродажа и выключение, самовключение через 6 ч), DDA (% — тревога). TAG + d<DDS>.
# h269 (02.11) = h267 на tools167 + 5-й аргумент: доля предела для самовключения после остановки (по умолчанию 0.8).
# h267 = h263 на tools167 (воспроизводимый групповой прогон), предел LIM у новой раскладки, L62=1 — у e/d/f функция под лот $61.8 и своя таблица запаса (Q = 1),
# пишутся моменты постановок (placed) и суточные пики (placements_day). Аргументы: окно вариант [предел=160] [L62=0].
# h263 (02.11, перенос на живых): пара на монету в ОДНОМ процессе (одна книга, один счёт), три процесса — BTC, ETH, SOL —
# с общим пулом реинвеста (как живой пул mm: капитал $74.17 у каждого, буфер 5%, шаг раз в час, капитал в [½, 3]).
# Вариант N (новый): a/c/b 1 ур. × 2.33 лота, e/d/f 1 ур. × 1.2 лота, предел 160 у всех, стоп $4 у всех.
# Вариант C (нынешний): a/c/b 1 ур. × 2.33, предел 95, стоп $4; e/d/f 3 ур. × 7 лотов (12/15/20), предел 235, стоп $12.
#   ⚠️ флаги на процесс: в C парковка в бурю e/d достаётся и a/c, календарь b достаётся и f.
# Вариант NF — N с функцией отступа (нынешняя func-coef-v, таблица запаса inv-v1, надбавка h10).
# Аргументы: окно (wall | w2830) вариант (N | NF | C). tools167.jar.
cd /c/revx-xrp
export JAVA_HOME=~/.jdks/liberica-25.0.3
W=$1; V=$2; LIM=${3:-160}; L62=${4:-0}; RF=${5:-0.8}; TAG=$V$LIM$([ "$L62" = 1 ] && echo g)r$RF
DDF=""; [ -n "$DDS" ] && { TAG=${TAG}d$DDS; DDF="-Drevx.exec.dd-stop-pct=$DDS -Drevx.exec.dd-alert-pct=${DDA:-0} -Drevx.exec.dd-resume-h=6"; }
[ -n "$FALL" ] && { TAG=${TAG}f$FALL; DDF="$DDF -Drevx.exec.fall-file=C:/revx-xrp/d274/fall/fall-$FALL.txt"; }
[ "$FARF" = 1 ] && { TAG=${TAG}F2; DDF="$DDF -Drevx.exec.far-func=true"; }
TAG=${TAG}H; DDF="$DDF -Drevx.sim.group-hybrid=true"
[ "$L10" = 1 ] && TAG=${TAG}L10
[ -n "$SH" ] && { TAG=${TAG}s$SH; DDF="$DDF -Drevx.exec.func-shift-bp=$SH"; }
[ "$F74" = 1 ] && TAG=${TAG}f74
[ -n "$LK" ] && TAG=${TAG}K$LK
[ "$LKOLD" = 1 ] && TAG=${TAG}o
[ -n "$LOTX" ] && TAG=${TAG}X$LOTX
[ -n "$FW" ] && { TAG=${TAG}w$FW; DDF="$DDF -Drevx.exec.fall-widen-bp=$FW"; }
[ -n "$FIP" ] && { TAG=${TAG}p$FIP; DDF="$DDF -Drevx.exec.fall-inv-bp=$FIP"; }
[ -n "$SIP" ] && { TAG=${TAG}v$SIP${SVOLT};  DDF="$DDF -Drevx.exec.storm-inv-bp=$SIP -Drevx.exec.storm-vol=${SVOL:-BTC:3.81,ETH:5.12,SOL:6.38}"; }
[ -n "$FLM" ] && { TAG=${TAG}m$FLM; DDF="$DDF -Drevx.exec.fall-lot-mult=$FLM"; }
[ -n "$FFILE" ] && TAG=${TAG}R
[ -n "$FSF" ] && { TAG=${TAG}z$FSF; DDF="$DDF -Drevx.exec.func-storm-floor=ETH:$FSF"; }
[ -n "$XD" ] && { TAG=${TAG}$XT; DDF="$DDF $XD"; }
[ -n "$LADDER" ] && { TAG=${TAG}L$LADDER; DDF="$DDF -Drevx.exec.ladder-file=C:/revx-xrp/d277/ladder244-$LADDER.csv"; }
[ "$LNF" = 1 ] && { TAG=${TAG}nf; DDF="$DDF -Drevx.exec.ladder-near-func=true"; }
# h322 (12.11, док. 244 этап 1): DFN=N — у e/d/f потолок тот же (74.17), лотов N, лот 74.17/N; цена запаса в % потолка (масштаб 1.2/N), глубина опоры в тех же долларах, что при 1.2 (618.1 $).
if [ -n "$DFN" ]; then DL=$(awk "BEGIN{printf \"%.3f\", 74.17/$DFN}"); DSC=$(awk "BEGIN{printf \"%.4f\", 1.2/$DFN}"); TAG=${TAG}D$DFN; DDF="$DDF -Drevx.fair.hybrid-depth-k=$(awk "BEGIN{printf \"%.3f\", 618.1/$DL}") -Drevx.exec.func-inv-lots-scale-e=$DSC -Drevx.exec.func-inv-lots-scale-d=$DSC -Drevx.exec.func-inv-lots-scale-f=$DSC"; fi
[ -n "$TAGX" ] && TAG=${TAG}$TAGX
[ -n "$FTL" ] && { TAG=${TAG}L$FTL; DDF="$DDF -Drevx.exec.func-tail-lambda=$FTL"; }
[ -n "$FWA" ] && { TAG=${TAG}a$FWA; DDF="$DDF -Drevx.exec.fall-widen-ask-bp=$FWA"; }
[ -n "$RW" ] && { TAG=${TAG}R$RW; DDF="$DDF -Drevx.exec.rise-file=C:/revx-xrp/d274/fall/rise-$RISE.txt -Drevx.exec.rise-widen-bp=$RW"; }
[ -n "$RWB" ] && { TAG=${TAG}B$RWB; DDF="$DDF -Drevx.exec.rise-widen-bid-bp=$RWB"; }
LB=${LIMBTC:-$LIM}; [ "$LB" != "$LIM" ] && TAG=${TAG}b$LB   # предел ботов BTC отдельно (env LIMBTC, 02.11)
[ -n "$SOLO" ] && TAG=${TAG}o$(echo $SOLO | tr -d ,)
case $W in
  wall1) DB=D:/revx-data/wall.db; ST=D:/revx-data/wall.db.stalls.txt; FC=D:/revx-data/fcache1; NF=D:/revx-data/ff-0915-1018.json; F=2026-09-20T00:00:00Z; T=2026-09-22T00:00:00Z; X=6g;;
  wall)  DB=D:/revx-data/wall.db; ST=D:/revx-data/wall.db.stalls.txt; FC=D:/revx-data/fcache; NF=D:/revx-data/ff-0915-1018.json; F=2026-09-15T00:00:00Z; T=2026-10-18T00:00:00Z; X=${XMX:-6g};;
  w0104) DB=C:/revx-xrp/w0104.db; ST=C:/revx-xrp/w0104.db.stalls.txt; FC=C:/revx-xrp/fcache0104; NF=C:/revx-xrp/ff-0104.json; F=2026-11-01T00:00:00Z; T=2026-11-04T03:00:00Z; X=3g;;
  w0610) DB=C:/revx-xrp/w0610.db; ST=C:/revx-xrp/w0610.db.stalls.txt; FC=C:/revx-xrp/fcache0610; NF=C:/revx-xrp/ff-0610.json; F=2026-11-06T04:00:00Z; T=2026-11-10T03:00:00Z; X=${XMX:-3g};;
  w2830) DB=C:/revx-xrp/w2830.db; ST=C:/revx-xrp/w2830.db.stalls.txt; FC=C:/revx-xrp/fcache2830; NF=C:/revx-xrp/ff-1026-1101.json; F=2026-10-28T00:00:00Z; T=2026-10-31T00:00:00Z; X=${XMX:-3g};;
esac
B="-Drevx.sim.ghost-at-sec=-1 -Drevx.sim.stall-effects=ghost,late204,live404,stale,slow,fresh404,lag -Drevx.sim.fresh404-every=10 -Drevx.sim.ghost-every=10 -Drevx.fair.hybrid-depth-k=10 -Drevx.fair.hybrid-mix=0.25 -Drevx.fair.hybrid-tau-sec=180 -Drevx.sim.stall-schedule=$ST -Drevx.fair.hybrid-dir=D:/revx-data/bnb1s -Drevx.forecast.cache-dir=$FC -Drevx.forecast.models=market -Drevx.sim.fair-book=true -Drevx.forecast.join-min=240 -Drevx.fair.hybrid-clamp-bp=5"
COM="-Drevx.sim.level-growth=1.6666667 -Drevx.sim.ghost-hold-min=120 -Drevx.exec.venue-locks=true -Drevx.exec.venue-slots=true -Drevx.exec.stall-freeze-sec=5 -Drevx.exec.partial-stale-bp=10 -Drevx.exec.budget-unwind=true -Drevx.sim.auto-resume-min=0 -Drevx.exec.limit-resume-frac=$RF"
PD=C:/revx-xrp/hp/pool-$W-d$TAG; rm -rf "$PD"; mkdir -p C:/revx-xrp/hp
POOL="-Drevx.exec.reinvest=pool -Drevx.exec.pool-dir=$PD -Drevx.exec.pool-members=a,b,c,d,e,f -Drevx.exec.pool-capital=74.17 -Drevx.exec.reinvest-min=60 -Drevx.exec.reinvest-buffer-pct=5 -Drevx.exec.reinvest-max=3 -Drevx.exec.pool-shrink=true"
[ "$NOPOOL" = 1 ] && { POOL=""; TAG=${TAG}n; }
P="-Drevx.exec.replace-danger-first=true"; QA="-Drevx.exec.queue-adapt=true"
PK="-Drevx.exec.storm-park-range-bp=40 -Drevx.exec.storm-park-paced=true -Drevx.exec.storm-park-levels=2 -Drevx.exec.storm-park-side=both"
FN=""; G=""; [ "$L62" = 1 ] && G="-Drevx.exec.func-file-e=C:/revx-xrp/d222/l62/coef-l62.csv -Drevx.exec.func-file-d=C:/revx-xrp/d222/l62/coef-l62.csv -Drevx.exec.func-file-f=C:/revx-xrp/d222/l62/coef-l62.csv -Drevx.exec.func-inv-file-e=C:/revx-xrp/d222/l62/inv-l62.csv -Drevx.exec.func-inv-file-d=C:/revx-xrp/d222/l62/inv-l62.csv -Drevx.exec.func-inv-file-f=C:/revx-xrp/d222/l62/inv-l62.csv -Drevx.exec.func-inv-q-e=1 -Drevx.exec.func-inv-q-d=1 -Drevx.exec.func-inv-q-f=1"
if [ $V = NF ]; then V0=N; FN="-Drevx.exec.func-file=${FFILE:-C:/revx-xrp/d222/func-coef-v.csv} -Drevx.exec.func-inv-file=C:/revx-xrp/d225/inv-v1.csv -Drevx.exec.func-inv-q=2 -Drevx.exec.func-inv-gamma=0.002 -Drevx.exec.func-mu-head-from=0.6 -Drevx.exec.func-mu-head-bp=10"; else V0=$V; fi
if [ $V0 = N ]; then
  LOSS="-Drevx.sim.max-loss=4"
  spec() { case $1 in BTC) echo "a:BTC:1:12:31.79:2.33:$LB,e:BTC:1:12:61.81:1.2:$LB";; ETH) echo "c:ETH:1:14:31.79:2.33:$LIM,d:ETH:1:14:61.81:1.2:$LIM";; SOL) echo "b:SOL:1:20:31.79:2.33:$LIM,f:SOL:1:20:61.81:1.2:$LIM";; esac; }
  if [ "$L10" = 1 ]; then
    DDF="$DDF -Drevx.fair.hybrid-depth-k=42.86 -Drevx.exec.func-inv-lots-scale-a=0.233 -Drevx.exec.func-inv-lots-scale-c=0.233 -Drevx.exec.func-inv-lots-scale-b=0.233 -Drevx.exec.func-inv-lots-scale-e=0.12 -Drevx.exec.func-inv-lots-scale-d=0.12 -Drevx.exec.func-inv-lots-scale-f=0.12"
    spec() { case $1 in BTC) echo "a:BTC:1:12:7.417:10:$LB,e:BTC:1:12:7.417:10:$LB";; ETH) echo "c:ETH:1:14:7.417:10:$LIM,d:ETH:1:14:7.417:10:$LIM";; SOL) echo "b:SOL:1:20:7.417:10:$LIM,f:SOL:1:20:7.417:10:$LIM";; esac; }
  fi
  # LK: лот и потолок ×k при том же числе лотов; стоп и капитал ×k, глубина опоры в тех же долларах; своя функция и таблица из d222/lotk$LK
  if [ -n "$LK" ]; then
    Aa=$(awk "BEGIN{printf \"%.2f\", 31.79*$LK}"); Ee=$(awk "BEGIN{printf \"%.2f\", 61.81*$LK}"); Cc=$(awk "BEGIN{printf \"%.2f\", 74.17*$LK}")
    DDF="$DDF -Drevx.fair.hybrid-depth-k=$(awk "BEGIN{print 10/$LK}") -Drevx.exec.pool-capital=$Cc -Drevx.sim.max-loss=$(awk "BEGIN{printf \"%.2f\", 4*$LK}")"
    # LKOLD: нынешняя функция и таблица (func-coef-v, inv-v1) на большем лоте
    [ "$LKOLD" = 1 ] || DDF="$DDF -Drevx.exec.func-file=C:/revx-xrp/d222/lotk$LK/coef.csv -Drevx.exec.func-inv-file=C:/revx-xrp/d222/lotk$LK/inv.csv"
    spec() { case $1 in BTC) echo "a:BTC:1:12:$Aa:2.33:$LB,e:BTC:1:12:$Ee:1.2:$LB";; ETH) echo "c:ETH:1:14:$Aa:2.33:$LIM,d:ETH:1:14:$Ee:1.2:$LIM";; SOL) echo "b:SOL:1:20:$Aa:2.33:$LIM,f:SOL:1:20:$Ee:1.2:$LIM";; esac; }
  fi
  # LOTX: все шесть ботов лот $LOTX и потолок 10 лотов, нынешняя функция, таблица запаса Q=10, стоп пропорционально потолку, глубина опоры в тех же долларах
  if [ -n "$LOTX" ]; then
    Cc=$(awk "BEGIN{printf \"%.2f\", 10*$LOTX}"); DDF="$DDF -Drevx.fair.hybrid-depth-k=$(awk "BEGIN{printf \"%.3f\", 317.9/$LOTX}") -Drevx.exec.pool-capital=$Cc -Drevx.sim.max-loss=$(awk "BEGIN{printf \"%.2f\", 4*$Cc/74.17}") -Drevx.exec.func-inv-q=10"
    spec() { case $1 in BTC) echo "a:BTC:1:12:$LOTX:10:$LB,e:BTC:1:12:$LOTX:10:$LB";; ETH) echo "c:ETH:1:14:$LOTX:10:$LIM,d:ETH:1:14:$LOTX:10:$LIM";; SOL) echo "b:SOL:1:20:$LOTX:10:$LIM,f:SOL:1:20:$LOTX:10:$LIM";; esac; }
  fi
  # F74: своя функция и таблица запаса на 10 лотов: масштаб запаса выключен
  [ "$F74" = 1 ] && DDF="$DDF -Drevx.exec.func-file=C:/revx-xrp/d222/lot74/coef.csv -Drevx.exec.func-inv-file=C:/revx-xrp/d222/lot74/inv.csv -Drevx.exec.func-inv-q=10 -Drevx.exec.func-inv-lots-scale-a=1 -Drevx.exec.func-inv-lots-scale-c=1 -Drevx.exec.func-inv-lots-scale-b=1 -Drevx.exec.func-inv-lots-scale-e=1 -Drevx.exec.func-inv-lots-scale-d=1 -Drevx.exec.func-inv-lots-scale-f=1"
  flags() { case $1 in BTC) echo "-Drevx.exec.news-file=$NF -Drevx.exec.news-levels=1 $P";; *) echo "-Drevx.exec.news-file=$NF -Drevx.exec.news-levels=1 $P $QA";; esac; }
else
  LOSS="-Drevx.sim.max-loss-a=4 -Drevx.sim.max-loss-b=4 -Drevx.sim.max-loss-c=4 -Drevx.sim.max-loss-d=12 -Drevx.sim.max-loss-e=12 -Drevx.sim.max-loss-f=12"
  spec() { case $1 in BTC) echo "a:BTC:1:12:31.79:2.33:95,e:BTC:3:12:10.60:7:235";; ETH) echo "c:ETH:1:14:31.79:2.33:95,d:ETH:3:12:10.60:7:235";; SOL) echo "b:SOL:1:20:31.79:2.33:95,f:SOL:3:20:10.60:7:235";; esac; }
  flags() { case $1 in BTC) echo "-Drevx.exec.news-file=$NF -Drevx.exec.news-levels=2 $PK $P";; ETH) echo "-Drevx.exec.news-file=$NF -Drevx.exec.news-levels=2 $PK $P $QA -Drevx.exec.queue-adapt-first-only=true";; SOL) echo "-Drevx.exec.news-file=$NF -Drevx.exec.news-levels=1 $P $QA -Drevx.exec.queue-adapt-first-only=true";; esac; }
fi
mkdir -p C:/revx-xrp/hp
for c in BTC ETH SOL; do
  SP=$(spec $c); [ -n "$LADDER" ] && SP=$(echo $SP | sed -E "s/:(BTC|ETH|SOL):1:/:\1:4:/g"); [ -n "$DFN" ] && SP=$(echo $SP | sed "s/:61.81:1.2:/:$DL:$DFN:/g"); [ -n "$SOLO" ] && SP=$(echo $SP | tr , "\n" | grep -E "^($(echo $SOLO | tr , "|")):" | paste -sd,); [ -z "$SP" ] && continue
  out=C:/revx-xrp/hp/$W-d$TAG-$c.log
  $JAVA_HOME/bin/java -Xmx$X $B $COM $POOL $LOSS $FN $G -Drevx.sim.placed-events=true -Drevx.sim.live-cap=$([ $c = BTC ] && echo $LB || echo $LIM) $(flags $c) $DDF $([ -n "$FTR" ] && echo -Drevx.exec.func-trace=$FTR-$c.csv) -jar C:/revx-xrp/${TOOLS:-tools187.jar} --revx-group-forecast --no-budget \
    --bots=$SP --from=$F --to=$T --journal-out=C:/revx-xrp/hp/j-$W-d$TAG-$c --revx.exec.park-distance=0.10 --revx.exec.stand-db=$DB > $out 2>&1 &
done
wait
echo "H282 DONE $W $TAG"
