# BICO — Biconomy

| | |
|---|---|
| Инструмент Kraken | `PF_BICOUSD` (перп, USD) |
| CoinGecko id | biconomy |
| Капитализация | $22.02 млн |
| Цена | $0.02199012 |
| Циркулирующее предложение | 1000 млн |
| Всего / максимум | 1 млрд / 1 млрд |
| Теги | Ethereum (ETH) Token (ERC-20), Arbitrum Ecosystem, Polygon (MATIC) Token |

## Что за монета

Biconomy — инфраструктура абстракции аккаунтов и релееров.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `hyphen` («Hyphen»)
- Категория / сеть: Bridge / 
- Событий в расписании: **59** (прошедших 59, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 1 млрд
- Не распределено (TBD): 59.7 тыс

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Public Sale | 5 | 5 | 100% |
| Strategic Investors | 0.5 | 0.5 | 100% |
| Team & Advisors | 22 | 22 | 100% |
| Seed Round | 6.4 | 6.4 | 100% |
| Pre Seed Round | 6 | 6 | 100% |
| Private Round | 12 | 12 | 100% |
| Foundation | 10 | 10 | 100% |
| Community Rewards & Incentives | 38.1 | 38.1 | 100% |

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
GET https://defillama-datasets.llama.fi/emissions/hyphen
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
