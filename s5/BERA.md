# BERA — Berachain

| | |
|---|---|
| Инструмент Kraken | `PF_BERAUSD` (перп, USD) |
| CoinGecko id | berachain-bera |
| Капитализация | $55.87 млн |
| Цена | $0.175146 |
| Циркулирующее предложение | 318.92 млн |
| Всего / максимум | 557.99 млн / не ограничен |
| Теги | Cryptocurrency, Layer 1 (L1), DeFi |

## Что за монета

Berachain — L1 с консенсусом Proof-of-Liquidity. TGE февраль 2025.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `berachain` («Berachain»)
- Категория / сеть: Chain Bribes / Berachain
- Событий в расписании: **1158** (прошедших 1158, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 563.08 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Validator Rewards | 3.1 | 1.8 | 100% |
| Reward Vault Emissions | 15.8 | 9.4 | 100% |
| Investors | 20.7 | 30.4 | 40.4% |
| Initial Core Contributors | 10.1 | 14.9 | 40.4% |
| Community Initiatives | 7.9 | 11.6 | 40.4% |
| Ecosystem & R&D | 20.6 | 17.8 | 69% |
| Airdrop | 21.9 | 14 | 92.8% |

### Ближайшие разлоки (cliff)

Дискретных (cliff) событий впереди нет — для S5, который торгует именно обрывы, монета сейчас событий не даёт.
Это НЕ значит, что предложение не растёт: см. непрерывную эмиссию ниже.

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 13.09 млн | 4.1% |
| +90 дней | 39.26 млн | 12.31% |
| до конца расписания | 227.69 млн | 71.39% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/berachain
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
