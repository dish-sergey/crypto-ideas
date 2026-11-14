# Док. 248: почему ручной ×1.5 лучше — сетка множителя (×1.25/×2/×3), таблица с γ 0.004/0.006 (FuncInv), и ×1.5 только на стороне с запасом (hi15: p_b(1), p_a(2)) или только у пустого (lo15: p_b(0), p_a(1)). ETH c,d, 4 окна, tools200. Ждёт q248j.done.
cd /c/revx-xrp
while [ ! -f q248j.done ]; do sleep 60; done
free() { df -k /c | awk 'NR==2{print int($4/1048576)}'; }
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=6g TOOLS=tools200.jar SOLO=c,d"
G=C:/revx-xrp/d248/gam
run() { local w=$1 n=$2; shift 2; [ $(free) -lt 15 ] && { echo "$(date +%H:%M) СТОП место" >> q248.log; exit 1; }; echo "$(date +%H:%M) запас $w $n" >> q248.log; env $B "$@" bash h325.sh $w NF 100000 0 0.95 > q248l-$w-$n.out 2>&1; }
for W in w0714 w0610 w2830 wall; do
  run $W k125 XD=-Dx.v=200 XT=v200 FINV=$G/inv-k125.csv TAGX=k125 & run $W k3 XD=-Dx.v=200 XT=v200 FINV=$G/inv-k3.csv TAGX=k3 & run $W hi15 XD=-Dx.v=200 XT=v200 FINV=$G/inv-hi15.csv TAGX=hi15 & wait
  run $W lo15 XD=-Dx.v=200 XT=v200 FINV=$G/inv-lo15.csv TAGX=lo15 & run $W g004 XD="-Dx.v=200 -Drevx.exec.func-inv-gamma=0.004" XT=v200g004 FINV=$G/inv-gam.csv & run $W g006 XD="-Dx.v=200 -Drevx.exec.func-inv-gamma=0.006" XT=v200g006 FINV=$G/inv-gam.csv & wait
  [ $W != w0714 ] && { run $W i2 XD=-Dx.v=200 XT=v200 FINV=C:/revx-xrp/d248/inv-eth2.csv TAGX=i2; }
done
date > q248l.done
