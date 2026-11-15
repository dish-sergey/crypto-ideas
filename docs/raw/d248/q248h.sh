# Док. 248 (владелец 14.11): окно σ для цены запаса — 60/30/15/5 мин, и «основа 30 суток + колебания» (σ²_long^0.5·σ²_short^0.5, short 60 и 15), и одна основа 30 суток. ETH c,d, 4 окна, tools202. Ждёт q248f.done и q248j.done (память: базы 18–28.10 по 7.7 ГБ).
cd /c/revx-xrp
while [ ! -f q248f.done ] || [ ! -f q248j.done ]; do sleep 60; done
free() { df -k /c | awk 'NR==2{print int($4/1048576)}'; }
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=6g TOOLS=tools202.jar SOLO=c,d"
declare -A SEED=([wall]=59.6 [w2830]=39.3 [w0610]=48.8 [w0714]=46.8)
run() { local w=$1 n=$2 x=$3; [ $(free) -lt 15 ] && { echo "$(date +%H:%M) СТОП место" >> q248.log; exit 1; }; echo "$(date +%H:%M) σокно $w $n" >> q248.log
  env $B XD="-Dx.v=202 -Drevx.exec.func-inv-vol=BTC:22,ETH:44,SOL:62 $x" XT=v202$n bash h325.sh $w NF 100000 0 0.95 > q248h-$w-$n.out 2>&1; }
for W in w0714 w0610 w2830 wall; do L="-Drevx.exec.func-inv-vol-long-min=43200 -Drevx.exec.func-inv-vol-seed=ETH:${SEED[$W]}"
  run $W m60 "-Drevx.exec.func-inv-vol-min=60" & run $W m30 "-Drevx.exec.func-inv-vol-min=30" & run $W m15 "-Drevx.exec.func-inv-vol-min=15" & wait
  run $W m5 "-Drevx.exec.func-inv-vol-min=5" & run $W b60 "$L -Drevx.exec.func-inv-vol-min=60" & run $W b15 "$L -Drevx.exec.func-inv-vol-min=15" & wait
  run $W b0 "$L -Drevx.exec.func-inv-vol-w=0" & wait
done
date > q248h.done
