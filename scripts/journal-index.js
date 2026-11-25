// ИНДЕКС АРХИВА ЖУРНАЛОВ (25.11.2026): какой файл какие сутки на самом деле содержит.
//
// ⚠️ ИМЯ ФАЙЛА АРХИВА — ДАТА ЧИСТКИ, А НЕ ДАТА ДАННЫХ. `revx-journal-prune.sh` раз в сутки уносит строки старше
// 14 суток в `<бот>-<ДАТА ЧИСТКИ>.db.gz`, поэтому `revx-exec-exec-20261123.db.gz` — это котировки 08.11 05:30 →
// 09.11 05:30, а не 23.11. Сборка недели по имени файла 25.11.2026 подсунула стенду задержки, заморозку и двойника
// с чужих дней (две недели прогонов в корзину). Выбирать файлы ТОЛЬКО по этому индексу или по min/max(ts_ms) внутри.
//
// Пишет `<каталог>/INDEX.csv`: файл,бот,первый_UTC,последний_UTC,первый_мс,последний_мс,тиков. Дописывает только новые
// файлы (распаковка ~1–2 с на файл). Запуск: node scripts/journal-index.js [C:/data-archive/journal-archive]
// Зовётся из scripts/data-pull.sh после забора архива.
const {DatabaseSync} = require('node:sqlite');
const fs = require('fs'), zlib = require('zlib'), path = require('path'), os = require('os');
const DIR = process.argv[2] || 'C:/data-archive/journal-archive';
const IDX = path.join(DIR, 'INDEX.csv');
const HEAD = 'file,bot,first_utc,last_utc,first_ms,last_ms,quotes';
const known = new Map();
if (fs.existsSync(IDX)) {
  for (const l of fs.readFileSync(IDX, 'utf8').trim().split(/\r?\n/).slice(1)) {
    if (l) known.set(l.split(',')[0], l);
  }
}
const tmp = path.join(os.tmpdir(), 'journal-index-' + process.pid + '.db');
let added = 0;
for (const f of fs.readdirSync(DIR).filter(x => x.endsWith('.db.gz')).sort()) {
  if (known.has(f)) continue;
  const m = f.match(/^(.*)-(\d{8})\.db\.gz$/);
  const bot = m ? m[1] : f;
  try {
    fs.writeFileSync(tmp, zlib.gunzipSync(fs.readFileSync(path.join(DIR, f))));
    const db = new DatabaseSync(tmp, {readOnly: true});
    const r = db.prepare('SELECT min(ts_ms) a, max(ts_ms) z, count(*) n FROM exec_quote').get();
    db.close();
    const iso = t => t == null ? '' : new Date(t).toISOString().slice(0, 16) + 'Z';
    known.set(f, [f, bot, iso(r.a), iso(r.z), r.a ?? '', r.z ?? '', r.n].join(','));
    added++;
  } catch (e) {
    console.error('не прочитался', f, e.message);
  }
}
try { fs.unlinkSync(tmp); } catch (e) { /* нет файла */ }
const rows = [...known.values()].sort();
fs.writeFileSync(IDX, HEAD + '\n' + rows.join('\n') + '\n');
console.log(`индекс архива журналов: ${rows.length} файлов, новых ${added} → ${IDX}`);
