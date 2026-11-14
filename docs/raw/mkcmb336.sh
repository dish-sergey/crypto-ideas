combo() { local N=$1 W=$2; shift 2; local coins=(BTC ETH SOL) i=0
  for spec in "$@"; do local C=${coins[$i]}; i=$((i+1))
    for g in acb edf; do local bot; case "$C$g" in BTCacb) bot=a;; ETHacb) bot=c;; SOLacb) bot=b;; BTCedf) bot=e;; ETHedf) bot=d;; SOLedf) bot=f;; esac
      local s1=${spec%%|*} s2=${spec#*|}; local t; [ $g = acb ] && t=$s1 || t=$s2
      local src="j-$W-d$t-$C/bot-$bot.db" dst="j-$W-dCMB$N$g-$C"
      [ -f "$src" ] || { echo "MISSING $src"; continue; }
      mkdir -p "$dst"; ln -f "$src" "$dst/bot-$bot.db"
    done; done; }
for W in w2830 w0610 wall; do
  NB='NF100000r0.95HK1.75ov5allv195oacbnk10|NF100000r0.95HK1.75ov5allv195oedfnk10'
  NR='NF100000r0.95flq5m1mHK1.75ow20v5allm0.25v195ocbnk10|NF100000r0.95flq5m1mHK1.75ow20v5allm0.25v195odfnk10'
  C1='NF160r0.95HK1.75ov5allv195oacnk10|NF160r0.95HK1.75ov5allv195oednk10'; C5='NF175r0.95HK1.75ov5allv195obnk10|NF175r0.95HK1.75ov5allv195ofnk10'
  R1='NF160r0.95flq5m1mHK1.75ow20v5allm0.25v195ocnk10|NF160r0.95flq5m1mHK1.75ow20v5allm0.25v195odnk10'; R5='NF175r0.95flq5m1mHK1.75ow20v5allm0.25v195obnk10|NF175r0.95flq5m1mHK1.75ow20v5allm0.25v195ofnk10'
  combo KR $W "$NB" "$NR" "$NR"; combo KRE $W "$NB" "$NR" "$NB"
  combo KC $W "$C1" "$C1" "$C5"; combo KRC $W "$C1" "$R1" "$R5"; combo KREC $W "$C1" "$R1" "$C5"
done
