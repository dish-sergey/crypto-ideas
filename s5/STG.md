# STG — Stargate Finance

| | |
|---|---|
| Инструмент Kraken | `PF_STGUSD` (перп, USD) |
| CoinGecko id | stargate-finance |
| Капитализация | $23.03 млн |
| Цена | $0.163752 |
| Циркулирующее предложение | 140.67 млн |
| Всего / максимум | 140.67 млн / 1 млрд |

## Что за монета

Stargate — омничейн-мост поверх LayerZero.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `stargate-finance` («Stargate Finance»)
- Категория / сеть: Cross Chain Bridge / 
- Событий в расписании: **1634** (прошедших 1634, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 1 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Community Treasury | 24.4 | 24.4 | 100% |
| STG/USDC Curve Pool Incentives | 5 | 5 | 100% |
| Bonding Curve | 15.9 | 15.9 | 100% |
| STG DEX Liquidity | 1.5 | 1.5 | 100% |
| Initial Emissions Program | 2.1 | 2.1 | 100% |
| STG Launch Auction Purchasers | 10 | 10 | 100% |
| Core Contributors | 17.5 | 17.5 | 100% |
| Investors | 17.5 | 17.5 | 100% |
| LP Rewards | 6 | 6 | 100% |

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
GET https://defillama-datasets.llama.fi/emissions/stargate-finance
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
