# ANKR — Ankr Network

| | |
|---|---|
| Инструмент Kraken | `PF_ANKRUSD` (перп, USD) |
| CoinGecko id | ankr |
| Капитализация | $39.65 млн |
| Цена | $0.00396542 |
| Циркулирующее предложение | 10 млрд |
| Всего / максимум | 10 млрд / 10 млрд |
| Теги | Computing & Cloud Infrastructure, Ethereum (ETH) Token (ERC-20), Polygon (MATIC) Token, Avalanche (AVAX) Token, Binance Coin (BNB) Token (BEP-20), Fantom (FTM) Token |

## Что за монета

Ankr — инфраструктура RPC-нод и стейкинга.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `ankr` («Ankr»)
- Категория / сеть: Liquid Staking / 
- Событий в расписании: **8** (прошедших 8, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 10 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Private Sale 1 | 3 | 3 | 100% |
| Private Sale 2 | 12 | 12 | 100% |
| Private Sale 3 | 15 | 15 | 100% |
| Public Sale | 5 | 5 | 100% |
| Marketing | 5 | 5 | 100% |
| Team | 17 | 17 | 100% |
| Advisors | 3 | 3 | 100% |
| Mining Rewards | 40 | 40 | 100% |

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
GET https://defillama-datasets.llama.fi/emissions/ankr
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
