# DYDX — dYdX

| | |
|---|---|
| Инструмент Kraken | `PF_DYDXUSD` (перп, USD) |
| CoinGecko id | dydx-chain |
| Капитализация | $94.05 млн |
| Цена | $0.111124 |
| Циркулирующее предложение | 846.09 млн |
| Всего / максимум | 958.34 млн / 1 млрд |
| Теги | Exchange, Decentralized Exchange (DEX) |

## Что за монета

dYdX — децентрализованная перп-биржа, с версии v4 собственный чейн Cosmos.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `dydx` («dYdX»)
- Категория / сеть: Derivatives / dYdX
- Событий в расписании: **124** (прошедших 124, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 1000 млн
- Не распределено (TBD): 5.93 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Retroactive Rewards | 5.1 | 5.1 | 100% |
| Liquidity Staking Pool | 0.6 | 0.6 | 100% |
| Safety Staking Pool | 0.5 | 0.5 | 100% |
| Liquidity Provider Rewards | 3.3 | 3.3 | 100% |
| Investors | 27.9 | 27.9 | 100% |
| Employees & Consultants | 15.4 | 15.4 | 100% |
| Future Employees & Consultants | 7.1 | 7.1 | 100% |
| User Trading Rewards | 14.4 | 14.4 | 100% |
| Community Treasury | 25.7 | 25.7 | 100% |

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
GET https://defillama-datasets.llama.fi/emissions/dydx
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
