// ЖИВЫЕ ДАННЫЕ ДЛЯ ПРОГОНА СТЕНДА ЗА ОТРЕЗОК (25.11.2026): задержки замен, заморозка, двойник — из журналов живых ботов.
//
//   node scripts/stand-week.js --from=20261109 --days=7 --out=C:/revx-xrp/dx1109 [--extra=<каталог .db.gz>]
//
// Пишет в --out каталоги, которые берёт стенд:
//   lat/lat-<бот>.txt   «начало_мс задержка_мс» каждой живой замены  → -Drevx.sim.latency-dir=<out>/lat
//   fz/fz-<бот>.txt     интервалы заморозки живого (ответ ≥1 с + 5 с) → -Drevx.sim.freeze-dir=<out>/fz
//   twin/q-<бот>.csv.gz, twin/f-<бот>.csv   цены живых заявок и их сделки → -Drevx.sim.func-live-twin=<out>/twin
// и печатает покрытие: тиков, замен, доля заморозки, ЧАСЫ БЕЗ ДАННЫХ, сделок двойника. Пустые часы и «сделок 0» —
// признак, что источники не те: прогон с такими каталогами недействителен.
//
// 🔑 ⚠️ ИСТОЧНИКИ ВЫБИРАЮТСЯ ПО ВРЕМЕНИ ВНУТРИ ФАЙЛА, НЕ ПО ИМЕНИ. Имя архива — дата ЧИСТКИ (`<бот>-<дата>.db.gz`
// = данные на 14–15 суток раньше, см. scripts/journal-index.js). Перебираются все источники, берётся пересечение
// с [from, from+days):
//   1. C:/data-archive/journal-archive  — старше 14 суток (запросы с телами, котировки);
//   2. D:/revx-data/journal-fresh       — вчерашние сутки, ежедневно с 23.11.2026 (GET без тел);
//   3. --extra                          — снятое вручную с bot-arm за дни, которых нет ни там, ни там (окно между
//      «вчера» и «14 суток назад», пока свежий забор не накопился). Формат как у свежего дня.
// Сделки двойника — из venue.db последнего снимка учёта (C:/data-archive/bot-accounting/acct-*.tar.gz, таблица trade).
//
// ⚠️ Без двойника, задержек и заморозки по живому стенд отходит от живых (CLAUDE.md, раздел стражей). С каталогами
// не той недели — хуже, чем без них: до правки 25.11.2026 стенд при задержке без совпадения ронял тик.
const {DatabaseSync} = require('node:sqlite');
const fs = require('fs'), zlib = require('zlib'), path = require('path'), os = require('os'), cp = require('child_process');
const arg = k => (process.argv.find(a => a.startsWith('--' + k + '=')) || '').split('=').slice(1).join('=');
const FROM = arg('from'), ND = +(arg('days') || 7), OUT = arg('out'), EXTRA = arg('extra');
if (!/^\d{8}$/.test(FROM) || !OUT) {
  console.error('нужно --from=YYYYMMDD --out=<каталог> [--days=7] [--extra=<каталог>]');
  process.exit(2);
}
const A = Date.parse(`${FROM.slice(0, 4)}-${FROM.slice(4, 6)}-${FROM.slice(6)}T00:00:00Z`), Z = A + ND * 864e5;
const BOTS = {a: 'revx-exec-exec', e: 'revx-exec-e-exec-e-btc', c: 'revx-exec-c-exec-c-eth2', d: 'revx-exec-d-exec-d-eth2',
  b: 'revx-exec-b-exec-b-sol', f: 'revx-exec-f-exec-f-sol'};
const SRC = ['C:/data-archive/journal-archive', 'D:/revx-data/journal-fresh', EXTRA].filter(d => d && fs.existsSync(d));
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'stand-week-'));
for (const d of ['lat', 'fz', 'twin']) fs.mkdirSync(path.join(OUT, d), {recursive: true});

// сделки площадки (наши) из последнего снимка учёта
const acct = fs.readdirSync('C:/data-archive/bot-accounting').filter(x => /^acct-\d{8}\.tar\.gz$/.test(x)).sort().pop();
cp.execFileSync('tar', ['--force-local', '-xzf', 'C:/data-archive/bot-accounting/' + acct, '-C', TMP]);
const vpath = path.join(TMP, acct.replace('.tar.gz', ''), 'venue.db');
const vdb = new DatabaseSync(vpath, {readOnly: true});
const V = vdb.prepare('SELECT oid, side, qty, tdt FROM trade WHERE tdt >= ? AND tdt < ?').all(A - 864e5, Z + 864e5);
vdb.close();

for (const [b, pre] of Object.entries(BOTS)) {
  const db = new DatabaseSync(path.join(TMP, b + '.db'));
  db.exec('CREATE TABLE q(ts_ms,fair); CREATE TABLE req(ts_ms,method,path,status,body,resp,latency_ms)');
  let used = [];
  for (const dir of SRC) {
    for (const f of fs.readdirSync(dir).filter(x => (x.startsWith(pre + '-') || x === pre + '.db.gz') && x.endsWith('.db.gz'))) {
      const t = path.join(TMP, 'src.db');
      fs.writeFileSync(t, zlib.gunzipSync(fs.readFileSync(path.join(dir, f))));
      db.exec(`ATTACH '${t.replace(/\\/g, '/')}' AS s`);
      const mm = db.prepare('SELECT min(ts_ms) a, max(ts_ms) z FROM s.exec_quote').get();
      if (mm.a !== null && mm.z >= A && mm.a < Z) {
        used.push(f);
        db.exec(`INSERT INTO q SELECT ts_ms, fair FROM s.exec_quote WHERE ts_ms >= ${A} AND ts_ms < ${Z}`);
        db.exec(`INSERT INTO req SELECT ts_ms, method, path, status, body, substr(response, 1, 300), latency_ms FROM s.exec_request
                 WHERE method <> 'GET' AND ts_ms >= ${A} AND ts_ms < ${Z}`);
      }
      db.exec('DETACH s');
    }
  }
  // источники перекрываются (архив и свежий день) — дубли убрать
  db.exec(`CREATE TABLE q2 AS SELECT DISTINCT * FROM q; DROP TABLE q; ALTER TABLE q2 RENAME TO q;
           CREATE TABLE r2 AS SELECT DISTINCT * FROM req; DROP TABLE req; ALTER TABLE r2 RENAME TO req;
           CREATE INDEX iq ON q(ts_ms); CREATE INDEX ir ON req(ts_ms);`);
  const have = new Set(db.prepare('SELECT DISTINCT ts_ms / 3600000 h FROM q').all().map(r => r.h));
  const miss = [];
  for (let h = A / 3600000; h < Z / 3600000; h++) if (!have.has(h)) miss.push(new Date(h * 3600000).toISOString().slice(5, 13));

  // задержки и заморозка
  const lat = db.prepare("SELECT ts_ms - latency_ms s, latency_ms l FROM req WHERE method = 'PUT' AND latency_ms IS NOT NULL ORDER BY ts_ms").all();
  fs.writeFileSync(path.join(OUT, 'lat', `lat-${b}.txt`), lat.map(x => x.s + ' ' + x.l).join('\n') + '\n');
  const iv = [];
  for (const x of lat) {
    if (x.l < 1000) continue;
    const a = x.s + x.l, z = a + 5000, l = iv[iv.length - 1];
    if (l && a <= l[1]) l[1] = Math.max(l[1], z); else iv.push([a, z]);
  }
  fs.writeFileSync(path.join(OUT, 'fz', `fz-${b}.txt`), iv.map(x => x.join(' ')).join('\n') + '\n');

  // двойник: текущая цена живой заявки на каждой стороне по телам запросов, сделки — по ленте площадки
  const fair = db.prepare('SELECT fair FROM q WHERE ts_ms <= ? AND fair IS NOT NULL ORDER BY ts_ms DESC LIMIT 1');
  const idOf = r => ((r.resp || '').match(/"venue_order_id"\s*:\s*"([^"]+)"/) || [])[1] || null;
  const pathId = p => ((p || '').match(/orders\/([0-9a-f-]{36})/) || [])[1] || null;
  const cur = {buy: null, sell: null}, ev = [], mine = new Set();
  for (const r of db.prepare('SELECT ts_ms, method, path, status, body, resp FROM req ORDER BY ts_ms').all()) {
    const t = r.ts_ms;
    if ((r.method === 'POST' && /\/orders$/.test(r.path)) || r.method === 'PUT') {
      if (r.status !== 200) continue;
      let j;
      try { j = JSON.parse(r.body); } catch (e) { continue; }
      const id = idOf(r);
      if (!id) continue;
      mine.add(id);
      const lim = j.order_configuration && j.order_configuration.limit;
      const price = +(lim ? lim.price : j.price), size = +(lim ? lim.base_size : j.base_size);
      let side = j.side;
      const old0 = r.method === 'PUT' ? pathId(r.path) : null;
      if (!side) {
        // в теле PUT стороны нет — наследуется по цепочке, иначе по цене относительно опоры
        if (old0 && cur.buy && cur.buy.id === old0) side = 'buy';
        else if (old0 && cur.sell && cur.sell.id === old0) side = 'sell';
        else { const f = fair.get(t); if (!f) continue; side = price < f.fair ? 'buy' : 'sell'; }
      }
      ev.push([t, () => {
        let sd = side;
        if (old0) { if (cur.buy && cur.buy.id === old0) sd = 'buy'; else if (cur.sell && cur.sell.id === old0) sd = 'sell'; }
        cur[sd] = {id, price, size};
        const other = sd === 'buy' ? 'sell' : 'buy';
        if (old0 && cur[other] && cur[other].id === old0) cur[other] = null;
      }]);
    } else if (r.method === 'DELETE') {
      const id = pathId(r.path);
      ev.push([t, () => { for (const s of ['buy', 'sell']) if (cur[s] && (id === null || cur[s].id === id)) cur[s] = null; }]);
    }
  }
  const ff = [];
  for (const v of V) {
    if (!mine.has(v.oid)) continue;
    ff.push(v.tdt + ',' + v.side + ',' + v.qty);
    ev.push([v.tdt, () => { const s = v.side; if (cur[s] && cur[s].id === v.oid) { cur[s].size -= v.qty; if (cur[s].size <= 1e-12) cur[s] = null; } }]);
  }
  ev.sort((x, y) => x[0] - y[0]);
  const lines = [];
  let last = '', lastMin = -1;
  for (const [t, fn] of ev) {
    fn();
    const k = (cur.buy ? cur.buy.price : '') + ',' + (cur.sell ? cur.sell.price : ''), m = Math.floor(t / 60000);
    if (k !== last || m !== lastMin) { lines.push(t + ',' + k); last = k; lastMin = m; }
  }
  fs.writeFileSync(path.join(OUT, 'twin', `q-${b}.csv.gz`), zlib.gzipSync(lines.join('\n') + '\n'));
  fs.writeFileSync(path.join(OUT, 'twin', `f-${b}.csv`), ff.join('\n') + (ff.length ? '\n' : ''));
  const n = db.prepare('SELECT count(*) n FROM q').get().n;
  console.log(`${b}: источников ${used.length}, тиков ${n}, замен ${lat.length}, заморозка ${(iv.reduce((s, x) => s + x[1] - x[0], 0) / (Z - A) * 100).toFixed(1)}%, `
    + `сделок двойника ${ff.length}, часов без данных ${miss.length}${miss.length ? ' (' + miss.slice(0, 6).join(',') + (miss.length > 6 ? ' …' : '') + ')' : ''}`);
  if (!ff.length || miss.length > 24) console.log(`  ⚠️ ${b}: данные неполны — прогон с этими каталогами недействителен`);
  db.close();
}
fs.rmSync(TMP, {recursive: true, force: true});
