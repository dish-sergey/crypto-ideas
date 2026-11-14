# Док. 248: функция SOL на BTC — wall, 28–30.10, 06–10.11 (a,e). tools200.
cd /c/revx-xrp
free() { df -k /c | awk 'NR==2{print int($4/1048576)}'; }
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=6g TOOLS=tools200.jar SOLO=a,e XD=-Dx.v=200 XT=v200"
run() { local w=$1 n=$2; shift 2; [ $(free) -lt 15 ] && { echo "$(date +%H:%M) СТОП место" >> q248.log; exit 1; }; echo "$(date +%H:%M) BTC $w $n" >> q248.log; env $B "$@" bash h325.sh $w NF 100000 0 0.95 > q248e-$w-$n.out 2>&1; }
for W in wall w2830 w0610; do run $W base & run $W solB FFILE=C:/revx-xrp/d248/coef-solB.csv TAGX=solB & wait; done
date > q248e.done
