# Док. 248 (владелец 14.11): функция SOL на BTC, окно 07–14.11, a,e.
cd /c/revx-xrp
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools200.jar SOLO=a,e XD=-Dx.v=200 XT=v200"
run() { local n=$1; shift; echo "$(date +%H:%M) w0714 BTC $n" >> q248.log; env $B "$@" bash h325.sh w0714 NF 100000 0 0.95 > q248d-$n.out 2>&1; }
run base & run solB FFILE=C:/revx-xrp/d248/coef-solB.csv TAGX=solB & wait
date > q248d.done
