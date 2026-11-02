#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
225. Отступ с учётом запаса и лимита постановок — эталонный расчёт.

Правило бота (каждый тик, на каждой стороне):
    δ* = argmax_δ  λ(δ | книга, поток) · ( M(δ | книга, поток) − p )
    p  = p_запас(q) + μ          (если максимум < 0 — сторону не котировать)

  λ(δ|s)  — скорость снятия, раз в минуту. Из P за 5 мин функции 224: λ = −ln(1−P)/5 · k_cal
  M(δ|s)  — маркаут снятой, б.п. (функция 224 как есть)
  p_запас — «цена запаса»: насколько одна лишняя (бид) или одна недостающая (аск) единица
            запаса дороже/дешевле в будущем доходе. Берётся из уравнения Беллмана по запасу
            q = 0..Q лотов (Guéant 2017, «модель B»: штраф ½γσ²q² в минуту, без шорта, потолок Q).
            Бид: p_b(q) = h(q) − h(q+1),  аск: p_a(q) = h(q) − h(q−1).
  μ       — теневая цена одной постановки (POST): каждое полное исполнение лота съедает одну.
            Подбирается так, чтобы ожидаемое число постановок в сутки = бюджет.

Допущение «быстрое состояние»: книга и поток меняются за секунды–минуты, запас — за часы.
Поэтому для уравнения по запасу берётся среднее по состояниям:
    H(p) = E_s [ max(0, max_δ λ(δ|s)·(M(δ|s) − p)) ]       («гамильтониан» стороны)

Режимы:
    python3 d225_dp_zapas_limit.py --demo
        синтетические состояния BTC: стены по долям времени из 221 §4, уровень потока и спред —
        из примера 224 §3 (воспроизводит его P и M), коэффициенты — 223 (недели 1–3, старая мерка).
    python3 d225_dp_zapas_limit.py --knife
        синтетика: таблицы цены запаса по режимам «спокойно / нож вниз», задержка детектора (225-v2 §9).
    python3 d225_dp_zapas_limit.py --fpm fpm-BTC.csv --coef func-coef-v.csv --coin BTC [--every 4]
        реальные мысленные заявки (формат 224 §9). ⚠️ На реальных файлах не запускался — проверить
        разбор строк на первых сотнях.
Только numpy.
"""
import argparse, math, sys
import numpy as np

# ---------------------------------------------------------------- коэффициенты 223 (недели 1–3)
COEF_223 = {
    ("BTC", 0): ([-2.074, -0.1514, +0.0030, -0.0205, -0.0065, +0.813, +0.0033, +0.223, +0.0166],
                 [-0.20, +1.090, -0.0096, -0.219, +0.0278, +0.03, -0.044, -0.031, -0.059]),
    ("BTC", 1): ([-1.769, -0.1200, -0.0010, -0.0140, -0.0062, +0.447, +0.0106, +0.182, +0.0494],
                 [-1.28, +1.045, -0.0006, -0.039, -0.0165, -2.20, +0.094, +0.226, -0.029]),
    ("ETH", 0): ([-1.837, -0.1140, +0.0022, +0.0031, -0.0064, +0.638, -0.0033, +0.185, +0.0035],
                 [-2.78, +1.041, -0.0019, -0.091, +0.0008, -1.78, +0.193, +0.942, -0.068]),
    ("ETH", 1): ([-2.114, -0.0638, +0.0003, +0.0243, -0.0058, +0.645, -0.0029, +0.201, +0.0131],
                 [-5.15, +1.011, -0.0040, +0.164, -0.0221, -0.63, +0.020, +0.469, +0.064]),
    ("SOL", 0): ([-2.080, -0.0904, +0.0014, -0.0251, -0.0031, +0.850, -0.0046, +0.141, +0.0108],
                 [-5.57, +1.185, -0.0004, -0.422, -0.0104, -1.06, +0.035, +0.803, +0.025]),
    ("SOL", 1): ([-1.913, -0.0485, -0.0001, -0.0310, -0.0025, +0.480, -0.0031, +0.141, +0.0178],
                 [-3.34, +1.151, -0.0075, -0.337, +0.0125, -0.32, +0.063, +0.534, +0.001]),
}


def p5(c, d, L, F, N, S):
    z = c[0] + c[1]*d + c[2]*d*d + c[3]*L + c[4]*d*L + c[5]*F + c[6]*d*F + c[7]*N + c[8]*S
    return 1.0 / (1.0 + np.exp(-z))


def mk(m, d, L, F, N, S):
    return m[0] + m[1]*d + m[2]*d*d + m[3]*L + m[4]*d*L + m[5]*F + m[6]*d*F + m[7]*N + m[8]*S


# ---------------------------------------------------------------- состояния
class Side:
    """Набор состояний одной стороны: lam[s,j] (раз/мин), M[s,j] (б.п.), w[s], сетка δ."""
    def __init__(self, deltas, lam, M, w):
        self.d = np.asarray(deltas, float)
        self.lam = np.asarray(lam, float)
        self.M = np.asarray(M, float)
        self.w = np.asarray(w, float) / np.sum(w)

    def scaled(self, k):
        return Side(self.d, self.lam * k, self.M, self.w)

    def choose(self, p):
        """для сдвига p: индекс δ по состоянию (−1 = не котировать), λ*, M*"""
        v = self.lam * (self.M - p)
        j = np.argmax(v, axis=1)
        best = v[np.arange(len(j)), j]
        quote = best > 0
        lam = np.where(quote, self.lam[np.arange(len(j)), j], 0.0)
        M = np.where(quote, self.M[np.arange(len(j)), j], 0.0)
        return np.where(quote, j, -1), lam, M, np.where(quote, best, 0.0)

    def stats(self, p):
        j, lam, M, best = self.choose(p)
        H = np.sum(self.w * best)                 # гамильтониан
        R = np.sum(self.w * lam)                  # снятий в минуту
        I = np.sum(self.w * lam * M)              # доход торговли, б.п.·лот в минуту
        q = j >= 0
        dmean = np.sum(self.w[q] * self.d[j[q]]) / np.sum(self.w[q]) if q.any() else float("nan")
        share_q = np.sum(self.w[q])
        return H, R, I, dmean, share_q


def demo_sides():
    """BTC, синтетика. Профиль книги: до стены $100, от стены W — $7 500, от W+8 — $25 000,
    от W+16 — $400 000 (как в примере 224 §3: 0 / 7 510 / 25 607 / 430 586).
    Доли положения стены — 221 §4 (без встречной сделки). Доля минут со встречной сделкой —
    221 §3 (бид 10%, аск 13%). N и S подобраны так, чтобы воспроизвести пример 224 §3."""
    N, S = 1.59, 15.5
    walls = [4, 7, 11, 15, 22, 28, 99]
    share = {0: [35, 32, 16, 7, 7, 2, 1],      # бид
             1: [23, 28, 29, 14, 5, 0.5, 0.5]}  # аск
    fshare = {0: 0.10, 1: 0.13}
    deltas = np.arange(4, 31)
    sides = {}
    for side in (0, 1):
        c, m = COEF_223[("BTC", side)]
        lam, MM, w = [], [], []
        for W, sh in zip(walls, share[side]):
            V = np.where(deltas < W, 100.0,
                np.where(deltas < W + 8, 7500.0,
                np.where(deltas < W + 16, 25000.0, 400000.0)))
            L = np.log1p(V)
            for F, fw in ((0, 1 - fshare[side]), (1, fshare[side])):
                P = p5(c, deltas, L, F, N, S)
                lam.append(-np.log1p(-P) / 5.0)
                MM.append(mk(m, deltas, L, F, N, S))
                w.append(sh * fw)
        sides[side] = Side(deltas, lam, MM, w)
    return sides


def check_example():
    """Сверка с таблицей примера 224 §3 (BTC аск)."""
    c, m = COEF_223[("BTC", 1)]
    d = np.array([4, 6, 8, 10, 12, 20, 30.0])
    V = np.array([0, 0, 0, 7510, 7515, 25607, 430586.0])
    L = np.log1p(V)
    P = p5(c, d, L, 0, 1.59, 15.5)
    M = mk(m, d, L, 0, 1.59, 15.5)
    print("Сверка с примером 224 §3 (BTC аск):")
    print("  δ   :", " ".join(f"{x:6.0f}" for x in d))
    print("  P,% :", " ".join(f"{100*x:6.1f}" for x in P), "   (в 224: 23 19 15 6.5 4.5 0.8 0.0)")
    print("  M   :", " ".join(f"{x:6.1f}" for x in M), "   (в 224: 2.8 4.9 7.0 7.2 9.0 15.6 22.6)")
    print("  D   :", " ".join(f"{x:6.2f}" for x in P*M), "   (в 224: 0.66 0.94 1.07 0.47 0.40 0.12 0.01)")
    print()


def fpm_sides(path, coefpath, coin, every=1, dmin=4):
    """Реальные мысленные заявки (224 §9). Признаки: L по $ впереди на δ сетки,
    F = встречный $ за 60 с > 0, N = ln(1 + встречный $/мин за прошлый час), S = спред."""
    grid = [2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30]
    coefs = {}
    with open(coefpath, encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split(";")
            if len(parts) < 20 or parts[0] != coin:
                continue
            try:
                side = int(parts[1]); vals = [float(x) for x in parts[2:20]]
            except ValueError:
                continue
            coefs[side] = (vals[:9], vals[9:18])
    rows = {0: [], 1: []}
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f):
            if every > 1 and i % every:
                continue
            parts = line.strip().split(";")
            if len(parts) < 8 + 5 * len(grid):
                continue
            try:
                side = int(parts[1]); spread = float(parts[3]); f60 = float(parts[5]); permin = float(parts[7])
                ahead = [float(parts[8 + 5*k]) for k in range(len(grid))]
            except ValueError:
                continue
            rows[side].append((spread, f60, permin, ahead))
    deltas = np.array([g for g in grid if g >= dmin], float)
    idx = [k for k, g in enumerate(grid) if g >= dmin]
    sides = {}
    for side in (0, 1):
        c, m = coefs[side]
        arr = rows[side]
        S = np.array([r[0] for r in arr])[:, None]
        F = (np.array([r[1] for r in arr]) > 0).astype(float)[:, None]
        N = np.log1p(np.array([r[2] for r in arr]))[:, None]
        V = np.array([[r[3][k] for k in idx] for r in arr])
        ok = np.isfinite(V)
        V = np.where(ok, V, 1e12)                 # нет данных/через рынок — как непроходимая стена
        L = np.log1p(V)
        P = p5(c, deltas[None, :], L, F, N, S)
        lam = np.where(ok, -np.log1p(-np.clip(P, 0, 0.999)) / 5.0, 0.0)
        M = mk(m, deltas[None, :], L, F, N, S)
        sides[side] = Side(deltas, lam, M, np.ones(len(arr)))
    return sides


# ---------------------------------------------------------------- уравнение по запасу
class Ham:
    """H(p), R(p), I(p) на сетке p — для быстрых вызовов в итерациях."""
    def __init__(self, side, pmin=-80, pmax=80, step=0.05):
        if side is None:
            return
        self.p = np.arange(pmin, pmax + step/2, step)
        st = np.array([side.stats(x)[:3] for x in self.p])
        self.H, self.R, self.I = st[:, 0], st[:, 1], st[:, 2]
        self.Rmax = self.R.max()

    def scaled(self, k):
        """λ × k: выбор δ не меняется, H, R, I умножаются на k — пересчитывать не нужно."""
        o = Ham(None)
        o.p, o.H, o.R, o.I, o.Rmax = self.p, self.H * k, self.R * k, self.I * k, self.Rmax * k
        return o

    def __call__(self, x):
        return np.interp(x, self.p, self.H)

    def rate(self, x):
        return np.interp(x, self.p, self.R)

    def inc(self, x):
        return np.interp(x, self.p, self.I)


def solve(Hb, Ha, Q, gamma=0.0, sigma2=12.25, mu=0.0, iters=200000, tol=1e-9):
    """Среднее за время (эргодическое) уравнение Беллмана по q = 0..Q.
    g = −½γσ²q² + [q<Q]·Hb(h(q)−h(q+1)+μ) + [q>0]·Ha(h(q)−h(q−1)+μ).
    Возвращает h, p_b, p_a (сдвиги БЕЗ μ), g."""
    q = np.arange(Q + 1)
    phi = 0.5 * gamma * sigma2 * q**2
    U = 1.1 * (Hb.Rmax + Ha.Rmax)
    h = np.zeros(Q + 1)
    for _ in range(iters):
        T = -phi.copy()
        pb = h[:-1] - h[1:]          # q = 0..Q−1
        pa = h[1:] - h[:-1]          # q = 1..Q
        T[:-1] += Hb(pb + mu)
        T[1:] += Ha(pa + mu)
        if T.max() - T.min() < tol:
            break
        h = h + T / U
        h -= h[0]
    pb = np.append(h[:-1] - h[1:], np.nan)
    pa = np.insert(h[1:] - h[:-1], 0, np.nan)
    return h, pb, pa, T.mean()


def evaluate(Hb, Ha, Q, pb, pa, mu=0.0, gamma=0.0, sigma2=12.25):
    """Стационарная доля времени по запасу и итоги для заданных сдвигов (без μ)."""
    q = np.arange(Q + 1)
    rb = np.array([Hb.rate(pb[k] + mu) if k < Q else 0.0 for k in q])
    ra = np.array([Ha.rate(pa[k] + mu) if k > 0 else 0.0 for k in q])
    ib = np.array([Hb.inc(pb[k] + mu) if k < Q else 0.0 for k in q])
    ia = np.array([Ha.inc(pa[k] + mu) if k > 0 else 0.0 for k in q])
    pi = np.ones(Q + 1)
    for k in range(Q):
        pi[k + 1] = pi[k] * rb[k] / ra[k + 1] if ra[k + 1] > 0 else 0.0
    pi /= pi.sum()
    out = dict(pi=pi, rb=rb, ra=ra,
               income=np.sum(pi * (ib + ia)),                    # б.п.·лот в минуту
               fills=np.sum(pi * (rb + ra)) * 1440,              # постановок (исполнений лота) в сутки
               penalty=np.sum(pi * 0.5 * gamma * sigma2 * q**2),
               qmean=np.sum(pi * q))
    return out


def find_mu(Hb, Ha, Q, budget, gamma=0.0, sigma2=12.25):
    lo, hi = 0.0, 40.0
    for _ in range(40):
        mid = 0.5 * (lo + hi)
        _, pb, pa, _ = solve(Hb, Ha, Q, gamma, sigma2, mid)
        f = evaluate(Hb, Ha, Q, pb, pa, mid, gamma, sigma2)["fills"]
        lo, hi = (mid, hi) if f > budget else (lo, mid)
    mu = 0.5 * (lo + hi)
    _, pb, pa, _ = solve(Hb, Ha, Q, gamma, sigma2, mu)
    return mu, pb, pa


def usd_day(bp_lot_per_min, lot=31.5):
    return bp_lot_per_min * 1440 * lot * 1e-4


def fmt(a, w=6, dec=1):
    return " ".join(("   —  " if (x is None or (isinstance(x, float) and math.isnan(x))) else f"{x:{w}.{dec}f}") for x in a)


def report(sides, Q, gammas, budgets, target_fills=None, lot=31.5, sigma2=12.25, bgamma=None):
    sb, sa = sides[0], sides[1]
    Hb0, Ha0 = Ham(sb), Ham(sa)
    k = 1.0
    if target_fills:
        # калибровка уровня: близорукая политика (p = 0, μ = 0) даёт target_fills исполнений лота в сутки
        lo, hi = 0.01, 100.0
        for _ in range(60):
            mid = math.sqrt(lo * hi)
            z = np.zeros(Q + 1)
            f = evaluate(Hb0.scaled(mid), Ha0.scaled(mid), Q, z, z)["fills"]
            lo, hi = (lo, mid) if f > target_fills else (mid, hi)
        k = math.sqrt(lo * hi)
        print(f"Калибровка уровня λ: ×{k:.3f} (близорукая политика → {target_fills} исполнений лота в сутки)\n")
    sb, sa = sb.scaled(k), sa.scaled(k)
    Hb, Ha = Hb0.scaled(k), Ha0.scaled(k)

    print("Как сдвиг p двигает отступ (среднее по состояниям; доля времени, когда сторона котируется):")
    print("   p, б.п.      :", fmt([-6, -4, -2, 0, 2, 4, 6, 8, 12], 6, 0))
    for nm, s in (("бид", sb), ("аск", sa)):
        dm = [s.stats(p)[3] for p in (-6, -4, -2, 0, 2, 4, 6, 8, 12)]
        sh = [100*s.stats(p)[4] for p in (-6, -4, -2, 0, 2, 4, 6, 8, 12)]
        rr = [1440*s.stats(p)[1] for p in (-6, -4, -2, 0, 2, 4, 6, 8, 12)]
        print(f"   {nm}: δ ср.    :", fmt(dm))
        print(f"   {nm}: котир., %:", fmt(sh, 6, 0))
        print(f"   {nm}: снятий/сут:", fmt(rr, 6, 0))
    print()

    q = np.arange(Q + 1)
    z = np.zeros(Q + 1)
    my = evaluate(Hb, Ha, Q, z, z)
    print(f"=== Потолок Q = {Q} лотов ===")
    print(f"Близорукая (p = 0): доход {usd_day(my['income'], lot):.2f} $/сут, исполнений {my['fills']:.0f}/сут, "
          f"пусто {100*my['pi'][0]:.0f}% времени, у потолка {100*my['pi'][-1]:.0f}%, средний запас {my['qmean']:.2f}")
    for g in gammas:
        h, pb, pa, gval = solve(Hb, Ha, Q, g, sigma2)
        ev = evaluate(Hb, Ha, Q, pb, pa, 0.0, g, sigma2)
        print(f"\nγ = {g}:  доход торговли {usd_day(ev['income'], lot):.2f} $/сут, штраф запаса {usd_day(ev['penalty'], lot):.2f}, "
              f"исполнений {ev['fills']:.0f}/сут, пусто {100*ev['pi'][0]:.0f}%, у потолка {100*ev['pi'][-1]:.0f}%, "
              f"средний запас {ev['qmean']:.2f}")
        print("   запас q, лотов :", fmt(q, 6, 0))
        print("   p бида, б.п.   :", fmt(pb))
        print("   p аска, б.п.   :", fmt(pa))
        print("   δ бида ср.     :", fmt([sb.stats(x)[3] if not math.isnan(x) else float('nan') for x in pb]))
        print("   δ аска ср.     :", fmt([sa.stats(x)[3] if not math.isnan(x) else float('nan') for x in pa]))
        print("   доля времени, %:", fmt(100*ev['pi'], 6, 1))
    print()
    if budgets:
        g = gammas[0] if bgamma is None else bgamma
        _, pb0, pa0, _ = solve(Hb, Ha, Q, g, sigma2, 0.0)
        ev0 = evaluate(Hb, Ha, Q, pb0, pa0, 0.0, g, sigma2)
        print(f"Лимит постановок (γ = {g}): μ — теневая цена постановки, б.п. на лот")
        print("   бюджет/сут | близорукая, гаснет | с запасом, гаснет | с запасом и μ: μ, б.п. | доход | исп./сут | δ бид/аск при q=1")
        for B in budgets:
            mu, pb, pa = find_mu(Hb, Ha, Q, B, g, sigma2)
            ev = evaluate(Hb, Ha, Q, pb, pa, mu, g, sigma2)
            nm = usd_day(my['income'], lot) * min(1.0, B / my['fills'])
            nd = usd_day(ev0['income'], lot) * min(1.0, B / ev0['fills'])
            db = sb.stats(pb[1] + mu)[3]; da = sa.stats(pa[1] + mu)[3]
            print(f"   {B:10.0f} | {nm:18.2f} | {nd:17.2f} | {mu:22.2f} | {usd_day(ev['income'], lot):5.2f} | "
                  f"{ev['fills']:8.0f} | {db:.1f} / {da:.1f}")
        print("   («гаснет» — тратит постановки без цены и стоит тёмным после упора; доход умножен на долю\n"
              "    светлого времени — оценка сверху: не учтено, что гаснет в живые часы и с запасом — 190)")


# ---------------------------------------------------------------- резкие движения: таблицы по режимам (225-v2 §9)
def solve_regimes(H, Q, gamma, sig2, drift, k01, k10, mu=0.0, iters=400000, tol=1e-9):
    """Два режима: 0 спокойно, 1 «нож вниз». H[r] = (Hb, Ha); sig2[r], drift[r] — дисперсия и
    ожидаемый ход опоры (б.п./мин; запас q лотов теряет drift·q в минуту); k01, k10 — частоты
    переключения (в минуту). Возвращает таблицы p_b[r][q], p_a[r][q]."""
    q = np.arange(Q + 1)
    U = 1.1 * (max(H[r][0].Rmax + H[r][1].Rmax for r in (0, 1)) + max(k01, k10))
    h = np.zeros((2, Q + 1))
    for _ in range(iters):
        T = np.zeros((2, Q + 1))
        for r in (0, 1):
            Hb, Ha = H[r]
            T[r] = -0.5 * gamma * sig2[r] * q**2 + drift[r] * q
            T[r, :-1] += Hb(h[r, :-1] - h[r, 1:] + mu)
            T[r, 1:] += Ha(h[r, 1:] - h[r, :-1] + mu)
        T[0] += k01 * (h[1] - h[0]); T[1] += k10 * (h[0] - h[1])
        if T.max() - T.min() < tol:
            break
        h = h + T / U; h -= h[0, 0]
    pb = {r: np.append(h[r, :-1] - h[r, 1:], np.nan) for r in (0, 1)}
    pa = {r: np.insert(h[r, 1:] - h[r, :-1], 0, np.nan) for r in (0, 1)}
    return pb, pa


def eval_lag(Hcalm, Hknife, tables, Q, delay, fa_per_day, per_day, dur, drift, lot=31.5):
    """Политика с запаздывающим и ошибающимся детектором. Состояния: 0 спокойно, 1 нож ещё не
    замечен (бот видит «спокойно»), 2 нож замечен, 3 ложная тревога (бот видит «нож», рынок
    спокоен, 10 мин). tables = (PB, PA): PB[видимый режим][q]; inf — не котировать.
    Итог: доход торговли + потеря запаса на дрейфе, $/сут."""
    dyn = {0: 0, 1: 1, 2: 1, 3: 0}; seen = {0: 0, 1: 0, 2: 1, 3: 1}
    Hd = {0: Hcalm, 1: Hknife}; dd = {0: 0.0, 1: drift}
    rates = {(1, 2): 1 / max(delay, 1e-3), (3, 0): 1 / 10.0, (2, 0): 1 / max(dur - delay, 1.0)}
    rates[(0, 1) if delay > 0 else (0, 2)] = per_day / 1440
    if fa_per_day > 0:
        rates[(0, 3)] = fa_per_day / 1440
    n = 4 * (Q + 1); G = np.zeros((n, n)); idx = lambda z, x: z * (Q + 1) + x
    inc = np.zeros(n); drl = np.zeros(n)
    PB, PA = tables
    for z in range(4):
        Hb, Ha = Hd[dyn[z]]; o = seen[z]
        for x in range(Q + 1):
            i = idx(z, x)
            if x < Q and np.isfinite(PB[o][x]):
                G[i, idx(z, x + 1)] += Hb.rate(PB[o][x]); inc[i] += Hb.inc(PB[o][x])
            if x > 0 and np.isfinite(PA[o][x]):
                G[i, idx(z, x - 1)] += Ha.rate(PA[o][x]); inc[i] += Ha.inc(PA[o][x])
            for (a, b), rt in rates.items():
                if a == z:
                    G[i, idx(b, x)] += rt
            drl[i] = dd[dyn[z]] * x
    np.fill_diagonal(G, -G.sum(1))
    A = np.vstack([G.T, np.ones(n)]); bb = np.zeros(n + 1); bb[-1] = 1
    pi = np.linalg.lstsq(A, bb, rcond=None)[0]
    return usd_day(pi @ inc, lot) + usd_day(pi @ drl, lot)


def demo_knife(Q=10, gamma=0.002, sigma2=12.25, k=2.815, bid_x=3.0, ask_x=0.5, s2x=4.0, dur=10.0):
    """Синтетика BTC: «нож вниз» — бид снимают в bid_x раз чаще, аск в ask_x, дисперсия ×s2x,
    опора идёт на drift б.п./мин, длится в среднем dur мин. Все параметры — допущения."""
    sides = demo_sides()
    Hc = (Ham(sides[0]).scaled(k), Ham(sides[1]).scaled(k))
    Hk = (Hc[0].scaled(bid_x), Hc[1].scaled(ask_x))
    _, pbc, pac, _ = solve(Hc[0], Hc[1], Q, gamma, sigma2)
    inf = np.full(Q + 1, np.inf)
    print("Резкие движения (синтетика BTC, потолок", Q, ", γ =", gamma, ")")
    print(f"нож: бид ×{bid_x}, аск ×{ask_x}, дисперсия ×{s2x}, длится в среднем {dur:.0f} мин\n")
    pb, pa = solve_regimes({0: Hc, 1: Hk}, Q, gamma, {0: sigma2, 1: sigma2 * s2x}, {0: 0.0, 1: -3.0}, 3 / 1440, 1 / dur)
    print("Таблицы по режимам (нож −3 б.п./мин, 3 раза в сутки):")
    for r, nm in ((0, "спокойно"), (1, "нож    ")):
        db = [sides[0].stats(x)[3] if np.isfinite(x) and sides[0].stats(x)[4] > 0 else float("nan") for x in pb[r]]
        da = [sides[1].stats(x)[3] if np.isfinite(x) and sides[1].stats(x)[4] > 0 else float("nan") for x in pa[r]]
        print(f"  {nm} p бида:", fmt(pb[r])); print(f"  {nm} δ бида:", fmt(db)); print(f"  {nm} δ аска:", fmt(da))
    print("  (сравнить: таблица 225 без ножей, p бида:", fmt(pbc), ")\n")
    print("Итог, $/сут (торговля + потеря запаса на дрейфе); детектор: задержка 2 мин, 3 ложные тревоги в сутки")
    print("  дрейф, б.п./мин | ножей/сут | таблица 225 везде | нож: бид снят | таблица с ножами, без детектора | таблицы по режимам")
    for drift, per_day in ((-1.5, 3), (-3, 3), (-6, 3), (-3, 1), (-3, 6)):
        pbr, par = solve_regimes({0: Hc, 1: Hk}, Q, gamma, {0: sigma2, 1: sigma2 * s2x}, {0: 0.0, 1: drift}, per_day / 1440, 1 / dur)
        args = (Q, 2.0, 3.0, per_day, dur, drift)
        v = [eval_lag(Hc, Hk, ({0: pbc, 1: pbc}, {0: pac, 1: pac}), *args),
             eval_lag(Hc, Hk, ({0: pbc, 1: inf}, {0: pac, 1: pac}), *args),
             eval_lag(Hc, Hk, ({0: pbr[0], 1: pbr[0]}, {0: par[0], 1: par[0]}), *args),
             eval_lag(Hc, Hk, (pbr, par), *args)]
        print(f"  {drift:15.1f} | {per_day:9.0f} | " + " | ".join(f"{x:.2f}" for x in v))
    print("\nЗадержка детектора и ложные тревоги (нож −3 б.п./мин, 3 раза в сутки): таблица 225 везде / нож: бид снят / по режимам")
    for d in (0, 2, 5):
        for fa in (0, 3, 10):
            v = [eval_lag(Hc, Hk, t, Q, d, fa, 3, dur, -3.0) for t in
                 (({0: pbc, 1: pbc}, {0: pac, 1: pac}), ({0: pbc, 1: inf}, {0: pac, 1: pac}), (pb, pa))]
            print(f"  задержка {d} мин, ложных {fa:2d}/сут: " + " / ".join(f"{x:.2f}" for x in v))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--demo", action="store_true")
    ap.add_argument("--knife", action="store_true", help="синтетика: таблицы по режимам «спокойно / нож» (225-v2 §9)")
    ap.add_argument("--fpm"); ap.add_argument("--coef"); ap.add_argument("--coin", default="BTC")
    ap.add_argument("--every", type=int, default=1)
    ap.add_argument("--Q", type=int, nargs="+", default=[10, 3])
    ap.add_argument("--gamma", type=float, nargs="+", default=[0.0, 0.002, 0.01])
    ap.add_argument("--budgets", type=float, nargs="+", default=[100, 70, 50, 30])
    ap.add_argument("--target", type=float, default=95, help="калибровка: исполнений лота в сутки у близорукой (0 — без)")
    ap.add_argument("--sigma2", type=float, default=12.25, help="дисперсия минутного хода, б.п.² (BTC ~3.5²)")
    ap.add_argument("--bgamma", type=float, default=0.002, help="γ для таблицы лимита постановок")
    ap.add_argument("--lot", type=float, default=31.5, help="лот, $ (для пересчёта в $/сут)")
    a = ap.parse_args()
    if a.knife:
        demo_knife()
        return
    if a.demo:
        check_example()
        sides = demo_sides()
    elif a.fpm and a.coef:
        sides = fpm_sides(a.fpm, a.coef, a.coin, a.every)
    else:
        ap.error("нужно --demo или --fpm и --coef")
    for Q in a.Q:
        report(sides, Q, a.gamma, a.budgets if Q == a.Q[0] else [], a.target or None, lot=a.lot, sigma2=a.sigma2, bgamma=a.bgamma)
        print("\n")


if __name__ == "__main__":
    main()
