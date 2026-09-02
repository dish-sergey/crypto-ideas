# NEAR — NEAR Protocol

| | |
|---|---|
| Инструмент Kraken | `PF_NEARUSD` (перп, USD) |
| CoinGecko id | near |
| Капитализация | $2.41 млрд |
| Цена | $1.84 |
| Циркулирующее предложение | 1.31 млрд |
| Всего / максимум | 1.31 млрд / не ограничен |

## Что за монета

NEAR Protocol — шардированный L1 с абстракцией цепей. TGE 2020.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `near-protocol` («NEAR Protocol»)
- Категория / сеть: Canonical Bridge / Near
- Событий в расписании: **13** (прошедших 13, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 1.3 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Early Ecosystem | 9 | 9 | 100% |
| Community Sale | 9.2 | 9.2 | 100% |
| Prior Backers 12m | 0.5 | 0.5 | 100% |
| Prior Backers 24m | 11.9 | 11.9 | 100% |
| Prior Backers 36m | 5.8 | 5.8 | 100% |
| Foundation Endowment | 7.7 | 7.7 | 100% |
| Core Team | 10.7 | 10.7 | 100% |
| Community Grants | 13.2 | 13.2 | 100% |
| Operations Grants | 8.7 | 8.7 | 100% |
| Staking Rewards | 23.3 | 23.3 | 100% |

### Ближайшие разлоки (cliff)

Дискретных (cliff) событий впереди нет — для S5, который торгует именно обрывы, монета сейчас событий не даёт.
Это НЕ значит, что предложение не растёт: см. непрерывную эмиссию ниже.

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 0 | 0% |
| +90 дней | 0 | 0% |
| до конца расписания | 0 | 0% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/near-protocol
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
