# STRK — Starknet

| | |
|---|---|
| Инструмент Kraken | `PF_STRKUSD` (перп, USD) |
| CoinGecko id | starknet |
| Капитализация | $188.41 млн |
| Цена | $0.0262502 |
| Циркулирующее предложение | 7.18 млрд |
| Всего / максимум | 10 млрд / 10 млрд |

## Что за монета

Starknet — zk-роллап на языке Cairo. TGE февраль 2024.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `starknet-bridge` («Starknet Bridge»)
- Категория / сеть: Canonical Bridge / Starknet
- Событий в расписании: **451** (прошедших 253, будущих cliff-событий 198)
- Максимальное предложение по расписанию: 9.99 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Community Provisions | 13.4 | 9 | 100% |
| Starkware | 13.4 | 10.8 | 83.3% |
| Early Contributors | 22.9 | 20 | 76.7% |
| Investors | 20.8 | 18.2 | 76.7% |
| Community Rebates | 8.7 | 9 | 64.6% |
| Foundation Strategic Reserves | 7.7 | 10 | 51.7% |
| Foundation Treasury | 5.4 | 8.1 | 44.9% |
| Donations | 1.3 | 2 | 44.9% |
| Grants including Development Partners | 6.2 | 12.9 | 32.3% |

### Ближайшие разлоки (cliff)

| Дата | Токенов | % циркуляции | ≈ USD | Транш |
|---|---|---|---|---|
| 2026-09-14 | 226.36 млн | 3.15% | $5.94 млн | insiders, liquidity, privateSale |
| 2026-10-14 | 226.36 млн | 3.15% | $5.94 млн | insiders, liquidity, privateSale |
| 2026-11-13 | 226.36 млн | 3.15% | $5.94 млн | insiders, liquidity, privateSale |
| 2026-12-14 | 226.36 млн | 3.15% | $5.94 млн | insiders, liquidity, privateSale |
| 2027-01-13 | 190.52 млн | 2.65% | $5 млн | insiders, liquidity, privateSale |
| 2027-02-13 | 190.52 млн | 2.65% | $5 млн | insiders, liquidity, privateSale |
| 2027-03-15 | 63.52 млн | 0.88% | $1.67 млн | insiders, liquidity |
| 2027-04-15 | 63.52 млн | 0.88% | $1.67 млн | insiders, liquidity |
| 2027-05-15 | 63.52 млн | 0.88% | $1.67 млн | insiders, liquidity |
| 2027-06-15 | 63.52 млн | 0.88% | $1.67 млн | insiders, liquidity |

**Давление на горизонте S5:** 30 дней — 226.36 млн токенов (3.15% циркуляции, $5.94 млн; 90 дней — 679.07 млн, 9.46%).

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 226.36 млн | 3.15% |
| +90 дней | 679.07 млн | 9.46% |
| до конца расписания | 3.3 млрд | 45.98% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/starknet-bridge
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
