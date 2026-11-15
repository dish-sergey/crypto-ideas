# Док. 248: трасса функции (выбранный δ по запасу) — база и lo15 на 07–14.11, ETH c,d. Ждёт, пока java стенда ≤ 4.
cd /c/revx-xrp
jc() { powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"name='java.exe'\").Count" | tr -d '\r'; }
while [ "$(jc)" -gt 4 ]; do sleep 60; done
B="NOPOOL=1 SIP=5 SVOL=BTC:0.0001,ETH:0.0001,SOL:0.0001 SVOLT=all XMX=4g TOOLS=tools200.jar SOLO=c,d"
env $B XD=-Dx.v=200 XT=v200ftr FTR=C:/revx-xrp/d248/ftr/base bash h325.sh w0714 NF 100000 0 0.95 > d248/ftr/base.out 2>&1 &
env $B XD=-Dx.v=200 XT=v200ftr FTR=C:/revx-xrp/d248/ftr/lo15 FINV=C:/revx-xrp/d248/gam/inv-lo15.csv TAGX=lo15 bash h325.sh w0714 NF 100000 0 0.95 > d248/ftr/lo15.out 2>&1 & wait
date > d248/ftr/done
