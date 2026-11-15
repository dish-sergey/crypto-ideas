# Док. 248: пол отступа функции ETH 12/14 б.п. и функция SOL + цена запаса ×1.5. Окно 07–14.11, c,d.
cd /c/revx-xrp
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools200.jar SOLO=c,d"
run() { local n=$1; shift; echo "$(date +%H:%M) $n" >> q248.log; env $B "$@" bash h325.sh w0714 NF 100000 0 0.95 > q248-$n.out 2>&1; }
run m12 XD="-Dx.v=200 -Drevx.exec.func-min-bp=12" XT=v200m12 & run m14 XD="-Dx.v=200 -Drevx.exec.func-min-bp=14" XT=v200m14 &
run solci15 XD=-Dx.v=200 XT=v200 FFILE=C:/revx-xrp/d248/coef-solE.csv FINV=C:/revx-xrp/d248/inv-eth1.5.csv TAGX=solci15 & wait
date > q248b.done
