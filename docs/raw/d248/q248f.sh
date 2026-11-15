# Док. 248: цена запаса по текущей σ (revx.exec.func-inv-vol, tools201) на ETH: pow 0.5 и 1, и вместе с функцией SOL. 4 окна, c,d. Ждёт q248c.done.
cd /c/revx-xrp
while [ ! -f q248c.done ]; do sleep 60; done
free() { df -k /c | awk 'NR==2{print int($4/1048576)}'; }
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=6g TOOLS=tools201.jar SOLO=c,d"
V="-Dx.v=201 -Drevx.exec.func-inv-vol=BTC:22,ETH:44,SOL:62"
run() { local w=$1 n=$2; shift 2; [ $(free) -lt 15 ] && { echo "$(date +%H:%M) СТОП место" >> q248.log; exit 1; }; echo "$(date +%H:%M) σ $w $n" >> q248.log; env $B "$@" bash h325.sh $w NF 100000 0 0.95 > q248f-$w-$n.out 2>&1; }
run w0714 base201 XD=-Dx.v=201 XT=v201 &
for W in w0714 w0610 w2830 wall; do
  run $W iv05 XD="$V" XT=v201iv05 & run $W iv1 XD="$V -Drevx.exec.func-inv-vol-pow=1" XT=v201iv1 & run $W solciv05 XD="$V" XT=v201iv05 FFILE=C:/revx-xrp/d248/coef-solE.csv TAGX=solc & wait
done
date > q248f.done
