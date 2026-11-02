#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
227. Всплески постановок: модель и сравнение способов тратить суточный предел.

Синтетический рынок, подогнанный под 224 («Проблема для анализа»): сделок в сутки у BTC-подобного
бота в среднем 106, разброс 0.6 от среднего, 95% суток ≈ 2× среднего, 8 лучших суток из 33 дают ~44%
сделок, у монет общий режим. Режимы по часам: обычный, доходный всплеск (×4 поток, ценность ×2),
нож (×4 поток, «видимая» ценность обычная, истинная в среднем −0.3 цента).

Ценность снятия в центах ~ экспоненциальная: так «цена постановки μ» режет снятия так же, как
в 225 §5.2 (бюджет −57% исполнений → доход −21…−26%).

Предел — скользящие 24 ч (как у площадки: место возвращается, когда постановке исполнится 24 ч).

    python3 d227_postanovki_sim.py --single     один бот, предел 100: способы траты
    python3 d227_postanovki_sim.py --sweep      «до упора» против «цены от занятости» на пределах 60…200
    python3 d227_postanovki_sim.py --account    шесть ботов, счёт 1000: доли против общей цены
Только numpy. Всё — синтетика: числа показывают механизм, а не ваши деньги.
"""
import numpy as np, collections, math, argparse
MIN=1440
def gen_market(days=400, seed=1, pb=0.035, dur=3.0, mult=4.0, sd_day=0.45, rho_day=0.5,
               sd_idio=0.25, sd_hour=0.35, rho_hour=0.6, base=(106,76,65), m=(1.0,2.0,1.0), knife_bias=1.4):
    """Общий для монет режим по часам (0 обычный, 1 доходный всплеск, 2 нож), общий дневной фактор,
    свой шум монеты по дням и часам. Возвращает для каждой монеты поминутные числа возможностей,
    режим по минутам, время от начала всплеска."""
    rng=np.random.default_rng(seed)
    H=days*24
    reg=np.zeros(H,int); st=0; since=np.zeros(H,int); s=0
    for h in range(H):
        if st==0:
            u=rng.random()
            if u<pb/2: st=1; s=0
            elif u<pb: st=2; s=0
        else:
            if rng.random()<1/dur: st=0
            else: s+=1
        reg[h]=st; since[h]=s
    dlog=np.zeros(days)
    for d in range(1,days): dlog[d]=rho_day*dlog[d-1]+np.sqrt(1-rho_day**2)*rng.normal(0,sd_day)
    coins=[]
    for c,b in enumerate(base):
        idio=rng.normal(0,sd_idio,days)
        hn=np.zeros(H)
        for h in range(1,H): hn[h]=rho_hour*hn[h-1]+np.sqrt(1-rho_hour**2)*rng.normal(0,sd_hour)
        lvl=np.exp(np.repeat(dlog+idio,24)+hn)*np.where(reg>0,mult,1.0)
        lvl=lvl/lvl.mean()*b/MIN          # средняя скорость = base в сутки
        rate=np.repeat(lvl,60)
        cnt=rng.poisson(rate)
        coins.append(cnt)
    reg_m=np.repeat(reg,60)
    # минут от начала всплеска
    since_m=np.repeat(since,60)*60+np.tile(np.arange(60),H)
    return coins, reg_m, since_m
M_VAL={0:1.0,1:2.0,2:1.0}; KBIAS=1.4   # центы: средняя «видимая» ценность снятия; в ноже истинная = видимая − 1.4

class Pol:
    """Политика траты постановок для одного бюджета (бота или счёта). accept(v) по цене mu."""
    def __init__(s, kind, B, **kw):
        s.kind=kind; s.B=B; s.kw=kw; s.mu=0.0; s.win=collections.deque(); s.arr=collections.deque()
        s.vals=collections.deque(maxlen=3000)
    def count(s,t):
        while s.win and s.win[0]<=t-MIN: s.win.popleft()
        return len(s.win)
    def note_arrivals(s,t,n,vals):
        if n: s.arr.append((t,n))
        while s.arr and s.arr[0][0]<=t-MIN: s.arr.popleft()
        s.vals.extend(vals)
    def update(s,t):
        k=s.kind; kw=s.kw
        if k=="hourly" and t%60==0:
            c=s.count(t); Bt=kw.get("Bt",80); s.mu=max(0.0,s.mu+kw.get("eta",1.6)*(c-Bt)/Bt)
        elif k=="usage":
            u=s.count(t)/s.B; u0=kw.get("u0",0.6); x=min(1,max(0,(u-u0)/(1-u0))); s.mu=kw.get("mumax",3.15)*x**2
        elif k=="mpc" and t%15==0:
            s.mu=s.mpc(t)
    def mpc(s,t):
        kw=s.kw; safety=kw.get("safety",0.1); tau=kw.get("tau",120.0)
        # что вернётся: моменты, когда записи окна выпадают
        ent=np.array(s.win) if s.win else np.zeros(0)
        grid=t+15*np.arange(1,97)
        known=np.array([np.sum(ent>g-MIN) for g in grid])
        n24=sum(n for _,n in s.arr); rbar=max(n24,1)/MIN
        n60=sum(n for tt,n in s.arr if tt>t-60); rnow=n60/60
        tau_g=grid-t
        cum=rbar*tau_g+(rnow-rbar)*tau*(1-np.exp(-tau_g/tau))      # ожидаемые возможности до каждой точки
        vals=np.array(s.vals) if s.vals else np.array([1.0])
        cap=s.B*(1-safety)
        def ok(mu):
            f=np.mean(vals>=mu) if mu>0 else np.mean(vals>0)
            return np.all(known+f*cum<=cap)
        if ok(0.0): return 0.0
        lo,hi=0.0,max(vals.max(),0.1)
        if not ok(hi): return hi
        for _ in range(25):
            mid=0.5*(lo+hi); lo,hi=(lo,mid) if ok(mid) else (mid,hi)
        return hi

def run_single(cnt, reg, since, kind, B=100, detect=None, seed=7, **kw):
    """Один бот. detect: None — нож не виден; число минут — нож распознаётся через столько минут."""
    rng=np.random.default_rng(seed)
    p=Pol(kind,B,**kw); tot=0.0; fills=np.zeros(len(cnt)//MIN); dark=0; days=len(cnt)//MIN
    totd=np.zeros(days)
    for t in range(len(cnt)):
        n=cnt[t]; r=reg[t]
        vs=rng.exponential(M_VAL[r],n) if n else np.zeros(0)
        vt=vs-KBIAS if r==2 else vs
        seen=vt if (r==2 and detect is not None and since[t]>=detect) else vs
        p.note_arrivals(t,n,seen)
        p.update(t)
        c=p.count(t)
        if c>=B: dark+=1
        for i in range(n):
            if seen[i]>0 and seen[i]>=p.mu and c<B:
                p.win.append(t); c+=1; tot+=vt[i]; fills[t//MIN]+=1; totd[t//MIN]+=vt[i]
    return dict(usd_day=tot/100/days, fills=fills.mean(), p95=np.percentile(fills,95), dark_h=dark/60/days, cap_days=np.mean([0]), totd=totd)

def usage_mu(u, mumax, u0, pw=2):
    x=min(1.0,max(0.0,(u-u0)/(1-u0))); return mumax*x**pw

def u_forward(win, arr, t, B, f, H=240, tau=120.0):
    """Наибольшая будущая доля окна в ближайшие H минут: что останется из уже поставленного
    (известно точно — возвраты по вчерашнему рисунку) + ожидаемые новые при доле принятия f."""
    ent=np.array(win) if win else np.zeros(0)
    grid=t+np.arange(15,H+1,15)
    known=np.array([np.sum(ent>g-MIN) for g in grid])
    n24=sum(n for _,n in arr); rbar=max(n24,1)/MIN
    rnow=sum(n for tt,n in arr if tt>t-60)/60
    tg=grid-t; cum=rbar*tg+(rnow-rbar)*tau*(1-np.exp(-tg/tau))
    return max(len(win), np.max(known+f*cum))/B

def run_single2(cnt, reg, since, B=100, mumax=3.15, u0=0.6, pw=2, forward=False, H=240, detect=None, seed=7):
    rng=np.random.default_rng(seed)
    win=collections.deque(); arr=collections.deque(); vals=collections.deque(maxlen=3000)
    tot=0.0; days=len(cnt)//MIN; fills=np.zeros(days); dark=0; mu=0.0
    for t in range(len(cnt)):
        n=cnt[t]; r=reg[t]
        vs=rng.exponential(M_VAL[r],n) if n else np.zeros(0)
        vt=vs-KBIAS if r==2 else vs
        seen=vt if (r==2 and detect is not None and since[t]>=detect) else vs
        if n: arr.append((t,n))
        while arr and arr[0][0]<=t-MIN: arr.popleft()
        vals.extend(seen)
        while win and win[0]<=t-MIN: win.popleft()
        c=len(win)
        if forward:
            if t%5==0:
                va=np.array(vals) if vals else np.array([1.0])
                f=np.mean(va>=max(mu,1e-12))
                mu=usage_mu(u_forward(win,arr,t,B,f,H),mumax,u0,pw)
        else:
            mu=usage_mu(c/B,mumax,u0,pw)
        if c>=B: dark+=1
        for i in range(n):
            if seen[i]>0 and seen[i]>=mu and c<B:
                win.append(t); c+=1; tot+=vt[i]; fills[t//MIN]+=1
    return dict(usd_day=tot/100/days, fills=fills.mean(), p95=np.percentile(fills,95), dark_h=dark/60/days)
# боты: (имя, монета, множитель числа возможностей, ценность в центах при обычной 1.0, лот $, доля бюджета)
BOTS=[("a BTC L1",0,1.0,1.0,31.5,100),("c ETH L1",1,1.0,1.4,31.5,100),("b SOL L1",2,1.0,2.2,31.5,100),
      ("e BTC L3",0,1.5,1/3,10.5,250),("d ETH L3",1,1.5,1.4/3,10.5,250),("f SOL L3",2,1.5,2.2/3,10.5,250)]
ACCT=1000
def run_account(coins, reg, since, mode, mumax_bp=10, u0=0.6, detect=None, seed=11):
    rng=np.random.default_rng(seed)
    nb=len(BOTS); days=len(reg)//MIN
    # поминутные возможности бота: монета × множитель (пуассоновское прореживание/дублирование)
    cnts=[]
    for (_,c,k,_,_,_) in BOTS:
        base=coins[c]
        if k==1.0: cnts.append(base)
        else: cnts.append(base+rng.binomial(base,k-1.0) if k<2 else base*int(k))
    wins=[collections.deque() for _ in BOTS]; acc=collections.deque()
    tot=np.zeros(nb); fills=np.zeros(nb); dark=np.zeros(nb)
    for t in range(len(reg)):
        r=reg[t]
        while acc and acc[0]<=t-MIN: acc.popleft()
        for i,(nm,c,k,val,lot,share) in enumerate(BOTS):
            w=wins[i]
            while w and w[0]<=t-MIN: w.popleft()
            n=cnts[i][t]
            if n==0: continue
            vs=rng.exponential(M_VAL[r]*val,n)
            vt=vs-KBIAS*val if r==2 else vs
            seen=vt if (r==2 and detect is not None and since[t]>=detect) else vs
            for j in range(n):
                cap_bot = len(w)<share if mode in ("shares_greedy","shares_price") else True
                if not cap_bot or len(acc)>=ACCT: dark[i]+=1/ n; continue
                if mode=="shares_greedy": mu=0.0
                elif mode=="shares_price": mu=usage_mu(len(w)/share, mumax_bp*lot/100, u0)        # своя цена в центах
                elif mode=="common_price": mu=usage_mu(len(acc)/ACCT, mumax_bp*31.5/100, u0)      # одна цена в центах на счёт
                elif mode=="common_bp": mu=usage_mu(len(acc)/ACCT, mumax_bp*lot/100, u0)          # одна цена в б.п. (неверно: в центах разная)
                if seen[j]>0 and seen[j]>=mu:
                    w.append(t); acc.append(t); tot[i]+=vt[j]; fills[i]+=1
    return tot/100/days, fills/days


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--single",action="store_true"); ap.add_argument("--sweep",action="store_true")
    ap.add_argument("--account",action="store_true"); ap.add_argument("--seed",type=int,default=3)
    ap.add_argument("--days",type=int,default=300)
    a=ap.parse_args()
    coins,reg,since=gen_market(days=a.days,seed=a.seed,sd_idio=0.18)
    D=np.array([c.reshape(-1,MIN).sum(1) for c in coins])
    print("Рынок: сделок в сутки (BTC/ETH/SOL) среднее", np.round(D.mean(1)), "разброс/среднее", np.round(D.std(1)/D.mean(1),2),
          "95%/среднее", np.round(np.percentile(D,95,axis=1)/D.mean(1),2), "корр.", np.round(np.corrcoef(D)[np.triu_indices(3,1)],2))
    cnt=coins[0]
    if a.single:
        rows=[("без предела, нож не виден",lambda: run_single(cnt,reg,since,"greedy",B=10**9)),
              ("без предела, нож виден через 10 мин",lambda: run_single(cnt,reg,since,"greedy",B=10**9,detect=10)),
              ("предел 100, тратить до упора (сейчас)",lambda: run_single(cnt,reg,since,"greedy")),
              ("часовая μ, η 5 б.п., бюджет 80",lambda: run_single(cnt,reg,since,"hourly",eta=1.6,Bt=80)),
              ("цена от занятости x², 10 б.п., с 60%",lambda: run_single2(cnt,reg,since)),
              ("то же, занятость с учётом возвратов на 4 ч",lambda: run_single2(cnt,reg,since,forward=True)),
              ("то же + нож виден через 10 мин",lambda: run_single2(cnt,reg,since,forward=True,detect=10))]
        for nm,f in rows:
            r=f(); print(f"{nm:45s} {r['usd_day']:.3f} $/сут  исполнений {r['fills']:.0f}/сут (95% {r['p95']:.0f})  темно {r['dark_h']:.2f} ч/сут",flush=True)
    if a.sweep:
        u=run_single(cnt,reg,since,"greedy",B=10**9)['usd_day']; print(f"без предела {u:.3f} $/сут")
        for B in (60,80,100,150,200):
            g=run_single(cnt,reg,since,"greedy",B=B)['usd_day']; x=run_single2(cnt,reg,since,B=B)['usd_day']
            print(f"предел {B:3d}: до упора {g:.3f} ({g/u*100:.0f}%) | цена от занятости {x:.3f} ({x/u*100:.0f}%)",flush=True)
    if a.account:
        base=None
        for mode,det,nm in (("shares_greedy",None,"доли 100/250, до упора (сейчас)"),("shares_price",None,"доли, своя цена от занятости"),
                            ("common_price",None,"общий счёт 1000, одна цена в центах"),("common_price",10,"то же + нож виден через 10 мин")):
            tot,f=run_account(coins,reg,since,mode,detect=det); t=tot.sum(); base=base or t
            print(f"{nm:40s} {t:.2f} $/сут ({(t/base-1)*100:+.0f}%)  постановок {f.sum():.0f}/сут  L1 {tot[:3].sum():.2f}  L3 {tot[3:].sum():.2f}",flush=True)

if __name__=="__main__":
    main()
