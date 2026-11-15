# Док. 248: таблица запаса под потолок бота — d (1.2 лота) масштаб 2/1.2 = 1.667, чтобы потолок попадал на точку «2 лота»; c (2.33) масштаб 2/2.33 = 0.858; и d-масштаб вместе с lo15. ETH c,d, 4 окна. Ждёт q248l.done.
cd /c/revx-xrp
while [ ! -f q248l.done ]; do sleep 60; done
free() { df -k /c | awk 'NR==2{print int($4/1048576)}'; }
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=6g TOOLS=tools200.jar SOLO=c,d"
run() { local w=$1 n=$2; shift 2; [ $(free) -lt 15 ] && { echo "$(date +%H:%M) СТОП место" >> q248.log; exit 1; }; echo "$(date +%H:%M) потолок $w $n" >> q248.log; env $B "$@" bash h325.sh $w NF 100000 0 0.95 > q248n-$w-$n.out 2>&1; }
for W in w0714 w0610 w2830 wall; do
  run $W dsc XD="-Dx.v=200 -Drevx.exec.func-inv-lots-scale-d=1.6667" XT=v200dsc &
  run $W cdsc XD="-Dx.v=200 -Drevx.exec.func-inv-lots-scale-d=1.6667 -Drevx.exec.func-inv-lots-scale-c=0.8584" XT=v200cdsc &
  run $W dsclo XD="-Dx.v=200 -Drevx.exec.func-inv-lots-scale-d=1.6667" XT=v200dsc FINV=C:/revx-xrp/d248/gam/inv-lo15.csv TAGX=lo15 & wait
done
date > q248n.done
