# Док. 248: край перебора и запас — max-bp 50 (бид с запасом может уйти дальше 30), lo15 + max-bp 50, цена запаса при лоте ×0.5 (hi05: охотнее докупать), lo15 + hi05. ETH c,d, 4 окна.
cd /c/revx-xrp
free() { df -k /c | awk 'NR==2{print int($4/1048576)}'; }
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=6g TOOLS=tools200.jar SOLO=c,d"
G=C:/revx-xrp/d248/gam
run() { local w=$1 n=$2; shift 2; [ $(free) -lt 15 ] && { echo "$(date +%H:%M) СТОП место" >> q248.log; exit 1; }; echo "$(date +%H:%M) край $w $n" >> q248.log; env $B "$@" bash h325.sh $w NF 100000 0 0.95 > q248o-$w-$n.out 2>&1; }
for W in w0714 w0610 w2830 wall; do
  run $W mx50 XD="-Dx.v=200 -Drevx.exec.func-max-bp=50" XT=v200mx50 & run $W lomx50 XD="-Dx.v=200 -Drevx.exec.func-max-bp=50" XT=v200mx50 FINV=$G/inv-lo15.csv TAGX=lo15 & run $W hi05 XD=-Dx.v=200 XT=v200 FINV=$G/inv-hi05.csv TAGX=hi05 & wait
  run $W lohi05 XD=-Dx.v=200 XT=v200 FINV=$G/inv-lo15hi05.csv TAGX=lohi05
done
date > q248o.done
