# Док. 248 (владелец 14.11): ETH на окне 07–14.11 — шире отступ (сдвиг +2/+4/+6), функция SOL, цена запаса ×1.5/×2. Только c,d (процесс ETH). tools200. Без правила ликвидаций (живым не выложено).
cd /c/revx-xrp
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools200.jar XD=-Dx.v=200 XT=v200 SOLO=c,d"
run() { local n=$1; shift; echo "$(date +%H:%M) $n" >> q248.log; env $B "$@" bash h325.sh w0714 NF 100000 0 0.95 > q248-$n.out 2>&1; }
run base
run s2 SH=2 & run s4 SH=4 & run s6 SH=6 & run solc FFILE=C:/revx-xrp/d248/coef-solE.csv TAGX=solc & wait
run solci FFILE=C:/revx-xrp/d248/coef-solE.csv FINV=C:/revx-xrp/d248/inv-solE.csv TAGX=solci & run i15 FINV=C:/revx-xrp/d248/inv-eth1.5.csv TAGX=i15 & run i2 FINV=C:/revx-xrp/d248/inv-eth2.csv TAGX=i2 & run s4i15 SH=4 FINV=C:/revx-xrp/d248/inv-eth1.5.csv TAGX=i15 & wait
date > q248.done
