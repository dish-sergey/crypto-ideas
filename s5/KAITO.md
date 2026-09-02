# KAITO — KAITO

| | |
|---|---|
| Инструмент Kraken | `PF_KAITOUSD` (перп, USD) |
| CoinGecko id | kaito |
| Капитализация | $72.48 млн |
| Цена | $0.300216 |
| Циркулирующее предложение | 241.39 млн |
| Всего / максимум | 1 млрд / 1 млрд |

## Что за монета

Kaito — ИИ-платформа анализа крипто-внимания (InfoFi). TGE февраль 2025.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `kaito` («Kaito»)
- Категория / сеть: Services / 
- Событий в расписании: **166** (прошедших 53, будущих cliff-событий 113)
- Максимальное предложение по расписанию: 1 млрд
- Не распределено (TBD): 242.08 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Initial Community & Ecosystem | 21.8 | 10 | 100% |
| Liquidity Incentives | 10.9 | 5 | 100% |
| Binance Holder | 4.4 | 2 | 100% |
| Long Term Creator Incentives | 9.8 | 7.5 | 60% |
| Core Contributors | 10.6 | 25 | 19.4% |
| Early Backers | 3.5 | 8.3 | 19.4% |
| Ecosystem & Network Growth | 24.9 | 32.2 | 35.5% |
| Foundation | 14.2 | 10 | 65.5% |

### Ближайшие разлоки (cliff)

| Дата | Токенов | % циркуляции | ≈ USD | Транш |
|---|---|---|---|---|
| 2026-09-19 | 17.6 млн | 7.29% | $5.28 млн | insiders, noncirculating, privateSale |
| 2026-10-19 | 17.6 млн | 7.29% | $5.28 млн | insiders, noncirculating, privateSale |
| 2026-11-19 | 17.6 млн | 7.29% | $5.28 млн | insiders, noncirculating, privateSale |
| 2026-12-19 | 17.6 млн | 7.29% | $5.28 млн | insiders, noncirculating, privateSale |
| 2027-01-19 | 17.6 млн | 7.29% | $5.28 млн | insiders, noncirculating, privateSale |
| 2027-02-18 | 32.6 млн | 13.5% | $9.79 млн | farming, insiders, noncirculating, privateSale |
| 2027-03-20 | 17.6 млн | 7.29% | $5.28 млн | insiders, noncirculating, privateSale |
| 2027-04-20 | 17.6 млн | 7.29% | $5.28 млн | insiders, noncirculating, privateSale |
| 2027-05-20 | 17.6 млн | 7.29% | $5.28 млн | insiders, noncirculating, privateSale |
| 2027-06-20 | 17.6 млн | 7.29% | $5.28 млн | insiders, noncirculating, privateSale |

**Давление на горизонте S5:** 30 дней — 17.6 млн токенов (7.29% циркуляции, $5.28 млн; 90 дней — 52.79 млн, 21.87%).

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 17.6 млн | 7.29% |
| +90 дней | 52.79 млн | 21.87% |
| до конца расписания | 540.33 млн | 223.84% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/kaito
```

Без ключа и без лимитов (CDN), JSON. Ключевые поля:

- `metadata.events[]` — дискретные события: `timestamp`, `noOfTokens`, `category`, `unlockType` (`cliff` / `linear`), `rateDurationDays`. Форвардные события присутствуют.
- `documentedData.data[]` — посуточный ряд `{timestamp, unlocked, rawEmission, burned}` по каждому траншу, включая будущее.
- `documentedData.tokenAllocation` — доли траншей (`current` / `final` / `progress`).
- `supplyMetrics` — `maxSupply`, `adjustedSupply`, `tbdAmount`.

⚠️ Платный `api.llama.fi/emissions` (HTTP 402) заменяется этим CDN — данные те же.
⚠️ У `unlockType: "linear"` поле `noOfTokens` хранит **смену скорости** `[было, стало]` за `rateDurationDays`, а не объём. Объём считать по `cliff` либо по разностям ряда `unlocked`.
⚠️ Часть `timestamp` приходит строкой, а не числом — приводить типы при разборе.

---

Сгенерировано 2026-09-02 из Kraken Futures `instruments`, DefiLlama emissions и CoinGecko.
