# CAKE — PancakeSwap

| | |
|---|---|
| Инструмент Kraken | `PF_CAKEUSD` (перп, USD) |
| CoinGecko id | pancakeswap-token |
| Капитализация | $577.74 млн |
| Цена | $1.8 |
| Циркулирующее предложение | 320.77 млн |
| Всего / максимум | 332.45 млн / 400 млн |
| Теги | Binance Coin (BNB) Token (BEP-20), Ethereum (ETH) Token (ERC-20), Exchange, Decentralized Exchange (DEX), DeFi, Governance |

## Что за монета

PancakeSwap — крупнейший DEX BNB Chain, инфляционная эмиссия со сжиганием.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `pancakeswap` («PancakeSwap»)
- Категория / сеть: Dexs / 
- Событий в расписании: **595** (прошедших 595, будущих cliff-событий 0)
- Максимальное предложение по расписанию: н/д

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Ecosystem Growth | 4.7 | 4.7 | 100% |
| Staking Rewards | 3.3 | 3.3 | 100% |
| Farming Rewards | 92 | 92 | 100% |

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
GET https://defillama-datasets.llama.fi/emissions/pancakeswap
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
