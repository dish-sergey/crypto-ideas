# POL — POL (ex-MATIC)

| | |
|---|---|
| Инструмент Kraken | `PF_POLUSD` (перп, USD) |
| CoinGecko id | polygon-ecosystem-token |
| Капитализация | $968.47 млн |
| Цена | $0.090478 |
| Циркулирующее предложение | 10.71 млрд |
| Всего / максимум | 10.71 млрд / не ограничен |

## Что за монета

POL — токен экосистемы Polygon, миграция с MATIC в 2024, эмиссия 2% в год.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `polygon-bridge` («Polygon Bridge»)
- Категория / сеть: Chain / Polygon
- Событий в расписании: **31** (прошедших 31, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 10.71 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Launchpad Sale | 17.7 | 17.7 | 100% |
| Seed | 2 | 2 | 100% |
| Early Supporters | 1.6 | 1.6 | 100% |
| Advisors | 3.7 | 3.7 | 100% |
| Team | 14.9 | 14.9 | 100% |
| Validator Staking Emissions | 3.3 | 3.3 | 100% |
| Community Treasury Emissions | 3.3 | 3.3 | 100% |
| Foundation | 20.4 | 20.4 | 100% |
| Ecosystem | 21.8 | 21.8 | 100% |
| Staking Rewards | 11.2 | 11.2 | 100% |

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
GET https://defillama-datasets.llama.fi/emissions/polygon-bridge
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
