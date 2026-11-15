# Док. 248, переобучение шаг А: общие функции FuncPool на всех сутках 15.09–17.10 (λ 0.1 и общие наклоны 1e6) на ETH (c,d) и BTC (a,e), окна 07–14.11, 06–10.11, 28–30.10 (вне выборки). Ждёт q248e.done.
cd /c/revx-xrp
while [ ! -f q248e.done ]; do sleep 60; done
free() { df -k /c | awk 'NR==2{print int($4/1048576)}'; }
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools200.jar XD=-Dx.v=200 XT=v200"
run() { local w=$1 n=$2; shift 2; [ $(free) -lt 15 ] && { echo "$(date +%H:%M) СТОП место" >> q248.log; exit 1; }; echo "$(date +%H:%M) pool $w $n" >> q248.log; env $B "$@" bash h325.sh $w NF 100000 0 0.95 > q248g-$w-$n.out 2>&1; }
for W in w0714 w0610 w2830; do for L in L01 L1e6; do
  run $W eth$L SOLO=c,d FFILE=C:/revx-xrp/d248/coef-pool$L.csv TAGX=pool$L & run $W btc$L SOLO=a,e FFILE=C:/revx-xrp/d248/coef-pool$L.csv TAGX=pool$L & wait
done; done
date > q248g.done
