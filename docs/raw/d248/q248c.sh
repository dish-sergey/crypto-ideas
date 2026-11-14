# Док. 248: проверка функции SOL на ETH (solc) и цены запаса ×1.5 (i15) — большое окно wall, 28–30.10, 06–10.11; зёрна 1–10 на 07–14.11 (fill-drop 2%). c,d. tools200.
cd /c/revx-xrp
free() { df -k /c | awk 'NR==2{print int($4/1048576)}'; }
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=6g TOOLS=tools200.jar SOLO=c,d"
run() { local w=$1 n=$2; shift 2; [ $(free) -lt 15 ] && { echo "$(date +%H:%M) СТОП место" >> q248.log; exit 1; }; echo "$(date +%H:%M) $w $n" >> q248.log; env $B "$@" bash h325.sh $w NF 100000 0 0.95 > q248c-$w-$n.out 2>&1; }
SC="FFILE=C:/revx-xrp/d248/coef-solE.csv TAGX=solc"; I15="FINV=C:/revx-xrp/d248/inv-eth1.5.csv TAGX=i15"
for W in wall w2830 w0610; do
  run $W base XD=-Dx.v=200 XT=v200 & run $W solc XD=-Dx.v=200 XT=v200 $SC & run $W i15 XD=-Dx.v=200 XT=v200 $I15 & wait
done
for s in $(seq 1 10); do X="-Dx.v=200 -Drevx.sim.fill-drop=0.02 -Drevx.sim.seed=$s"
  run w0714 base$s XD="$X" XT=v200d2s$s & run w0714 solc$s XD="$X" XT=v200d2s$s $SC & run w0714 i15$s XD="$X" XT=v200d2s$s $I15 & wait
done
date > q248c.done
