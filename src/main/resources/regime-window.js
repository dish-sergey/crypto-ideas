/* Общий модуль окна времени для отчётов детектора режима.
   Встраивается в каждый шаблон вместо плейсхолдера окна (см. RegimeReport.writeReport).
   Данные уже вшиты в страницу целиком — окно режется на клиенте, без перегенерации.
   Состояние окна пишется в hash: #w=1m | #w=ytd | #w=all | #w=2024-01-01..2024-06-30 */
window.RegimeWindow = (function () {
  var PRESETS = [["1М", 30], ["3М", 91], ["6М", 182], ["YTD", "ytd"], ["1Г", 365], ["2Г", 730], ["Всё", "all"]];
  var CSS = ".rw{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:10px 0 2px;font-size:13px}"
    + ".rw .grp{display:flex;border:1px solid var(--grid);border-radius:8px;overflow:hidden}"
    + ".rw button{appearance:none;background:var(--panel);color:var(--muted);border:0;border-right:1px solid var(--grid);"
    + "padding:5px 11px;font:inherit;cursor:pointer;line-height:1.2}"
    + ".rw .grp button:last-child{border-right:0}"
    + ".rw button:hover{color:var(--ink)} .rw button.on{background:var(--ink);color:var(--bg)}"
    + ".rw .nav button{padding:5px 9px}"
    + ".rw .dates{display:flex;align-items:center;gap:6px;color:var(--muted)}"
    + ".rw input[type=date]{background:var(--panel);color:var(--ink);border:1px solid var(--grid);border-radius:8px;"
    + "padding:4px 7px;font:inherit;color-scheme:light dark}"
    + ".rw .home{margin-left:auto;color:var(--muted);text-decoration:none;border:1px solid var(--grid);"
    + "border-radius:8px;padding:5px 11px}"
    + ".rw .home:hover{color:var(--ink)}";

  function addDays(day, n) {
    var d = new Date(day + "T00:00:00Z");
    d.setUTCDate(d.getUTCDate() + n);
    return d.toISOString().slice(0, 10);
  }
  function diffDays(a, b) {
    return Math.round((Date.parse(b + "T00:00:00Z") - Date.parse(a + "T00:00:00Z")) / 86400000);
  }
  function lowerBound(all, day) { // первый индекс с датой >= day
    var lo = 0, hi = all.length;
    while (lo < hi) { var m = (lo + hi) >> 1; if (all[m][0] < day) lo = m + 1; else hi = m; }
    return lo;
  }
  function upperBound(all, day) { // первый индекс с датой > day
    var lo = 0, hi = all.length;
    while (lo < hi) { var m = (lo + hi) >> 1; if (all[m][0] <= day) lo = m + 1; else hi = m; }
    return lo;
  }

  function init(opts) {
    var all = opts.all, apply = opts.apply;
    var host = document.getElementById(opts.mount || "toolbar");
    var label = document.getElementById(opts.label || "range");
    var first = all[0][0], lastDay = all[all.length - 1][0];
    var style = document.createElement("style");
    style.textContent = CSS;
    document.head.appendChild(style);

    var st = {mode: "all", preset: "all", from: first, to: lastDay};

    host.className = "rw";
    host.innerHTML = "<div class='grp'>"
      + PRESETS.map(function (p) { return "<button data-w='" + key(p[1]) + "'>" + p[0] + "</button>"; }).join("")
      + "</div><div class='grp nav'><button data-nav='-1' title='раньше'>◀</button>"
      + "<button data-nav='1' title='позже'>▶</button></div>"
      + "<div class='dates'><input type='date' id='rw-from' min='" + first + "' max='" + lastDay + "'>"
      + "<span>—</span><input type='date' id='rw-to' min='" + first + "' max='" + lastDay + "'></div>"
      + "<a class='home' href='index.html'>☰ все графики</a>";
    var inFrom = host.querySelector("#rw-from"), inTo = host.querySelector("#rw-to");

    function key(v) { return v === "all" ? "all" : v === "ytd" ? "ytd" : v + "d"; }

    function presetRange(p) {
      if (p === "all") return [first, lastDay];
      if (p === "ytd") return [lastDay.slice(0, 4) + "-01-01", lastDay];
      var n = parseInt(p, 10);
      return [addDays(lastDay, -n), lastDay];
    }

    function setPreset(p, push) {
      var r = presetRange(p);
      st = {mode: "preset", preset: p, from: r[0], to: r[1]};
      render(push);
    }
    function setRange(from, to, push) {
      if (from > to) { var t = from; from = to; to = t; }
      if (from < first) from = first;
      if (to > lastDay) to = lastDay;
      st = {mode: "range", preset: null, from: from, to: to};
      render(push);
    }

    function shift(dir) { // сдвиг окна на его собственную длину, встык (без перекрытия)
      var len = diffDays(st.from, st.to) + 1;
      if (len < 2) len = 2;
      var from = addDays(st.from, dir * len), to = addDays(st.to, dir * len);
      if (to > lastDay) { to = lastDay; from = addDays(to, -(len - 1)); }
      if (from < first) { from = first; to = addDays(from, len - 1); if (to > lastDay) to = lastDay; }
      setRange(from, to, true);
    }

    function render(push) {
      var i0 = lowerBound(all, st.from), i1 = upperBound(all, st.to);
      if (i1 - i0 < 2) { // окно без данных или из одной точки — расширяем до двух
        i1 = Math.min(all.length, Math.max(i1, i0 + 2));
        i0 = Math.max(0, i1 - 2);
      }
      var slice = all.slice(i0, i1);
      host.querySelectorAll("button[data-w]").forEach(function (b) {
        b.classList.toggle("on", st.mode === "preset" && b.dataset.w === key(st.preset));
      });
      inFrom.value = slice[0][0];
      inTo.value = slice[slice.length - 1][0];
      if (label) {
        label.textContent = slice[0][0] + " — " + slice[slice.length - 1][0] + " · " + slice.length + " дней";
      }
      if (push) {
        var h = st.mode === "preset" ? key(st.preset) : st.from + ".." + st.to;
        if (location.hash !== "#w=" + h) { history.replaceState(null, "", "#w=" + h); }
      }
      apply(slice);
    }

    host.addEventListener("click", function (e) {
      var b = e.target.closest("button");
      if (!b) return;
      if (b.dataset.w) setPreset(b.dataset.w === "all" ? "all" : b.dataset.w === "ytd" ? "ytd" : parseInt(b.dataset.w, 10), true);
      else if (b.dataset.nav) shift(+b.dataset.nav);
    });
    inFrom.addEventListener("change", function () { setRange(inFrom.value || first, inTo.value || lastDay, true); });
    inTo.addEventListener("change", function () { setRange(inFrom.value || first, inTo.value || lastDay, true); });
    addEventListener("keydown", function (e) {
      if (e.target.tagName === "INPUT") return;
      if (e.key === "ArrowLeft") shift(-1);
      else if (e.key === "ArrowRight") shift(1);
    });

    fromHash(opts.def || "all");
    addEventListener("hashchange", function () { fromHash(opts.def || "all"); });

    function fromHash(def) {
      var m = /(?:^|[#&])w=([^&]+)/.exec(location.hash);
      var v = m ? decodeURIComponent(m[1]) : def;
      var rng = /^(\d{4}-\d{2}-\d{2})\.\.(\d{4}-\d{2}-\d{2})$/.exec(v);
      if (rng) setRange(rng[1], rng[2], false);
      else if (v === "all" || v === "ytd") setPreset(v, false);
      else if (/^\d+d?$/.test(v)) setPreset(parseInt(v, 10), false);
      else setPreset("all", false);
    }
  }

  /* Подписи оси времени под текущее окно: годы / месяцы / дни.
     Возвращает [[индекс_в_окне, подпись], ...]. */
  function ticks(P) {
    var n = P.length;
    if (n < 2) return [];
    var span = diffDays(P[0][0], P[n - 1][0]), out = [], i, cur, prev = null;
    var mon = function (d) { return d.slice(8, 10) + "." + d.slice(5, 7); };
    if (span > 1500) {
      for (i = 0; i < n; i++) { cur = P[i][0].slice(0, 4); if (cur !== prev) { out.push([i, cur]); prev = cur; } }
    } else if (span > 400) {
      for (i = 0; i < n; i++) {
        cur = P[i][0].slice(0, 7);
        if (cur !== prev && (+cur.slice(5, 7)) % 3 === 1) { out.push([i, cur.slice(5, 7) + "." + cur.slice(2, 4)]); }
        prev = cur;
      }
    } else if (span > 100) {
      for (i = 0; i < n; i++) {
        cur = P[i][0].slice(0, 7);
        if (cur !== prev) { out.push([i, cur.slice(5, 7) + "." + cur.slice(2, 4)]); prev = cur; }
      }
    } else {
      var step = span > 45 ? 7 : span > 20 ? 3 : 1;
      for (i = 0; i < n; i++) { if (diffDays(P[0][0], P[i][0]) % step === 0) out.push([i, mon(P[i][0])]); }
      if (out.length > 18) out = out.filter(function (t, k) { return k % Math.ceil(out.length / 18) === 0; });
    }
    // первая метка часто стоит вплотную к следующей (окно начинается в середине месяца/года) —
    // подписи наезжают друг на друга, поэтому её убираем
    if (out.length > 2 && (out[1][0] - out[0][0]) * 2 < (out[2][0] - out[1][0])) out.shift();
    return out;
  }

  /* Уровни цены для лог-шкалы. На широком окне — декады 1/2/5·10ⁿ; на узком (месяц-два)
     в декады не попадает ни одного уровня, поэтому переходим на линейную «красивую» сетку. */
  function priceTicks(lo, hi) {
    var out = [], e, v;
    for (e = Math.floor(Math.log10(lo)); e <= Math.ceil(Math.log10(hi)); e++) {
      [1, 2, 5].forEach(function (m) {
        v = m * Math.pow(10, e);
        if (v >= lo * 0.9 && v <= hi * 1.1) out.push(v);
      });
    }
    if (out.length >= 3) return out.sort(function (a, b) { return a - b; });
    var raw = (hi - lo) / 4;
    if (!(raw > 0)) return out;
    var p = Math.pow(10, Math.floor(Math.log10(raw))), f = raw / p;
    var step = (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p;
    out = [];
    for (v = Math.ceil(lo / step) * step; v <= hi * 1.0001; v += step) out.push(+v.toPrecision(12));
    return out;
  }

  return {init: init, ticks: ticks, priceTicks: priceTicks};
})();
