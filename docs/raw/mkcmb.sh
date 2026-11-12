# combo NAME W BTCtag ETHtag SOLtag ; tags use {g}=acb|edf group, {s}=sub-letters set for single-coin runs given as X:Y (g1:g2)
combo() { local N=$1 W=$2; shift 2; local coins=(BTC ETH SOL) i=0
  for spec in "$@"; do local C=${coins[$i]}; i=$((i+1))
    for g in acb edf; do local bot; case "$C$g" in BTCacb) bot=a;; ETHacb) bot=c;; SOLacb) bot=b;; BTCedf) bot=e;; ETHedf) bot=d;; SOLedf) bot=f;; esac
      local s1=${spec%%|*} s2=${spec#*|}; local t; [ $g = acb ] && t=$s1 || t=$s2
      local src="j-$W-d$t-$C/bot-$bot.db" dst="j-$W-dCMB$N$g-$C"
      [ -f "$src" ] || { echo "MISSING $src"; continue; }
      mkdir -p "$dst"; ln -f "$src" "$dst/bot-$bot.db"
    done; done; }
for W in w2830 w0610 wall; do
  OB='NF100000r0.95Hv5alloacbn|NF100000r0.95Hv5alloedfn'
  OL='NF100000r0.95flq5m1mHw20v5allm0.25oacbn|NF100000r0.95flq5m1mHw20v5allm0.25oedfn'
  combo OLES $W "$OB" "$OL" "$OL"
  combo OLE $W "$OB" "$OL" "$OB"
  for X in 'S15:fsw30Hw15v5all' 'S20:fsw30Hw20v5allm0.25' 'U20:fsw30lqHw20v5allm0.25'; do n=${X%%:*}; t=${X#*:}
    T="NF100000r0.95${t}oacn|NF100000r0.95${t}oedn"
    combo $n $W "$T" "$T" "$OB"; combo ${n}E $W "$OB" "$T" "$OB"; combo ${n}B $W "$T" "$OB" "$OB"; done
  NB='NF100000r0.95Hv5allv195oacbn|NF100000r0.95Hv5allv195oedfn'
  NR='NF100000r0.95flq5m1mHw20v5allm0.25v195ocbn|NF100000r0.95flq5m1mHw20v5allm0.25v195odfn'
  C1='NF160r0.95Hv5allv195oacn|NF160r0.95Hv5allv195oedn'; C5='NF175r0.95Hv5allv195obn|NF175r0.95Hv5allv195ofn'
  R1='NF160r0.95flq5m1mHw20v5allm0.25v195ocn|NF160r0.95flq5m1mHw20v5allm0.25v195odn'; R5='NF175r0.95flq5m1mHw20v5allm0.25v195obn|NF175r0.95flq5m1mHw20v5allm0.25v195ofn'
  combo NR $W "$NB" "$NR" "$NR"; combo NRE $W "$NB" "$NR" "$NB"
  combo NC $W "$C1" "$C1" "$C5"; combo NRC $W "$C1" "$R1" "$R5"; combo NREC $W "$C1" "$R1" "$C5"
done
