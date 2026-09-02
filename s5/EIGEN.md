# EIGEN — EigenCloud (prev. EigenLayer)

| | |
|---|---|
| Инструмент Kraken | `PF_EIGENUSD` (перп, USD) |
| CoinGecko id | eigenlayer |
| Капитализация | $180.5 млн |
| Цена | $0.196645 |
| Циркулирующее предложение | 918.21 млн |
| Всего / максимум | 1.84 млрд / не ограничен |

## Что за монета

EigenCloud/EigenLayer — рестейкинг Ethereum. TGE октябрь 2024.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `eigencloud` («EigenCloud»)
- Категория / сеть: Restaking / 
- Событий в расписании: **50** (прошедших 30, будущих cliff-событий 20)
- Максимальное предложение по расписанию: 1.59 млрд
- Не распределено (TBD): 318.83 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Stakedrops | 21.2 | 14.5 | 100% |
| Community Initiatives | 7.9 | 5.4 | 100% |
| Programmatic Incentives | 15.4 | 10.5 | 100% |
| Investors | 29.8 | 37.4 | 54.2% |
| Early Contributors | 25.7 | 32.3 | 54.2% |

### Ближайшие разлоки (cliff)

| Дата | Токенов | % циркуляции | ≈ USD | Транш |
|---|---|---|---|---|
| 2026-10-01 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |
| 2026-10-31 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |
| 2026-11-30 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |
| 2026-12-31 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |
| 2027-01-30 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |
| 2027-03-02 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |
| 2027-04-01 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |
| 2027-05-02 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |
| 2027-06-01 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |
| 2027-07-02 | 36.82 млн | 4.01% | $7.24 млн | insiders, privateSale |

**Давление на горизонте S5:** 30 дней — 36.82 млн токенов (4.01% циркуляции, $7.24 млн; 90 дней — 110.46 млн, 12.03%).

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 36.82 млн | 4.01% |
| +90 дней | 110.46 млн | 12.03% |
| до конца расписания | 405.02 млн | 44.11% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/eigencloud
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
