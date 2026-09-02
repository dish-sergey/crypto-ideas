# CVX — Convex Finance

| | |
|---|---|
| Инструмент Kraken | `PF_CVXUSD` (перп, USD) |
| CoinGecko id | convex-finance |
| Капитализация | $219.15 млн |
| Цена | $2.35 |
| Циркулирующее предложение | 93.19 млн |
| Всего / максимум | 99.99 млн / 100 млн |
| Теги | Ethereum (ETH) Token (ERC-20), Solana (SOL) Token |

## Что за монета

Convex — надстройка над Curve, аккумулятор veCRV.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `convex-finance` («Convex Finance»)
- Категория / сеть: Yield / 
- Событий в расписании: **3950** (прошедших 3950, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 96.94 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| veCRV voters | 1 | 1 | 100% |
| veCRV holders | 1 | 1 | 100% |
| Investors | 3.4 | 3.4 | 100% |
| Team | 10.3 | 10.3 | 100% |
| Staking Rewards | 5.5 | 5.5 | 100% |
| Farming Incentives | 27.1 | 27.1 | 100% |
| Curve LP Rewards | 51.5 | 51.5 | 100% |

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
GET https://defillama-datasets.llama.fi/emissions/convex-finance
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
